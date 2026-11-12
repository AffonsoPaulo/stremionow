// SPDX-License-Identifier: GPL-3.0-or-later
//! Skip markers for anime from AniSkip: openings, recaps and endings.
//!
//! The player finds intros and credits in a file's chapters first (in the app). For anime,
//! whose releases often have no chapters, this crate asks AniSkip, a crowd-sourced database
//! keyed by MyAnimeList id, after working out the episode from the ids anime addons use.
//! See docs/adr/0009-aniskip.md.

mod aniskip;
mod ids;

pub use aniskip::{
    ANISKIP_BASE_URL, AniSkip, MAPPING_BASE_URL, MarkersError, SkipKind, SkipSegment,
};
pub use ids::{AnimeEpisode, AnimeId, anime_episode};
