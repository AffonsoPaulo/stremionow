// SPDX-License-Identifier: GPL-3.0-or-later
//! Errors reported to the app.

use std::fmt;
use std::time::Duration;

/// Why the stream server could not do what was asked.
#[derive(Debug, thiserror::Error)]
pub enum TorrentError {
    /// The info hash is not 40 hexadecimal digits.
    #[error("invalid info hash: {0:?}")]
    InvalidInfoHash(String),
    /// Only `http` and `https` sources can be proxied.
    #[error("unsupported URL: only http and https sources can be proxied")]
    UnsupportedUrl,
    /// A source from the internet points at this device or the home network.
    #[error("the source points into the local network, which only local addons may do")]
    PrivateAddress,
    /// No peer sent the torrent metadata in time.
    #[error("no peer sent the torrent metadata within {} seconds", .0.as_secs())]
    MetadataTimeout(Duration),
    /// The torrent contains nothing a video player can open.
    #[error("the torrent contains no video file")]
    NoVideoFile,
    /// The addon asked for a file the torrent does not have.
    #[error("the torrent has no file number {0}")]
    NoSuchFile(usize),
    /// A file name in the torrent would be written outside its folder, or open a device,
    /// on this system.
    #[error("the torrent has a file name that cannot be stored safely on this device")]
    UnsafeFileName,
    /// The disk holding the cache has too little free space for the file.
    #[error(
        "not enough free space: the file needs {} MB more, {} MB are free",
        .needed / 1_000_000,
        .free / 1_000_000
    )]
    NotEnoughSpace { needed: u64, free: u64 },
    /// [`StreamServer::stop`](crate::StreamServer::stop) was called while the torrent was
    /// being added.
    #[error("the torrent was stopped")]
    Stopped,
    /// An address for serving to other devices is not on the home network.
    #[error("the address is not on the local network")]
    NotLocalNetwork,
    /// Serving to other devices needs a home-network connection, and there is none.
    #[error("this device is not connected to a local network")]
    NoLocalNetwork,
    /// The torrent engine failed.
    #[error("torrent engine error: {0}")]
    Engine(String),
    /// A local file, socket or random number source failed.
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
}

impl TorrentError {
    /// Wraps an engine error. The alternate form makes `anyhow` errors print their causes.
    pub(crate) fn engine(error: impl fmt::Display) -> Self {
        Self::Engine(format!("{error:#}"))
    }
}
