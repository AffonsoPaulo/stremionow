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
const HDR: &str = "hdr";
const MATCH_REFRESH_RATE: &str = "match_refresh_rate";
/// Titles the user turned HDR on for ("Only for this title"), one setting each: the prefix and the meta id.
const HDR_TITLE_PREFIX: &str = "hdr_title:";
/// Longest meta id remembered; addon ids are short (`tt0063350`, `kitsu:1`).
const MAX_META_ID_LEN: usize = 200;
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
    /// Turning on HDR for HDR videos, on displays that support it (desktop).
    pub hdr: DisplaySwitch,
    /// Matching the display's refresh rate to the video's frame rate (desktop; off by default).
    pub match_refresh_rate: bool,
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
            hdr: DisplaySwitch::Ask,
            match_refresh_rate: false,
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
            hdr: store
                .setting(HDR)?
                .map_or(defaults.hdr, |value| DisplaySwitch::parse(&value)),
            match_refresh_rate: store
                .setting(MATCH_REFRESH_RATE)?
                .is_some_and(|value| value == "1"),
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
        store.set_setting(HDR, settings.hdr.as_str())?;
        store.set_setting(
            MATCH_REFRESH_RATE,
            if settings.match_refresh_rate {
                "1"
            } else {
                "0"
            },
        )?;
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

/// Keeps a resolution limit to one the ranking understands, or no limit.
fn clamp_resolution(lines: u32) -> u32 {
    match lines {
        2160 | 1080 | 720 => lines,
        _ => 0,
    }
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
            hdr: DisplaySwitch::Never,
            match_refresh_rate: true,
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
        assert_eq!(saved.hdr, DisplaySwitch::Never);
        assert!(saved.match_refresh_rate);
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
