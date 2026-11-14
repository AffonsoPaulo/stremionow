// SPDX-License-Identifier: GPL-3.0-or-later
//! Optional sync with a Stremio account: addons, library and watch progress.
//!
//! [`StremioApi`] is a thin client for the official account API
//! (`https://api.strem.io/api/<method>`, JSON over POST). Request and response shapes follow
//! the official `stremio-core` crate (`src/types/api`), so data written here is read back
//! unchanged by the official apps. Formats the server owns (addon manifests, unknown library
//! fields) are passed through as raw JSON instead of being re-modelled.
//!
//! [`merge_library`] reconciles a local library with the remote one without doing any I/O.
//!
//! [`LikesApi`] keeps the Like and Love of each title on Stremio's likes service
//! (`https://likes.stremio.com`), as the official apps do.

mod api;
mod error;
mod library;
mod likes;
mod merge;
mod time;
mod types;

pub use api::{DEFAULT_BASE_URL, StremioApi};
pub use error::SyncError;
pub use library::{BehaviorHints, LibraryItemState, PosterShape, RemoteLibraryItem};
pub use likes::{DEFAULT_LIKES_URL, LikesApi, Rating};
pub use merge::{MergeResult, merge_library};
pub use time::{format_timestamp, parse_timestamp};
pub use types::{AddonFlags, RemoteAddon, Session, User};
