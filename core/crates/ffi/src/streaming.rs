// SPDX-License-Identifier: GPL-3.0-or-later
//! Local playback addresses for every source.
//!
//! The player only ever receives `http://127.0.0.1` URLs: torrents go through the embedded
//! engine, or through the device paired for torrents (ADR 0008), and web sources through
//! the relay. Besides streaming torrents, this keeps debrid addresses, which carry the
//! user's API key, out of the player and its logs.
//!
//! Only torrents go through another device. Web and debrid sources are relayed by this
//! device, as before: sending them through another one would not save bandwidth, would put
//! debrid keys on a second device, and would turn the server into a proxy into its network.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use stremionow_torrent::{
    LanServer, RemoteError, RemoteHealth, RemoteServer, ServerConfig, StreamServer, TorrentError,
    TorrentRoute, TorrentState, TorrentStatus, is_private_url, url_host,
};
use tokio::runtime::Runtime;
use tokio::sync::OnceCell;

use crate::core::StremioCore;
use crate::playback::{StreamSource, addon_key};
use crate::records::CoreError;
use crate::sharing::SecretSink;

/// How long to look for the paired device at a new address before giving up on it.
const REDISCOVERY_TIMEOUT: Duration = Duration::from_secs(2);

/// Tracker entries kept per source; addons sometimes send hundreds.
const MAX_TRACKERS: usize = 64;

/// The streaming server and the runtime it lives on, started on first use.
pub(crate) struct Streaming {
    /// Dedicated to the server so its tasks never depend on who calls into the core.
    runtime: Option<Runtime>,
    server: OnceCell<StreamServer>,
    cache_dir: PathBuf,
    /// Serving torrents to other devices, when on.
    lan: Mutex<Option<Arc<LanServer>>>,
    /// The device used for torrents, and where to save its pairing when it moves.
    remote: Mutex<Option<(RemoteServer, Box<dyn SecretSink>)>>,
    /// Torrents that should have played from the other device but run here, and why.
    fallbacks: Mutex<HashMap<String, FallbackReason>>,
    on_mobile_data: AtomicBool,
    /// What the app knows about the current network (Android), for the server.
    network_hint: Mutex<Option<String>>,
    /// How many playbacks use each torrent, by lowercase info hash. One torrent can play in
    /// two players at once (the next episode of a season pack starts before the last player
    /// closes), and it stops only when the last one is done with it.
    torrent_uses: Mutex<HashMap<String, usize>>,
}

impl Streaming {
    pub(crate) fn new(cache_dir: PathBuf) -> Result<Self, CoreError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("stremionow-stream")
            .enable_all()
            .build()
            .map_err(|e| CoreError::Playback {
                reason: e.to_string(),
            })?;
        Ok(Self {
            runtime: Some(runtime),
            server: OnceCell::new(),
            cache_dir,
            lan: Mutex::default(),
            remote: Mutex::default(),
            fallbacks: Mutex::default(),
            on_mobile_data: AtomicBool::new(false),
            network_hint: Mutex::default(),
            torrent_uses: Mutex::default(),
        })
    }

    /// Counts a playback of a torrent, which one call to [`Self::give_torrent`] ends.
    fn take_torrent(&self, info_hash: &str) {
        *lock(&self.torrent_uses)
            .entry(info_hash.to_ascii_lowercase())
            .or_default() += 1;
    }

    /// Ends a playback of a torrent. Returns whether none is left, so the torrent should stop;
    /// also true for a torrent no playback counted.
    fn give_torrent(&self, info_hash: &str) -> bool {
        let mut uses = lock(&self.torrent_uses);
        let key = info_hash.to_ascii_lowercase();
        match uses.get_mut(&key) {
            Some(count) if *count > 1 => {
                *count -= 1;
                false
            }
            _ => {
                uses.remove(&key);
                true
            }
        }
    }

    fn runtime(&self) -> Result<&Runtime, CoreError> {
        self.runtime.as_ref().ok_or_else(|| CoreError::Playback {
            reason: "the streaming runtime has stopped".into(),
        })
    }

    /// The server, started with `max_cache_bytes` the first time it is needed.
    async fn server(&self, max_cache_bytes: u64) -> Result<StreamServer, CoreError> {
        let handle = self.runtime()?.handle().clone();
        let cache_dir = self.cache_dir.clone();
        self.server
            .get_or_try_init(|| async move {
                handle
                    .spawn(StreamServer::start(ServerConfig {
                        cache_dir,
                        max_cache_bytes,
                    }))
                    .await
                    .map_err(|e| CoreError::Playback {
                        reason: e.to_string(),
                    })?
                    .map_err(CoreError::from)
                    .inspect(|server| {
                        // Trim what an earlier run left over (it may have been killed mid-play).
                        let server = server.clone();
                        handle.spawn(async move { server.prune_cache().await });
                    })
            })
            .await
            .cloned()
    }

    pub(crate) fn lan(&self) -> Option<Arc<LanServer>> {
        lock(&self.lan).clone()
    }

    pub(crate) fn with_lan<T>(&self, f: impl FnOnce(&LanServer) -> T) -> Option<T> {
        lock(&self.lan).as_deref().map(f)
    }

    /// Replaces the LAN server and returns the previous one. Serving and using another
    /// device exclude each other, so starting to serve stops using the other device.
    pub(crate) fn set_lan(&self, lan: Option<LanServer>) -> Option<Arc<LanServer>> {
        if lan.is_some() {
            lock(&self.remote).take();
        }
        std::mem::replace(&mut *lock(&self.lan), lan.map(Arc::new))
    }

    pub(crate) fn remote(&self) -> Option<RemoteServer> {
        lock(&self.remote)
            .as_ref()
            .map(|(server, _)| server.clone())
    }

    /// Replaces the device used for torrents and returns the previous one.
    pub(crate) fn set_remote(
        &self,
        remote: Option<(RemoteServer, Box<dyn SecretSink>)>,
    ) -> Option<RemoteServer> {
        std::mem::replace(&mut *lock(&self.remote), remote).map(|(server, _)| server)
    }

    /// Looks for the paired device at another address (its DHCP lease changed, or its old
    /// address now belongs to another device). If it answers at one of the addresses it
    /// announces, uses and saves that address.
    pub(crate) async fn rediscover(&self) -> Option<RemoteServer> {
        let current = self.remote()?;
        let id = current.server_id().to_owned();
        let addresses: Vec<std::net::SocketAddr> = self
            .run(stremionow_torrent::discover(REDISCOVERY_TIMEOUT, None))
            .await
            .ok()?
            .into_iter()
            .filter(|server| server.is_server(&id))
            .flat_map(|server| server.addresses)
            .filter(|address| *address != current.address())
            .collect();
        let mut moved = None;
        for address in addresses {
            let Ok(candidate) = current.at(address) else {
                continue;
            };
            let check = candidate.clone();
            if self.run(async move { check.check().await }).await.ok() == Some(RemoteHealth::Ready)
            {
                moved = Some(candidate);
                break;
            }
        }
        let moved = moved?;
        let mut remote = lock(&self.remote);
        // Unless the user changed the pairing meanwhile.
        let (server, sink) = remote
            .as_mut()
            .filter(|(server, _)| server.server_id() == id)?;
        *server = moved.clone();
        sink.save(moved.save());
        log::info!("streaming: the other device moved to a new address");
        Some(moved)
    }

    pub(crate) fn network_hint(&self) -> Option<String> {
        lock(&self.network_hint).clone()
    }

    /// Records what the app knows about the network and passes it to the server.
    pub(crate) fn set_network_hint(&self, hint: Option<String>) {
        *lock(&self.network_hint) = hint.clone();
        self.with_lan(|lan| lan.set_network_hint(hint));
    }

    pub(crate) fn set_on_mobile_data(&self, on_mobile_data: bool) {
        self.on_mobile_data.store(on_mobile_data, Ordering::Relaxed);
    }

    /// Allows torrents on this device unless it is on mobile data with `wifi_only` set.
    pub(crate) fn apply_network_policy(&self, wifi_only: bool) {
        let allowed = !(wifi_only && self.on_mobile_data.load(Ordering::Relaxed));
        if let Some(server) = self.server.get() {
            server.set_local_torrents_allowed(allowed);
        }
        self.with_lan(|lan| lan.set_torrents_allowed(allowed));
    }

    fn note_fallback(&self, info_hash: &str, reason: FallbackReason) {
        lock(&self.fallbacks).insert(info_hash.to_ascii_lowercase(), reason);
    }

    fn forget_fallback(&self, info_hash: &str) {
        lock(&self.fallbacks).remove(&info_hash.to_ascii_lowercase());
    }

    fn fallback(&self, info_hash: &str) -> Option<FallbackReason> {
        lock(&self.fallbacks)
            .get(&info_hash.to_ascii_lowercase())
            .copied()
    }

    /// Runs a server call on the streaming runtime.
    pub(crate) async fn run<T, F>(&self, task: F) -> Result<T, CoreError>
    where
        T: Send + 'static,
        F: Future<Output = T> + Send + 'static,
    {
        self.runtime()?
            .spawn(task)
            .await
            .map_err(|e| CoreError::Playback {
                reason: e.to_string(),
            })
    }
}

/// The cache size from Settings, in bytes.
fn cache_bytes(torrent_cache_mb: u32) -> u64 {
    u64::from(torrent_cache_mb) * 1024 * 1024
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    // Every update leaves the data consistent, so a panic elsewhere cannot corrupt it.
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Drop for Streaming {
    fn drop(&mut self) {
        // Dropping a runtime blocks, which panics inside async code; let it wind down instead.
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }
    }
}

/// How far a torrent is from playing, for the loading screen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TorrentPhase {
    /// Finding peers and downloading the torrent's file list.
    Metadata,
    /// Verifying data already in the cache.
    Checking,
    Downloading,
    /// The file can be streamed.
    Ready,
    Failed,
}

/// Why a torrent meant for the other device runs on this one.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FallbackReason {
    /// The other device did not answer: off, asleep or on another network.
    Unreachable,
    /// The other device no longer knows this one; pair again in Settings.
    Unpaired,
    /// The other device is on mobile data and not starting torrents.
    Paused,
}

/// Which device downloads a torrent.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TorrentSource {
    ThisDevice,
    /// The device paired for torrents, called `name`.
    Remote {
        name: String,
    },
    /// This device, because the one called `name` could not.
    FellBack {
        name: String,
        reason: FallbackReason,
    },
}

/// Live state of a torrent.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TorrentProgress {
    pub phase: TorrentPhase,
    pub source: TorrentSource,
    pub peers: u32,
    /// Bytes per second.
    pub download_speed: u64,
    pub downloaded_bytes: u64,
    pub file_size: u64,
    pub file_name: Option<String>,
}

impl From<TorrentStatus> for TorrentProgress {
    fn from(status: TorrentStatus) -> Self {
        Self {
            phase: match status.state {
                TorrentState::Metadata => TorrentPhase::Metadata,
                TorrentState::Checking => TorrentPhase::Checking,
                TorrentState::Downloading => TorrentPhase::Downloading,
                TorrentState::Ready => TorrentPhase::Ready,
                TorrentState::Error => TorrentPhase::Failed,
            },
            source: TorrentSource::ThisDevice,
            peers: status.peers,
            download_speed: status.download_speed,
            downloaded_bytes: status.downloaded_bytes,
            file_size: status.file_size,
            file_name: status.file_name,
        }
    }
}

impl From<&RemoteError> for FallbackReason {
    fn from(error: &RemoteError) -> Self {
        match error {
            RemoteError::Unpaired | RemoteError::CertificateChanged => Self::Unpaired,
            RemoteError::Paused => Self::Paused,
            _ => Self::Unreachable,
        }
    }
}

impl From<TorrentError> for CoreError {
    fn from(error: TorrentError) -> Self {
        match error {
            TorrentError::NoLocalNetwork | TorrentError::NotLocalNetwork => Self::NoLocalNetwork,
            error => Self::Playback {
                reason: error.to_string(),
            },
        }
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// The address to hand the player for `source`: always a local one. Torrents start
    /// downloading here, which can take a while to find peers; web sources are relayed.
    ///
    /// With another device paired for torrents, torrents are downloaded there and streamed
    /// through this device's loopback server. If that device cannot be reached, or no longer
    /// knows this one, the torrent downloads here instead and its progress says so.
    ///
    /// Every call for a torrent, whether it succeeds or not, counts as one playback, which
    /// exactly one [`StremioCore::stop_torrent`] ends. A web source may lead into the home
    /// network only when its addon is there too.
    pub async fn playback_url(&self, source: StreamSource) -> Result<String, CoreError> {
        if source.url.is_none()
            && let Some(info_hash) = &source.info_hash
        {
            self.streaming.take_torrent(info_hash);
        }
        let server = self.streaming_server().await?;
        if let Some(url) = source.url {
            let private_allowed = self.private_addon(&source.addon_key);
            return Ok(server.proxy_url(&url, private_allowed)?);
        }
        let info_hash = source.info_hash.ok_or(CoreError::NotFound)?;
        let trackers: Vec<String> = source.trackers.into_iter().take(MAX_TRACKERS).collect();
        let file_idx = source.file_idx;
        // Without an index, the title often names the file or the episode of a season pack.
        let file_hint = Some(source.description).filter(|text| !text.trim().is_empty());
        let file_hint = file_hint.filter(|_| file_idx.is_none());
        self.streaming.forget_fallback(&info_hash);
        let remote = self
            .streaming
            .remote()
            .filter(|_| self.streaming.lan().is_none());
        if let Some(remote) = remote {
            match self
                .remote_playback_url(
                    &server,
                    remote,
                    &info_hash,
                    file_idx,
                    file_hint.clone(),
                    &trackers,
                )
                .await?
            {
                Ok(url) => return Ok(url),
                Err(RemoteError::Torrent(reason)) => return Err(CoreError::Playback { reason }),
                Err(RemoteError::Stopped) => {
                    return Err(CoreError::Playback {
                        reason: RemoteError::Stopped.to_string(),
                    });
                }
                Err(error) => {
                    log::warn!("streaming: {error}; downloading on this device instead");
                    self.streaming
                        .note_fallback(&info_hash, FallbackReason::from(&error));
                }
            }
        }
        self.streaming
            .run(async move {
                server
                    .torrent_url(&info_hash, file_idx, file_hint.as_deref(), &trackers)
                    .await
            })
            .await?
            .map_err(CoreError::from)
    }

    /// A local address relaying a web file, such as a subtitle, so the player never sees
    /// the original address (configured addons can carry keys in it). An address in the home
    /// network is relayed only from the host of an addon installed there.
    pub async fn relay_url(&self, url: String) -> Result<String, CoreError> {
        let private_allowed = self.on_private_addon_host(&url);
        Ok(self
            .streaming_server()
            .await?
            .proxy_url(&url, private_allowed)?)
    }

    /// Live state of a torrent started by [`StremioCore::playback_url`], if it is running,
    /// including which device downloads it.
    pub fn torrent_status(&self, info_hash: String) -> Option<TorrentProgress> {
        let server = self.streaming.server.get()?;
        let mut progress = TorrentProgress::from(server.status(&info_hash)?);
        progress.source = match server.torrent_route(&info_hash) {
            Some(TorrentRoute::Remote { server_name }) => {
                TorrentSource::Remote { name: server_name }
            }
            Some(TorrentRoute::FellBack { server_name }) => TorrentSource::FellBack {
                name: server_name,
                reason: FallbackReason::Unreachable,
            },
            _ => match (self.streaming.fallback(&info_hash), self.streaming.remote()) {
                (Some(reason), Some(remote)) => TorrentSource::FellBack {
                    name: remote.name().to_owned(),
                    reason,
                },
                _ => TorrentSource::ThisDevice,
            },
        };
        Some(progress)
    }

    /// Ends one playback of a torrent started by [`StremioCore::playback_url`]. When no other
    /// playback uses it, stops it and trims the cache to its size limit.
    pub async fn stop_torrent(&self, info_hash: String) -> Result<(), CoreError> {
        if !self.streaming.give_torrent(&info_hash) {
            return Ok(());
        }
        self.streaming.forget_fallback(&info_hash);
        let Some(server) = self.streaming.server.get().cloned() else {
            return Ok(());
        };
        self.streaming
            .run(async move {
                server.stop(&info_hash).await;
                server.prune_cache().await.map(|_| ())
            })
            .await?
            .map_err(CoreError::from)
    }
}

impl StremioCore {
    /// The stream server, started with the cache size from Settings on first use.
    pub(crate) async fn streaming_server(&self) -> Result<StreamServer, CoreError> {
        let max_cache_bytes = cache_bytes(self.settings()?.torrent_cache_mb);
        let server = self.streaming.server(max_cache_bytes).await?;
        server.set_max_cache_bytes(max_cache_bytes);
        self.apply_network_policy();
        Ok(server)
    }

    /// Gives the running server the cache size from Settings, which may have changed.
    pub(crate) fn apply_cache_limit(&self) {
        let Some(server) = self.streaming.server.get() else {
            return;
        };
        match self.settings() {
            Ok(settings) => server.set_max_cache_bytes(cache_bytes(settings.torrent_cache_mb)),
            Err(error) => log::warn!("streaming: cannot read the cache size: {error}"),
        }
    }

    /// Whether the enabled addon whose [`addon_key`] is `key` is on this device or the home
    /// network, which makes its sources there expected rather than an addon reaching in.
    pub(crate) fn private_addon(&self, key: &str) -> bool {
        self.enabled().is_ok_and(|addons| {
            addons.iter().any(|addon| {
                let manifest = addon.url.manifest_url();
                addon_key(manifest) == key && is_private_url(manifest)
            })
        })
    }

    /// Whether `url` is on the host of an addon on this device or the home network. Subtitle
    /// addresses arrive without their addon; a home-network addon serves its own files.
    fn on_private_addon_host(&self, url: &str) -> bool {
        let Some(host) = url_host(url) else {
            return false;
        };
        self.enabled().is_ok_and(|addons| {
            addons.iter().any(|addon| {
                let manifest = addon.url.manifest_url();
                is_private_url(manifest) && url_host(manifest).as_deref() == Some(host.as_str())
            })
        })
    }

    /// Starts a torrent on the other device, looking for it at a new address once if it does
    /// not answer at the saved one.
    async fn remote_playback_url(
        &self,
        server: &StreamServer,
        remote: RemoteServer,
        info_hash: &str,
        file_idx: Option<u32>,
        file_hint: Option<String>,
        trackers: &[String],
    ) -> Result<Result<String, RemoteError>, CoreError> {
        let attempt = |remote: RemoteServer| {
            let (server, info_hash, trackers, file_hint) = (
                server.clone(),
                info_hash.to_owned(),
                trackers.to_vec(),
                file_hint.clone(),
            );
            async move {
                server
                    .remote_torrent_url(
                        &remote,
                        &info_hash,
                        file_idx,
                        file_hint.as_deref(),
                        &trackers,
                    )
                    .await
            }
        };
        let first = self.streaming.run(attempt(remote)).await?;
        // Not answering, or another device at its address: it may have moved.
        if !matches!(
            first,
            Err(RemoteError::Unreachable | RemoteError::CertificateChanged)
        ) {
            return Ok(first);
        }
        match self.streaming.rediscover().await {
            Some(moved) => self.streaming.run(attempt(moved)).await,
            None => Ok(first),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_torrent_stops_with_its_last_playback() {
        let dir = tempfile::tempdir().expect("temp dir");
        let streaming = Streaming::new(dir.path().to_path_buf()).expect("streaming");
        let hash = "08ADA5A7A6183AAE1E09D831DF6748D566095A10";
        streaming.take_torrent(hash);
        streaming.take_torrent(&hash.to_ascii_lowercase());
        assert!(
            !streaming.give_torrent(hash),
            "another player still plays it"
        );
        assert!(streaming.give_torrent(hash));
        // A stop nobody counted a playback for stops it, as before counting.
        assert!(streaming.give_torrent(hash));
    }

    #[test]
    fn without_an_addon_there_nothing_leads_into_the_home_network() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        assert!(!core.private_addon("k"));
        assert!(!core.on_private_addon_host("http://192.168.1.5:8080/subtitles.srt"));
        assert!(!core.on_private_addon_host("not a url"));
    }
}
