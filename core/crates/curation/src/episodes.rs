// SPDX-License-Identifier: GPL-3.0-or-later
//! Episodes released since the user last watched a series, for the "New episodes" row.

/// An episode is new for this long after its release. Older ones the user has not watched
/// are a backlog, not news.
pub const NEW_EPISODE_WINDOW_MS: i64 = 90 * 86_400_000;

/// What the curation needs of an episode.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EpisodeInfo {
    pub season: Option<u32>,
    pub episode: Option<u32>,
    /// Release time in Unix milliseconds, when the addon gives a date.
    pub released_ms: Option<i64>,
}

/// Index of the episode to offer as new in a series, if any: the first regular episode after
/// `last_seen` (season and number of the last one the user watched; None if they watched none
/// yet) that came out after `since_ms` (when the user last watched the series, or added it to
/// their list) and before `now_ms`, within [`NEW_EPISODE_WINDOW_MS`].
///
/// Specials (season 0), episodes without a number and episodes without a release date are
/// never offered: the first may come out of order, and the others may not be out at all.
pub fn new_episode(
    episodes: &[EpisodeInfo],
    last_seen: Option<(u32, u32)>,
    since_ms: i64,
    now_ms: i64,
) -> Option<usize> {
    episodes
        .iter()
        .enumerate()
        .filter_map(|(index, info)| {
            let season = info.season.filter(|season| *season > 0)?;
            let episode = info.episode?;
            let released = info.released_ms?;
            let after_last = last_seen.is_none_or(|last| (season, episode) > last);
            let fresh = released > since_ms
                && released <= now_ms
                && now_ms - released <= NEW_EPISODE_WINDOW_MS;
            (after_last && fresh).then_some((season, episode, index))
        })
        .min()
        .map(|(_, _, index)| index)
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY: i64 = 86_400_000;
    const NOW: i64 = 1_800_000_000_000;

    fn episode(season: u32, number: u32, released_days_ago: Option<i64>) -> EpisodeInfo {
        EpisodeInfo {
            season: Some(season),
            episode: Some(number),
            released_ms: released_days_ago.map(|days| NOW - days * DAY),
        }
    }

    #[test]
    fn offers_the_first_episode_out_since_the_last_watch() {
        let episodes = [
            episode(1, 1, Some(40)),
            episode(1, 2, Some(33)),
            episode(1, 4, Some(5)),
            episode(1, 3, Some(12)),
            episode(1, 5, Some(-2)),
        ];
        // Watched E2 a month ago: E3 is new, before E4; E5 is not out yet.
        assert_eq!(
            new_episode(&episodes, Some((1, 2)), NOW - 30 * DAY, NOW),
            Some(3)
        );
        // Watched up to E4: nothing out after it.
        assert_eq!(new_episode(&episodes, Some((1, 4)), NOW - DAY, NOW), None);
    }

    #[test]
    fn episodes_out_before_the_last_watch_are_not_news() {
        let episodes = [episode(1, 1, Some(20)), episode(1, 2, Some(15))];
        // The user stopped after E1 last week, when E2 was already out.
        assert_eq!(
            new_episode(&episodes, Some((1, 1)), NOW - 7 * DAY, NOW),
            None
        );
    }

    #[test]
    fn a_saved_series_offers_what_came_out_since_it_was_saved() {
        let episodes = [episode(1, 1, Some(400)), episode(2, 1, Some(3))];
        assert_eq!(new_episode(&episodes, None, NOW - 10 * DAY, NOW), Some(1));
    }

    #[test]
    fn skips_specials_unnumbered_undated_and_old_episodes() {
        let episodes = [
            episode(0, 1, Some(1)),
            EpisodeInfo {
                season: Some(2),
                episode: None,
                released_ms: Some(NOW - DAY),
            },
            EpisodeInfo {
                season: None,
                episode: Some(1),
                released_ms: Some(NOW - DAY),
            },
            episode(2, 1, None),
            episode(1, 9, Some(200)),
        ];
        assert_eq!(new_episode(&episodes, Some((1, 1)), 0, NOW), None);
        assert_eq!(new_episode(&[], None, 0, NOW), None);
    }

    #[test]
    fn a_new_season_follows_the_last_one() {
        let episodes = [episode(1, 10, Some(300)), episode(2, 1, Some(2))];
        assert_eq!(
            new_episode(&episodes, Some((1, 10)), NOW - 290 * DAY, NOW),
            Some(1)
        );
    }
}
