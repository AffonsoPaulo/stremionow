// SPDX-License-Identifier: GPL-3.0-or-later
//! The librqbit session: adding torrents, choosing the file to play, reporting progress and
//! streaming the file over HTTP.

use std::collections::{HashMap, HashSet};
use std::io::{self, SeekFrom};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use bytes::Bytes;
use futures::StreamExt;
use hyper::header::{self, HeaderValue};
use hyper::{Method, Response, StatusCode};
use librqbit::api::TorrentIdOrHash;
use librqbit::dht::DhtPersistenceConfig;
use librqbit::{
    AddTorrent, AddTorrentOptions, AddTorrentResponse, ByteBufOwned, DhtSessionConfig,
    ListOnlyResponse, ListenerOptions, ManagedTorrent, ManagedTorrentState, Session,
    SessionOptions, TorrentStatsState, ValidatedTorrentMetaV1Info,
};
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio::sync::Semaphore;
use tokio_util::io::ReaderStream;
use tokio_util::sync::CancellationToken;

use crate::files::{self, TorrentFile};
use crate::http::{self, Body};
use crate::magnet::{self, InfoHash};
use crate::range::{self, RangeRequest};
use crate::{TorrentError, TorrentState, TorrentStatus};

/// Folder under the cache directory holding one folder of data per torrent.
pub(crate) const DATA_DIR: &str = "torrents";

/// DHT routing table saved between runs, so peers are found faster after a restart.
const DHT_FILE: &str = "dht.json";

/// How long peers get to send the metadata of a newly added torrent.
const METADATA_TIMEOUT: Duration = Duration::from_secs(60);

/// librqbit's limit on concurrent blocking disk operations. Every open file stream holds one
/// slot for as long as it lives, and piece writes need one too.
const BLOCKING_SLOTS: usize = 16;

/// File streams open at once, kept well under [`BLOCKING_SLOTS`] so that open streams can
/// never stop pieces from being written, which they are waiting for.
const MAX_STREAMS: usize = 8;

/// How long a request waits for a stream slot before getting 503. A player that seeks opens
/// the new request while the old one is still closing.
const STREAM_SLOT_TIMEOUT: Duration = Duration::from_secs(20);

/// Read size when streaming a file.
const CHUNK_BYTES: usize = 64 * 1024;

/// Whether the session talks to the BitTorrent network. Tests run offline.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Network {
    Online,
    #[cfg(test)]
    Offline,
}

/// Torrents known to the server, with the file chosen for each.
pub(crate) struct Engine {
    session: Arc<Session>,
    data_dir: PathBuf,
    torrents: Mutex<HashMap<InfoHash, Arc<Entry>>>,
    stream_slots: Arc<Semaphore>,
}

/// A torrent the app asked for.
struct Entry {
    /// Serializes `add` calls for the same torrent.
    adding: tokio::sync::Mutex<()>,
    /// Cancelled when the app stops the torrent, which also ends its open streams.
    stopped: CancellationToken,
    state: Mutex<EntryState>,
}

#[derive(Clone)]
enum EntryState {
    FetchingMetadata,
    Ready(Selection),
    Failed(String),
}

/// What is known about a torrent before its download starts.
struct Metadata {
    /// The torrent file, with the info dictionary and the trackers.
    torrent: Bytes,
    files: Vec<TorrentFile>,
    /// Peers met while fetching the metadata, to connect to first.
    peers: Vec<SocketAddr>,
}

/// A torrent in the session and the file being played from it.
#[derive(Clone)]
struct Selection {
    handle: Arc<ManagedTorrent>,
    index: usize,
    file: TorrentFile,
}

/// How the session's DHT starts.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Dht {
    /// On the port saved with the routing table, so nodes that know this one find it again.
    SavedPort,
    /// On any free port, keeping the saved routing table. The saved port can become
    /// impossible to open: Windows reserves port ranges for Hyper-V, WSL and Docker that
    /// change on every boot, and opening a reserved port fails with "access forbidden".
    AnyPort,
    /// Off: torrents find peers through their trackers only.
    Off,
}

/// The ways [`Engine::start`] tries to start the session, in order: (IPv4 only, DHT). IPv4
/// only is for devices that cannot open dual-stack sockets.
const START_ATTEMPTS: [(bool, Dht); 6] = [
    (false, Dht::SavedPort),
    (false, Dht::AnyPort),
    (false, Dht::Off),
    (true, Dht::SavedPort),
    (true, Dht::AnyPort),
    (true, Dht::Off),
];

/// The torrent engine, started on the first torrent request (and by [`Self::warm_up`]). A
/// failure to start it is returned to that request and the next one tries again, so it never
/// stops the server from relaying remote sources, which do not need it.
pub(crate) struct LazyEngine {
    engine: tokio::sync::OnceCell<Engine>,
    cache_dir: PathBuf,
    data_dir: PathBuf,
    shutdown: CancellationToken,
    network: Network,
}

impl LazyEngine {
    pub(crate) fn new(cache_dir: &Path, shutdown: &CancellationToken, network: Network) -> Self {
        Self {
            engine: tokio::sync::OnceCell::new(),
            cache_dir: cache_dir.to_path_buf(),
            data_dir: cache_dir.join(DATA_DIR),
            shutdown: shutdown.clone(),
            network,
        }
    }

    /// Starts the engine ahead of the first torrent, so the DHT is already finding nodes by
    /// then. A failure is only logged: the first torrent tries again.
    pub(crate) async fn warm_up(&self) {
        if let Err(error) = self.get().await {
            log::warn!("torrent: engine not started, will retry on the first torrent: {error}");
        }
    }

    /// The engine, starting it if needed.
    async fn get(&self) -> Result<&Engine, TorrentError> {
        self.engine
            .get_or_try_init(|| Engine::start(&self.cache_dir, &self.shutdown, self.network))
            .await
    }

    /// The engine if it has started; when it has not, no torrent is running.
    fn started(&self) -> Option<&Engine> {
        self.engine.get()
    }

    /// See [`Engine::add`].
    pub(crate) async fn add(
        &self,
        hash: InfoHash,
        requested: Option<usize>,
        trackers: &[String],
    ) -> Result<usize, TorrentError> {
        self.get().await?.add(hash, requested, trackers).await
    }

    /// See [`Engine::status`].
    pub(crate) fn status(&self, hash: InfoHash) -> Option<TorrentStatus> {
        self.started()?.status(hash)
    }

    /// See [`Engine::selected_index`].
    pub(crate) fn selected_index(&self, hash: InfoHash) -> Option<usize> {
        self.started()?.selected_index(hash)
    }

    /// See [`Engine::stop`].
    pub(crate) async fn stop(&self, hash: InfoHash) {
        if let Some(engine) = self.started() {
            engine.stop(hash).await;
        }
    }

    /// See [`Engine::in_use`].
    pub(crate) fn in_use(&self, name: &str) -> bool {
        self.started().is_some_and(|engine| engine.in_use(name))
    }

    /// The folder holding one folder of data per torrent. Known before the engine starts, so
    /// the cache can be pruned without starting it.
    pub(crate) fn data_dir(&self) -> &Path {
        &self.data_dir
    }

    /// See [`Engine::respond`].
    pub(crate) async fn respond(
        &self,
        hash: &str,
        index: &str,
        method: &Method,
        range: Option<&str>,
    ) -> Response<Body> {
        match self.started() {
            Some(engine) => engine.respond(hash, index, method, range).await,
            None => http::not_found(),
        }
    }

    /// See [`Engine::add_torrent_file`].
    #[cfg(test)]
    pub(crate) async fn add_torrent_file(
        &self,
        hash: InfoHash,
        torrent: Bytes,
        files: Vec<TorrentFile>,
    ) -> Result<usize, TorrentError> {
        self.get()
            .await?
            .add_torrent_file(hash, torrent, files)
            .await
    }
}

impl Engine {
    /// Starts a librqbit session storing data under `cache_dir`, trying each of
    /// [`START_ATTEMPTS`] until one works. Returns the first attempt's error if none does.
    pub(crate) async fn start(
        cache_dir: &Path,
        shutdown: &CancellationToken,
        network: Network,
    ) -> Result<Self, TorrentError> {
        Self::start_trying(cache_dir, shutdown, network, &START_ATTEMPTS).await
    }

    async fn start_trying(
        cache_dir: &Path,
        shutdown: &CancellationToken,
        network: Network,
        attempts: &[(bool, Dht)],
    ) -> Result<Self, TorrentError> {
        let data_dir = cache_dir.join(DATA_DIR);
        tokio::fs::create_dir_all(&data_dir).await?;
        install_tls_provider();
        let mut first_error = None;
        let mut session = None;
        for &(ipv4_only, dht) in attempts {
            match start_session(cache_dir, &data_dir, shutdown, network, ipv4_only, dht).await {
                Ok(started) => {
                    if first_error.is_some() {
                        log::warn!("torrent: started with ipv4_only={ipv4_only}, dht={dht:?}");
                    }
                    session = Some(started);
                    break;
                }
                Err(error) => {
                    log::warn!(
                        "torrent: start failed with ipv4_only={ipv4_only}, dht={dht:?}: {error}"
                    );
                    first_error.get_or_insert(error);
                }
            }
        }
        let Some(session) = session else {
            return Err(first_error.unwrap_or_else(|| TorrentError::engine("no start attempt")));
        };
        Ok(Self {
            session,
            data_dir,
            torrents: Mutex::default(),
            stream_slots: Arc::new(Semaphore::new(MAX_STREAMS)),
        })
    }

    /// Adds the torrent if needed, selects the file to play and returns its index.
    pub(crate) async fn add(
        &self,
        hash: InfoHash,
        requested: Option<usize>,
        trackers: &[String],
    ) -> Result<usize, TorrentError> {
        let entry = self.entry_or_insert(hash);
        let _adding = entry.adding.lock().await;
        let result = tokio::select! {
            () = entry.stopped.cancelled() => Err(TorrentError::Stopped),
            result = self.select(hash, &entry, requested, trackers) => result,
        };
        if entry.stopped.is_cancelled() {
            // `stop` ran meanwhile and may have missed a torrent added since. If the app has
            // already asked for the torrent again, that newer request owns it.
            let replaced = self.torrents().contains_key(&hash);
            if !replaced {
                self.delete_from_session(hash).await;
            }
            return Err(TorrentError::Stopped);
        }
        match result {
            Ok(selection) => {
                let index = selection.index;
                entry.set_state(EntryState::Ready(selection));
                self.mark_used(hash).await;
                Ok(index)
            }
            Err(error) => {
                entry.record_failure(&error);
                Err(error)
            }
        }
    }

    /// Chooses the file of a torrent already in the session, or adds it.
    async fn select(
        &self,
        hash: InfoHash,
        entry: &Entry,
        requested: Option<usize>,
        trackers: &[String],
    ) -> Result<Selection, TorrentError> {
        if let Some(handle) = self.session.get(TorrentIdOrHash::Hash(hash.id20())) {
            let failed = handle.with_state(|state| matches!(state, ManagedTorrentState::Error(_)));
            if !failed {
                return self.reselect(handle, requested).await;
            }
            // Asking again for a failed torrent retries it from scratch.
            self.delete_from_session(hash).await;
        }
        entry.set_state(EntryState::FetchingMetadata);
        let listing = self.fetch_metadata(hash, trackers).await?;
        let metadata = Metadata {
            files: torrent_files(&listing.info),
            torrent: listing.torrent_bytes,
            peers: listing.seen_peers,
        };
        self.download(hash, metadata, requested).await
    }

    /// Adds a torrent whose metadata is known, downloading only the chosen file.
    async fn download(
        &self,
        hash: InfoHash,
        metadata: Metadata,
        requested: Option<usize>,
    ) -> Result<Selection, TorrentError> {
        let files = metadata.files;
        let index = files::pick_file(&files, requested).ok_or(TorrentError::NoVideoFile)?;
        let file = files.get(index).cloned().ok_or(TorrentError::NoVideoFile)?;
        let options = AddTorrentOptions {
            only_files: Some(vec![index]),
            // Data left in the cache by an earlier run is verified and reused.
            overwrite: true,
            output_folder: Some(self.folder_name(hash)?),
            initial_peers: Some(metadata.peers),
            ..AddTorrentOptions::default()
        };
        let added = self
            .session
            .add_torrent(AddTorrent::from_bytes(metadata.torrent), Some(options))
            .await
            .map_err(TorrentError::engine)?;
        match added {
            AddTorrentResponse::Added(_, handle) => Ok(Selection {
                handle,
                index,
                file,
            }),
            AddTorrentResponse::AlreadyManaged(_, handle) => {
                self.reselect(handle, Some(index)).await
            }
            AddTorrentResponse::ListOnly(_) => Err(TorrentError::engine(
                "the torrent was listed instead of added",
            )),
        }
    }

    /// Resolves the magnet link and returns the metadata without starting the download, so
    /// that the file to download can be chosen first.
    async fn fetch_metadata(
        &self,
        hash: InfoHash,
        trackers: &[String],
    ) -> Result<ListOnlyResponse, TorrentError> {
        let link = magnet::magnet_link(hash, trackers);
        let options = AddTorrentOptions {
            list_only: true,
            ..AddTorrentOptions::default()
        };
        let listed = tokio::time::timeout(
            METADATA_TIMEOUT,
            self.session
                .add_torrent(AddTorrent::from_url(link), Some(options)),
        )
        .await
        .map_err(|_| TorrentError::MetadataTimeout(METADATA_TIMEOUT))?
        .map_err(TorrentError::engine)?;
        match listed {
            AddTorrentResponse::ListOnly(listing) => Ok(listing),
            _ => Err(TorrentError::engine(
                "the torrent was added instead of listed",
            )),
        }
    }

    /// Points a torrent already in the session at the requested file.
    async fn reselect(
        &self,
        handle: Arc<ManagedTorrent>,
        requested: Option<usize>,
    ) -> Result<Selection, TorrentError> {
        // librqbit refuses to change the file selection while it checks existing data.
        handle
            .wait_until_initialized()
            .await
            .map_err(TorrentError::engine)?;
        let files = handle
            .with_metadata(|metadata| torrent_files(&metadata.info))
            .map_err(TorrentError::engine)?;
        let index = files::pick_file(&files, requested).ok_or(TorrentError::NoVideoFile)?;
        let file = files.get(index).cloned().ok_or(TorrentError::NoVideoFile)?;
        if handle.only_files() != Some(vec![index]) {
            self.session
                .update_only_files(&handle, &HashSet::from([index]))
                .await
                .map_err(TorrentError::engine)?;
        }
        Ok(Selection {
            handle,
            index,
            file,
        })
    }

    /// Progress of a torrent the app asked for, if it has not been stopped.
    pub(crate) fn status(&self, hash: InfoHash) -> Option<TorrentStatus> {
        let entry = self.torrents().get(&hash).cloned()?;
        Some(match entry.state() {
            EntryState::FetchingMetadata => TorrentStatus::fetching_metadata(),
            EntryState::Failed(error) => TorrentStatus::failed(error),
            EntryState::Ready(selection) => selection.status(),
        })
    }

    /// The index of the file being streamed from a torrent, once it is chosen.
    pub(crate) fn selected_index(&self, hash: InfoHash) -> Option<usize> {
        let entry = self.torrents().get(&hash).cloned()?;
        match entry.state() {
            EntryState::Ready(selection) => Some(selection.index),
            _ => None,
        }
    }

    /// Forgets a torrent and ends its streams. Downloaded data stays in the cache.
    pub(crate) async fn stop(&self, hash: InfoHash) {
        let entry = self.torrents().remove(&hash);
        if let Some(entry) = entry {
            entry.stopped.cancel();
        }
        self.delete_from_session(hash).await;
    }

    /// Whether the cache entry called `name` belongs to a torrent the app is using.
    pub(crate) fn in_use(&self, name: &str) -> bool {
        InfoHash::parse(name).is_some_and(|hash| self.torrents().contains_key(&hash))
    }

    /// Folder holding the downloaded data of all torrents.
    /// Answers a player request for file `index` of torrent `hash`, ranges included. Only
    /// the selected file of a torrent the app added is served; anything else is 404.
    pub(crate) async fn respond(
        &self,
        hash: &str,
        index: &str,
        method: &Method,
        range: Option<&str>,
    ) -> Response<Body> {
        let Some((selection, stopped)) = self.selected(hash, index) else {
            return http::not_found();
        };
        let total = selection.file.len;
        let part = match range::resolve(range, total) {
            RangeRequest::Full => None,
            RangeRequest::Partial(part) => Some(part),
            RangeRequest::Unsatisfiable => return unsatisfiable(total),
        };
        let (status, start, len) = match part {
            None => (StatusCode::OK, 0, total),
            Some(part) => (StatusCode::PARTIAL_CONTENT, part.start, part.len()),
        };
        let mut response = if method == Method::HEAD {
            http::empty(status)
        } else {
            match self.open(&selection, start, len, stopped).await {
                Ok(body) => {
                    let mut response = Response::new(body);
                    *response.status_mut() = status;
                    response
                }
                Err(status) => return http::empty(status),
            }
        };
        let headers = response.headers_mut();
        headers.insert(
            header::CONTENT_TYPE,
            HeaderValue::from_static(files::content_type(&selection.file.path)),
        );
        headers.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
        headers.insert(header::CONTENT_LENGTH, HeaderValue::from(len));
        if let Some(part) = part
            && let Ok(value) = HeaderValue::from_str(&part.content_range(total))
        {
            headers.insert(header::CONTENT_RANGE, value);
        }
        response
    }

    /// The selected file, if `hash` and `index` name it.
    fn selected(&self, hash: &str, index: &str) -> Option<(Selection, CancellationToken)> {
        let hash = InfoHash::parse(hash)?;
        let index: usize = index.parse().ok()?;
        let entry = self.torrents().get(&hash).cloned()?;
        match entry.state() {
            EntryState::Ready(selection) if selection.index == index => {
                Some((selection, entry.stopped.clone()))
            }
            _ => None,
        }
    }

    /// A body streaming `len` bytes of the selected file from `start`. Reads wait for pieces
    /// that are not downloaded yet, and librqbit fetches pieces ahead of the read position
    /// first. The body ends early if the torrent is stopped.
    async fn open(
        &self,
        selection: &Selection,
        start: u64,
        len: u64,
        stopped: CancellationToken,
    ) -> Result<Body, StatusCode> {
        let slot = tokio::time::timeout(
            STREAM_SLOT_TIMEOUT,
            self.stream_slots.clone().acquire_owned(),
        )
        .await
        .map_err(|_| StatusCode::SERVICE_UNAVAILABLE)?
        .map_err(|_| StatusCode::SERVICE_UNAVAILABLE)?;
        let mut stream = selection
            .handle
            .clone()
            .stream(selection.index)
            .await
            .map_err(|error| {
                log::warn!("torrent: cannot open stream: {error:#}");
                StatusCode::SERVICE_UNAVAILABLE
            })?;
        stream.seek(SeekFrom::Start(start)).await.map_err(|error| {
            // Unreachable for ranges resolved against the file length.
            log::warn!("torrent: cannot seek stream: {error}");
            StatusCode::INTERNAL_SERVER_ERROR
        })?;
        let chunks = ReaderStream::with_capacity(stream.take(len), CHUNK_BYTES)
            .take_until(stopped.cancelled_owned())
            .map(move |chunk| {
                // The slot is released when the player closes the connection.
                let _slot = &slot;
                chunk
            });
        Ok(http::stream_body(chunks))
    }

    /// Adds a torrent from a torrent file, without fetching metadata from peers.
    #[cfg(test)]
    pub(crate) async fn add_torrent_file(
        &self,
        hash: InfoHash,
        torrent: Bytes,
        files: Vec<TorrentFile>,
    ) -> Result<usize, TorrentError> {
        let entry = self.entry_or_insert(hash);
        let metadata = Metadata {
            torrent,
            files,
            peers: Vec::new(),
        };
        let selection = self.download(hash, metadata, None).await?;
        let index = selection.index;
        selection
            .handle
            .wait_until_initialized()
            .await
            .map_err(TorrentError::engine)?;
        entry.set_state(EntryState::Ready(selection));
        Ok(index)
    }

    fn entry_or_insert(&self, hash: InfoHash) -> Arc<Entry> {
        self.torrents()
            .entry(hash)
            .or_insert_with(|| {
                Arc::new(Entry {
                    adding: tokio::sync::Mutex::new(()),
                    stopped: CancellationToken::new(),
                    state: Mutex::new(EntryState::FetchingMetadata),
                })
            })
            .clone()
    }

    async fn delete_from_session(&self, hash: InfoHash) {
        let id = TorrentIdOrHash::Hash(hash.id20());
        if self.session.get(id).is_some()
            && let Err(error) = self.session.delete(id, false).await
        {
            log::warn!("torrent: could not remove {hash} from the session: {error:#}");
        }
    }

    /// Marks the torrent's cache folder as just used, for oldest-first pruning.
    async fn mark_used(&self, hash: InfoHash) {
        let folder = self.data_dir.join(hash.to_string());
        let touched = tokio::task::spawn_blocking(move || crate::cache::touch(&folder)).await;
        match touched {
            Ok(Ok(())) => {}
            Ok(Err(error)) => log::warn!("torrent: cannot mark {hash} as used: {error}"),
            Err(error) => log::warn!("torrent: cannot mark {hash} as used: {error}"),
        }
    }

    fn folder_name(&self, hash: InfoHash) -> Result<String, TorrentError> {
        let folder = self.data_dir.join(hash.to_string());
        folder.to_str().map(str::to_owned).ok_or_else(|| {
            TorrentError::Io(io::Error::new(
                io::ErrorKind::InvalidInput,
                "the cache directory path is not valid UTF-8",
            ))
        })
    }

    fn torrents(&self) -> MutexGuard<'_, HashMap<InfoHash, Arc<Entry>>> {
        // Every update leaves the map consistent, so a panic elsewhere cannot corrupt it.
        self.torrents.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

impl Entry {
    fn state(&self) -> EntryState {
        self.state
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    fn set_state(&self, state: EntryState) {
        *self.state.lock().unwrap_or_else(PoisonError::into_inner) = state;
    }

    /// Shows the error in the status, unless an earlier selection is still playing.
    fn record_failure(&self, error: &TorrentError) {
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        if !matches!(*state, EntryState::Ready(_)) {
            *state = EntryState::Failed(error.to_string());
        }
    }
}

impl Selection {
    fn status(&self) -> TorrentStatus {
        let stats = self.handle.stats();
        let downloaded = stats.file_progress.get(self.index).copied().unwrap_or(0);
        let (peers, download_speed) = stats.live.as_ref().map_or((0, 0), |live| {
            (
                live.snapshot.peer_stats.live,
                live.download_speed.as_bytes(),
            )
        });
        let state = match stats.state {
            TorrentStatsState::Initializing { .. } => TorrentState::Checking,
            TorrentStatsState::Error => TorrentState::Error,
            TorrentStatsState::Live | TorrentStatsState::Paused if downloaded >= self.file.len => {
                TorrentState::Ready
            }
            TorrentStatsState::Live | TorrentStatsState::Paused => TorrentState::Downloading,
        };
        TorrentStatus {
            state,
            peers,
            download_speed,
            downloaded_bytes: downloaded,
            file_size: self.file.len,
            file_name: Some(file_name(&self.file.path).to_owned()),
            error: stats.error,
        }
    }
}

/// `416 Range Not Satisfiable`, telling the player how long the file really is.
fn unsatisfiable(total: u64) -> Response<Body> {
    let mut response = http::empty(StatusCode::RANGE_NOT_SATISFIABLE);
    if let Ok(value) = HeaderValue::from_str(&format!("bytes */{total}")) {
        response.headers_mut().insert(header::CONTENT_RANGE, value);
    }
    response
}

/// The files listed in torrent metadata, in torrent order.
fn torrent_files(info: &ValidatedTorrentMetaV1Info<ByteBufOwned>) -> Vec<TorrentFile> {
    info.iter_file_details()
        .map(|details| TorrentFile {
            path: details
                .filename
                .iter_components()
                .collect::<Vec<_>>()
                .join("/"),
            len: details.len,
            padding: details.attrs().padding,
        })
        .collect()
}

/// Last component of a path inside a torrent.
fn file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

/// Makes ring the process-wide rustls provider. librqbit's own HTTP client (for HTTP
/// trackers) is built without an explicit provider and would panic without one.
fn install_tls_provider() {
    // Fails only if a provider is already installed, which serves just as well.
    let _ = rustls::crypto::ring::default_provider().install_default();
}

async fn start_session(
    cache_dir: &Path,
    data_dir: &Path,
    shutdown: &CancellationToken,
    network: Network,
    ipv4_only: bool,
    dht: Dht,
) -> Result<Arc<Session>, TorrentError> {
    let token = shutdown.child_token();
    let online = network == Network::Online;
    let options = SessionOptions {
        dht: (online && dht != Dht::Off).then(|| DhtSessionConfig {
            // An explicit port wins over the saved one; port 0 is any free port.
            port: (dht == Dht::AnyPort).then_some(0),
            persistence: Some(DhtPersistenceConfig {
                config_filename: Some(cache_dir.join(DHT_FILE)),
                dump_interval: None,
            }),
            ..DhtSessionConfig::default()
        }),
        disable_trackers: !online,
        // LAN multicast rarely finds peers for public torrents and keeps phones awake.
        disable_local_service_discovery: true,
        // Torrents are added again by the app when played; nothing to restore at startup.
        persistence: None,
        fastresume: false,
        // Any free port, TCP only, no UPnP: incoming peers help, but are not required.
        listen: online.then(|| ListenerOptions {
            ipv4_only,
            ..ListenerOptions::default()
        }),
        ipv4_only,
        runtime_worker_threads: Some(BLOCKING_SLOTS),
        cancellation_token: Some(token.clone()),
        ..SessionOptions::default()
    };
    Session::new_with_opts(data_dir.to_path_buf(), options)
        .await
        .map_err(|error| {
            // Stop whatever the failed attempt already started.
            token.cancel();
            TorrentError::engine(error)
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::{Ipv4Addr, UdpSocket};

    #[test]
    fn file_name_is_the_last_component() {
        assert_eq!(file_name("Show/Season 1/S01E01.mkv"), "S01E01.mkv");
        assert_eq!(file_name("movie.mp4"), "movie.mp4");
    }

    /// Saves a DHT state with `port`, in the format librqbit writes, with an empty routing
    /// table.
    fn save_dht_port(cache_dir: &Path, port: u16) {
        let json = format!(
            r#"{{"addr":"0.0.0.0:{port}","table":{{"id":"0123456789abcdef0123456789abcdef01234567","size":0,"buckets":{{"data":[{{"bits":160,"start":"0000000000000000000000000000000000000000","end_inclusive":"ffffffffffffffffffffffffffffffffffffffff","data":{{"Leaf":{{"nodes":[]}}}}}}],"size":1,"max_size":512}}}}}}"#
        );
        std::fs::write(cache_dir.join(DHT_FILE), json).expect("write DHT state");
    }

    /// Holds a UDP port, like one Windows has reserved since the last run, and saves it as
    /// the DHT's port. IPv4 only: a dual-stack socket may still open a port held for IPv4.
    fn saved_port_in_use(cache_dir: &Path) -> (UdpSocket, u16) {
        let held = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).expect("bind");
        let port = held.local_addr().expect("address").port();
        save_dht_port(cache_dir, port);
        (held, port)
    }

    // Online: the DHT binds a real UDP port. Starting it does not wait for the network.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_saved_dht_port_that_cannot_be_opened_falls_back_to_another() {
        let cache = tempfile::tempdir().expect("temp dir");
        let (_held, port) = saved_port_in_use(cache.path());
        let shutdown = CancellationToken::new();

        // The saved state really pins the port: on its own, that attempt fails.
        let pinned = Engine::start_trying(
            cache.path(),
            &shutdown,
            Network::Online,
            &[(true, Dht::SavedPort)],
        )
        .await;
        assert!(
            pinned.is_err(),
            "the saved port should be impossible to open"
        );

        let attempts = [(true, Dht::SavedPort), (true, Dht::AnyPort)];
        let engine = Engine::start_trying(cache.path(), &shutdown, Network::Online, &attempts)
            .await
            .expect("engine starts");
        let dht = engine.session.get_dht().expect("DHT on");
        assert_ne!(dht.listen_addr().port(), port);
        shutdown.cancel();
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_engine_starts_without_dht_when_it_cannot_open_one() {
        let cache = tempfile::tempdir().expect("temp dir");
        let (_held, _) = saved_port_in_use(cache.path());
        let shutdown = CancellationToken::new();

        let attempts = [(true, Dht::SavedPort), (true, Dht::Off)];
        let engine = Engine::start_trying(cache.path(), &shutdown, Network::Online, &attempts)
            .await
            .expect("engine starts");
        assert!(engine.session.get_dht().is_none());
        shutdown.cancel();
    }
}
