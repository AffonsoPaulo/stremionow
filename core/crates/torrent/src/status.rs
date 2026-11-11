// SPDX-License-Identifier: GPL-3.0-or-later
//! Progress of a torrent, for the loading screen and the player.

/// Where a torrent is on its way to playback.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum TorrentState {
    /// Looking for peers and downloading the file list.
    Metadata,
    /// Verifying data already in the cache before downloading resumes.
    Checking,
    /// Downloading the selected file; it can be streamed meanwhile.
    Downloading,
    /// The whole selected file is on disk; playback no longer depends on peers.
    Ready,
    /// The torrent failed; [`TorrentStatus::error`] says why.
    Error,
}

/// A snapshot of a torrent's progress.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TorrentStatus {
    pub state: TorrentState,
    /// Peers currently connected.
    pub peers: u32,
    /// Current download speed in bytes per second.
    pub download_speed: u64,
    /// Bytes of the selected file already downloaded and verified.
    pub downloaded_bytes: u64,
    /// Size of the selected file in bytes; 0 until the metadata arrives.
    pub file_size: u64,
    /// Name of the selected file, once known.
    pub file_name: Option<String>,
    /// What went wrong, in the [`TorrentState::Error`] state.
    pub error: Option<String>,
}

impl TorrentStatus {
    /// A torrent whose metadata has not arrived yet.
    pub(crate) fn fetching_metadata() -> Self {
        Self {
            state: TorrentState::Metadata,
            peers: 0,
            download_speed: 0,
            downloaded_bytes: 0,
            file_size: 0,
            file_name: None,
            error: None,
        }
    }

    /// A torrent that could not be started.
    pub(crate) fn failed(error: String) -> Self {
        Self {
            state: TorrentState::Error,
            error: Some(error),
            ..Self::fetching_metadata()
        }
    }
}
