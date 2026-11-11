// SPDX-License-Identifier: GPL-3.0-or-later
//! User preferences kept in the local database.

use crate::core::StremioCore;
use crate::records::CoreError;

const TORRENT_WIFI_ONLY: &str = "torrent_wifi_only";
const TORRENT_CACHE_MB: &str = "torrent_cache_mb";
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
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            torrent_wifi_only: false,
            torrent_cache_mb: DEFAULT_TORRENT_CACHE_MB,
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
        Ok(())
    }

    /// The cache sizes offered in Settings, in MiB.
    pub fn torrent_cache_choices(&self) -> Vec<u32> {
        TORRENT_CACHE_CHOICES_MB.to_vec()
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
        })
        .expect("saves");
        let saved = core.settings().expect("reads");
        assert!(saved.torrent_wifi_only);
        assert_eq!(
            saved.torrent_cache_mb, TORRENT_CACHE_CHOICES_MB[0],
            "too small is raised"
        );
    }
}
