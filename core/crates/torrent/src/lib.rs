// SPDX-License-Identifier: GPL-3.0-or-later
//! Embedded torrent engine with a local HTTP server for streaming.
//!
//! [`StreamServer`] hands the video player plain `http://127.0.0.1` URLs, whatever the
//! source: torrents are downloaded with librqbit, which fetches the pieces near the player's
//! read position first, and remote `http(s)` sources are relayed with their `Range` requests.
//!
//! One device can also download torrents for the others on the home network
//! ([`StreamServer::serve_lan`]); a device paired with it ([`pair`]) streams through it with
//! [`StreamServer::remote_torrent_url`], still behind a loopback URL.
//!
//! ```no_run
//! # async fn play() -> Result<(), stremionow_torrent::TorrentError> {
//! use stremionow_torrent::{ServerConfig, StreamServer};
//!
//! let server = StreamServer::start(ServerConfig {
//!     cache_dir: "/data/cache/streams".into(),
//!     max_cache_bytes: 4 << 30,
//! })
//! .await?;
//! let trackers = vec!["tracker:udp://tracker.opentrackr.org:1337/announce".to_owned()];
//! let url = server
//!     .torrent_url("08ada5a7a6183aae1e09d831df6748d566095a10", None, None, &trackers)
//!     .await?;
//! // Give `url` to the player; poll `server.status(..)` for the loading screen.
//! # Ok(())
//! # }
//! ```

mod address;
mod cache;
mod error;
mod files;
mod http;
mod lan;
mod magnet;
mod proxy;
mod range;
mod secret;
mod server;
mod status;
mod torrent;

pub use address::{is_private_url, reaches_private_network, url_host};
pub use error::TorrentError;
pub use lan::{
    DiscoveredServer, LanConfig, LanServer, LanStatus, PairError, PairedDevice, PairingCode,
    PauseReason, RemoteError, RemoteHealth, RemoteServer, TorrentRoute, discover, pair,
};
pub use server::{ServerConfig, StreamServer};
pub use status::{TorrentState, TorrentStatus};
