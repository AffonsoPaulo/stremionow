// SPDX-License-Identifier: GPL-3.0-or-later
//! JSON bodies of the device-to-device API. Binary values are lowercase hex.

use serde::{Deserialize, Serialize};

use crate::{TorrentState, TorrentStatus};

/// `POST /v1/pair/start`
#[derive(Debug, Serialize, Deserialize)]
pub(crate) struct PairStart {
    /// The client's SPAKE2 message.
    pub(crate) message: String,
}

/// Answer to [`PairStart`].
#[derive(Debug, Serialize, Deserialize)]
pub(crate) struct PairStarted {
    pub(crate) exchange: String,
    /// The server's SPAKE2 message.
    pub(crate) message: String,
}

/// `POST /v1/pair/finish`
#[derive(Debug, Serialize, Deserialize)]
pub(crate) struct PairFinish {
    pub(crate) exchange: String,
    /// HMAC over the server certificate fingerprint the client saw.
    pub(crate) proof: String,
    /// Shown on the server's list of paired devices.
    pub(crate) name: String,
}

/// Answer to [`PairFinish`].
#[derive(Serialize, Deserialize)]
pub(crate) struct Paired {
    pub(crate) token: String,
    pub(crate) server_id: String,
    pub(crate) server_name: String,
    /// HMAC over the fingerprint, the server id and the token.
    pub(crate) proof: String,
}

/// `GET /v1/hello`
#[derive(Debug, Serialize, Deserialize)]
pub(crate) struct Hello {
    pub(crate) server_id: String,
    pub(crate) server_name: String,
}

/// `POST /v1/torrents`
#[derive(Debug, Serialize, Deserialize)]
pub(crate) struct AddTorrent {
    pub(crate) info_hash: String,
    pub(crate) file_idx: Option<u32>,
    #[serde(default)]
    pub(crate) trackers: Vec<String>,
}

/// Answer to [`AddTorrent`].
#[derive(Debug, Serialize, Deserialize)]
pub(crate) struct Added {
    pub(crate) index: usize,
}

/// `GET /v1/torrents/<hash>`
#[derive(Debug, Serialize, Deserialize)]
pub(crate) struct Status {
    pub(crate) state: String,
    pub(crate) peers: u32,
    pub(crate) download_speed: u64,
    pub(crate) downloaded_bytes: u64,
    pub(crate) file_size: u64,
    pub(crate) file_name: Option<String>,
    pub(crate) error: Option<String>,
}

/// The body of every error answer.
#[derive(Debug, Serialize, Deserialize)]
pub(crate) struct Failure {
    pub(crate) error: String,
    #[serde(default)]
    pub(crate) message: Option<String>,
}

impl From<TorrentStatus> for Status {
    fn from(status: TorrentStatus) -> Self {
        Self {
            state: match status.state {
                TorrentState::Metadata => "metadata",
                TorrentState::Checking => "checking",
                TorrentState::Downloading => "downloading",
                TorrentState::Ready => "ready",
                TorrentState::Error => "error",
            }
            .to_owned(),
            peers: status.peers,
            download_speed: status.download_speed,
            downloaded_bytes: status.downloaded_bytes,
            file_size: status.file_size,
            file_name: status.file_name,
            error: status.error,
        }
    }
}

impl From<Status> for TorrentStatus {
    fn from(status: Status) -> Self {
        Self {
            state: match status.state.as_str() {
                "metadata" => TorrentState::Metadata,
                "checking" => TorrentState::Checking,
                "downloading" => TorrentState::Downloading,
                "ready" => TorrentState::Ready,
                _ => TorrentState::Error,
            },
            peers: status.peers,
            download_speed: status.download_speed,
            downloaded_bytes: status.downloaded_bytes,
            file_size: status.file_size,
            file_name: status.file_name,
            error: status.error,
        }
    }
}
