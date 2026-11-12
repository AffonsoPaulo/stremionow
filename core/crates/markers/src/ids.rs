// SPDX-License-Identifier: GPL-3.0-or-later
//! Which anime episode a Stremio video is, from the ids anime addons use.
//!
//! Anime addons name titles `kitsu:<id>`, `mal:<id>` or `anilist:<id>`, and their episodes
//! `<prefix>:<id>:<episode>`, numbered from 1 within that entry. Anything else (IMDb ids from
//! Cinemeta, other addons' schemes) is not recognized, so no request is ever made for it.

/// Largest numeric id accepted. Real ids are far below it; the mapping service refuses more.
const MAX_ID: u32 = 50_000_000;
/// Largest episode number accepted. The longest-running series are around 1,200.
const MAX_EPISODE: u32 = 10_000;

/// An anime entry in one of the databases anime addons use.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum AnimeId {
    /// MyAnimeList, the id AniSkip uses.
    Mal(u32),
    Kitsu(u32),
    Anilist(u32),
}

impl AnimeId {
    /// Reads `kitsu:12`, `mal:21` or `anilist:21`. The prefix must be lowercase, as addons send
    /// it, and the number plain decimal digits.
    fn parse(prefix: &str, number: &str) -> Option<Self> {
        let number = parse_number(number, MAX_ID)?;
        match prefix {
            "mal" => Some(Self::Mal(number)),
            "kitsu" => Some(Self::Kitsu(number)),
            "anilist" => Some(Self::Anilist(number)),
            _ => None,
        }
    }
}

/// One episode of an anime entry.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct AnimeEpisode {
    pub id: AnimeId,
    /// Episode number within the entry, from 1. Movies are episode 1.
    pub episode: u32,
}

/// Works out the anime episode being played, or `None` when it cannot be told for sure.
///
/// `meta_id` is the title's id and `video_id` the id addons were asked streams for; `season`
/// and `episode` come from the title's metadata. The video id wins when it carries the
/// episode (`kitsu:12:5`), since it numbers episodes within the very entry it names. Otherwise
/// the metadata's episode number is used only for the first season: a later season of a title
/// is usually a separate entry, which a single id cannot point to. Specials (season 0) are
/// numbered apart from the series and are left out.
pub fn anime_episode(
    meta_id: &str,
    video_id: &str,
    season: Option<u32>,
    episode: Option<u32>,
) -> Option<AnimeEpisode> {
    if season == Some(0) {
        return None;
    }
    let first_season = season.is_none_or(|season| season == 1);
    let from_metadata = || episode.filter(|_| first_season);

    let (id, episode) = match parse_video_id(video_id) {
        Some((id, Some(number))) => (id, Some(number)),
        // A movie's video id is the title's own id; AniSkip files movies as episode 1.
        Some((id, None)) => (id, from_metadata().or((video_id == meta_id).then_some(1))),
        None => {
            let (id, rest) = parse_video_id(meta_id)?;
            if rest.is_some() {
                return None;
            }
            (id, from_metadata())
        }
    };
    let episode = episode.filter(|number| (1..=MAX_EPISODE).contains(number))?;
    Some(AnimeEpisode { id, episode })
}

/// Splits `prefix:id` or `prefix:id:episode`. Anything with more parts, or an episode that is
/// not a plain number, is not an id this module understands.
fn parse_video_id(value: &str) -> Option<(AnimeId, Option<u32>)> {
    let mut parts = value.split(':');
    let id = AnimeId::parse(parts.next()?, parts.next()?)?;
    let episode = match parts.next() {
        Some(number) => Some(parse_number(number, u32::MAX)?),
        None => None,
    };
    if parts.next().is_some() {
        return None;
    }
    Some((id, episode))
}

/// A positive decimal number of at most nine digits and at most `max`; no signs, spaces or
/// leading `+`, so the value can go into a URL as is.
fn parse_number(value: &str, max: u32) -> Option<u32> {
    if value.is_empty() || value.len() > 9 || !value.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    value.parse().ok().filter(|n| (1..=max).contains(n))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn episode(id: AnimeId, episode: u32) -> Option<AnimeEpisode> {
        Some(AnimeEpisode { id, episode })
    }

    #[test]
    fn reads_episodes_from_video_ids() {
        assert_eq!(
            anime_episode("kitsu:12", "kitsu:12:1000", Some(1), Some(1000)),
            episode(AnimeId::Kitsu(12), 1000)
        );
        assert_eq!(
            anime_episode("mal:21", "mal:21:5", None, None),
            episode(AnimeId::Mal(21), 5)
        );
        // The video id numbers episodes within its own entry, whatever the season says.
        assert_eq!(
            anime_episode("kitsu:1", "kitsu:7:3", Some(2), Some(15)),
            episode(AnimeId::Kitsu(7), 3)
        );
        assert_eq!(
            anime_episode("anilist:21", "anilist:21:2", Some(1), Some(2)),
            episode(AnimeId::Anilist(21), 2)
        );
    }

    #[test]
    fn falls_back_to_the_metadata_for_the_first_season() {
        assert_eq!(
            anime_episode("kitsu:12", "other-addon-id", Some(1), Some(4)),
            episode(AnimeId::Kitsu(12), 4)
        );
        assert_eq!(
            anime_episode("kitsu:12", "other-addon-id", Some(2), Some(4)),
            None,
            "a later season is another entry"
        );
        assert_eq!(anime_episode("kitsu:12", "other", Some(1), None), None);
    }

    #[test]
    fn treats_a_movie_as_episode_one() {
        assert_eq!(
            anime_episode("kitsu:7", "kitsu:7", None, None),
            episode(AnimeId::Kitsu(7), 1)
        );
    }

    #[test]
    fn ignores_specials_episode_zero_and_other_ids() {
        assert_eq!(
            anime_episode("kitsu:12", "kitsu:12:3", Some(0), Some(3)),
            None
        );
        assert_eq!(
            anime_episode("kitsu:12", "kitsu:12:0", Some(1), Some(0)),
            None
        );
        assert_eq!(
            anime_episode("tt0388629", "tt0388629:1:1", Some(1), Some(1)),
            None
        );
        assert_eq!(
            anime_episode("tmdb:37854", "tmdb:37854:1:1", Some(1), Some(1)),
            None
        );
        assert_eq!(
            anime_episode("kitsu:12", "kitsu:12:2:1", Some(2), Some(1)),
            None
        );
    }

    #[test]
    fn rejects_malformed_numbers() {
        for id in [
            "kitsu:",
            "kitsu:0",
            "kitsu:-1",
            "kitsu:+5",
            "kitsu: 5",
            "kitsu:5a",
            "kitsu:1234567890",
            "kitsu:99999999",
            "kitsu:12/../../x",
            "kitsu:12?x=1",
            "KITSU:12",
            "kitsu:１２",
        ] {
            assert_eq!(anime_episode(id, id, None, None), None, "{id}");
        }
        assert_eq!(
            anime_episode("kitsu:12", "kitsu:12:10001", None, None),
            None
        );
        // An unrecognized video id leaves the title's id and the metadata to go by.
        assert_eq!(
            anime_episode("kitsu:12", "kitsu:12:x", Some(1), Some(1)),
            episode(AnimeId::Kitsu(12), 1)
        );
    }
}
