// SPDX-License-Identifier: GPL-3.0-or-later
//! Picks the personal rows of the home screen from the user's own history, on the device.
//!
//! Nothing here touches the network or the database: the core reads the history and the
//! addons' metadata, and these functions turn them into rows (see
//! `docs/adr/0011-home-curation.md`):
//!
//! - [`genre_profile`] weighs the genres of what the user watched by how recently and how
//!   completely they watched it, for "More <genre>" rows;
//! - [`rank_similar`] orders candidates by how much they resemble a title the user finished,
//!   for "Because you watched" rows, with the documented [`similarity`] score;
//! - [`new_episode`] finds an episode released since the user last watched a series;
//! - [`dedupe_in_order`] keeps each title in the first, most personal, row that has it.
//!
//! Every function is deterministic for its inputs (the current time is a parameter), never
//! panics on odd metadata, and bounds its output.

mod episodes;
mod profile;
mod similar;

use std::collections::HashSet;
use std::hash::Hash;

pub use episodes::{EpisodeInfo, NEW_EPISODE_WINDOW_MS, new_episode};
pub use profile::{GenreWeight, HALF_LIFE_DAYS, genre_profile, top_genres};
pub use similar::{Candidate, FoundBy, rank_similar, similarity};

/// What the curation knows about a title, from its addon's metadata.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Title {
    pub id: String,
    /// Content type, usually `movie` or `series`.
    pub kind: String,
    pub genres: Vec<String>,
    /// First year of release.
    pub year: Option<i32>,
    pub directors: Vec<String>,
    /// Main cast, as the addon lists it (Cinemeta: the first three or four names).
    pub cast: Vec<String>,
    /// IMDb rating out of ten.
    pub rating: Option<f32>,
}

/// A title from the user's history.
#[derive(Debug, Clone, PartialEq)]
pub struct Watched {
    pub title: Title,
    /// How much of it the user watched, from 0 to 1: a finished movie is 1, a movie stopped
    /// halfway 0.5, a series grows with the episodes watched.
    pub completion: f32,
    /// When the user last watched it, in Unix milliseconds.
    pub watched_at: i64,
}

/// The first year in a release text such as `1968`, `2019-2023` or `2019–`.
pub fn parse_year(release_info: &str) -> Option<i32> {
    let digits: String = release_info
        .trim()
        .chars()
        .take_while(char::is_ascii_digit)
        .collect();
    if digits.len() != 4 {
        return None;
    }
    digits.parse().ok()
}

/// A rating such as `7.4` out of ten; anything else is no rating.
pub fn parse_rating(text: &str) -> Option<f32> {
    text.trim()
        .parse::<f32>()
        .ok()
        .filter(|rating| rating.is_finite() && (0.0..=10.0).contains(rating))
}

/// Removes from each section the items an earlier section (or `already`, such as the titles
/// in "Continue watching") has, so every title shows once, in the first section that has it.
/// Sections are in priority order, the most personal first. Repeats inside a section go too.
pub fn dedupe_in_order<T, K: Eq + Hash>(
    sections: &mut [Vec<T>],
    key: impl Fn(&T) -> K,
    mut already: HashSet<K>,
) {
    for section in sections.iter_mut() {
        section.retain(|item| already.insert(key(item)));
    }
}

/// Lowercased, trimmed text for comparing names and genres from different addons.
fn normalized(text: &str) -> String {
    text.trim().to_lowercase()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_years_and_ratings() {
        assert_eq!(parse_year("1968"), Some(1968));
        assert_eq!(parse_year("2019-2023"), Some(2019));
        assert_eq!(parse_year("2019–"), Some(2019));
        assert_eq!(parse_year(" 2001 "), Some(2001));
        assert_eq!(parse_year("19"), None);
        assert_eq!(parse_year("TBA"), None);
        assert_eq!(parse_rating("7.4"), Some(7.4));
        assert_eq!(parse_rating("NaN"), None);
        assert_eq!(parse_rating("11"), None);
        assert_eq!(parse_rating(""), None);
    }

    #[test]
    fn keeps_each_item_in_its_first_section() {
        let mut sections = vec![vec![1, 2, 2, 3], vec![3, 4, 1], vec![5, 4]];
        dedupe_in_order(&mut sections, |n| *n, HashSet::from([5]));
        assert_eq!(sections, vec![vec![1, 2, 3], vec![4], vec![]]);
    }
}
