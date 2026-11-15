// SPDX-License-Identifier: GPL-3.0-or-later
//! Scoring of [`Traits`] against [`Preferences`], and ranking of source lists.

use std::cmp::Reverse;

use crate::parse::parse;
use crate::traits::{Codec, Hdr, ReleaseQuality, Resolution, SourceText, Traits};

/// What the user wants and what the device can play.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Preferences {
    /// Sources above this resolution sink to the bottom.
    pub max_resolution: Option<Resolution>,
    /// ISO 639-1 codes, most preferred first. Region subtags (`pt-BR`) are ignored.
    pub preferred_languages: Vec<String>,
    /// Rank debrid-cached sources above ones that must be downloaded first.
    pub prefer_cached: bool,
    /// Sink theater captures and screeners (CAM, TS, SCR) to the bottom.
    pub avoid_low_quality: bool,
    /// Sources larger than this sink to the bottom.
    pub max_size_bytes: Option<u64>,
    /// The display path can show HDR.
    pub supports_hdr: bool,
    /// The device can decode HEVC.
    pub supports_hevc: bool,
    /// The device can decode AV1.
    pub supports_av1: bool,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            max_resolution: None,
            preferred_languages: Vec::new(),
            prefer_cached: true,
            avoid_low_quality: true,
            max_size_bytes: None,
            supports_hdr: true,
            supports_hevc: true,
            supports_av1: false,
        }
    }
}

/// Score weights. Each tier outweighs the whole range of every tier below it, so the criteria
/// apply in priority order; within a tier, unknown values score as a neutral middle.
pub mod weights {
    /// Added once when a source breaks a hard limit (resolution, size, low quality). The source
    /// stays listed, below every source that does not.
    pub const UNWANTED: i64 = -1_000_000_000_000;
    /// Added for a cached debrid source and subtracted for an uncached one, when cached sources
    /// are preferred. Unknown cache state scores zero.
    pub const CACHED: i64 = 1_000_000_000;
    /// Added when the source has the most preferred language.
    pub const LANGUAGE_MATCH: i64 = 100_000_000;
    /// Subtracted from [`LANGUAGE_MATCH`] per position down the preference list.
    pub const LANGUAGE_RANK_STEP: i64 = 10_000_000;
    /// Lowest position that still lowers the language bonus.
    pub const LANGUAGE_RANK_MAX: i64 = 5;
    /// Per resolution class (SD = 1 ... 8K = 6). Unknown scores 2.5 steps, between 720p and
    /// 1080p.
    pub const RESOLUTION_STEP: i64 = 1_000_000;
    /// Per release quality class (CAM = 1 ... Remux = 9). Unknown scores 5.5 steps, between
    /// HDTV and WEBRip.
    pub const QUALITY_STEP: i64 = 100_000;
    /// Added for any HDR format when the display supports HDR.
    pub const HDR: i64 = 40_000;
    /// Added on top of [`HDR`] for Dolby Vision.
    pub const DOLBY_VISION: i64 = 20_000;
    /// Added on top of [`HDR`] for HDR10+.
    pub const HDR10_PLUS: i64 = 10_000;
    /// Added for Dolby Vision without an HDR10 fallback layer on an SDR display, which plays
    /// with wrong colors.
    pub const DOLBY_VISION_ONLY_ON_SDR: i64 = -15_000;
    /// Added when the device cannot decode the video codec.
    pub const UNSUPPORTED_CODEC: i64 = -75_000;
    /// Per bit of the seeder count (a log2 scale), capping at 32 bits.
    pub const SEEDERS_PER_BIT: i64 = 250;
}

/// Scores a source; higher is better. See [`weights`] for how criteria combine.
pub fn score(traits: &Traits, prefs: &Preferences) -> i64 {
    let unwanted = if is_unwanted(traits, prefs) {
        weights::UNWANTED
    } else {
        0
    };
    unwanted
        + cache_score(traits, prefs)
        + language_score(traits, prefs)
        + resolution_score(traits)
        + quality_score(traits)
        + hdr_score(traits, prefs)
        + codec_score(traits, prefs)
        + seeders_score(traits)
}

/// Sorts `items` best first, keeping the original order between equal scores. `text` gives
/// each item's addon text; every item is parsed exactly once.
pub fn rank<T>(items: &mut [T], prefs: &Preferences, text: impl Fn(&T) -> SourceText<'_>) {
    items.sort_by_cached_key(|item| Reverse(score(&parse(&text(item)), prefs)));
}

fn is_unwanted(t: &Traits, p: &Preferences) -> bool {
    let too_sharp = matches!((t.resolution, p.max_resolution), (Some(r), Some(max)) if r > max);
    let too_big = matches!((t.size_bytes, p.max_size_bytes), (Some(s), Some(max)) if s > max);
    let too_poor = p.avoid_low_quality && t.quality.is_some_and(ReleaseQuality::is_low);
    too_sharp || too_big || too_poor
}

fn cache_score(t: &Traits, p: &Preferences) -> i64 {
    match t.cached {
        Some(true) if p.prefer_cached => weights::CACHED,
        Some(false) if p.prefer_cached => -weights::CACHED,
        _ => 0,
    }
}

/// Primary language subtag, lowercased: `"pt-BR"` becomes `"pt"`. Codes that name the same
/// language become the one sources are tagged with: Java's withdrawn `iw`, `in` and `ji`, and
/// Tagalog's `tl` for Filipino (`fil`).
fn primary_subtag(code: &str) -> String {
    let primary = code
        .split(['-', '_'])
        .next()
        .unwrap_or_default()
        .trim()
        .to_lowercase();
    match primary.as_str() {
        "iw" => "he".into(),
        "in" => "id".into(),
        "ji" => "yi".into(),
        "tl" => "fil".into(),
        _ => primary,
    }
}

fn language_score(t: &Traits, p: &Preferences) -> i64 {
    let position = p.preferred_languages.iter().position(|wanted| {
        let wanted = primary_subtag(wanted);
        t.languages
            .iter()
            .any(|have| primary_subtag(have) == wanted)
    });
    position.map_or(0, |i| {
        let rank = i64::try_from(i)
            .unwrap_or(i64::MAX)
            .min(weights::LANGUAGE_RANK_MAX);
        weights::LANGUAGE_MATCH - rank * weights::LANGUAGE_RANK_STEP
    })
}

fn resolution_score(t: &Traits) -> i64 {
    // Counted in half steps so that "unknown" can sit between two classes.
    let half_steps = match t.resolution {
        None => 5,
        Some(Resolution::Sd) => 2,
        Some(Resolution::P720) => 4,
        Some(Resolution::P1080) => 6,
        Some(Resolution::P1440) => 8,
        Some(Resolution::P2160) => 10,
        Some(Resolution::P4320) => 12,
    };
    half_steps * weights::RESOLUTION_STEP / 2
}

fn quality_score(t: &Traits) -> i64 {
    use ReleaseQuality as Q;
    let half_steps = match t.quality {
        Some(Q::Cam) => 2,
        Some(Q::Telesync) => 4,
        Some(Q::Screener) => 6,
        Some(Q::Dvd) => 8,
        Some(Q::Hdtv) => 10,
        None => 11,
        Some(Q::WebRip) => 12,
        Some(Q::WebDl) => 14,
        Some(Q::BluRay) => 16,
        Some(Q::Remux) => 18,
    };
    half_steps * weights::QUALITY_STEP / 2
}

fn hdr_score(t: &Traits, p: &Preferences) -> i64 {
    let has = |format| t.hdr.contains(&format);
    if t.hdr.is_empty() {
        0
    } else if p.supports_hdr {
        let dv = if has(Hdr::DolbyVision) {
            weights::DOLBY_VISION
        } else {
            0
        };
        let plus = if has(Hdr::Hdr10Plus) {
            weights::HDR10_PLUS
        } else {
            0
        };
        weights::HDR + dv + plus
    } else if t.hdr == [Hdr::DolbyVision] {
        weights::DOLBY_VISION_ONLY_ON_SDR
    } else {
        0
    }
}

fn codec_score(t: &Traits, p: &Preferences) -> i64 {
    let supported = match t.codec {
        None | Some(Codec::Avc) => true,
        Some(Codec::Hevc) => p.supports_hevc,
        Some(Codec::Av1) => p.supports_av1,
    };
    if supported {
        0
    } else {
        weights::UNSUPPORTED_CODEC
    }
}

fn seeders_score(t: &Traits) -> i64 {
    let bits = t.seeders.map_or(0, |s| u32::BITS - s.leading_zeros());
    i64::from(bits) * weights::SEEDERS_PER_BIT
}

#[cfg(test)]
mod tests {
    use super::*;

    const GIB: u64 = 1 << 30;

    fn traits(f: impl FnOnce(&mut Traits)) -> Traits {
        let mut t = Traits::default();
        f(&mut t);
        t
    }

    fn ranked(titles: &[&'static str], prefs: &Preferences) -> Vec<&'static str> {
        let mut items = titles.to_vec();
        rank(&mut items, prefs, |title| SourceText {
            name: "",
            title,
            description: "",
        });
        items
    }

    #[test]
    fn default_preferences() {
        let p = Preferences::default();
        assert_eq!(p.max_resolution, None);
        assert!(p.preferred_languages.is_empty());
        assert!(p.prefer_cached && p.avoid_low_quality && p.supports_hdr && p.supports_hevc);
        assert!(!p.supports_av1);
        assert_eq!(p.max_size_bytes, None);
    }

    #[test]
    fn higher_resolution_and_quality_win() {
        let order = ranked(
            &[
                "Movie.720p.WEB-DL",
                "Movie.2160p.BluRay.REMUX",
                "Movie.1080p.WEBRip",
                "Movie.1080p.BluRay",
            ],
            &Preferences::default(),
        );
        assert_eq!(
            order,
            [
                "Movie.2160p.BluRay.REMUX",
                "Movie.1080p.BluRay",
                "Movie.1080p.WEBRip",
                "Movie.720p.WEB-DL",
            ]
        );
    }

    #[test]
    fn hard_limits_sink_but_stay_listed() {
        let prefs = Preferences {
            max_resolution: Some(Resolution::P1080),
            max_size_bytes: Some(10 * GIB),
            ..Preferences::default()
        };
        let order = ranked(
            &[
                "Movie.2160p.Remux 💾 60 GB",
                "Movie.2024.HDCAM.1080p",
                "Movie.1080p.BluRay 💾 30 GB",
                "Movie.720p.WEB 💾 2 GB",
            ],
            &prefs,
        );
        assert_eq!(order[0], "Movie.720p.WEB 💾 2 GB");
        assert_eq!(order.len(), 4);
    }

    #[test]
    fn low_quality_ranks_normally_when_not_avoided() {
        let prefs = Preferences {
            avoid_low_quality: false,
            ..Preferences::default()
        };
        let order = ranked(&["Movie.720p.WEB", "Movie.2024.CAM.1080p"], &prefs);
        assert_eq!(order[0], "Movie.2024.CAM.1080p");
    }

    #[test]
    fn cached_beats_language_and_resolution() {
        let prefs = Preferences {
            preferred_languages: vec!["pt".into()],
            ..Preferences::default()
        };
        let cached = traits(|t| {
            t.cached = Some(true);
            t.resolution = Some(Resolution::P720);
        });
        let unknown = traits(|t| t.resolution = Some(Resolution::P2160));
        let uncached = traits(|t| {
            t.cached = Some(false);
            t.languages = vec!["pt".into()];
            t.resolution = Some(Resolution::P4320);
        });
        assert!(score(&cached, &prefs) > score(&unknown, &prefs));
        assert!(score(&unknown, &prefs) > score(&uncached, &prefs));

        let indifferent = Preferences {
            prefer_cached: false,
            ..prefs
        };
        assert!(score(&uncached, &indifferent) > score(&cached, &indifferent));
    }

    #[test]
    fn preferred_language_beats_resolution_and_order_matters() {
        let prefs = Preferences {
            preferred_languages: vec!["pt-BR".into(), "en".into()],
            ..Preferences::default()
        };
        let order = ranked(
            &[
                "Movie.2160p.Remux 🇬🇧",
                "Movie.480p.DVDRip 🇮🇹",
                "Movie.720p.WEB Dublado",
            ],
            &prefs,
        );
        assert_eq!(
            order,
            [
                "Movie.720p.WEB Dublado",
                "Movie.2160p.Remux 🇬🇧",
                "Movie.480p.DVDRip 🇮🇹",
            ]
        );
    }

    #[test]
    fn old_and_alternative_codes_match_their_language() {
        for (wanted, have) in [("iw", "he"), ("in", "id"), ("tl", "fil"), ("fil", "fil")] {
            let prefs = Preferences {
                preferred_languages: vec![wanted.into()],
                ..Preferences::default()
            };
            let source = traits(|t| t.languages = vec![have.into()]);
            assert!(
                score(&source, &prefs) > score(&Traits::default(), &prefs),
                "{wanted}"
            );
        }
    }

    #[test]
    fn subtitles_alone_do_not_match_the_language() {
        let prefs = Preferences {
            preferred_languages: vec!["it".into()],
            ..Preferences::default()
        };
        let order = ranked(
            &[
                "Movie.2023.1080p.Eng.Sub.Ita",
                "Movie.2023.720p.Ita",
                "Movie.2023.1080p.VOSTFR",
            ],
            &prefs,
        );
        assert_eq!(order[0], "Movie.2023.720p.Ita");
    }

    #[test]
    fn hdr_depends_on_display() {
        let dv_only = traits(|t| t.hdr = vec![Hdr::DolbyVision]);
        let dv_hdr = traits(|t| t.hdr = vec![Hdr::DolbyVision, Hdr::Hdr10]);
        let sdr = Traits::default();
        let hdr_display = Preferences::default();
        assert!(score(&dv_hdr, &hdr_display) > score(&sdr, &hdr_display));
        assert!(score(&dv_only, &hdr_display) > score(&sdr, &hdr_display));

        let sdr_display = Preferences {
            supports_hdr: false,
            ..Preferences::default()
        };
        assert_eq!(score(&dv_hdr, &sdr_display), score(&sdr, &sdr_display));
        assert!(score(&dv_only, &sdr_display) < score(&sdr, &sdr_display));
    }

    #[test]
    fn unsupported_codec_is_penalized_below_quality() {
        let prefs = Preferences::default();
        let av1 = traits(|t| t.codec = Some(Codec::Av1));
        let avc = traits(|t| t.codec = Some(Codec::Avc));
        assert!(score(&av1, &prefs) < score(&avc, &prefs));

        let av1_remux = traits(|t| {
            t.codec = Some(Codec::Av1);
            t.quality = Some(ReleaseQuality::Remux);
        });
        let avc_bluray = traits(|t| {
            t.codec = Some(Codec::Avc);
            t.quality = Some(ReleaseQuality::BluRay);
        });
        assert!(score(&av1_remux, &prefs) > score(&avc_bluray, &prefs));
    }

    #[test]
    fn seeders_only_break_ties() {
        let prefs = Preferences::default();
        let many = traits(|t| {
            t.seeders = Some(u32::MAX);
            t.resolution = Some(Resolution::P720);
        });
        let few = traits(|t| {
            t.seeders = Some(1);
            t.resolution = Some(Resolution::P1080);
        });
        assert!(score(&few, &prefs) > score(&many, &prefs));
        let none = traits(|t| t.resolution = Some(Resolution::P720));
        assert!(score(&many, &prefs) > score(&none, &prefs));
    }

    #[test]
    fn unknown_values_are_neutral() {
        let prefs = Preferences::default();
        let unknown = Traits::default();
        let sd = traits(|t| t.resolution = Some(Resolution::Sd));
        let fhd = traits(|t| t.resolution = Some(Resolution::P1080));
        assert!(score(&sd, &prefs) < score(&unknown, &prefs));
        assert!(score(&unknown, &prefs) < score(&fhd, &prefs));
    }

    #[test]
    fn rank_is_stable() {
        let order = ranked(
            &["a 1080p", "b 1080p", "c 720p", "d 1080p"],
            &Preferences::default(),
        );
        assert_eq!(order, ["a 1080p", "b 1080p", "d 1080p", "c 720p"]);
    }

    #[test]
    fn rank_handles_empty_and_single() {
        assert!(ranked(&[], &Preferences::default()).is_empty());
        assert_eq!(ranked(&["x"], &Preferences::default()), ["x"]);
    }

    #[test]
    fn extreme_traits_do_not_overflow() {
        let worst = traits(|t| {
            t.resolution = Some(Resolution::P4320);
            t.size_bytes = Some(u64::MAX);
            t.quality = Some(ReleaseQuality::Cam);
            t.cached = Some(false);
            t.codec = Some(Codec::Av1);
            t.hdr = vec![Hdr::DolbyVision];
            t.seeders = Some(u32::MAX);
        });
        let prefs = Preferences {
            max_resolution: Some(Resolution::Sd),
            max_size_bytes: Some(0),
            preferred_languages: vec![String::new(); 1000],
            supports_hdr: false,
            ..Preferences::default()
        };
        assert!(score(&worst, &prefs) < 0);
    }
}
