// SPDX-License-Identifier: GPL-3.0-or-later
//! The genres the user watches most, for "More <genre>" rows.

use std::collections::HashMap;

use crate::{Watched, normalized};

/// A title's weight halves for every this many days since it was watched.
pub const HALF_LIFE_DAYS: f64 = 30.0;
/// Least weight of a title in the history, however little of it was watched: starting
/// something still says something about taste.
const MIN_COMPLETION: f64 = 0.25;
/// A genre beyond the first makes the top only with at least this share of the first's weight.
const RUNNER_UP_SHARE: f64 = 0.5;
const MS_PER_DAY: f64 = 86_400_000.0;

/// How much the user's history leans toward a genre.
#[derive(Debug, Clone, PartialEq)]
pub struct GenreWeight {
    /// The genre as the addon first spelled it, for display and catalog filters.
    pub genre: String,
    pub weight: f64,
    /// The content type that brought most of the weight, to fill the row with.
    pub kind: String,
}

/// Weighs every genre of the `history`. Each title counts
/// `max(completion, 0.25) × 0.5^(age in days / 30)`, shared equally among its genres, so a
/// title with many genres does not outweigh one with few. Heaviest first; ties by name.
pub fn genre_profile(history: &[Watched], now_ms: i64) -> Vec<GenreWeight> {
    // Lowercased genre -> (display name, total, weight per kind).
    let mut genres: HashMap<String, (String, f64, HashMap<String, f64>)> = HashMap::new();
    for watched in history {
        let names: Vec<&String> = watched
            .title
            .genres
            .iter()
            .filter(|genre| !genre.trim().is_empty())
            .collect();
        if names.is_empty() {
            continue;
        }
        let age_days = ((now_ms - watched.watched_at).max(0) as f64) / MS_PER_DAY;
        let recency = 0.5_f64.powf(age_days / HALF_LIFE_DAYS);
        let completion = f64::from(watched.completion).clamp(MIN_COMPLETION, 1.0);
        let share = completion * recency / names.len() as f64;
        for name in names {
            let entry = genres
                .entry(normalized(name))
                .or_insert_with(|| (name.trim().to_owned(), 0.0, HashMap::new()));
            entry.1 += share;
            *entry.2.entry(watched.title.kind.clone()).or_default() += share;
        }
    }
    let mut profile: Vec<GenreWeight> = genres
        .into_values()
        .map(|(genre, weight, kinds)| {
            let kind = kinds
                .into_iter()
                .max_by(|a, b| a.1.total_cmp(&b.1).then_with(|| b.0.cmp(&a.0)))
                .map(|(kind, _)| kind)
                .unwrap_or_default();
            GenreWeight {
                genre,
                weight,
                kind,
            }
        })
        .collect();
    profile.sort_by(|a, b| {
        b.weight
            .total_cmp(&a.weight)
            .then_with(|| a.genre.cmp(&b.genre))
    });
    profile
}

/// The user's top genres, at most `max`: the heaviest one, and the next ones while they keep
/// at least half its weight, so a passing interest does not get a row.
pub fn top_genres(profile: &[GenreWeight], max: usize) -> Vec<GenreWeight> {
    let Some(first) = profile.first() else {
        return Vec::new();
    };
    profile
        .iter()
        .take(max)
        .take_while(|genre| genre.weight >= first.weight * RUNNER_UP_SHARE)
        .cloned()
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Title;

    const DAY: i64 = 86_400_000;
    const NOW: i64 = 1_800_000_000_000;

    fn watched(kind: &str, genres: &[&str], completion: f32, days_ago: i64) -> Watched {
        Watched {
            title: Title {
                id: format!("tt{days_ago}"),
                kind: kind.into(),
                genres: genres.iter().map(|g| (*g).to_owned()).collect(),
                ..Title::default()
            },
            completion,
            watched_at: NOW - days_ago * DAY,
        }
    }

    #[test]
    fn recent_and_finished_titles_weigh_more() {
        let history = [
            watched("movie", &["Horror"], 1.0, 0),
            watched("movie", &["Comedy"], 1.0, 60),
            watched("series", &["Drama"], 0.1, 0),
        ];
        let profile = genre_profile(&history, NOW);
        let names: Vec<_> = profile.iter().map(|g| g.genre.as_str()).collect();
        assert_eq!(names, ["Horror", "Comedy", "Drama"]);
        assert!((profile[0].weight - 1.0).abs() < 1e-9);
        assert!((profile[1].weight - 0.25).abs() < 1e-9, "two half-lives");
        assert!(
            (profile[2].weight - 0.25).abs() < 1e-9,
            "a barely started title still counts a quarter"
        );
        assert_eq!(profile[2].kind, "series");
    }

    #[test]
    fn genres_share_a_title_and_merge_across_spellings() {
        let history = [
            watched("movie", &["Action", "Adventure"], 1.0, 0),
            watched("series", &["action ", ""], 1.0, 0),
            watched("movie", &[], 1.0, 0),
        ];
        let profile = genre_profile(&history, NOW);
        assert_eq!(profile.len(), 2);
        assert_eq!(profile[0].genre, "Action");
        assert!((profile[0].weight - 1.5).abs() < 1e-9);
        assert_eq!(profile[0].kind, "series", "the series brought more of it");
    }

    #[test]
    fn top_genres_leave_out_passing_interests() {
        let history = [
            watched("movie", &["Horror"], 1.0, 0),
            watched("movie", &["Horror"], 1.0, 1),
            watched("movie", &["Sci-Fi"], 1.0, 2),
            watched("movie", &["Romance"], 0.25, 90),
        ];
        let top = top_genres(&genre_profile(&history, NOW), 2);
        let names: Vec<_> = top.iter().map(|g| g.genre.as_str()).collect();
        assert_eq!(
            names,
            ["Horror"],
            "Sci-Fi has under half of Horror's weight"
        );
        assert!(top_genres(&[], 2).is_empty());
    }

    #[test]
    fn a_history_from_the_future_counts_as_now() {
        let profile = genre_profile(&[watched("movie", &["Drama"], 1.0, -10)], NOW);
        assert!((profile[0].weight - 1.0).abs() < 1e-9);
    }
}
