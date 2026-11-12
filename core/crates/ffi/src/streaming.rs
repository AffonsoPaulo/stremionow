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
    TorrentRoute, TorrentState, TorrentStatus,
};
use tokio::runtime::Runtime;
use tokio::sync::OnceCell;

use crate::core::StremioCore;
use crate::playback::StreamSource;
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
        })
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

    /// Looks for the paired device at another address (its DHCP lease changed). If it
    /// answers there, uses and saves the new address.
    pub(crate) async fn rediscover(&self) -> Option<RemoteServer> {
        let current = self.remote()?;
        let id = current.server_id().to_owned();
        let found = self
            .run(stremionow_torrent::discover(REDISCOVERY_TIMEOUT, None))
            .await
            .ok()?
            .into_iter()
            .find(|server| server.server_id == id && server.address != current.address())?;
        let moved = current.at(found.address).ok()?;
        let check = moved.clone();
        let health = self.run(async move { check.check().await }).await.ok()?;
        if health != RemoteHealth::Ready {
            return None;
        }
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
            RemoteError::Unpaired => Self::Unpaired,
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
    pub async fn playback_url(&self, source: StreamSource) -> Result<String, CoreError> {
        let server = self.streaming_server().await?;
        if let Some(url) = source.url {
            return Ok(server.proxy_url(&url)?);
        }
        let info_hash = source.info_hash.ok_or(CoreError::NotFound)?;
        let trackers: Vec<String> = source.trackers.into_iter().take(MAX_TRACKERS).collect();
        let file_idx = source.file_idx;
        self.streaming.forget_fallback(&info_hash);
        let remote = self
            .streaming
            .remote()
            .filter(|_| self.streaming.lan().is_none());
        if let Some(remote) = remote {
            match self
                .remote_playback_url(&server, remote, &info_hash, file_idx, &trackers)
                .await?
            {
                Ok(url) => return Ok(url),
                Err(RemoteError::Torrent(reason)) => return Err(CoreError::Playback { reason }),
                Err(error) => {
                    log::warn!("streaming: {error}; downloading on this device instead");
                    self.streaming
                        .note_fallback(&info_hash, FallbackReason::from(&error));
                }
            }
        }
        self.streaming
            .run(async move { server.torrent_url(&info_hash, file_idx, &trackers).await })
            .await?
            .map_err(CoreError::from)
    }

    /// A local address relaying a web file, such as a subtitle, so the player never sees
    /// the original address (configured addons can carry keys in it).
    pub async fn relay_url(&self, url: String) -> Result<String, CoreError> {
        Ok(self.streaming_server().await?.proxy_url(&url)?)
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

    /// Stops a torrent when playback ends and trims the cache to its size limit.
    pub async fn stop_torrent(&self, info_hash: String) -> Result<(), CoreError> {
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
        let max_cache_bytes = u64::from(self.settings()?.torrent_cache_mb) * 1024 * 1024;
        let server = self.streaming.server(max_cache_bytes).await?;
        self.apply_network_policy();
        Ok(server)
    }

    /// Starts a torrent on the other device, looking for it at a new address once if it does
    /// not answer at the saved one.
    async fn remote_playback_url(
        &self,
        server: &StreamServer,
        remote: RemoteServer,
        info_hash: &str,
        file_idx: Option<u32>,
        trackers: &[String],
    ) -> Result<Result<String, RemoteError>, CoreError> {
        let attempt = |remote: RemoteServer| {
            let (server, info_hash, trackers) =
                (server.clone(), info_hash.to_owned(), trackers.to_vec());
            async move {
                server
                    .remote_torrent_url(&remote, &info_hash, file_idx, &trackers)
                    .await
            }
        };
        let first = self.streaming.run(attempt(remote)).await?;
        if first != Err(RemoteError::Unreachable) {
            return Ok(first);
        }
        match self.streaming.rediscover().await {
            Some(moved) => self.streaming.run(attempt(moved)).await,
            None => Ok(first),
        }
    }
}
