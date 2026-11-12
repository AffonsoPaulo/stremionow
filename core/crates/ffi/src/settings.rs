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
const SERVE_TO_DEVICES: &str = "serve_to_devices";
const SERVE_NAME: &str = "serve_name";

/// Longest name for this device as a server, in characters.
const MAX_SERVE_NAME_CHARS: usize = 40;
/// Cache sizes the user can pick, in MiB. The first one is the smallest allowed.
pub const TORRENT_CACHE_CHOICES_MB: [u32; 5] = [1024, 2048, 5120, 10240, 20480];
const DEFAULT_TORRENT_CACHE_MB: u32 = 2048;

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
    /// Serve torrents to other devices on the home network while the app runs (ADR 0008).
    pub serve_to_devices: bool,
    /// The name other devices see for this one when it serves, announced on the network;
    /// empty until the user turns serving on, when the app sets a neutral default.
    pub serve_name: String,
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
            serve_to_devices: false,
            serve_name: String::new(),
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
            serve_to_devices: store
                .setting(SERVE_TO_DEVICES)?
                .is_some_and(|value| value == "1"),
            serve_name: store
                .setting(SERVE_NAME)?
                .map_or(defaults.serve_name, |value| serve_name(&value)),
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
        store.set_setting(
            SERVE_TO_DEVICES,
            if settings.serve_to_devices { "1" } else { "0" },
        )?;
        store.set_setting(SERVE_NAME, &serve_name(&settings.serve_name))?;
        drop(store);
        // "Only on Wi-Fi" may have changed.
        self.apply_network_policy();
        Ok(())
    }

    /// The cache sizes offered in Settings, in MiB.
    pub fn torrent_cache_choices(&self) -> Vec<u32> {
        TORRENT_CACHE_CHOICES_MB.to_vec()
    }
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
            serve_to_devices: true,
            serve_name: "  StremioNow\non   TV ".into(),
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
        assert!(saved.serve_to_devices);
        assert_eq!(saved.serve_name, "StremioNow on TV");
    }
}
