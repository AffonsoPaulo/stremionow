// SPDX-License-Identifier: GPL-3.0-or-later
//! Extraction of [`Traits`] from addon text.

use crate::text::{
    Token, first_size, flag_countries, label_after, lookahead, number_after, scan, stem, tokenize,
    truncate,
};
use crate::traits::{Audio, Codec, Hdr, ReleaseQuality, Resolution, SourceText, Traits};

/// Only this many leading bytes of each [`SourceText`] field are inspected (cut on a char
/// boundary), which bounds the work done on hostile or absurdly long input.
pub const MAX_INSPECTED_BYTES: usize = 4096;

/// Extracts every recognizable [`Traits`] field from a source's text. Never panics.
pub fn parse(text: &SourceText<'_>) -> Traits {
    let joined = [text.name, text.title, text.description]
        .map(|field| truncate(field, MAX_INSPECTED_BYTES))
        .join("\n");
    let tokens = tokenize(&joined);
    let tags = tag_tokens(&joined);
    let lower = joined.to_lowercase();
    let (languages, subtitle_languages) = languages(&joined, &tags);
    Traits {
        resolution: resolution(&tokens),
        hdr: sorted_unique(scan(&tokens, hdr)),
        codec: scan(&tokens, codec).into_iter().next(),
        audio: sorted_unique(scan(&tokens, audio)),
        quality: quality(&tags),
        size_bytes: first_size(&joined),
        seeders: seeders(&lower),
        cached: cached(truncate(text.name, MAX_INSPECTED_BYTES), &joined, &tokens),
        languages,
        subtitle_languages,
        multi_audio: multi_audio(&tokens),
        provider: provider(&joined),
        release_group: release_group(&joined),
    }
}

/// Endings of scene names that follow the last `-` but are not groups.
const NOT_GROUPS: &[&str] = &[
    "dl", "rip", "hd", "sd", "x264", "x265", "h264", "h265", "hevc", "avc", "aac", "dts", "ac3",
    "web", "hdr", "dv", "uhd",
];
/// File extensions dropped before looking for the group.
const VIDEO_EXTENSIONS: &[&str] = &[".mkv", ".mp4", ".avi", ".m4v", ".ts", ".webm", ".mov"];

/// The release group of the first scene-style name in `text`: a word with dots between its
/// parts whose last `-` precedes the group, as in `Movie.2023.1080p.WEB-DL.H264-GRP.mkv`.
fn release_group(text: &str) -> Option<String> {
    text.split_whitespace().find_map(group_of)
}

fn group_of(word: &str) -> Option<String> {
    let lower = word.to_ascii_lowercase();
    let mut end = word.len();
    if let Some(extension) = VIDEO_EXTENSIONS.iter().find(|e| lower.ends_with(*e)) {
        end -= extension.len();
    }
    let mut name = word.get(..end)?;
    // A site tag after the group, as in `-ETHEL[eztv.re]`.
    if name.ends_with(']')
        && let Some(open) = name.rfind('[')
    {
        name = &name[..open];
    }
    if name.matches('.').count() < 2 {
        return None;
    }
    let (_, group) = name.rsplit_once('-')?;
    let plausible = (2..=20).contains(&group.len())
        && group.bytes().all(|b| b.is_ascii_alphanumeric())
        && group.bytes().any(|b| b.is_ascii_alphabetic())
        && !NOT_GROUPS.contains(&group.to_ascii_lowercase().as_str());
    plausible.then(|| group.to_owned())
}

/// The tokens that can be tags, line by line. On a line with a release name, only those after
/// its year or episode tag: release names put the title first, and title words are not tags
/// ("The.Italian.Job.2003" is not in Italian, "Charlottes.Web.2006" is no WEB-DL). They are
/// marked [`Token::after_title`]. Every token of a line without a year or episode tag counts
/// ("🌐 English, Spanish", "Movie PT-BR 720p").
fn tag_tokens(text: &str) -> Vec<Token<'_>> {
    let mut tags = Vec::new();
    for line in text.split('\n') {
        let mut tokens = tokenize(line);
        // The first word may be a title that is a year ("1917.2019.1080p", "2012.2009").
        match tokens.iter().skip(1).position(ends_title) {
            Some(before) => {
                let end = before + 1;
                for token in &mut tokens[end..] {
                    token.after_title = true;
                }
                tags.extend(tokens.into_iter().skip(end));
            }
            None => tags.extend(tokens),
        }
    }
    tags
}

/// A release year (`2003`) or an episode tag (`S01`, `S01E02`, `1x02`), which end the title of
/// a release name. The token itself is kept as a tag.
fn ends_title(token: &Token<'_>) -> bool {
    let digits = |s: &str| (1..=3).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_digit());
    let lower = token.lower.as_str();
    let year = lower.len() == 4
        && lower.bytes().all(|b| b.is_ascii_digit())
        && lower
            .parse::<u32>()
            .is_ok_and(|y| (1900..=2099).contains(&y));
    let episode = match lower.strip_prefix('s') {
        Some(rest) => match rest.split_once('e') {
            Some((season, episode)) => digits(season) && digits(episode),
            None => digits(rest),
        },
        None => lower.split_once('x').is_some_and(|(season, episode)| {
            digits(season) && digits(episode) && episode.len() >= 2
        }),
    };
    year || episode
}

fn sorted_unique<T: Ord>(mut values: Vec<T>) -> Vec<T> {
    values.sort();
    values.dedup();
    values
}

/// Highest resolution mentioned. Explicit pixel heights (`1080p`, `1920x1080`) win over words
/// such as `4K`, which also appear in titles like "4K Remaster".
fn resolution(tokens: &[Token<'_>]) -> Option<Resolution> {
    let found = scan(tokens, |t| Some((resolution_of(&t[0].lower)?, 1)));
    let explicit = found
        .iter()
        .filter(|(_, explicit)| *explicit)
        .map(|(r, _)| *r)
        .max();
    explicit.or_else(|| found.into_iter().map(|(r, _)| r).max())
}

/// A resolution and whether it was an explicit pixel height.
fn resolution_of(token: &str) -> Option<(Resolution, bool)> {
    let word = match token {
        "8k" => Some(Resolution::P4320),
        "4k" | "uhd" => Some(Resolution::P2160),
        "qhd" => Some(Resolution::P1440),
        "fhd" => Some(Resolution::P1080),
        "sd" => Some(Resolution::Sd),
        _ => None,
    };
    if let Some(res) = word {
        return Some((res, false));
    }
    let height = match token.strip_suffix(['p', 'i']) {
        Some(digits) => digits,
        None => token.split_once('x')?.1,
    };
    Some((resolution_from_height(height.parse().ok()?)?, true))
}

fn resolution_from_height(height: u32) -> Option<Resolution> {
    Some(match height {
        240 | 288 | 360 | 480 | 540 | 576 => Resolution::Sd,
        720 => Resolution::P720,
        1080 => Resolution::P1080,
        1440 => Resolution::P1440,
        2160 => Resolution::P2160,
        4320 => Resolution::P4320,
        _ => return None,
    })
}

fn hdr(t: &[Token<'_>]) -> Option<(Hdr, usize)> {
    Some(match lookahead(t) {
        ["dolby", "vision", _] => (Hdr::DolbyVision, 2),
        ["hdr10", "plus", _] | ["hdr", "10+", _] => (Hdr::Hdr10Plus, 2),
        ["hdr", "10", _] => (Hdr::Hdr10, 2),
        ["dv" | "dovi" | "dolbyvision", ..] => (Hdr::DolbyVision, 1),
        ["hdr10+" | "hdr10plus", ..] => (Hdr::Hdr10Plus, 1),
        ["hdr10" | "hdr", ..] => (Hdr::Hdr10, 1),
        ["hlg", ..] => (Hdr::Hlg, 1),
        _ => return None,
    })
}

fn codec(t: &[Token<'_>]) -> Option<(Codec, usize)> {
    Some(match lookahead(t) {
        ["h", "265", _] => (Codec::Hevc, 2),
        ["h", "264", _] => (Codec::Avc, 2),
        ["x265" | "h265" | "hevc", ..] => (Codec::Hevc, 1),
        ["x264" | "h264" | "avc", ..] => (Codec::Avc, 1),
        ["av1", ..] => (Codec::Av1, 1),
        _ => return None,
    })
}

fn audio(t: &[Token<'_>]) -> Option<(Audio, usize)> {
    let [a, b, c] = lookahead(t);
    Some(match [a, b, c] {
        ["dolby", "digital", "plus"] => (Audio::Ddp, 3),
        ["dolby", "digital", _] => (Audio::Dd, 2),
        ["dts", "hd", "ma"] => (Audio::DtsHdMa, 3),
        ["dts", "hdma", _] | ["dtshd", "ma", _] => (Audio::DtsHdMa, 2),
        ["dts", "x", _] => (Audio::DtsX, 2),
        ["dts", "hd", _] => (Audio::Dts, 2),
        ["true", "hd", _] => (Audio::TrueHd, 2),
        ["e", "ac", "3"] => (Audio::Ddp, 3),
        ["e", "ac3", _] => (Audio::Ddp, 2),
        _ => (audio_word(a)?, 1),
    })
}

fn audio_word(token: &str) -> Option<Audio> {
    let is = |names: &[&str]| names.contains(&token) || names.contains(&stem(token));
    Some(if is(&["atmos"]) {
        Audio::Atmos
    } else if is(&["truehd"]) {
        Audio::TrueHd
    } else if is(&["dtsx"]) {
        Audio::DtsX
    } else if is(&["dtshdma", "dtsma"]) {
        Audio::DtsHdMa
    } else if is(&["dts", "dtshd"]) {
        Audio::Dts
    } else if is(&["ddp", "dd+", "eac3"]) {
        Audio::Ddp
    } else if is(&["dd", "ac3"]) {
        Audio::Dd
    } else if is(&["aac"]) {
        Audio::Aac
    } else if is(&["opus"]) {
        Audio::Opus
    } else if is(&["flac"]) {
        Audio::Flac
    } else {
        return None;
    })
}

/// Release quality. Theater captures are reported even next to better tags, since a "CAM"
/// mislabeled as "WEB" is far more common than the reverse.
fn quality(tokens: &[Token<'_>]) -> Option<ReleaseQuality> {
    let found = scan(tokens, quality_at);
    let worst_low = found.iter().copied().filter(|q| q.is_low()).min();
    worst_low.or_else(|| found.into_iter().max())
}

fn quality_at(t: &[Token<'_>]) -> Option<(ReleaseQuality, usize)> {
    use ReleaseQuality as Q;
    let [a, b, _] = lookahead(t);
    Some(match [a, b] {
        ["web", "dl"] => (Q::WebDl, 2),
        ["web", "rip"] => (Q::WebRip, 2),
        ["blu", "ray"] => (Q::BluRay, 2),
        ["dvd" | "bd" | "web", "scr"] => (Q::Screener, 2),
        ["cam", "rip"] if t[0].raw == "CAM" => (Q::Cam, 2),
        _ => (quality_word(t[0].raw, a)?, 1),
    })
}

fn quality_word(raw: &str, token: &str) -> Option<ReleaseQuality> {
    use ReleaseQuality as Q;
    // Short tags are only trusted in upper case: "ts" is also a file extension and "cam" a word.
    match raw {
        "CAM" => return Some(Q::Cam),
        "TS" | "TC" => return Some(Q::Telesync),
        _ => {}
    }
    Some(match stem(token) {
        "remux" | "bdremux" => Q::Remux,
        "bluray" | "bdrip" | "brrip" | "bdmux" => Q::BluRay,
        "webdl" | "web" => Q::WebDl,
        "webrip" => Q::WebRip,
        "hdtv" | "pdtv" | "hdtvrip" | "tvrip" => Q::Hdtv,
        "dvd" | "dvdrip" | "dvdr" => Q::Dvd,
        "scr" | "screener" | "dvdscr" | "bdscr" | "webscr" => Q::Screener,
        "telesync" | "hdts" | "tsrip" | "telecine" | "hdtc" => Q::Telesync,
        "camrip" | "hdcam" | "hqcam" => Q::Cam,
        _ => return None,
    })
}

fn seeders(lower: &str) -> Option<u32> {
    ["👤", "👥", "🌱", "seeders", "seeds", "seeder"]
        .into_iter()
        .find_map(|marker| number_after(lower, marker))
}

/// Debrid tags such as `[RD+]` or `[AD⚡]`. Only these service prefixes are trusted inside
/// brackets, so `[HDR10+]` is not mistaken for a cache marker.
const DEBRID_TAGS: &[&str] = &[
    "rd",
    "ad",
    "pm",
    "tb",
    "dl",
    "ed",
    "oc",
    "pp",
    "db",
    "ez",
    "realdebrid",
    "alldebrid",
    "premiumize",
    "torbox",
    "debridlink",
    "easydebrid",
    "offcloud",
];

/// Debrid cache state. Addons say it in the stream name (`[RD+] Torrentio`, `[RD⚡] Comet`,
/// `MediaFusion | RD ⏳`, `Jackettio Instant`) or spell it out (`Not cached`, `Cached: Yes`).
/// Words and emoji that also turn up in release names and descriptions (`Instant.Family.2018`,
/// a `⏳` before a duration) are only trusted in the name or right next to a debrid service.
fn cached(name: &str, text: &str, tokens: &[Token<'_>]) -> Option<bool> {
    if let Some(state) = bracket_tags(text).find_map(debrid_tag_state) {
        return Some(state);
    }
    if let Some(state) = tokens.windows(2).find_map(stated_cache).or_else(|| {
        // A spelled-out state as the very last word ("... Uncached").
        tokens
            .last()
            .and_then(|t| stated_cache(std::slice::from_ref(t)))
    }) {
        return Some(state);
    }
    if let Some(state) = emoji_next_to_service(text) {
        return Some(state);
    }
    let name_has = |words: &[&str]| {
        tokenize(name).iter().any(|t| {
            words.contains(&t.lower.as_str())
                || t.lower
                    .strip_suffix('+')
                    .is_some_and(|service| DEBRID_TAGS.contains(&service))
        })
    };
    if name.contains(['⏳', '⬇']) {
        Some(false)
    } else if name.contains('⚡') || name_has(&["instant", "cached"]) {
        Some(true)
    } else {
        None
    }
}

/// A cache state spelled out at the start of `pair`: `uncached`, `not cached`, `cached: no`,
/// `cached: yes`, or a debrid service followed by `cached`, `instant` or `download`. The
/// positive words alone say nothing here; see [`cached`].
fn stated_cache(pair: &[Token<'_>]) -> Option<bool> {
    let a = pair.first().map_or("", |t| t.lower.as_str());
    let b = pair.get(1).map_or("", |t| t.lower.as_str());
    match [a, b] {
        ["uncached" | "notcached" | "noncached", _] => Some(false),
        ["not" | "non", "cached"] => Some(false),
        ["cached", "no" | "false" | "0"] => Some(false),
        ["cached", "yes" | "true" | "1"] => Some(true),
        [service, state] if DEBRID_TAGS.contains(&service) => match state {
            "cached" | "instant" => Some(true),
            "uncached" | "download" => Some(false),
            _ => None,
        },
        _ => None,
    }
}

/// A `⚡` or `⏳` right after or before a debrid service, as in `RD ⚡️` or `⏳RD`.
fn emoji_next_to_service(text: &str) -> Option<bool> {
    let is_service = |word: &str| DEBRID_TAGS.contains(&word.to_lowercase().as_str());
    let filler = |c: char| c.is_whitespace() || matches!(c, '\u{fe0f}' | '\u{200d}');
    text.match_indices(['⚡', '⏳', '⬇'])
        .find_map(|(at, emoji)| {
            let before = text[..at].trim_end_matches(filler);
            let word_before = before
                .rsplit(|c: char| !c.is_ascii_alphabetic())
                .next()
                .unwrap_or_default();
            let after = text[at + emoji.len()..].trim_start_matches(filler);
            let word_after = after
                .split(|c: char| !c.is_ascii_alphabetic())
                .next()
                .unwrap_or_default();
            (is_service(word_before) || is_service(word_after)).then_some(emoji == "⚡")
        })
}

/// Contents of short `[...]` groups in `text`.
fn bracket_tags(text: &str) -> impl Iterator<Item = &str> {
    const MAX_TAG_BYTES: usize = 32;
    text.split('[').skip(1).filter_map(|rest| {
        let (tag, _) = rest.split_once(']')?;
        (tag.len() <= MAX_TAG_BYTES).then_some(tag)
    })
}

fn debrid_tag_state(tag: &str) -> Option<bool> {
    let tag = tag.trim().to_lowercase();
    let service_len = tag.bytes().take_while(u8::is_ascii_alphabetic).count();
    let (service, state) = tag.split_at(service_len);
    if !DEBRID_TAGS.contains(&service) {
        return None;
    }
    if state.contains(['+', '⚡']) {
        Some(true)
    } else if state.contains("download") || state.contains(['⬇', '⏳']) {
        Some(false)
    } else {
        None
    }
}

/// Words that say the languages next to them are subtitles: "Sub.Ita", "Subs: English",
/// "English Subtitles", "Napisy PL".
const SUBTITLE_MARKERS: &[&str] = &[
    "sub",
    "subs",
    "subbed",
    "subtitle",
    "subtitles",
    "subtitled",
    "legenda",
    "legendas",
    "subtitulado",
    "subtitulos",
    "subtítulos",
    "sottotitoli",
    "untertitel",
    "napisy",
];

/// Codes of India's languages, which its flag stands for.
const INDIAN_LANGUAGES: &[&str] = &["hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "ur"];

/// Audio and subtitle languages, each as ISO 639-1 codes (`fil` for Filipino, which has none
/// that Android uses), deduplicated, in order of discovery. Flags are read as audio.
///
/// India's flag stands for any of its languages: Torrentio shows it for Hindi, Tamil and
/// Telugu alike. It adds Hindi, the most common, only when the text names none of India's
/// languages; with `Tam` or `Telugu` in the name, those are the languages.
fn languages(text: &str, tags: &[Token<'_>]) -> (Vec<String>, Vec<String>) {
    let mut audio: Vec<&'static str> = Vec::new();
    let mut subtitles: Vec<&'static str> = Vec::new();
    let push = |list: &mut Vec<&'static str>, code: &'static str| {
        if !list.contains(&code) {
            list.push(code);
        }
    };
    let mut indian_flag = false;
    for country in flag_countries(text) {
        if country == ['i', 'n'] {
            indian_flag = true;
        } else if let Some(code) = language_of_country(country) {
            push(&mut audio, code);
        }
    }
    for found in scan(tags, language_at) {
        let list = if found.subtitles {
            &mut subtitles
        } else {
            &mut audio
        };
        for code in found.codes {
            push(list, code);
        }
    }
    if indian_flag && !audio.iter().any(|code| INDIAN_LANGUAGES.contains(code)) {
        push(&mut audio, "hi");
    }
    let owned = |list: Vec<&str>| list.into_iter().map(str::to_owned).collect();
    (owned(audio), owned(subtitles))
}

/// Languages found together, and whether they are subtitles.
struct FoundLanguages {
    codes: Vec<&'static str>,
    subtitles: bool,
}

fn language_at(t: &[Token<'_>]) -> Option<(FoundLanguages, usize)> {
    let [a, ..] = lookahead(t);
    if SUBTITLE_MARKERS.contains(&a) {
        // "Sub.Ita", "Subs: English, Spanish": the languages that follow.
        let mut codes = Vec::new();
        let mut i = 1;
        while let Some((code, consumed)) = language_word(&t[i..]) {
            codes.push(code);
            i += consumed;
        }
        return (!codes.is_empty()).then_some((
            FoundLanguages {
                codes,
                subtitles: true,
            },
            i,
        ));
    }
    if let Some(code) = subtitle_tag(a) {
        let found = FoundLanguages {
            codes: vec![code],
            subtitles: true,
        };
        return Some((found, 1));
    }
    let (code, consumed) = language_word(t)?;
    // "English Subtitles" and "Eng.Sub" are subtitles, but in "Eng.Sub.Ita" English is the
    // audio and the subtitles are Italian.
    let then = t.get(consumed..).unwrap_or_default();
    let subtitles = then.first().is_some_and(|marker| {
        SUBTITLE_MARKERS.contains(&marker.lower.as_str()) && language_word(&then[1..]).is_none()
    });
    let found = FoundLanguages {
        codes: vec![code],
        subtitles,
    };
    Some((found, consumed + usize::from(subtitles)))
}

/// Single-word subtitle tags: `VOSTFR` (original audio, French subtitles), `VOSE`,
/// `Legendado` (Portuguese subtitles), and `Sub` joined to a language (`SubIta`, `SubEng`).
fn subtitle_tag(token: &str) -> Option<&'static str> {
    Some(match token {
        "vostfr" | "stfr" | "subfrench" => "fr",
        "vose" => "es",
        "legendado" | "legendada" => "pt",
        _ => {
            let language = token.strip_prefix("sub")?;
            return word_language(language, true).or_else(|| short_code(language));
        }
    })
}

/// Two-letter codes, trusted only joined to a marker (`SubPt`).
fn short_code(code: &str) -> Option<&'static str> {
    const CODES: &[&str] = &[
        "en", "pt", "es", "fr", "de", "it", "nl", "pl", "ru", "ja", "ko", "zh", "ar", "tr", "el",
        "he", "hi", "sv", "da", "fi", "cs", "hu", "ro", "uk", "bg", "hr", "sr", "vi", "th", "id",
    ];
    CODES.iter().find(|c| **c == code).copied()
}

fn language_of_country([a, b]: [char; 2]) -> Option<&'static str> {
    Some(match [a, b] {
        ['g', 'b'] | ['u', 's'] | ['a', 'u'] | ['n', 'z'] | ['i', 'e'] => "en",
        ['b', 'r'] | ['p', 't'] | ['a', 'o'] | ['m', 'z'] => "pt",
        ['e', 's']
        | ['m', 'x']
        | ['a', 'r']
        | ['c', 'o']
        | ['c', 'l']
        | ['p', 'e']
        | ['v', 'e']
        | ['u', 'y']
        | ['e', 'c']
        | ['b', 'o']
        | ['p', 'y']
        | ['c', 'u']
        | ['g', 't']
        | ['c', 'r'] => "es",
        ['i', 't'] => "it",
        ['f', 'r'] => "fr",
        ['d', 'e'] | ['a', 't'] => "de",
        ['j', 'p'] => "ja",
        ['k', 'r'] => "ko",
        ['r', 'u'] => "ru",
        ['c', 'n'] | ['t', 'w'] | ['h', 'k'] => "zh",
        ['n', 'l'] => "nl",
        ['p', 'l'] => "pl",
        ['t', 'r'] => "tr",
        ['s', 'e'] => "sv",
        ['n', 'o'] => "no",
        ['d', 'k'] => "da",
        ['f', 'i'] => "fi",
        ['g', 'r'] => "el",
        ['c', 'z'] => "cs",
        ['s', 'k'] => "sk",
        ['h', 'u'] => "hu",
        ['r', 'o'] => "ro",
        ['b', 'g'] => "bg",
        ['r', 's'] => "sr",
        ['h', 'r'] => "hr",
        ['s', 'i'] => "sl",
        ['b', 'a'] => "bs",
        ['m', 'k'] => "mk",
        ['a', 'l'] => "sq",
        ['l', 't'] => "lt",
        ['l', 'v'] => "lv",
        ['e', 'e'] => "et",
        ['i', 's'] => "is",
        ['u', 'a'] => "uk",
        ['b', 'y'] => "be",
        ['g', 'e'] => "ka",
        ['a', 'm'] => "hy",
        ['k', 'z'] => "kk",
        ['a', 'z'] => "az",
        ['u', 'z'] => "uz",
        ['i', 'l'] => "he",
        ['s', 'a']
        | ['a', 'e']
        | ['e', 'g']
        | ['m', 'a']
        | ['d', 'z']
        | ['t', 'n']
        | ['i', 'q']
        | ['j', 'o']
        | ['l', 'b']
        | ['s', 'y']
        | ['k', 'w']
        | ['q', 'a'] => "ar",
        ['i', 'r'] => "fa",
        ['p', 'k'] => "ur",
        ['b', 'd'] => "bn",
        ['n', 'p'] => "ne",
        ['l', 'k'] => "si",
        ['t', 'h'] => "th",
        ['v', 'n'] => "vi",
        ['i', 'd'] => "id",
        ['m', 'y'] => "ms",
        ['p', 'h'] => "fil",
        ['k', 'h'] => "km",
        ['m', 'm'] => "my",
        ['m', 'n'] => "mn",
        _ => return None,
    })
}

/// A language named at the start of `t`, and how many tokens name it: the `PT-BR` pair, a
/// language name or a release-name tag (see [`word_language`]).
fn language_word(t: &[Token<'_>]) -> Option<(&'static str, usize)> {
    let [a, b, _] = lookahead(t);
    if [a, b] == ["pt", "br"] {
        return Some(("pt", 2));
    }
    let token = t.first()?;
    Some((word_language(a, token.after_title)?, 1))
}

/// Language names, in English and in the language itself, and release-name tags. Two-letter
/// codes alone ("It", "De") are too ambiguous to trust and are ignored; so are three-letter
/// codes that are also common words in titles ("por", "fra", "chi", "vie", "rum").
/// Three-letter tags that are names or words too (`Ben`, `Mar`, `Tam`, `Fin`) count only among
/// a release name's tags, after its year (`after_title`).
fn word_language(word: &str, after_title: bool) -> Option<&'static str> {
    let code = match word {
        "english" | "eng" | "inglés" | "ingles" | "inglês" | "anglais" | "englisch" => "en",
        "portuguese" | "português" | "portugues" | "dublado" | "dublada" | "ptbr" | "brazilian"
        | "brasileiro" => "pt",
        "spanish" | "español" | "espanol" | "castellano" | "castilian" | "latino" | "latam"
        | "spa" | "esp" | "espanhol" | "espagnol" => "es",
        "french" | "français" | "francais" | "francés" | "francês" | "truefrench" | "vff"
        | "vfq" | "fre" => "fr",
        "german" | "deutsch" | "ger" | "deu" | "alemán" | "aleman" | "alemão" | "allemand" => {
            "de"
        }
        "italian" | "italiano" | "ita" | "italien" => "it",
        "japanese" | "jpn" | "japonês" | "japones" | "日本語" => "ja",
        "korean" | "kor" | "한국어" => "ko",
        "russian" | "rus" | "русский" | "ruso" | "russe" => "ru",
        "chinese" | "mandarin" | "cantonese" | "chs" | "cht" | "zho" | "中文" | "国语" | "粤语" => {
            "zh"
        }
        "hindi" => "hi",
        "dutch" | "nederlands" | "nld" | "flemish" => "nl",
        "polish" | "polski" | "lektor" => "pl",
        "turkish" | "türkçe" | "turkce" => "tr",
        "swedish" | "svenska" | "swe" => "sv",
        "norwegian" | "norsk" => "no",
        "danish" | "dansk" => "da",
        "finnish" | "suomi" => "fi",
        "greek" | "ελληνικά" => "el",
        "czech" | "cesky" | "česky" | "cze" | "ces" => "cs",
        "slovak" | "slovakian" | "slk" => "sk",
        "slovenian" | "slovene" | "slv" => "sl",
        "hungarian" | "magyar" => "hu",
        "romanian" | "romana" | "română" => "ro",
        "bulgarian" | "български" => "bg",
        "serbian" | "srpski" | "srp" => "sr",
        "croatian" | "hrvatski" | "hrv" => "hr",
        "bosnian" => "bs",
        "macedonian" => "mk",
        "albanian" => "sq",
        "lithuanian" => "lt",
        "latvian" => "lv",
        "estonian" => "et",
        "icelandic" => "is",
        "ukrainian" | "ukr" | "українська" => "uk",
        "belarusian" => "be",
        "georgian" => "ka",
        "armenian" => "hy",
        "kazakh" => "kk",
        "azerbaijani" => "az",
        "uzbek" => "uz",
        "hebrew" | "heb" | "עברית" => "he",
        "arabic" | "العربية" => "ar",
        "persian" | "farsi" => "fa",
        "urdu" => "ur",
        "tamil" => "ta",
        "telugu" => "te",
        "malayalam" => "ml",
        "kannada" => "kn",
        "bengali" | "bangla" => "bn",
        "marathi" => "mr",
        "punjabi" => "pa",
        "gujarati" => "gu",
        "nepali" => "ne",
        "sinhala" => "si",
        "thai" => "th",
        "vietnamese" => "vi",
        "indonesian" => "id",
        "malay" | "melayu" | "msa" => "ms",
        "filipino" | "tagalog" => "fil",
        "khmer" => "km",
        "burmese" => "my",
        "mongolian" => "mn",
        "catalan" | "català" | "catala" => "ca",
        "basque" | "euskera" => "eu",
        "galician" | "galego" => "gl",
        "welsh" => "cy",
        "swahili" => "sw",
        "afrikaans" => "af",
        _ if after_title => match word {
            "hin" => "hi",
            "tam" => "ta",
            "tel" => "te",
            "mal" => "ml",
            "kan" => "kn",
            "ben" => "bn",
            "mar" => "mr",
            "pun" | "pan" => "pa",
            "guj" => "gu",
            "urd" => "ur",
            "bul" => "bg",
            "pol" => "pl",
            "tur" => "tr",
            "gre" | "ell" => "el",
            "hun" => "hu",
            "fin" => "fi",
            "dan" => "da",
            "nor" => "no",
            "dut" => "nl",
            "jap" => "ja",
            "ara" => "ar",
            "tha" => "th",
            "ind" => "id",
            "fil" => "fil",
            "est" => "et",
            "lit" => "lt",
            "cat" => "ca",
            "lav" => "lv",
            "per" | "fas" => "fa",
            _ => return None,
        },
        _ => return None,
    };
    Some(code)
}

fn multi_audio(tokens: &[Token<'_>]) -> bool {
    const SUBTITLE_WORDS: &[&str] = &["sub", "subs", "subbed", "subtitle", "subtitles"];
    tokens.iter().enumerate().any(|(i, token)| {
        let next = tokens.get(i + 1).map_or("", |t| t.lower.as_str());
        let advertises = match token.lower.as_str() {
            "multi" | "multiaudio" | "dualaudio" => true,
            // "Dual" alone is also a word in titles; trust it with "audio" or in upper case.
            "dual" => next == "audio" || token.raw == "DUAL",
            _ => false,
        };
        advertises && !SUBTITLE_WORDS.contains(&next)
    })
}

fn provider(text: &str) -> Option<String> {
    ["⚙", "🔍", "🔎"]
        .into_iter()
        .find_map(|marker| label_after(text, marker))
}

#[cfg(test)]
mod tests {
    use super::*;

    const GIB: f64 = 1024.0 * 1024.0 * 1024.0;

    fn parse3(name: &str, title: &str, description: &str) -> Traits {
        parse(&SourceText {
            name,
            title,
            description,
        })
    }

    fn parse_title(title: &str) -> Traits {
        parse3("", title, "")
    }

    #[test]
    fn torrentio_full_stream() {
        let t = parse3(
            "Torrentio\n4k DV | HDR",
            "Movie.Name.2023.2160p.WEB-DL.DV.HDR.H265-GRP\n👤 154 💾 14.2 GB ⚙️ ThePirateBay\n\
             Multi Audio / 🇬🇧 / 🇮🇹",
            "",
        );
        assert_eq!(t.resolution, Some(Resolution::P2160));
        assert_eq!(t.hdr, [Hdr::DolbyVision, Hdr::Hdr10]);
        assert_eq!(t.codec, Some(Codec::Hevc));
        assert_eq!(t.quality, Some(ReleaseQuality::WebDl));
        assert_eq!(t.size_bytes, Some((14.2 * GIB) as u64));
        assert_eq!(t.seeders, Some(154));
        assert_eq!(t.cached, None);
        assert_eq!(t.languages, ["en", "it"]);
        assert!(t.multi_audio);
        assert_eq!(t.provider.as_deref(), Some("ThePirateBay"));
        assert_eq!(t.release_group.as_deref(), Some("GRP"));
    }

    #[test]
    fn torrentio_debrid_cached_and_uncached() {
        let cached = parse3("[RD+] Torrentio\n1080p", "Show.S01E01.1080p.WEB.h264", "");
        assert_eq!(cached.cached, Some(true));
        let uncached = parse3("[RD download] Torrentio\n1080p", "Show.S01E01", "");
        assert_eq!(uncached.cached, Some(false));
        for name in [
            "[AD+] Torrentio",
            "[TB+] Torrentio",
            "[RD⚡] Comet 4K",
            "[PM+]",
        ] {
            assert_eq!(parse3(name, "", "").cached, Some(true), "{name}");
        }
        assert_eq!(parse3("[RD⬇️] Comet 4K", "", "").cached, Some(false));
    }

    #[test]
    fn hdr10_plus_bracket_is_not_a_cache_marker() {
        let t = parse_title("Movie [HDR10+] [2160p]");
        assert_eq!(t.cached, None);
        assert_eq!(t.hdr, [Hdr::Hdr10Plus]);
        assert_eq!(t.resolution, Some(Resolution::P2160));
    }

    #[test]
    fn comet_description() {
        let t = parse3(
            "[RD⚡] Comet 1080p",
            "",
            "📄 Movie.2021.1080p.BluRay.x264.DTS-HD.MA.5.1-FGT\n📹 x264 • DTS-HD MA\n\
             💾 9.85 GB 👤 32 🔎 TorrentGalaxy\n🇬🇧",
        );
        assert_eq!(t.cached, Some(true));
        assert_eq!(t.resolution, Some(Resolution::P1080));
        assert_eq!(t.quality, Some(ReleaseQuality::BluRay));
        assert_eq!(t.codec, Some(Codec::Avc));
        assert_eq!(t.audio, [Audio::DtsHdMa]);
        assert_eq!(t.size_bytes, Some((9.85 * GIB) as u64));
        assert_eq!(t.seeders, Some(32));
        assert_eq!(t.provider.as_deref(), Some("TorrentGalaxy"));
        assert_eq!(t.languages, ["en"]);
    }

    #[test]
    fn mediafusion_description() {
        let t = parse3(
            "MediaFusion | RD ⚡️ 4K",
            "",
            "Movie 2024 2160p UHD BluRay REMUX HDR10+ DV TrueHD Atmos 7.1\n💾 58.3 GB 👤 87\n\
             🌐 English, Spanish\n🔗 ThePirateBay",
        );
        assert_eq!(t.cached, Some(true));
        assert_eq!(t.resolution, Some(Resolution::P2160));
        assert_eq!(t.quality, Some(ReleaseQuality::Remux));
        assert_eq!(t.hdr, [Hdr::DolbyVision, Hdr::Hdr10Plus]);
        assert_eq!(t.audio, [Audio::Atmos, Audio::TrueHd]);
        assert_eq!(t.languages, ["en", "es"]);
        assert_eq!(t.seeders, Some(87));
    }

    #[test]
    fn mediafusion_uncached_hourglass() {
        assert_eq!(
            parse3("MediaFusion RD ⏳ 1080p", "", "").cached,
            Some(false)
        );
    }

    #[test]
    fn instant_marker() {
        assert_eq!(parse3("Jackettio Instant 720p", "", "").cached, Some(true));
    }

    #[test]
    fn spelled_out_cache_states() {
        for (name, title, description, expected) in [
            (
                "TorBox\n1080p",
                "",
                "Movie.2020.1080p.WEB\nNot cached",
                Some(false),
            ),
            (
                "TorBox\n1080p",
                "",
                "Movie.2020.1080p.WEB\nCached: No",
                Some(false),
            ),
            ("Addon", "Cached: false", "", Some(false)),
            (
                "TorBox\n1080p",
                "",
                "Movie.2020.1080p.WEB\nCached: Yes",
                Some(true),
            ),
            ("Addon 1080p", "Movie.2020.1080p Uncached", "", Some(false)),
            ("Addon", "", "Movie.2020 | TB instant", Some(true)),
            ("Addon", "", "Movie.2020 | RD download", Some(false)),
            ("Not cached", "", "", Some(false)),
            ("RD+ Torrentio", "", "", Some(true)),
            ("TorBox Cached\n4K", "", "", Some(true)),
        ] {
            assert_eq!(
                parse3(name, title, description).cached,
                expected,
                "{name} / {title} / {description}"
            );
        }
    }

    #[test]
    fn release_names_and_descriptions_do_not_fake_a_cache_state() {
        for (name, title, description) in [
            (
                "Torrentio\n1080p",
                "Instant.Family.2018.1080p.BluRay.x264",
                "",
            ),
            ("Torrentio\n1080p", "Cached.2005.1080p.BluRay", ""),
            ("Addon", "", "Movie.2020.1080p\n⏳ 2h 10m"),
            ("Addon", "Movie ⚡ Lightning.2019.720p", ""),
            ("Addon", "", "💾 1.2 GB ⚡ fast mirror"),
        ] {
            assert_eq!(
                parse3(name, title, description).cached,
                None,
                "{name} / {title} / {description}"
            );
        }
    }

    #[test]
    fn emoji_next_to_a_debrid_service_count_anywhere() {
        assert_eq!(parse3("MediaFusion", "", "RD ⚡️ 4K").cached, Some(true));
        assert_eq!(parse3("MediaFusion", "", "⏳ TB 4K").cached, Some(false));
    }

    #[test]
    fn ts_false_positives() {
        for title in [
            "Ghosts.S02E05.1080p.WEB.h264",
            "Lights.Out.2016.VTS.Remux",
            "Movie.2020.1080p.mp4.ts",
            "Its Always Sunny 720p",
            "Shorts.2009.DVDRip",
        ] {
            let q = parse_title(title).quality;
            assert!(q.is_none_or(|q| !q.is_low()), "{title}: {q:?}");
        }
    }

    #[test]
    fn low_quality_markers() {
        let cases = [
            ("Movie.2024.CAM.x264", ReleaseQuality::Cam),
            ("Movie 2024 HDCAM 720p", ReleaseQuality::Cam),
            ("Movie.2024.HDTS.1080p", ReleaseQuality::Telesync),
            ("Movie.2024.TS.XviD", ReleaseQuality::Telesync),
            ("Movie 2024 TELESYNC", ReleaseQuality::Telesync),
            ("Movie.2024.DVDSCR.x264", ReleaseQuality::Screener),
            // A CAM mislabeled next to "WEB" still counts as a CAM.
            ("Movie.2024.1080p.WEB.HDCAM", ReleaseQuality::Cam),
        ];
        for (title, expected) in cases {
            assert_eq!(parse_title(title).quality, Some(expected), "{title}");
        }
    }

    #[test]
    fn release_qualities() {
        let cases = [
            ("Movie.2019.1080p.BluRay.REMUX.AVC", ReleaseQuality::Remux),
            ("Movie 2019 Blu-ray 720p", ReleaseQuality::BluRay),
            ("Movie.2019.WEB-DL.1080p", ReleaseQuality::WebDl),
            ("Movie.2019.WEBRip.x265", ReleaseQuality::WebRip),
            ("Show.S01E01.HDTV.x264", ReleaseQuality::Hdtv),
            ("Movie.1999.DVDRip.XviD", ReleaseQuality::Dvd),
        ];
        for (title, expected) in cases {
            assert_eq!(parse_title(title).quality, Some(expected), "{title}");
        }
    }

    #[test]
    fn hdr_variants() {
        assert_eq!(parse_title("Movie.2160p.HDR10+.HEVC").hdr, [Hdr::Hdr10Plus]);
        assert_eq!(parse_title("Movie.2160p.HDR10.HEVC").hdr, [Hdr::Hdr10]);
        assert_eq!(parse_title("Movie 2160p HDR").hdr, [Hdr::Hdr10]);
        assert_eq!(parse_title("Movie.HDR10Plus").hdr, [Hdr::Hdr10Plus]);
        assert_eq!(
            parse_title("Movie DV HDR 2160p").hdr,
            [Hdr::DolbyVision, Hdr::Hdr10]
        );
        assert_eq!(
            parse_title("Movie Dolby Vision 2160p").hdr,
            [Hdr::DolbyVision]
        );
        assert_eq!(parse_title("Movie.DoVi.2160p").hdr, [Hdr::DolbyVision]);
        assert_eq!(parse_title("Match HLG 2160p").hdr, [Hdr::Hlg]);
        assert!(parse_title("Movie.DVDRip.SDR").hdr.is_empty());
    }

    #[test]
    fn resolution_variants() {
        let cases = [
            ("Movie 4K", Some(Resolution::P2160)),
            ("Movie UHD", Some(Resolution::P2160)),
            ("Movie FHD", Some(Resolution::P1080)),
            ("Movie.720p", Some(Resolution::P720)),
            ("Movie.480p", Some(Resolution::Sd)),
            ("Movie 1920x1080", Some(Resolution::P1080)),
            ("Movie HD", None),
            ("Movie 1080", None),
            // Several explicit values: the highest wins.
            ("Show.S01.720p.1080p.2160p", Some(Resolution::P2160)),
            ("Pack [720p-1080p]", Some(Resolution::P1080)),
            // Explicit pixel heights beat words.
            ("Movie.4K.Remaster.1080p", Some(Resolution::P1080)),
        ];
        for (title, expected) in cases {
            assert_eq!(parse_title(title).resolution, expected, "{title}");
        }
    }

    #[test]
    fn codecs() {
        assert_eq!(parse_title("Movie.H.265").codec, Some(Codec::Hevc));
        assert_eq!(parse_title("Movie.HEVC.10bit").codec, Some(Codec::Hevc));
        assert_eq!(parse_title("Movie.H.264").codec, Some(Codec::Avc));
        assert_eq!(parse_title("Movie AVC").codec, Some(Codec::Avc));
        assert_eq!(parse_title("Movie.AV1.Opus").codec, Some(Codec::Av1));
        assert_eq!(parse_title("Movie.XviD").codec, None);
    }

    #[test]
    fn audio_formats() {
        let cases: [(&str, &[Audio]); 8] = [
            ("Movie.DDP5.1.Atmos", &[Audio::Atmos, Audio::Ddp]),
            ("Movie.DD+5.1", &[Audio::Ddp]),
            ("Movie.E-AC-3", &[Audio::Ddp]),
            ("Movie.AC3.5.1", &[Audio::Dd]),
            ("Movie.DD5.1", &[Audio::Dd]),
            ("Movie.DTS-X.7.1", &[Audio::DtsX]),
            ("Movie.DTS.AAC2.0", &[Audio::Dts, Audio::Aac]),
            ("Movie.FLAC.Opus", &[Audio::Opus, Audio::Flac]),
        ];
        for (title, expected) in cases {
            assert_eq!(parse_title(title).audio, expected, "{title}");
        }
    }

    #[test]
    fn sizes_with_commas_and_units() {
        assert_eq!(
            parse_title("💾 1,5 GB").size_bytes,
            Some((1.5 * GIB) as u64)
        );
        assert_eq!(parse_title("Size: 700 MB").size_bytes, Some(700 << 20));
        assert_eq!(
            parse_title("💾 3.1 GiB").size_bytes,
            Some((3.1 * GIB) as u64)
        );
        assert_eq!(parse_title("Movie.2023.1080p").size_bytes, None);
    }

    #[test]
    fn seeders_variants() {
        assert_eq!(parse_title("👤 154 💾 1 GB").seeders, Some(154));
        assert_eq!(parse_title("Seeders: 12").seeders, Some(12));
        assert_eq!(parse_title("Movie.2023").seeders, None);
    }

    #[test]
    fn language_words_and_flags() {
        let cases: [(&str, &[&str]); 7] = [
            ("Filme.2023.1080p.Dublado", &["pt"]),
            ("Movie PT-BR 720p", &["pt"]),
            ("Película Latino 1080p", &["es"]),
            ("Movie / 🇧🇷 / 🇵🇹", &["pt"]),
            ("🇲🇽 🇪🇸 🇺🇸", &["es", "en"]),
            ("🇫🇷 🇩🇪 🇯🇵 🇰🇷 🇷🇺 🇨🇳", &["fr", "de", "ja", "ko", "ru", "zh"]),
            ("It.2017.1080p", &[]),
        ];
        for (title, expected) in cases {
            assert_eq!(parse_title(title).languages, expected, "{title}");
        }
    }

    #[test]
    fn subtitle_tags_are_not_audio() {
        let cases: [(&str, &[&str], &[&str]); 11] = [
            ("Hokum.2026.4K.HDR.BDRemux.Eng.Sub.Ita", &["en"], &["it"]),
            ("Movie.2023.1080p.WEB.SubIta.SubEng", &[], &["it", "en"]),
            ("Film 2023 VOSTFR 1080p", &[], &["fr"]),
            ("Filme.2023.1080p.Legendado", &[], &["pt"]),
            ("Película 2023 1080p VOSE", &[], &["es"]),
            ("Movie.2023.1080p.ITA.ENG.Subs", &["it"], &["en"]),
            ("Movie 2023 1080p English Subtitles", &[], &["en"]),
            (
                "Movie 2023\nSubs: English, Spanish, PT-BR",
                &[],
                &["en", "es", "pt"],
            ),
            ("Film.2023.1080p.Lektor.PL.Napisy.PL", &["pl"], &[]),
            ("Film 2023 1080p Napisy Polski", &[], &["pl"]),
            (
                "Movie.2023.1080p.Dual.Audio.Hindi.English.ESub",
                &["hi", "en"],
                &[],
            ),
        ];
        for (title, audio, subtitles) in cases {
            let t = parse_title(title);
            assert_eq!(t.languages, audio, "{title}: audio");
            assert_eq!(t.subtitle_languages, subtitles, "{title}: subtitles");
        }
    }

    #[test]
    fn languages_of_every_region() {
        let cases: [(&str, &[&str]); 9] = [
            (
                "🇧🇬 🇷🇸 🇭🇷 🇸🇰 🇸🇮 🇱🇹 🇱🇻 🇪🇪",
                &["bg", "sr", "hr", "sk", "sl", "lt", "lv", "et"],
            ),
            ("🇮🇷 🇲🇾 🇵🇭 🇧🇩", &["fa", "ms", "fil", "bn"]),
            (
                "🇺🇦 🇹🇭 🇻🇳 🇮🇩 🇹🇷 🇬🇷 🇮🇱 🇨🇿",
                &["uk", "th", "vi", "id", "tr", "el", "he", "cs"],
            ),
            (
                "🇭🇺 🇷🇴 🇵🇱 🇳🇱 🇸🇪 🇳🇴 🇩🇰 🇫🇮",
                &["hu", "ro", "pl", "nl", "sv", "no", "da", "fi"],
            ),
            (
                "🌐 Bulgarian, Serbian, Croatian, Malay, Catalan",
                &["bg", "sr", "hr", "ms", "ca"],
            ),
            (
                "Movie.2023.1080p.WEB.Hin.Tam.Tel.Mal.Kan",
                &["hi", "ta", "te", "ml", "kn"],
            ),
            ("Movie.2023.720p.Ben.Mar.Pun", &["bn", "mr", "pa"]),
            ("Movie.2023.1080p.Rus.Ukr.Eng", &["ru", "uk", "en"]),
            ("Película.2023.1080p.Castellano.Cat", &["es", "ca"]),
        ];
        for (title, expected) in cases {
            assert_eq!(parse_title(title).languages, expected, "{title}");
        }
    }

    #[test]
    fn the_flag_of_india_means_hindi_only_without_another_indian_language() {
        assert_eq!(parse_title("Movie.2023.1080p\n🇮🇳").languages, ["hi"]);
        assert_eq!(
            parse_title("Movie.2023.1080p.Tamil\n🇬🇧 / 🇮🇳").languages,
            ["en", "ta"]
        );
        assert_eq!(
            parse_title("Movie.2023.1080p.Tel.Hin\n🇮🇳").languages,
            ["te", "hi"]
        );
    }

    #[test]
    fn title_words_are_not_tags() {
        for (title, languages, quality) in [
            (
                "The.Italian.Job.2003.1080p.BluRay.x264",
                &[][..],
                Some(ReleaseQuality::BluRay),
            ),
            (
                "The.Danish.Girl.2015.720p.HDTV",
                &[],
                Some(ReleaseQuality::Hdtv),
            ),
            (
                "My.Big.Fat.Greek.Wedding.2002.DVDRip",
                &[],
                Some(ReleaseQuality::Dvd),
            ),
            (
                "Charlottes.Web.2006.720p.HDTV.x264",
                &[],
                Some(ReleaseQuality::Hdtv),
            ),
            ("Charlotte's Web (2006) 1080p", &[], None),
            (
                "The.French.Dispatch.2021.1080p.WEB-DL.French",
                &["fr"],
                Some(ReleaseQuality::WebDl),
            ),
            (
                "Ben.Hur.1959.1080p.BluRay",
                &[],
                Some(ReleaseQuality::BluRay),
            ),
            ("Mar.Adentro.2004.1080p.Spanish", &["es"], None),
            (
                "1917.2019.1080p.WEB.Hin",
                &["hi"],
                Some(ReleaseQuality::WebDl),
            ),
            (
                "The.Office.US.S01E01.720p.WEB.Eng",
                &["en"],
                Some(ReleaseQuality::WebDl),
            ),
        ] {
            let t = parse_title(title);
            assert_eq!(t.languages, languages, "{title}");
            assert_eq!(t.quality, quality, "{title}");
        }
        // Without a year the whole line counts, as in a short label.
        assert_eq!(parse_title("Tam 1080p").languages, Vec::<String>::new());
    }

    #[test]
    fn more_language_names_and_subtitle_tags() {
        let cases: [(&str, &[&str]); 5] = [
            ("Movie 2023 LATAM 1080p", &["es"]),
            (
                "🌐 Dutch, Polish, Turkish, Swedish, Norwegian",
                &["nl", "pl", "tr", "sv", "no"],
            ),
            (
                "Danish Finnish Greek Czech Hungarian Romanian",
                &["da", "fi", "el", "cs", "hu", "ro"],
            ),
            (
                "Ukrainian Hebrew Arabic Thai Vietnamese Indonesian",
                &["uk", "he", "ar", "th", "vi", "id"],
            ),
            (
                "Tamil Telugu Bengali Farsi CHS",
                &["ta", "te", "bn", "fa", "zh"],
            ),
        ];
        for (title, expected) in cases {
            assert_eq!(parse_title(title).languages, expected, "{title}");
        }
    }

    #[test]
    fn words_that_only_look_like_language_codes_are_ignored() {
        for title in [
            "Por.Un.Punado.De.Dolares.1964.1080p",
            "Fra.Le.Righe.2022.1080p",
            "Chi.Raq.2015.720p",
            "La.Vie.En.Rose.2007.1080p",
            "Rum.Diary.2011.BluRay",
        ] {
            assert!(parse_title(title).languages.is_empty(), "{title}");
        }
    }

    #[test]
    fn multi_audio_detection() {
        assert!(parse_title("Movie Dual Audio 1080p").multi_audio);
        assert!(parse_title("Movie.2020.MULTi.1080p").multi_audio);
        assert!(parse_title("Filme.2020.DUAL.1080p").multi_audio);
        assert!(!parse_title("Movie.2020.Multi.Subs").multi_audio);
        assert!(!parse_title("Dual (2022) 1080p").multi_audio);
    }

    #[test]
    fn public_domain_and_empty_fields() {
        let t = parse3("Public Domain", "Night of the Living Dead (1968)", "");
        assert_eq!(t, Traits::default());
        assert_eq!(parse3("", "", ""), Traits::default());
    }

    #[test]
    fn watchhub_style() {
        let t = parse3("WatchHub\nNetflix", "Watch on Netflix", "");
        assert_eq!(t.resolution, None);
        assert_eq!(t.provider, None);
        assert_eq!(t.cached, None);
    }

    #[test]
    fn finds_release_groups_in_scene_names_only() {
        for (title, group) in [
            (
                "Show.S01E01.1080p.WEB.h264-ETHEL[eztv.re].mkv",
                Some("ETHEL"),
            ),
            ("Movie.2023.1080p.BluRay.x264-SPARKS.mp4", Some("SPARKS")),
            ("Movie.2023.1080p.WEB-DL", None),
            ("Movie.2023.1080p.WEB.x265-", None),
            ("Movie 2023 1080p WEB-DL x264-GRP", None),
            ("Movie.2023.1080p-12345", None),
            (
                "Movie.2023.1080p.BluRay.x264-THISNAMEISFARTOOLONGTOBEAGROUP",
                None,
            ),
            ("Movie.2023.1080p.HDR-DV", None),
            ("", None),
        ] {
            assert_eq!(
                parse_title(title).release_group.as_deref(),
                group,
                "{title}"
            );
        }
    }

    #[test]
    fn very_long_input_is_cut_on_a_char_boundary() {
        // The flag straddles the cut point; the 2160p marker lies past it and must be ignored.
        let mut title = "a".repeat(MAX_INSPECTED_BYTES - 2);
        title.push_str("🇬🇧 2160p");
        title.push_str(&"é".repeat(100_000));
        let t = parse_title(&title);
        assert_eq!(t.resolution, None);
        assert!(t.languages.is_empty());

        let huge = "1080p 👤 5 ".repeat(50_000);
        let t = parse_title(&huge);
        assert_eq!(t.resolution, Some(Resolution::P1080));
        assert_eq!(t.seeders, Some(5));
    }

    #[test]
    fn garbage_never_panics() {
        let samples = [
            "\0\u{1}\u{7f}",
            "[[[[]]]]][[RD",
            "[RD",
            "]",
            "👤👤👤💾💾⚙️",
            "⚙️",
            "💾 .5 GB , , 1, GB 1. MB",
            "🇬",
            "🇬🇧🇬",
            "\u{fe0f}\u{200d}\u{fe0f}",
            "9999999999999999999999999999999999999 GB 👤 99999999999999999999",
            "ﾟ･✿ヾ╲(｡◕‿◕｡)╱✿･ﾟ",
            "𝔘𝔫𝔦𝔠𝔬𝔡𝔢 Ｆｕｌｌｗｉｄｔｈ ２１６０ｐ",
            "x".repeat(10_000).as_str(),
            "+++ ++ +",
            "\u{202e}DV\u{202d} HDR\u{2028}10",
        ]
        .map(str::to_owned);
        for a in &samples {
            for b in &samples {
                let _ = parse3(a, b, a);
            }
        }
        // Every byte offset of an emoji-heavy string, re-sliced on char boundaries.
        let mixed = "[RD⚡] 🇧🇷 4K 💾 1,5 GB 👤 9 ⚙️ Prov";
        for (i, _) in mixed.char_indices() {
            let _ = parse_title(&mixed[i..]);
            let _ = parse_title(&mixed[..i]);
        }
    }
}
