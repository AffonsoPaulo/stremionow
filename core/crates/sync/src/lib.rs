// SPDX-License-Identifier: GPL-3.0-or-later
//! Optional sync with a Stremio account: addons, library and watch progress.
//!
//! [`StremioApi`] is a thin client for the official account API
//! (`https://api.strem.io/api/<method>`, JSON over POST). Request and response shapes follow
//! the official `stremio-core` crate (`src/types/api`), so data written here is read back
//! unchanged by the official apps. Formats the server owns (addon manifests, unknown library
//! fields) are passed through as raw JSON instead of being re-modelled.
//!
//! Signing in on another device or in the browser is described in [`signin`].
//!
//! [`merge_library`] reconciles a local library with the remote one without doing any I/O.
//!
//! [`merge_watched`] combines the watched videos of a title with the account's, in the official
//! `state.watched` format ([`encode_watched`], [`decode_watched`]).
//!
//! [`LikesApi`] keeps the Like and Love of each title on Stremio's likes service
//! (`https://likes.stremio.com`), as the official apps do.

mod api;
mod error;
mod library;
mod likes;
mod merge;
mod signin;
mod time;
mod types;
mod watched;

pub use api::{AccountLibrary, DEFAULT_BASE_URL, DEFAULT_LINK_URL, DEFAULT_WEB_URL, StremioApi};
pub use error::SyncError;
pub use library::{BehaviorHints, LibraryItemState, PosterShape, RemoteLibraryItem};
pub use likes::{DEFAULT_LIKES_URL, LikesApi, Rating};
pub use merge::{MergeResult, merge_library};
pub use signin::{
    AppleCredentials, FacebookCredentials, LinkCode, is_valid_state, state_from_bytes,
};
pub use time::{format_timestamp, parse_timestamp};
pub use types::{AddonFlags, RemoteAddon, Session, User};
pub use watched::{
    WatchedError, WatchedMerge, WatchedVideo, decode_watched, encode_watched, merge_mark,
    merge_watched, watched_order,
};
