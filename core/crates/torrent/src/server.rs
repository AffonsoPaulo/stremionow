// SPDX-License-Identifier: GPL-3.0-or-later
//! The public entry point: a loopback HTTP server that streams torrents and relays remote
//! sources to the video player.

use std::fmt;
use std::io;
use std::net::Ipv4Addr;
use std::path::PathBuf;
use std::sync::Arc;

use hyper::body::Incoming;
use hyper::header;
use hyper::{Method, Request, Response};
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::http::{self, Body};
use crate::magnet::{self, InfoHash};
use crate::proxy::Proxy;
use crate::torrent::{Engine, Network};
use crate::{TorrentError, TorrentStatus, cache, secret};

/// Settings for [`StreamServer::start`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerConfig {
    /// Folder for downloaded torrent data and the DHT routing table. Should be the app's
    /// cache directory: everything in it can be deleted and downloaded again.
    pub cache_dir: PathBuf,
    /// Disk space [`StreamServer::prune_cache`] trims downloaded data to.
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
    shared: Arc<Shared>,
    _running: Arc<StopOnDrop>,
}

/// State used by both the public API and the request handlers.
struct Shared {
    port: u16,
    secret: String,
    /// `http://127.0.0.1:<port>/<secret>`, the prefix of every URL handed out.
    base_url: String,
    max_cache_bytes: u64,
    engine: Engine,
    proxy: Proxy,
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
    pub async fn start(config: ServerConfig) -> Result<Self, TorrentError> {
        Self::start_with(config, Network::Online).await
    }

    async fn start_with(config: ServerConfig, network: Network) -> Result<Self, TorrentError> {
        let shutdown = CancellationToken::new();
        // Created first, so that an error below also stops whatever already started.
        let running = Arc::new(StopOnDrop(shutdown.clone()));
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let port = listener.local_addr()?.port();
        let secret = secret::random_hex()?;
        let proxy = Proxy::new()?;
        let engine = Engine::start(&config.cache_dir, &shutdown, network).await?;
        let shared = Arc::new(Shared {
            port,
            base_url: format!("http://127.0.0.1:{port}/{secret}"),
            secret,
            max_cache_bytes: config.max_cache_bytes,
            engine,
            proxy,
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
    /// are ignored (DHT is always on). The call waits until peers send the file list, for a
    /// minute at most, then downloads only the chosen file: `file_idx` when it names a file
    /// of the torrent, otherwise the largest video.
    ///
    /// Calling it again for a torrent already added switches it to the newly chosen file;
    /// URLs of the previous file then answer 404. The URL has the form
    /// `http://127.0.0.1:<port>/<secret>/torrent/<info hash>/<file index>`.
    pub async fn torrent_url(
        &self,
        info_hash: &str,
        file_idx: Option<u32>,
        trackers: &[String],
    ) -> Result<String, TorrentError> {
        let hash = parse_hash(info_hash)?;
        let requested = file_idx.and_then(|index| usize::try_from(index).ok());
        let trackers = magnet::tracker_urls(trackers);
        let index = self.shared.engine.add(hash, requested, &trackers).await?;
        Ok(format!("{}/torrent/{hash}/{index}", self.shared.base_url))
    }

    /// Registers a remote `http` or `https` URL and returns a local URL for it, of the form
    /// `http://127.0.0.1:<port>/<secret>/proxy/<token>`.
    ///
    /// GET and HEAD requests to it are forwarded with their `Range` header, following
    /// redirects, and the answer is streamed back with its status and its `Content-Type`,
    /// `Content-Length`, `Content-Range`, `Accept-Ranges` and `Last-Modified` headers. The
    /// most recent 64 registrations stay valid.
    pub fn proxy_url(&self, remote_url: &str) -> Result<String, TorrentError> {
        let token = self.shared.proxy.register(remote_url)?;
        Ok(format!("{}/proxy/{token}", self.shared.base_url))
    }

    /// Progress of a torrent added with [`Self::torrent_url`], or `None` if it was never
    /// added, was stopped, or `info_hash` is malformed.
    pub fn status(&self, info_hash: &str) -> Option<TorrentStatus> {
        let hash = InfoHash::parse(info_hash)?;
        self.shared.engine.status(hash)
    }

    /// Stops downloading a torrent and forgets it: its URLs answer 404 and open streams end.
    /// Downloaded data stays in the cache until [`Self::prune_cache`] removes it.
    pub async fn stop(&self, info_hash: &str) {
        if let Some(hash) = InfoHash::parse(info_hash) {
            self.shared.engine.stop(hash).await;
        }
    }

    /// Deletes cached torrent data, least recently played first, until the cache fits in
    /// [`ServerConfig::max_cache_bytes`]. Torrents that have not been stopped are kept even
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
            _ => http::not_found(),
        }
    }

    fn prune_cache(&self) -> Result<u64, TorrentError> {
        let entries = cache::scan(self.engine.data_dir())?;
        let doomed = cache::plan(entries, self.max_cache_bytes, |name| {
            self.engine.in_use(name)
        });
        let mut freed = 0u64;
        for entry in doomed {
            // The torrent may have been played again since the plan was made.
            if self.engine.in_use(&entry.name) {
                continue;
            }
            match cache::remove(&entry) {
                Ok(()) => freed = freed.saturating_add(entry.bytes),
                Err(error) => log::warn!("cache: cannot remove {}: {error}", entry.name),
            }
        }
        Ok(freed)
    }
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
mod tests;
