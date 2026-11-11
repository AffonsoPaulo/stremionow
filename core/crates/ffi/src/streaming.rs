// SPDX-License-Identifier: GPL-3.0-or-later
//! Local playback addresses for every source.
//!
//! The player only ever receives `http://127.0.0.1` URLs: torrents go through the embedded
//! engine and web sources through its relay. Besides streaming torrents, this keeps debrid
//! addresses, which carry the user's API key, out of the player and its logs.

use std::path::PathBuf;

use stremionow_torrent::{ServerConfig, StreamServer, TorrentError, TorrentState, TorrentStatus};
use tokio::runtime::Runtime;
use tokio::sync::OnceCell;

use crate::core::StremioCore;
use crate::playback::StreamSource;
use crate::records::CoreError;

/// Tracker entries kept per source; addons sometimes send hundreds.
const MAX_TRACKERS: usize = 64;

/// The streaming server and the runtime it lives on, started on first use.
pub(crate) struct Streaming {
    /// Dedicated to the server so its tasks never depend on who calls into the core.
    runtime: Option<Runtime>,
    server: OnceCell<StreamServer>,
    cache_dir: PathBuf,
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

    /// Runs a server call on the streaming runtime.
    async fn run<T, F>(&self, task: F) -> Result<T, CoreError>
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

/// Live state of a torrent.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TorrentProgress {
    pub phase: TorrentPhase,
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
            peers: status.peers,
            download_speed: status.download_speed,
            downloaded_bytes: status.downloaded_bytes,
            file_size: status.file_size,
            file_name: status.file_name,
        }
    }
}

impl From<TorrentError> for CoreError {
    fn from(error: TorrentError) -> Self {
        Self::Playback {
            reason: error.to_string(),
        }
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// The address to hand the player for `source`: always a local one. Torrents start
    /// downloading here, which can take a while to find peers; web sources are relayed.
    pub async fn playback_url(&self, source: StreamSource) -> Result<String, CoreError> {
        let max_cache_bytes = u64::from(self.settings()?.torrent_cache_mb) * 1024 * 1024;
        let server = self.streaming.server(max_cache_bytes).await?;
        if let Some(url) = source.url {
            return Ok(server.proxy_url(&url)?);
        }
        let info_hash = source.info_hash.ok_or(CoreError::NotFound)?;
        let trackers: Vec<String> = source.trackers.into_iter().take(MAX_TRACKERS).collect();
        let file_idx = source.file_idx;
        self.streaming
            .run(async move { server.torrent_url(&info_hash, file_idx, &trackers).await })
            .await?
            .map_err(CoreError::from)
    }

    /// A local address relaying a web file, such as a subtitle, so the player never sees
    /// the original address (configured addons can carry keys in it).
    pub async fn relay_url(&self, url: String) -> Result<String, CoreError> {
        let max_cache_bytes = u64::from(self.settings()?.torrent_cache_mb) * 1024 * 1024;
        Ok(self
            .streaming
            .server(max_cache_bytes)
            .await?
            .proxy_url(&url)?)
    }

    /// Live state of a torrent started by [`StremioCore::playback_url`], if it is running.
    pub fn torrent_status(&self, info_hash: String) -> Option<TorrentProgress> {
        self.streaming
            .server
            .get()?
            .status(&info_hash)
            .map(TorrentProgress::from)
    }

    /// Stops a torrent when playback ends and trims the cache to its size limit.
    pub async fn stop_torrent(&self, info_hash: String) -> Result<(), CoreError> {
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
