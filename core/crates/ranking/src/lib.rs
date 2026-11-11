// SPDX-License-Identifier: GPL-3.0-or-later
//! Scores and orders playback sources by user preferences and device capabilities.
//!
//! Stream addons (Torrentio, Comet, MediaFusion, Jackettio, ...) describe each source in free
//! text spread over a stream's `name`, `title` and `description`. This crate reads those strings
//! best effort:
//!
//! 1. [`parse`] extracts technical [`Traits`] (resolution, HDR, codec, audio, release quality,
//!    size, seeders, debrid cache state, languages, provider).
//! 2. [`score`] turns traits into a single number according to [`Preferences`]; [`rank`] sorts a
//!    list of sources with it, best first.
//! 3. [`badges`] renders short technical labels for the UI.
//!
//! Nothing here panics on malformed input, and only the first [`MAX_INSPECTED_BYTES`] of each
//! string are inspected.
//!
//! ```
//! use stremionow_ranking::{Preferences, Resolution, SourceText, badges, parse, rank};
//!
//! let text = SourceText {
//!     name: "[RD+] Torrentio\n4k DV | HDR",
//!     title: "Movie.2023.2160p.WEB-DL.DV.HDR.H265-GRP\n👤 154 💾 14.2 GB ⚙️ ThePirateBay",
//!     description: "",
//! };
//! let traits = parse(&text);
//! assert_eq!(traits.resolution, Some(Resolution::P2160));
//! assert_eq!(traits.cached, Some(true));
//! assert_eq!(badges(&traits), ["4K", "DV", "HDR10", "HEVC", "WEB-DL", "14.2 GB"]);
//!
//! let mut titles = ["Movie.720p.WEB", "Movie.1080p.BluRay"];
//! rank(&mut titles, &Preferences::default(), |title| SourceText {
//!     title,
//!     ..SourceText::default()
//! });
//! assert_eq!(titles, ["Movie.1080p.BluRay", "Movie.720p.WEB"]);
//! ```

mod badges;
mod parse;
mod score;
mod text;
mod traits;

pub use badges::badges;
pub use parse::{MAX_INSPECTED_BYTES, parse};
pub use score::{Preferences, rank, score, weights};
pub use traits::{Audio, Codec, Hdr, ReleaseQuality, Resolution, SourceText, Traits};
