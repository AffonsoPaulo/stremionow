// SPDX-License-Identifier: GPL-3.0-or-later
//! User preferences kept in the local database.

use crate::core::StremioCore;
use crate::records::CoreError;

const TORRENT_WIFI_ONLY: &str = "torrent_wifi_only";
const AUDIO_LANGUAGE: &str = "audio_language";
const SUBTITLE_LANGUAGE: &str = "subtitle_language";
const MAX_RESOLUTION: &str = "max_resolution";
const ONBOARDED: &str = "onboarded";
const TORRENT_CACHE_MB: &str = "torrent_cache_mb";
const ANISKIP: &str = "aniskip";
const HDR: &str = "hdr";
const MATCH_REFRESH_RATE: &str = "match_refresh_rate";
/// Titles the user turned HDR on for ("Only for this title"), one setting each: the prefix and the meta id.
const HDR_TITLE_PREFIX: &str = "hdr_title:";
/// Longest meta id remembered; addon ids are short (`tt0063350`, `kitsu:1`).
const MAX_META_ID_LEN: usize = 200;
const SERVE_TO_DEVICES: &str = "serve_to_devices";
const SERVE_NAME: &str = "serve_name";
const STREAM_MEMORY: &str = "stream_memory";
const STREAM_MEMORY_AHEAD_MIB: &str = "stream_memory_ahead_mib";
const STREAM_MEMORY_BEHIND_MIB: &str = "stream_memory_behind_mib";
const SUBTITLE_SIZE_PERCENT: &str = "subtitle_size_percent";
const SUBTITLE_POSITION: &str = "subtitle_position";
const SUBTITLE_TEXT_COLOR: &str = "subtitle_text_color";
const SUBTITLE_BACKGROUND_COLOR: &str = "subtitle_background_color";
const SUBTITLE_OUTLINE_COLOR: &str = "subtitle_outline_color";
const SUBTITLE_FILE_STYLING: &str = "subtitle_file_styling";

/// Subtitle sizes, in percent of the normal size, and the step between them: the same as the
/// player's size buttons, which change the size for the session only.
pub const SUBTITLE_SIZE_RANGE: (u32, u32) = (50, 200);
const SUBTITLE_SIZE_STEP: u32 = 10;
/// Subtitle places, in percent of the picture's height from its top (100 is the bottom, where
/// subtitles normally are), and the step between them: the same as the player's buttons.
pub const SUBTITLE_POSITION_RANGE: (u32, u32) = (30, 100);
const SUBTITLE_POSITION_STEP: u32 = 5;
/// Stremio's subtitle colours, as ARGB: white text, no background and a black outline.
const DEFAULT_SUBTITLE_TEXT_COLOR: u32 = 0xFFFF_FFFF;
const DEFAULT_SUBTITLE_BACKGROUND_COLOR: u32 = 0x0000_0000;
const DEFAULT_SUBTITLE_OUTLINE_COLOR: u32 = 0xFF00_0000;

/// Custom stream memory read ahead, in MiB: the player's `StreamMemorySizes` takes the same
/// range. Below it, a few seconds of a 4K film; above it, more memory than any level uses.
pub const STREAM_AHEAD_MIB_RANGE: (u32, u32) = (16, 1024);
/// Custom stream memory kept behind, in MiB; the same range as the player's.
pub const STREAM_BEHIND_MIB_RANGE: (u32, u32) = (8, 1024);
/// Custom sizes before the user changes them: the balanced level's.
const DEFAULT_STREAM_MIB: u32 = 128;

/// Longest name for this device as a server, in characters.
const MAX_SERVE_NAME_CHARS: usize = 40;
/// Cache sizes the user can pick, in MiB. The first one is the smallest allowed.
pub const TORRENT_CACHE_CHOICES_MB: [u32; 5] = [1024, 2048, 5120, 10240, 20480];
const DEFAULT_TORRENT_CACHE_MB: u32 = 2048;

/// Whether the app may change a display setting for a video, such as turning on HDR.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum DisplaySwitch {
    /// Ask each time, unless the user chose it for the title.
    #[default]
    Ask,
    /// Switch without asking.
    Always,
    /// Never switch, and never ask.
    Never,
}

impl DisplaySwitch {
    fn as_str(self) -> &'static str {
        match self {
            Self::Ask => "ask",
            Self::Always => "always",
            Self::Never => "never",
        }
    }

    fn parse(value: &str) -> Self {
        match value {
            "always" => Self::Always,
            "never" => Self::Never,
            _ => Self::Ask,
        }
    }
}

/// How much of the stream the player keeps in memory, ahead of and behind the playback
/// position ("Memory for the video" in Settings). The sizes of each level belong to the
/// player (`StreamMemory`); this is only the user's choice.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum StreamMemoryChoice {
    /// The player picks a level from the device's memory.
    #[default]
    Automatic,
    Light,
    Balanced,
    Smooth,
    /// The exact sizes in `stream_memory_ahead_mib` and `stream_memory_behind_mib`.
    Custom,
}

impl StreamMemoryChoice {
    fn as_str(self) -> &'static str {
        match self {
            Self::Automatic => "automatic",
            Self::Light => "light",
            Self::Balanced => "balanced",
            Self::Smooth => "smooth",
            Self::Custom => "custom",
        }
    }

    fn parse(value: &str) -> Self {
        match value {
            "light" => Self::Light,
            "balanced" => Self::Balanced,
            "smooth" => Self::Smooth,
            "custom" => Self::Custom,
            _ => Self::Automatic,
        }
    }
}

/// Preferences the user can change in Settings.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppSettings {
    /// Start torrents only when the device is not on mobile data.
    pub torrent_wifi_only: bool,
    /// Disk space torrents may keep for reuse, in MiB.
    pub torrent_cache_mb: u32,
    /// Preferred audio language as an ISO 639-1 code; empty follows the device.
    pub audio_language: String,
    /// Subtitle language as an ISO 639-1 code; empty turns subtitles off by default.
    pub subtitle_language: String,
    /// Highest resolution to prefer, in lines (2160, 1080, 720); 0 means no limit.
    pub max_resolution: u32,
    /// The first-run setup has been completed.
    pub onboarded: bool,
    /// Ask AniSkip for the openings and endings of anime episodes. This sends the anime's id
    /// and the episode number to AniSkip (and Kitsu or AniList ids to an id mapping service).
    pub aniskip: bool,
    /// Turning on HDR for HDR videos, on displays that support it (desktop).
    pub hdr: DisplaySwitch,
    /// Matching the display's refresh rate to the video's frame rate (desktop; off by default).
    pub match_refresh_rate: bool,
    /// Serve torrents to other devices on the home network while the app runs (ADR 0008).
    pub serve_to_devices: bool,
    /// The name other devices see for this one when it serves, announced on the network;
    /// empty until the user turns serving on, when the app sets a neutral default.
    pub serve_name: String,
    /// How much of the stream the player keeps in memory; applies from the next video.
    pub stream_memory: StreamMemoryChoice,
    /// Read ahead with [`StreamMemoryChoice::Custom`], in MiB, within [`STREAM_AHEAD_MIB_RANGE`].
    pub stream_memory_ahead_mib: u32,
    /// Kept behind with [`StreamMemoryChoice::Custom`], in MiB, within [`STREAM_BEHIND_MIB_RANGE`].
    pub stream_memory_behind_mib: u32,
    /// Subtitle size, in percent of the normal size, within [`SUBTITLE_SIZE_RANGE`] on 10 %
    /// steps. The player's buttons change it for the session only.
    pub subtitle_size_percent: u32,
    /// Where subtitles sit, in percent of the picture's height from its top, within
    /// [`SUBTITLE_POSITION_RANGE`] on 5 % steps: 100 is the bottom, lower raises them.
    pub subtitle_position: u32,
    /// Subtitle text colour, as ARGB (`0xAARRGGBB`).
    pub subtitle_text_color: u32,
    /// The box behind subtitle text, as ARGB; fully transparent draws none.
    pub subtitle_background_color: u32,
    /// The outline around subtitle text, as ARGB; fully transparent draws none.
    pub subtitle_outline_color: u32,
    /// Styled subtitles (ASS) keep the file's own fonts, colours and places; off, the colours
    /// and place above apply to them too, as with plain ones.
    pub subtitle_file_styling: bool,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            torrent_wifi_only: false,
            torrent_cache_mb: DEFAULT_TORRENT_CACHE_MB,
            audio_language: String::new(),
            subtitle_language: String::new(),
            max_resolution: 0,
            onboarded: false,
            aniskip: true,
            hdr: DisplaySwitch::Ask,
            match_refresh_rate: false,
            serve_to_devices: false,
            serve_name: String::new(),
            stream_memory: StreamMemoryChoice::Automatic,
            stream_memory_ahead_mib: DEFAULT_STREAM_MIB,
            stream_memory_behind_mib: DEFAULT_STREAM_MIB,
            subtitle_size_percent: 100,
            subtitle_position: 100,
            subtitle_text_color: DEFAULT_SUBTITLE_TEXT_COLOR,
            subtitle_background_color: DEFAULT_SUBTITLE_BACKGROUND_COLOR,
            subtitle_outline_color: DEFAULT_SUBTITLE_OUTLINE_COLOR,
            subtitle_file_styling: false,
        }
    }
}

#[uniffi::export]
impl StremioCore {
    /// The user's preferences, with defaults for anything never set.
    pub fn settings(&self) -> Result<AppSettings, CoreError> {
        let store = self.store()?;
        let defaults = AppSettings::default();
        Ok(AppSettings {
            torrent_wifi_only: store
                .setting(TORRENT_WIFI_ONLY)?
                .map_or(defaults.torrent_wifi_only, |value| value == "1"),
            torrent_cache_mb: store
                .setting(TORRENT_CACHE_MB)?
                .and_then(|value| value.parse().ok())
                .map_or(defaults.torrent_cache_mb, clamp_cache),
            audio_language: store
                .setting(AUDIO_LANGUAGE)?
                .map_or(defaults.audio_language, |value| language_code(&value)),
            subtitle_language: store
                .setting(SUBTITLE_LANGUAGE)?
                .map_or(defaults.subtitle_language, |value| language_code(&value)),
            max_resolution: store
                .setting(MAX_RESOLUTION)?
                .and_then(|value| value.parse().ok())
                .map_or(defaults.max_resolution, clamp_resolution),
            onboarded: store.setting(ONBOARDED)?.is_some_and(|value| value == "1"),
            aniskip: store
                .setting(ANISKIP)?
                .map_or(defaults.aniskip, |value| value == "1"),
            hdr: store
                .setting(HDR)?
                .map_or(defaults.hdr, |value| DisplaySwitch::parse(&value)),
            match_refresh_rate: store
                .setting(MATCH_REFRESH_RATE)?
                .is_some_and(|value| value == "1"),
            serve_to_devices: store
                .setting(SERVE_TO_DEVICES)?
                .is_some_and(|value| value == "1"),
            serve_name: store
                .setting(SERVE_NAME)?
                .map_or(defaults.serve_name, |value| serve_name(&value)),
            stream_memory: store
                .setting(STREAM_MEMORY)?
                .map_or(defaults.stream_memory, |value| {
                    StreamMemoryChoice::parse(&value)
                }),
            stream_memory_ahead_mib: store
                .setting(STREAM_MEMORY_AHEAD_MIB)?
                .and_then(|value| value.parse().ok())
                .map_or(defaults.stream_memory_ahead_mib, |mib| {
                    clamp_range(mib, STREAM_AHEAD_MIB_RANGE)
                }),
            stream_memory_behind_mib: store
                .setting(STREAM_MEMORY_BEHIND_MIB)?
                .and_then(|value| value.parse().ok())
                .map_or(defaults.stream_memory_behind_mib, |mib| {
                    clamp_range(mib, STREAM_BEHIND_MIB_RANGE)
                }),
            subtitle_size_percent: store
                .setting(SUBTITLE_SIZE_PERCENT)?
                .and_then(|value| value.parse().ok())
                .map_or(defaults.subtitle_size_percent, subtitle_size),
            subtitle_position: store
                .setting(SUBTITLE_POSITION)?
                .and_then(|value| value.parse().ok())
                .map_or(defaults.subtitle_position, subtitle_position),
            subtitle_text_color: store
                .setting(SUBTITLE_TEXT_COLOR)?
                .and_then(|value| value.parse().ok())
                .unwrap_or(defaults.subtitle_text_color),
            subtitle_background_color: store
                .setting(SUBTITLE_BACKGROUND_COLOR)?
                .and_then(|value| value.parse().ok())
                .unwrap_or(defaults.subtitle_background_color),
            subtitle_outline_color: store
                .setting(SUBTITLE_OUTLINE_COLOR)?
                .and_then(|value| value.parse().ok())
                .unwrap_or(defaults.subtitle_outline_color),
            subtitle_file_styling: store
                .setting(SUBTITLE_FILE_STYLING)?
                .map_or(defaults.subtitle_file_styling, |value| value == "1"),
        })
    }

    /// Saves the user's preferences. Out-of-range values are brought back into range.
    pub fn update_settings(&self, settings: AppSettings) -> Result<(), CoreError> {
        let store = self.store()?;
        store.set_setting(
            TORRENT_WIFI_ONLY,
            if settings.torrent_wifi_only { "1" } else { "0" },
        )?;
        store.set_setting(
            TORRENT_CACHE_MB,
            &clamp_cache(settings.torrent_cache_mb).to_string(),
        )?;
        store.set_setting(AUDIO_LANGUAGE, &language_code(&settings.audio_language))?;
        store.set_setting(
            SUBTITLE_LANGUAGE,
            &language_code(&settings.subtitle_language),
        )?;
        store.set_setting(
            MAX_RESOLUTION,
            &clamp_resolution(settings.max_resolution).to_string(),
        )?;
        store.set_setting(ONBOARDED, if settings.onboarded { "1" } else { "0" })?;
        store.set_setting(ANISKIP, if settings.aniskip { "1" } else { "0" })?;
        store.set_setting(HDR, settings.hdr.as_str())?;
        store.set_setting(
            MATCH_REFRESH_RATE,
            if settings.match_refresh_rate {
                "1"
            } else {
                "0"
            },
        )?;
        store.set_setting(
            SERVE_TO_DEVICES,
            if settings.serve_to_devices { "1" } else { "0" },
        )?;
        store.set_setting(SERVE_NAME, &serve_name(&settings.serve_name))?;
        store.set_setting(STREAM_MEMORY, settings.stream_memory.as_str())?;
        store.set_setting(
            STREAM_MEMORY_AHEAD_MIB,
            &clamp_range(settings.stream_memory_ahead_mib, STREAM_AHEAD_MIB_RANGE).to_string(),
        )?;
        store.set_setting(
            STREAM_MEMORY_BEHIND_MIB,
            &clamp_range(settings.stream_memory_behind_mib, STREAM_BEHIND_MIB_RANGE).to_string(),
        )?;
        store.set_setting(
            SUBTITLE_SIZE_PERCENT,
            &subtitle_size(settings.subtitle_size_percent).to_string(),
        )?;
        store.set_setting(
            SUBTITLE_POSITION,
            &subtitle_position(settings.subtitle_position).to_string(),
        )?;
        store.set_setting(
            SUBTITLE_TEXT_COLOR,
            &settings.subtitle_text_color.to_string(),
        )?;
        store.set_setting(
            SUBTITLE_BACKGROUND_COLOR,
            &settings.subtitle_background_color.to_string(),
        )?;
        store.set_setting(
            SUBTITLE_OUTLINE_COLOR,
            &settings.subtitle_outline_color.to_string(),
        )?;
        store.set_setting(
            SUBTITLE_FILE_STYLING,
            if settings.subtitle_file_styling {
                "1"
            } else {
                "0"
            },
        )?;
        drop(store);
        // "Only on Wi-Fi" may have changed.
        self.apply_network_policy();
        Ok(())
    }

    /// Whether the user turned HDR on for this title ("Only for this title").
    pub fn hdr_for_title(&self, meta_id: String) -> Result<bool, CoreError> {
        let Some(key) = hdr_title_key(&meta_id) else {
            return Ok(false);
        };
        Ok(self.store()?.setting(&key)?.is_some())
    }

    /// Remembers that the user wants HDR for this title. Unusable ids are ignored.
    pub fn set_hdr_for_title(&self, meta_id: String) -> Result<(), CoreError> {
        if let Some(key) = hdr_title_key(&meta_id) {
            self.store()?.set_setting(&key, "1")?;
        }
        Ok(())
    }

    /// Number of titles remembered for HDR.
    pub fn hdr_title_count(&self) -> Result<u32, CoreError> {
        Ok(self.store()?.count_settings_with_prefix(HDR_TITLE_PREFIX)?)
    }

    /// Forgets every title remembered for HDR; returns how many there were.
    pub fn clear_hdr_titles(&self) -> Result<u32, CoreError> {
        Ok(self
            .store()?
            .delete_settings_with_prefix(HDR_TITLE_PREFIX)?)
    }

    /// The cache sizes offered in Settings, in MiB.
    pub fn torrent_cache_choices(&self) -> Vec<u32> {
        TORRENT_CACHE_CHOICES_MB.to_vec()
    }
}

/// The setting key for a title's HDR choice, or None for an empty, overlong or control-character id.
fn hdr_title_key(meta_id: &str) -> Option<String> {
    let valid = !meta_id.is_empty()
        && meta_id.len() <= MAX_META_ID_LEN
        && !meta_id.chars().any(char::is_control);
    valid.then(|| format!("{HDR_TITLE_PREFIX}{meta_id}"))
}

/// Keeps a language code to two or three ASCII letters, lowercased; anything else is empty.
fn language_code(value: &str) -> String {
    let value = value.trim();
    if (2..=3).contains(&value.len()) && value.chars().all(|c| c.is_ascii_alphabetic()) {
        value.to_ascii_lowercase()
    } else {
        String::new()
    }
}

/// A server name fit to announce: one line, bounded.
fn serve_name(value: &str) -> String {
    value
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_SERVE_NAME_CHARS)
        .collect()
}

/// Keeps a resolution limit to one the ranking understands, or no limit.
fn clamp_resolution(lines: u32) -> u32 {
    match lines {
        2160 | 1080 | 720 => lines,
        _ => 0,
    }
}

/// Keeps `value` within the inclusive `(min, max)` range.
fn clamp_range(value: u32, (min, max): (u32, u32)) -> u32 {
    value.clamp(min, max)
}

/// Keeps `value` within the inclusive `(min, max)` range, on the nearest multiple of `step`
/// counted from `min`.
fn snap_to_step(value: u32, (min, max): (u32, u32), step: u32) -> u32 {
    let steps = (value.clamp(min, max) - min + step / 2) / step;
    (min + steps * step).min(max)
}

/// A subtitle size the player's buttons could reach.
fn subtitle_size(percent: u32) -> u32 {
    snap_to_step(percent, SUBTITLE_SIZE_RANGE, SUBTITLE_SIZE_STEP)
}

/// A subtitle place the player's buttons could reach.
fn subtitle_position(percent: u32) -> u32 {
    snap_to_step(percent, SUBTITLE_POSITION_RANGE, SUBTITLE_POSITION_STEP)
}

/// Keeps a cache size within the offered range.
fn clamp_cache(megabytes: u32) -> u32 {
    let (min, max) = (TORRENT_CACHE_CHOICES_MB[0], TORRENT_CACHE_CHOICES_MB[4]);
    megabytes.clamp(min, max)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_round_trip_with_defaults_and_limits() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        assert_eq!(core.settings().expect("reads"), AppSettings::default());

        core.update_settings(AppSettings {
            torrent_wifi_only: true,
            torrent_cache_mb: 5,
            audio_language: "PT".into(),
            subtitle_language: "../etc".into(),
            max_resolution: 1081,
            onboarded: true,
            aniskip: false,
            hdr: DisplaySwitch::Never,
            match_refresh_rate: true,
            serve_to_devices: true,
            serve_name: "  StremioNow\non   TV ".into(),
            stream_memory: StreamMemoryChoice::Custom,
            stream_memory_ahead_mib: 4,
            stream_memory_behind_mib: 99_999,
            subtitle_size_percent: 500,
            subtitle_position: 0,
            subtitle_text_color: 0xFFFF_E600,
            subtitle_background_color: 0x8000_0000,
            subtitle_outline_color: 0,
            subtitle_file_styling: true,
        })
        .expect("saves");
        let saved = core.settings().expect("reads");
        assert!(saved.torrent_wifi_only);
        assert_eq!(
            saved.torrent_cache_mb, TORRENT_CACHE_CHOICES_MB[0],
            "too small is raised"
        );
        assert_eq!(saved.audio_language, "pt");
        assert_eq!(saved.subtitle_language, "", "junk is dropped");
        assert_eq!(saved.max_resolution, 0, "unknown limits mean no limit");
        assert!(saved.onboarded);
        assert!(!saved.aniskip);
        assert_eq!(saved.hdr, DisplaySwitch::Never);
        assert!(saved.match_refresh_rate);
        assert!(saved.serve_to_devices);
        assert_eq!(saved.serve_name, "StremioNow on TV");
        assert_eq!(saved.stream_memory, StreamMemoryChoice::Custom);
        assert_eq!(
            saved.stream_memory_ahead_mib, STREAM_AHEAD_MIB_RANGE.0,
            "too little is raised"
        );
        assert_eq!(
            saved.stream_memory_behind_mib, STREAM_BEHIND_MIB_RANGE.1,
            "too much is lowered"
        );
        assert_eq!(saved.subtitle_size_percent, SUBTITLE_SIZE_RANGE.1);
        assert_eq!(saved.subtitle_position, SUBTITLE_POSITION_RANGE.0);
        assert_eq!(saved.subtitle_text_color, 0xFFFF_E600);
        assert_eq!(saved.subtitle_background_color, 0x8000_0000);
        assert_eq!(saved.subtitle_outline_color, 0);
        assert!(saved.subtitle_file_styling);
    }

    #[test]
    fn subtitle_style_defaults_steps_and_junk() {
        let defaults = AppSettings::default();
        assert_eq!(defaults.subtitle_size_percent, 100);
        assert_eq!(defaults.subtitle_position, 100, "at the bottom");
        assert_eq!(defaults.subtitle_text_color, 0xFFFF_FFFF, "white");
        assert_eq!(defaults.subtitle_background_color, 0, "no box");
        assert_eq!(defaults.subtitle_outline_color, 0xFF00_0000, "black");
        assert!(!defaults.subtitle_file_styling, "Stremio's default");

        // Sizes and places land on the player's steps.
        assert_eq!(subtitle_size(124), 120);
        assert_eq!(subtitle_size(125), 130);
        assert_eq!(subtitle_size(10), 50);
        assert_eq!(subtitle_position(82), 80);
        assert_eq!(subtitle_position(83), 85);
        assert_eq!(subtitle_position(101), 100);

        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        core.update_settings(AppSettings {
            subtitle_size_percent: 137,
            subtitle_position: 52,
            ..AppSettings::default()
        })
        .expect("saves");
        let saved = core.settings().expect("reads");
        assert_eq!(saved.subtitle_size_percent, 140);
        assert_eq!(saved.subtitle_position, 50);

        // Values that are not numbers fall back to the defaults.
        let store = core.store().expect("store");
        store
            .set_setting(SUBTITLE_TEXT_COLOR, "red")
            .expect("writes");
        store
            .set_setting(SUBTITLE_SIZE_PERCENT, "-3")
            .expect("writes");
        drop(store);
        let read = core.settings().expect("reads");
        assert_eq!(read.subtitle_text_color, defaults.subtitle_text_color);
        assert_eq!(read.subtitle_size_percent, defaults.subtitle_size_percent);
    }

    #[test]
    fn stream_memory_choices_round_trip() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        assert_eq!(
            core.settings().expect("reads").stream_memory,
            StreamMemoryChoice::Automatic,
            "the device decides until the user does"
        );
        for choice in [
            StreamMemoryChoice::Light,
            StreamMemoryChoice::Balanced,
            StreamMemoryChoice::Smooth,
            StreamMemoryChoice::Custom,
            StreamMemoryChoice::Automatic,
        ] {
            core.update_settings(AppSettings {
                stream_memory: choice,
                ..AppSettings::default()
            })
            .expect("saves");
            assert_eq!(core.settings().expect("reads").stream_memory, choice);
        }
        assert_eq!(
            StreamMemoryChoice::parse("something else"),
            StreamMemoryChoice::Automatic
        );
    }

    #[test]
    fn hdr_titles_are_remembered_and_cleared() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        assert!(!core.hdr_for_title("tt0063350".into()).expect("reads"));

        core.set_hdr_for_title("tt0063350".into()).expect("saves");
        core.set_hdr_for_title("kitsu:1".into()).expect("saves");
        core.set_hdr_for_title("tt0063350".into())
            .expect("saves twice");
        // Unusable ids are ignored rather than stored.
        core.set_hdr_for_title(String::new()).expect("ignored");
        core.set_hdr_for_title(format!("a{}b", char::from(1u8)))
            .expect("ignored");
        core.set_hdr_for_title("x".repeat(MAX_META_ID_LEN + 1))
            .expect("ignored");

        assert!(core.hdr_for_title("tt0063350".into()).expect("reads"));
        assert!(
            !core
                .hdr_for_title("tt00633".into())
                .expect("a prefix is another title")
        );
        assert_eq!(core.hdr_title_count().expect("counts"), 2);
        // Other settings never match the prefix.
        core.update_settings(AppSettings::default()).expect("saves");
        assert_eq!(core.clear_hdr_titles().expect("clears"), 2);
        assert_eq!(core.hdr_title_count().expect("counts"), 0);
        assert!(!core.hdr_for_title("kitsu:1".into()).expect("reads"));
        assert_eq!(core.settings().expect("reads").hdr, DisplaySwitch::Ask);
    }
}
