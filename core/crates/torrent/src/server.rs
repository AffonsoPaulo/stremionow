// SPDX-License-Identifier: GPL-3.0-or-later
//! The public entry point: a loopback HTTP server that streams torrents and relays remote
//! sources to the video player.

use std::collections::{HashMap, HashSet};
use std::fmt;
use std::io;
use std::net::Ipv4Addr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};

use hyper::body::Incoming;
use hyper::header;
use hyper::{Method, Request, Response};
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::files::{self, FileRequest};
use crate::http::{self, Body};
use crate::lan::{RemoteError, RemoteServer, RemoteTorrent, TorrentRoute};
use crate::magnet::{self, InfoHash};
use crate::proxy::Proxy;
use crate::torrent::{LazyEngine, Network};
use crate::{TorrentError, TorrentStatus, cache, secret};

/// Settings for [`StreamServer::start`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerConfig {
    /// Folder for downloaded torrent data and the DHT routing table. Should be the app's
    /// cache directory: everything in it can be deleted and downloaded again.
    pub cache_dir: PathBuf,
    /// Disk space [`StreamServer::prune_cache`] trims downloaded data to; see also
    /// [`StreamServer::set_max_cache_bytes`].
    pub max_cache_bytes: u64,
}

/// Streams torrents and remote sources to a local video player over HTTP.
///
/// The server listens on `127.0.0.1` only, on a port chosen by the system. Every URL it hands
/// out starts with a random per-process secret, and anything else is answered with 404: other
/// apps on the device can reach loopback ports, and must not get a file server or an open
/// proxy out of it.
///
/// Cheap to clone; clones share the server, which shuts down when the last one is dropped.
/// It needs a Tokio runtime, preferably multi-threaded: librqbit does disk I/O on runtime
/// threads through `block_in_place`.
#[derive(Clone)]
pub struct StreamServer {
    pub(crate) shared: Arc<Shared>,
    _running: Arc<StopOnDrop>,
}

/// State used by both the public API and the request handlers.
pub(crate) struct Shared {
    port: u16,
    secret: String,
    /// `http://127.0.0.1:<port>/<secret>`, the prefix of every URL handed out.
    base_url: String,
    /// Changed by Settings while the server runs.
    max_cache_bytes: AtomicU64,
    /// Held while the cache is pruned, so two prunes never plan from the same scan.
    pruning: Mutex<()>,
    pub(crate) engine: LazyEngine,
    proxy: Proxy,
    /// Who uses each torrent in the engine: this device's player, paired devices or both.
    /// A torrent stops when its last holder lets go.
    holders: Mutex<HashMap<InfoHash, HashSet<Holder>>>,
    /// Torrents a paired server downloads for this device.
    remote: Mutex<HashMap<InfoHash, Arc<RemoteTorrent>>>,
    /// How many times each torrent was stopped, so a remote torrent stopped while it was
    /// still being added is noticed even before it was registered.
    stops: Mutex<HashMap<InfoHash, u64>>,
    /// Whether this device may download torrents itself right now (see
    /// [`StreamServer::set_local_torrents_allowed`]).
    local_allowed: AtomicBool,
}

/// Someone using a torrent of the engine.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(crate) enum Holder {
    /// This device's player.
    Local,
    /// A paired device, by id.
    Device(String),
}

/// Stops the listener and the torrent session when the last [`StreamServer`] goes away.
struct StopOnDrop(CancellationToken);

impl Drop for StopOnDrop {
    fn drop(&mut self) {
        self.0.cancel();
    }
}

impl StreamServer {
    /// Binds `127.0.0.1` on a random port and starts the torrent session, with DHT on and
    /// incoming peers accepted on a random port. Data is downloaded under
    /// `config.cache_dir`.
    ///
    /// A torrent session that cannot start does not fail the server: remote sources do not
    /// need it, and torrent requests start it again, returning its error if it still fails.
    pub async fn start(config: ServerConfig) -> Result<Self, TorrentError> {
        Self::start_with(config, Network::Online).await
    }

    pub(crate) async fn start_with(
        config: ServerConfig,
        network: Network,
    ) -> Result<Self, TorrentError> {
        let shutdown = CancellationToken::new();
        // Created first, so that an error below also stops whatever already started.
        let running = Arc::new(StopOnDrop(shutdown.clone()));
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let port = listener.local_addr()?.port();
        let secret = secret::random_hex()?;
        let proxy = Proxy::new()?;
        let engine = LazyEngine::new(&config.cache_dir, &shutdown, network);
        engine.warm_up().await;
        let shared = Arc::new(Shared {
            port,
            base_url: format!("http://127.0.0.1:{port}/{secret}"),
            secret,
            max_cache_bytes: AtomicU64::new(config.max_cache_bytes),
            pruning: Mutex::new(()),
            engine,
            proxy,
            holders: Mutex::default(),
            remote: Mutex::default(),
            stops: Mutex::default(),
            local_allowed: AtomicBool::new(true),
        });
        let handler = {
            let shared = Arc::clone(&shared);
            move |request| {
                let shared = Arc::clone(&shared);
                async move { shared.handle(request).await }
            }
        };
        tokio::spawn(http::serve(listener, shutdown, handler));
        log::info!("stream server listening on 127.0.0.1:{port}");
        Ok(Self {
            shared,
            _running: running,
        })
    }

    /// Adds a torrent and returns the URL that streams one of its files.
    ///
    /// `info_hash` is the 40-digit hex hash. `trackers` are the stream's `sources` as
    /// Stremio addons send them: `tracker:<url>` entries are announced to, `dht:` entries
    /// are ignored (the DHT is on whenever it can start). The call waits until peers send the
    /// file list, for a minute at most, then downloads only the chosen file: `file_idx`, which
    /// must name a file of the torrent ([`TorrentError::NoSuchFile`] otherwise); without one,
    /// the video `file_hint` names (the source's title, which often holds the file name or the
    /// episode), or else the largest video.
    ///
    /// Calling it again for a torrent already added switches this device's player to the
    /// newly chosen file; URLs of the previous file then answer 404, unless a paired device
    /// plays it. The URL has the form
    /// `http://127.0.0.1:<port>/<secret>/torrent/<info hash>/<file index>`.
    pub async fn torrent_url(
        &self,
        info_hash: &str,
        file_idx: Option<u32>,
        file_hint: Option<&str>,
        trackers: &[String],
    ) -> Result<String, TorrentError> {
        let hash = parse_hash(info_hash)?;
        let request = file_request(file_idx, file_hint)?;
        let trackers = magnet::tracker_urls(trackers);
        let index = self
            .shared
            .add_for(hash, Holder::Local, request, &trackers)
            .await?;
        Ok(format!("{}/torrent/{hash}/{index}", self.shared.base_url))
    }

    /// Like [`Self::torrent_url`], but the torrent is downloaded by the paired `server`; the
    /// URL is a loopback one all the same, of the form
    /// `http://127.0.0.1:<port>/<secret>/remote/<token>`.
    ///
    /// Progress comes from the server through [`Self::status`], and [`Self::stop`] stops it
    /// there. If the server stops answering during playback, the next player request
    /// continues the same file on this device, unless local torrents are not allowed.
    pub async fn remote_torrent_url(
        &self,
        server: &RemoteServer,
        info_hash: &str,
        file_idx: Option<u32>,
        file_hint: Option<&str>,
        trackers: &[String],
    ) -> Result<String, RemoteError> {
        let hash =
            parse_hash(info_hash).map_err(|error| RemoteError::Torrent(error.to_string()))?;
        let trackers = magnet::tracker_urls(trackers);
        let token = secret::random_hex().map_err(|_| RemoteError::Protocol)?;
        let torrent = RemoteTorrent::new(server.clone(), hash, token.clone(), trackers);
        let stops = self.shared.stops(hash);
        // Replacing an earlier request for the same torrent ends its polling.
        if let Some(previous) = self.shared.remote().insert(hash, Arc::clone(&torrent)) {
            previous.stopped.cancel();
        }
        torrent.start_polling();
        // Once the server chose the file, the relay asks for it by index alone.
        let file_hint = file_hint.map(files::bounded_hint);
        let added = server
            .add(hash, file_idx, file_hint, &torrent.trackers)
            .await;
        // The player may have closed, and stopped the torrent, while it was being added.
        let current = {
            let remote = self.shared.remote();
            remote
                .get(&hash)
                .is_some_and(|current| Arc::ptr_eq(current, &torrent))
        };
        let stopped = !current || self.shared.stops(hash) != stops;
        match added {
            Ok(index) if !stopped => {
                let _ = torrent.index.set(index);
                Ok(format!("{}/remote/{token}", self.shared.base_url))
            }
            result => {
                torrent.stopped.cancel();
                if current {
                    self.shared.remote().remove(&hash);
                }
                if result.is_ok() {
                    // Added for nobody: let the server go of it now rather than at lease end.
                    server.stop(hash).await;
                    return Err(RemoteError::Stopped);
                }
                result.map(|_| String::new())
            }
        }
    }

    /// Where a torrent started by this device is downloaded; `None` if it is not running.
    pub fn torrent_route(&self, info_hash: &str) -> Option<TorrentRoute> {
        let hash = InfoHash::parse(info_hash)?;
        if let Some(torrent) = self.shared.remote().get(&hash) {
            return Some(torrent.route());
        }
        self.shared
            .engine
            .status(hash, &Holder::Local)
            .map(|_| TorrentRoute::Local)
    }

    /// Changes the size [`Self::prune_cache`] trims the cache to, as when the user changes it
    /// in Settings.
    pub fn set_max_cache_bytes(&self, max_cache_bytes: u64) {
        self.shared
            .max_cache_bytes
            .store(max_cache_bytes, Ordering::Relaxed);
    }

    /// Whether this device may download torrents itself right now. The app turns it off
    /// while on mobile data with "Only on Wi-Fi" set, so a paired server that stops
    /// answering mid-film is not replaced by a download over mobile data.
    pub fn set_local_torrents_allowed(&self, allowed: bool) {
        self.shared.local_allowed.store(allowed, Ordering::Relaxed);
    }

    /// Registers a remote `http` or `https` URL and returns a local URL for it, of the form
    /// `http://127.0.0.1:<port>/<secret>/proxy/<token>`.
    ///
    /// GET and HEAD requests to it are forwarded with their `Range` header, following
    /// redirects, and the answer is streamed back with its status and its `Content-Type`,
    /// `Content-Length`, `Content-Range`, `Content-Encoding`, `Accept-Ranges` and
    /// `Last-Modified` headers. The 64 most recently registered or used stay valid.
    ///
    /// `allow_private` lets the URL point at this device or the home network, which only a
    /// source the user placed there should: an addon on the home network. Otherwise such a URL
    /// is refused with [`TorrentError::PrivateAddress`], and a public one may neither resolve
    /// nor redirect into a private network.
    pub fn proxy_url(&self, remote_url: &str, allow_private: bool) -> Result<String, TorrentError> {
        let token = self.shared.proxy.register(remote_url, allow_private)?;
        Ok(format!("{}/proxy/{token}", self.shared.base_url))
    }

    /// Progress of a torrent added with [`Self::torrent_url`], or `None` if it was never
    /// added, was stopped, or `info_hash` is malformed.
    pub fn status(&self, info_hash: &str) -> Option<TorrentStatus> {
        let hash = InfoHash::parse(info_hash)?;
        let remote = self.shared.remote().get(&hash).cloned();
        match remote {
            Some(torrent) if !torrent.fell_back.load(Ordering::Relaxed) => torrent.last_status(),
            _ => self.shared.engine.status(hash, &Holder::Local),
        }
    }

    /// Stops downloading a torrent and forgets it: its URLs answer 404 and open streams end.
    /// Downloaded data stays in the cache until [`Self::prune_cache`] removes it.
    pub async fn stop(&self, info_hash: &str) {
        let Some(hash) = InfoHash::parse(info_hash) else {
            return;
        };
        *self.shared.stops_mut().entry(hash).or_default() += 1;
        let remote = self.shared.remote().remove(&hash);
        if let Some(torrent) = remote {
            torrent.stopped.cancel();
            torrent.server.stop(hash).await;
        }
        self.shared.release(hash, &Holder::Local).await;
    }

    /// Deletes cached torrent data, least recently played first, until the cache fits in
    /// [`ServerConfig::max_cache_bytes`] (or the size set since with
    /// [`Self::set_max_cache_bytes`]). Torrents that have not been stopped are kept even
    /// when that leaves the cache over the limit. Returns the number of bytes freed.
    pub async fn prune_cache(&self) -> Result<u64, TorrentError> {
        let shared = Arc::clone(&self.shared);
        tokio::task::spawn_blocking(move || shared.prune_cache())
            .await
            .map_err(|error| TorrentError::Io(io::Error::other(error)))?
    }
}

impl fmt::Debug for StreamServer {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // The secret stays out: debug output ends up in logs.
        f.debug_struct("StreamServer")
            .field("port", &self.shared.port)
            .finish_non_exhaustive()
    }
}

impl Shared {
    /// Routes a request. Anything not under the secret, or not a known route, is 404.
    async fn handle(&self, request: Request<Incoming>) -> Response<Body> {
        let mut segments = request.uri().path().split('/').skip(1);
        let authorized = segments
            .next()
            .is_some_and(|candidate| secret::constant_time_eq(candidate, &self.secret));
        if !authorized {
            return http::not_found();
        }
        let route = (
            segments.next(),
            segments.next(),
            segments.next(),
            segments.next(),
        );
        let method = request.method();
        let range = request.headers().get(header::RANGE);
        match route {
            (Some("torrent"), Some(hash), Some(index), None) => {
                if !is_read(method) {
                    return http::method_not_allowed();
                }
                let range = range.and_then(|value| value.to_str().ok());
                self.engine.respond(hash, index, method, range).await
            }
            (Some("proxy"), Some(token), None, None) => {
                if !is_read(method) {
                    return http::method_not_allowed();
                }
                self.proxy.respond(token, method, range).await
            }
            (Some("remote"), Some(token), None, None) => {
                if !is_read(method) {
                    return http::method_not_allowed();
                }
                let torrent = self
                    .remote()
                    .values()
                    .find(|torrent| secret::constant_time_eq(token, &torrent.token))
                    .cloned();
                match torrent {
                    Some(torrent) => torrent.respond(self, method, range).await,
                    None => http::not_found(),
                }
            }
            _ => http::not_found(),
        }
    }

    /// Adds a torrent to the engine for `holder` and returns the index of the file chosen.
    pub(crate) async fn add_for(
        &self,
        hash: InfoHash,
        holder: Holder,
        request: FileRequest<'_>,
        trackers: &[String],
    ) -> Result<usize, TorrentError> {
        self.holders()
            .entry(hash)
            .or_default()
            .insert(holder.clone());
        self.engine.add(hash, &holder, request, trackers).await
    }

    /// Lets go of a torrent for `holder`, stopping it if nobody else uses it, and otherwise
    /// only the file `holder` played.
    pub(crate) async fn release(&self, hash: InfoHash, holder: &Holder) {
        let unused = {
            let mut holders = self.holders();
            match holders.get_mut(&hash) {
                Some(set) => {
                    set.remove(holder);
                    let empty = set.is_empty();
                    if empty {
                        holders.remove(&hash);
                    }
                    empty
                }
                None => true,
            }
        };
        if unused {
            self.engine.stop(hash).await;
        } else {
            self.engine.let_go(hash, holder).await;
        }
    }

    pub(crate) fn local_torrents_allowed(&self) -> bool {
        self.local_allowed.load(Ordering::Relaxed)
    }

    fn holders(&self) -> MutexGuard<'_, HashMap<InfoHash, HashSet<Holder>>> {
        self.holders.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn remote(&self) -> MutexGuard<'_, HashMap<InfoHash, Arc<RemoteTorrent>>> {
        self.remote.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn stops_mut(&self) -> MutexGuard<'_, HashMap<InfoHash, u64>> {
        self.stops.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn stops(&self, hash: InfoHash) -> u64 {
        self.stops_mut().get(&hash).copied().unwrap_or(0)
    }

    fn prune_cache(&self) -> Result<u64, TorrentError> {
        // Prunes run after every stop, from this device's player and from paired devices.
        let _pruning = self.pruning.lock().unwrap_or_else(PoisonError::into_inner);
        let entries = cache::scan(self.engine.data_dir())?;
        let max_bytes = self.max_cache_bytes.load(Ordering::Relaxed);
        let doomed = cache::plan(entries, max_bytes, |name| self.engine.in_use(name));
        let mut freed = 0u64;
        for entry in doomed {
            // The torrent may have been played again since the plan was made; while the
            // folder is claimed, playing it again waits for the removal to finish.
            let Some(_claim) = self.engine.claim_removal(&entry.name) else {
                continue;
            };
            match cache::remove(&entry) {
                Ok(()) => freed = freed.saturating_add(entry.bytes),
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => log::warn!("cache: cannot remove {}: {error}", entry.name),
            }
        }
        Ok(freed)
    }
}

/// What [`StreamServer::torrent_url`] asks the engine for.
fn file_request(
    file_idx: Option<u32>,
    file_hint: Option<&str>,
) -> Result<FileRequest<'_>, TorrentError> {
    let index = file_idx
        .map(|index| usize::try_from(index).map_err(|_| TorrentError::NoSuchFile(usize::MAX)))
        .transpose()?;
    Ok(FileRequest {
        index,
        hint: file_hint.map(files::bounded_hint),
    })
}

fn is_read(method: &Method) -> bool {
    method == Method::GET || method == Method::HEAD
}

fn parse_hash(info_hash: &str) -> Result<InfoHash, TorrentError> {
    InfoHash::parse(info_hash).ok_or_else(|| {
        // Bounded, in case the caller passes something huge.
        let shown: String = info_hash.chars().take(64).collect();
        TorrentError::InvalidInfoHash(shown)
    })
}

#[cfg(test)]
pub(crate) mod tests;
