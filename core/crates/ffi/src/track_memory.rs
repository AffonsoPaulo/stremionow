// SPDX-License-Identifier: GPL-3.0-or-later
//! The audio and subtitle tracks picked for each title, so resuming it, or moving on to its
//! next episode, picks them again, as Stremio's player keeps a stream's tracks. Tracks are
//! told by language and title, which carry over from one file to the next; track numbers do
//! not. Kept in the local settings store, one setting per title.

use serde::{Deserialize, Serialize};

use crate::core::StremioCore;
use crate::records::CoreError;

/// One setting per title: the prefix and the title's id.
const TRACKS_PREFIX: &str = "tracks:";
/// Longest title id, language or track title kept.
const MAX_TEXT_LEN: usize = 200;

/// The tracks picked for a title. Every field is optional: none means "as the player picks".
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct TrackChoice {
    /// The audio track's language, as the file names it (`por`, `en`, `pt-BR`).
    pub audio_language: Option<String>,
    /// The audio track's title, to tell two of the same language apart ("Commentary").
    pub audio_title: Option<String>,
    /// Subtitles were turned off.
    pub subtitles_off: bool,
    /// The subtitle track's language.
    pub subtitle_language: Option<String>,
    /// The subtitle track's title ("Forced", or the addon an external one came from).
    pub subtitle_title: Option<String>,
}

#[uniffi::export]
impl StremioCore {
    /// Remembers the tracks picked for title `title_id`. Unusable ids and texts are dropped.
    pub fn remember_tracks(&self, title_id: String, choice: TrackChoice) -> Result<(), CoreError> {
        let Some(key) = tracks_key(&title_id) else {
            return Ok(());
        };
        let value = serde_json::to_string(&clean(choice)).map_err(|error| CoreError::Storage {
            reason: error.to_string(),
        })?;
        self.store()?.set_setting(&key, &value)?;
        Ok(())
    }

    /// The tracks picked for title `title_id`, if any; what was stored is checked again.
    pub fn tracks(&self, title_id: String) -> Result<Option<TrackChoice>, CoreError> {
        let Some(key) = tracks_key(&title_id) else {
            return Ok(None);
        };
        Ok(self
            .store()?
            .setting(&key)?
            .and_then(|value| serde_json::from_str::<TrackChoice>(&value).ok())
            .map(clean))
    }

    /// Forgets the tracks picked for every title; returns how many were remembered.
    pub fn forget_tracks(&self) -> Result<u32, CoreError> {
        Ok(self.store()?.delete_settings_with_prefix(TRACKS_PREFIX)?)
    }
}

fn tracks_key(title_id: &str) -> Option<String> {
    is_text(title_id).then(|| format!("{TRACKS_PREFIX}{title_id}"))
}

fn is_text(text: &str) -> bool {
    !text.is_empty() && text.len() <= MAX_TEXT_LEN && !text.chars().any(char::is_control)
}

/// [choice] with every text that cannot be kept dropped.
fn clean(choice: TrackChoice) -> TrackChoice {
    let keep = |text: Option<String>| text.filter(|text| is_text(text));
    TrackChoice {
        audio_language: keep(choice.audio_language),
        audio_title: keep(choice.audio_title),
        subtitles_off: choice.subtitles_off,
        subtitle_language: keep(choice.subtitle_language),
        subtitle_title: keep(choice.subtitle_title),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn core() -> (tempfile::TempDir, std::sync::Arc<StremioCore>) {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        (dir, core)
    }

    #[test]
    fn remembers_the_tracks_of_each_title() {
        let (_dir, core) = core();
        assert_eq!(core.tracks("tt1".into()).expect("reads"), None);
        let choice = TrackChoice {
            audio_language: Some("por".into()),
            audio_title: None,
            subtitles_off: false,
            subtitle_language: Some("eng".into()),
            subtitle_title: Some("Forced".into()),
        };
        core.remember_tracks("tt1".into(), choice.clone())
            .expect("keeps");
        assert_eq!(core.tracks("tt1".into()).expect("reads"), Some(choice));
        assert_eq!(core.tracks("tt2".into()).expect("reads"), None);
        assert_eq!(core.forget_tracks().expect("forgets"), 1);
        assert_eq!(core.tracks("tt1".into()).expect("reads"), None);
    }

    #[test]
    fn unusable_texts_are_dropped() {
        let (_dir, core) = core();
        let choice = TrackChoice {
            audio_language: Some("x".repeat(MAX_TEXT_LEN + 1)),
            subtitle_title: Some("a\nb".into()),
            subtitles_off: true,
            ..TrackChoice::default()
        };
        core.remember_tracks("tt1".into(), choice).expect("keeps");
        assert_eq!(
            core.tracks("tt1".into()).expect("reads"),
            Some(TrackChoice {
                subtitles_off: true,
                ..TrackChoice::default()
            })
        );
        core.remember_tracks(String::new(), TrackChoice::default())
            .expect("ignores");
        core.store()
            .expect("store")
            .set_setting("tracks:tt3", "not json")
            .expect("writes");
        assert_eq!(core.tracks("tt3".into()).expect("reads"), None);
    }
}
