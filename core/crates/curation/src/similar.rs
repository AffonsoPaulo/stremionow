// SPDX-License-Identifier: GPL-3.0-or-later
//! Titles like one the user finished, for "Because you watched" rows.

use std::collections::{HashMap, HashSet};

use crate::{Title, normalized};

/// Weight of the genres the two titles share (Jaccard index, 0 to 1).
const GENRE_WEIGHT: f64 = 3.0;
/// A director in common.
const DIRECTOR_WEIGHT: f64 = 2.0;
/// Found by searching the seed's director by name, when the candidate's own metadata lists no
/// director to confirm it. Search results carry no genres or people, so this stands for the
/// shared director and half the genre points: a search for a director's full name mostly
/// finds their films.
const DIRECTOR_SEARCH_WEIGHT: f64 = 3.5;
/// Found by searching the seed's lead actor, unconfirmed likewise. An actor spans more kinds
/// of films than a director, so it counts for less.
const ACTOR_SEARCH_WEIGHT: f64 = 1.5;
/// Each lead actor in common, up to [`MAX_CAST_BONUS`].
const CAST_WEIGHT: f64 = 0.5;
const MAX_CAST_BONUS: f64 = 1.0;
/// Same era: full weight for the same year, nothing at [`ERA_SPAN_YEARS`] apart or more.
const ERA_WEIGHT: f64 = 1.0;
const ERA_SPAN_YEARS: f64 = 15.0;
/// Well rated: nothing at 6 or below, full weight at 9 or above.
const RATING_WEIGHT: f64 = 0.5;
/// Higher in its source list (the catalog's own popularity order).
const SOURCE_RANK_WEIGHT: f64 = 0.5;

/// A title that might resemble the seed, as one of the addons listed it.
#[derive(Debug, Clone, PartialEq)]
pub struct Candidate {
    pub title: Title,
    /// Position in the list it came from, 0 first.
    pub rank: usize,
    /// Length of that list.
    pub of: usize,
    /// How it was found.
    pub found_by: FoundBy,
}

/// Where a [`Candidate`] came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FoundBy {
    /// A catalog list, such as the popular titles of a genre.
    Catalog,
    /// A search for the seed's director.
    Director,
    /// A search for the seed's lead actor.
    Actor,
}

/// How much `candidate` resembles `seed`, or None when nothing relates them (no genre in
/// common, no shared director, not found through a person of the seed). The sum of:
///
/// | Signal | Points |
/// | --- | --- |
/// | Genres in common (shared ÷ all of both) | up to 3 |
/// | A director in common | 2 |
/// | Found by searching the seed's director, with no director listed to confirm | 3.5 |
/// | Found by searching the seed's lead actor, with no cast listed to confirm | 1.5 |
/// | Each lead actor in common | 0.5, up to 1 |
/// | Release years apart (15 or more scores nothing) | up to 1 |
/// | IMDb rating from 6 to 9 | up to 0.5 |
/// | Position in its source list | up to 0.5 |
pub fn similarity(seed: &Title, candidate: &Candidate) -> Option<f64> {
    let title = &candidate.title;
    let seed_genres = names(&seed.genres);
    let genres = names(&title.genres);
    let shared_genres = seed_genres.intersection(&genres).count();
    let all_genres = seed_genres.union(&genres).count();
    let genre_score = if all_genres == 0 {
        0.0
    } else {
        shared_genres as f64 / all_genres as f64
    };
    let shared_director = !names(&seed.directors).is_disjoint(&names(&title.directors));
    let search = match candidate.found_by {
        FoundBy::Director if title.directors.is_empty() => DIRECTOR_SEARCH_WEIGHT,
        FoundBy::Actor if title.cast.is_empty() => ACTOR_SEARCH_WEIGHT,
        _ => 0.0,
    };
    if shared_genres == 0 && !shared_director && search == 0.0 {
        return None;
    }
    let shared_cast = names(&seed.cast).intersection(&names(&title.cast)).count();
    let era = match (seed.year, title.year) {
        (Some(a), Some(b)) => (1.0 - f64::from((a - b).abs()) / ERA_SPAN_YEARS).max(0.0),
        _ => 0.0,
    };
    let rating = title
        .rating
        .map_or(0.0, |r| ((f64::from(r) - 6.0) / 3.0).clamp(0.0, 1.0));
    let position = if candidate.of == 0 {
        0.0
    } else {
        1.0 - candidate.rank.min(candidate.of) as f64 / candidate.of as f64
    };
    Some(
        GENRE_WEIGHT * genre_score
            + if shared_director {
                DIRECTOR_WEIGHT
            } else {
                0.0
            }
            + search
            + (CAST_WEIGHT * shared_cast as f64).min(MAX_CAST_BONUS)
            + ERA_WEIGHT * era
            + RATING_WEIGHT * rating
            + SOURCE_RANK_WEIGHT * position,
    )
}

/// The candidates most like `seed`, best first, at most `limit`, without the seed itself,
/// titles of another type, titles `excluded` says the user has seen or started, and repeats
/// (a title found in several lists keeps its best score). Ties keep the earlier list position.
pub fn rank_similar(
    seed: &Title,
    candidates: Vec<Candidate>,
    excluded: impl Fn(&str) -> bool,
    limit: usize,
) -> Vec<Candidate> {
    let mut best: HashMap<String, (f64, Candidate)> = HashMap::new();
    for candidate in candidates {
        let id = candidate.title.id.clone();
        if id.is_empty() || id == seed.id || candidate.title.kind != seed.kind || excluded(&id) {
            continue;
        }
        let Some(score) = similarity(seed, &candidate) else {
            continue;
        };
        match best.get(&id) {
            Some((kept, _)) if *kept >= score => {}
            _ => {
                best.insert(id, (score, candidate));
            }
        }
    }
    let mut ranked: Vec<(f64, Candidate)> = best.into_values().collect();
    ranked.sort_by(|a, b| {
        b.0.total_cmp(&a.0)
            .then_with(|| a.1.rank.cmp(&b.1.rank))
            .then_with(|| a.1.title.id.cmp(&b.1.title.id))
    });
    ranked
        .into_iter()
        .take(limit)
        .map(|(_, candidate)| candidate)
        .collect()
}

fn names(values: &[String]) -> HashSet<String> {
    values
        .iter()
        .map(|value| normalized(value))
        .filter(|value| !value.is_empty())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn title(id: &str, genres: &[&str], year: i32, director: &str) -> Title {
        Title {
            id: id.into(),
            kind: "movie".into(),
            genres: genres.iter().map(|g| (*g).to_owned()).collect(),
            year: Some(year),
            directors: if director.is_empty() {
                vec![]
            } else {
                vec![director.into()]
            },
            cast: vec![],
            rating: None,
        }
    }

    fn candidate(title: Title, rank: usize) -> Candidate {
        Candidate {
            title,
            rank,
            of: 20,
            found_by: FoundBy::Catalog,
        }
    }

    fn seed() -> Title {
        let mut seed = title(
            "tt0063350",
            &["Horror", "Thriller"],
            1968,
            "George A. Romero",
        );
        seed.cast = vec!["Duane Jones".into(), "Judith O'Dea".into()];
        seed
    }

    #[test]
    fn scores_each_signal_as_documented() {
        let seed = seed();
        let mut same = title("a", &["Horror", "Thriller"], 1968, "George A. Romero");
        same.cast = vec!["duane jones".into()];
        same.rating = Some(9.5);
        let score = similarity(&seed, &candidate(same, 0)).expect("related");
        // 3 (genres) + 2 (director) + 0.5 (actor) + 1 (era) + 0.5 (rating) + 0.5 (first).
        assert!((score - 7.5).abs() < 1e-9, "{score}");

        let loose = title("b", &["Horror", "Comedy", "Romance"], 1998, "");
        let score = similarity(&seed, &candidate(loose, 10)).expect("shares a genre");
        // 3 × 1/4 + era 1 - 30/15 → 0 + rank 0.5 × (1 - 10/20).
        assert!((score - 1.0).abs() < 1e-9, "{score}");
    }

    #[test]
    fn unrelated_titles_are_left_out() {
        let seed = seed();
        let other = title("c", &["Animation"], 1968, "Someone Else");
        assert_eq!(similarity(&seed, &candidate(other.clone(), 0)), None);
        let found = Candidate {
            found_by: FoundBy::Director,
            ..candidate(title("d", &["Documentary"], 2010, ""), 0)
        };
        assert!(
            similarity(&seed, &found).is_some(),
            "a search for the director that cannot be confirmed still counts"
        );
        let wrong_person = Candidate {
            found_by: FoundBy::Director,
            ..candidate(other, 0)
        };
        assert_eq!(
            similarity(&seed, &wrong_person),
            None,
            "a listed director that differs rules the search hit out"
        );
    }

    #[test]
    fn ranks_without_the_seed_seen_titles_other_types_or_repeats() {
        let seed = seed();
        let close = title("close", &["Horror", "Thriller"], 1970, "George A. Romero");
        let far = title("far", &["Horror"], 2020, "");
        let seen = title("seen", &["Horror", "Thriller"], 1968, "George A. Romero");
        let mut series = close.clone();
        series.id = "show".into();
        series.kind = "series".into();
        let candidates = vec![
            candidate(far.clone(), 0),
            candidate(seed.clone(), 1),
            candidate(seen, 2),
            candidate(close.clone(), 9),
            candidate(series, 3),
            candidate(far, 5),
            candidate(close, 1),
        ];
        let ranked = rank_similar(&seed, candidates, |id| id == "seen", 10);
        let ids: Vec<_> = ranked.iter().map(|c| c.title.id.as_str()).collect();
        assert_eq!(ids, ["close", "far"]);
        assert_eq!(ranked[0].rank, 1, "the better-placed copy is kept");
        assert_eq!(rank_similar(&seed, vec![], |_| false, 10), vec![]);
    }

    #[test]
    fn respects_the_limit_and_breaks_ties_by_position() {
        let seed = seed();
        let candidates = (0..30)
            .map(|n| candidate(title(&format!("t{n:02}"), &["Horror"], 1990, ""), n))
            .collect();
        let ranked = rank_similar(&seed, candidates, |_| false, 5);
        let ids: Vec<_> = ranked.iter().map(|c| c.title.id.as_str()).collect();
        assert_eq!(ids, ["t00", "t01", "t02", "t03", "t04"]);
    }
}
