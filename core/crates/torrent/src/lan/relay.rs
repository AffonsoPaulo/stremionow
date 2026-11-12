// SPDX-License-Identifier: GPL-3.0-or-later
//! Torrents streamed from a paired server, as seen by this device's loopback server.
//!
//! The player gets a loopback URL like any other source. Requests to it are forwarded to the
//! paired server with the token in a header, so the token never reaches the player, its logs
//! or its cache. Progress is polled in the background, so the loading screen reads it
//! without waiting on the network. When the server stops answering, the torrent continues
//! on this device from the same file, if local torrents are allowed right now.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, PoisonError};
use std::time::Duration;

use hyper::header::HeaderValue;
use hyper::{Method, Response, StatusCode};
use tokio_util::sync::CancellationToken;

use super::client::{RemoteError, RemoteServer};
use crate::TorrentStatus;
use crate::http::{self, Body};
use crate::magnet::InfoHash;
use crate::server::{Holder, Shared};

/// How often progress is read from the server.
const STATUS_INTERVAL: Duration = Duration::from_secs(1);

/// Where a torrent is being downloaded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TorrentRoute {
    /// On this device.
    Local,
    /// On the paired server called `server_name`.
    Remote { server_name: String },
    /// It started on the server, which stopped answering; this device took over.
    FellBack { server_name: String },
}

/// A torrent the paired server downloads for this device.
pub(crate) struct RemoteTorrent {
    pub(crate) server: RemoteServer,
    pub(crate) hash: InfoHash,
    /// The relay token in the loopback URL.
    pub(crate) token: String,
    /// The file being streamed, once the server has chosen it.
    pub(crate) index: OnceLock<usize>,
    pub(crate) trackers: Vec<String>,
    pub(crate) status: Mutex<Option<TorrentStatus>>,
    /// Set once this device took over.
    pub(crate) fell_back: AtomicBool,
    /// Serializes taking over, so parallel requests start the local torrent once.
    taking_over: tokio::sync::Mutex<()>,
    /// Stops the progress poller.
    pub(crate) stopped: CancellationToken,
}

impl RemoteTorrent {
    pub(crate) fn new(
        server: RemoteServer,
        hash: InfoHash,
        token: String,
        trackers: Vec<String>,
    ) -> Arc<Self> {
        Arc::new(Self {
            server,
            hash,
            token,
            index: OnceLock::new(),
            trackers,
            status: Mutex::new(None),
            fell_back: AtomicBool::new(false),
            taking_over: tokio::sync::Mutex::new(()),
            stopped: CancellationToken::new(),
        })
    }

    pub(crate) fn route(&self) -> TorrentRoute {
        let server_name = self.server.name().to_owned();
        if self.fell_back.load(Ordering::Relaxed) {
            TorrentRoute::FellBack { server_name }
        } else {
            TorrentRoute::Remote { server_name }
        }
    }

    pub(crate) fn last_status(&self) -> Option<TorrentStatus> {
        self.status
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    /// Reads progress from the server until stopped or fallen back.
    pub(crate) async fn poll(self: Arc<Self>) {
        loop {
            tokio::select! {
                () = self.stopped.cancelled() => return,
                () = tokio::time::sleep(STATUS_INTERVAL) => {}
            }
            if self.fell_back.load(Ordering::Relaxed) {
                return;
            }
            // Failures keep the last status; the relay notices a server that went away.
            if let Ok(status) = self.server.status(self.hash).await {
                *self.status.lock().unwrap_or_else(PoisonError::into_inner) = status;
            }
        }
    }

    /// Answers a player request: from the server, or from this device after taking over.
    pub(crate) async fn respond(
        self: &Arc<Self>,
        shared: &Shared,
        method: &Method,
        range: Option<&HeaderValue>,
    ) -> Response<Body> {
        let Some(&index) = self.index.get() else {
            return http::not_found();
        };
        if !self.fell_back.load(Ordering::Relaxed) {
            match self.forward(method, index, range).await {
                Ok(response) => return response,
                Err(RemoteError::Unreachable | RemoteError::Unpaired) => {}
                Err(_) => return http::empty(StatusCode::BAD_GATEWAY),
            }
            if !self.take_over(shared, index).await {
                return http::empty(StatusCode::BAD_GATEWAY);
            }
        }
        let range = range.and_then(|value| value.to_str().ok());
        shared
            .engine
            .respond(&self.hash.to_string(), &index.to_string(), method, range)
            .await
    }

    /// Forwards one request. A server that lost the torrent (it restarted, or the lease
    /// expired while this device slept) gets it again once. Any other answer, errors
    /// included, comes from a server that is up, and goes to the player as it is; only a
    /// server that cannot be reached is replaced by this device.
    async fn forward(
        &self,
        method: &Method,
        index: usize,
        range: Option<&HeaderValue>,
    ) -> Result<Response<Body>, RemoteError> {
        let mut readded = false;
        loop {
            let upstream = self
                .server
                .stream(method, self.hash, index, range)
                .send()
                .await
                .map_err(|_| self.server.connection_error())?;
            match upstream.status() {
                StatusCode::UNAUTHORIZED => return Err(RemoteError::Unpaired),
                StatusCode::NOT_FOUND if !readded => {
                    readded = true;
                    let file = u32::try_from(index).ok();
                    let again = self.server.add(self.hash, file, &self.trackers).await?;
                    if again != index {
                        return Err(RemoteError::Protocol);
                    }
                }
                _ => return Ok(crate::proxy::relay(upstream, method == Method::HEAD)),
            }
        }
    }

    /// Starts the same file on this device. Returns whether it is ready to stream.
    async fn take_over(&self, shared: &Shared, index: usize) -> bool {
        let _taking_over = self.taking_over.lock().await;
        if self.fell_back.load(Ordering::Relaxed) {
            return true;
        }
        if !shared.local_torrents_allowed() {
            log::warn!(
                "relay: the other device stopped answering; local torrents are not allowed now"
            );
            return false;
        }
        log::warn!("relay: the other device stopped answering; continuing on this device");
        match shared
            .add_for(self.hash, Holder::Local, Some(index), &self.trackers)
            .await
        {
            Ok(local) if local == index => {
                self.fell_back.store(true, Ordering::Relaxed);
                true
            }
            Ok(_) => {
                // Unreachable for the same torrent and file index; never stream another file.
                shared.release(self.hash, &Holder::Local).await;
                false
            }
            Err(error) => {
                log::warn!("relay: cannot continue on this device: {error}");
                false
            }
        }
    }
}
