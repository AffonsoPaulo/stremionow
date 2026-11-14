// SPDX-License-Identifier: GPL-3.0-or-later
//! The source last played for each video, so the details page can offer it first next time
//! ("Continue with this source").
//!
//! Kept in the local settings store, one setting per video under its own prefix. What is kept
//! is a source's [`StreamSource::identity`](crate::playback::StreamSource::identity), never its
//! address: debrid addresses carry the user's key.

use crate::core::StremioCore;
use crate::records::CoreError;

/// One setting per video: the prefix and the video id (a movie's id, or an episode's).
const PLAYED_SOURCE_PREFIX: &str = "played_source:";
/// Longest video id remembered; episode ids are short (`tt0944947:1:5`, `kitsu:1:12`).
const MAX_VIDEO_ID_LEN: usize = 200;
/// Longest identity kept: an addon id and a digest or an info hash.
const MAX_IDENTITY_LEN: usize = 512;

#[uniffi::export]
impl StremioCore {
    /// Remembers the source played for a video, by its identity (see `StreamSource::identity`).
    /// Unusable video ids and identities are ignored.
    pub fn remember_played_source(
        &self,
        video_id: String,
        identity: String,
    ) -> Result<(), CoreError> {
        if let Some(key) = played_source_key(&video_id)
            && is_identity(&identity)
        {
            self.store()?.set_setting(&key, &identity)?;
        }
        Ok(())
    }

    /// The identity of the source last played for a video, if any.
    pub fn played_source(&self, video_id: String) -> Result<Option<String>, CoreError> {
        let Some(key) = played_source_key(&video_id) else {
            return Ok(None);
        };
        Ok(self
            .store()?
            .setting(&key)?
            .filter(|identity| is_identity(identity)))
    }

    /// Forgets the sources played for every video; returns how many were remembered.
    pub fn forget_played_sources(&self) -> Result<u32, CoreError> {
        Ok(self
            .store()?
            .delete_settings_with_prefix(PLAYED_SOURCE_PREFIX)?)
    }
}

/// The setting key for a video's source, or None for an empty, overlong or control-character id.
fn played_source_key(video_id: &str) -> Option<String> {
    let valid = !video_id.is_empty()
        && video_id.len() <= MAX_VIDEO_ID_LEN
        && !video_id.chars().any(char::is_control);
    valid.then(|| format!("{PLAYED_SOURCE_PREFIX}{video_id}"))
}

fn is_identity(identity: &str) -> bool {
    !identity.is_empty()
        && identity.len() <= MAX_IDENTITY_LEN
        && !identity.chars().any(char::is_control)
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
    fn remembers_the_last_source_of_each_video() {
        let (_dir, core) = core();
        assert_eq!(core.played_source("tt1".into()).expect("reads"), None);

        core.remember_played_source("tt1".into(), "a|bt|hash|".into())
            .expect("saves");
        core.remember_played_source("tt2:1:5".into(), "a|url|digest".into())
            .expect("saves");
        // A later choice replaces the earlier one.
        core.remember_played_source("tt1".into(), "b|bt|other|2".into())
            .expect("saves");

        assert_eq!(
            core.played_source("tt1".into()).expect("reads").as_deref(),
            Some("b|bt|other|2")
        );
        assert_eq!(
            core.played_source("tt2:1:5".into())
                .expect("reads")
                .as_deref(),
            Some("a|url|digest")
        );
        assert_eq!(
            core.played_source("tt2:1:6".into()).expect("reads"),
            None,
            "another episode has its own"
        );
        // Other settings never match the prefix.
        core.set_hdr_for_title("tt1".into()).expect("saves");
        assert_eq!(core.forget_played_sources().expect("clears"), 2);
        assert_eq!(core.played_source("tt1".into()).expect("reads"), None);
        assert!(core.hdr_for_title("tt1".into()).expect("reads"));
    }

    #[test]
    fn unusable_ids_and_identities_are_ignored() {
        let (_dir, core) = core();
        for (video, identity) in [
            (String::new(), "a|bt|h|".to_owned()),
            ("x".repeat(MAX_VIDEO_ID_LEN + 1), "a|bt|h|".to_owned()),
            (format!("tt{}1", char::from(1u8)), "a|bt|h|".to_owned()),
            ("tt1".to_owned(), String::new()),
            ("tt1".to_owned(), "x".repeat(MAX_IDENTITY_LEN + 1)),
            ("tt1".to_owned(), "a\n|bt|h|".to_owned()),
        ] {
            core.remember_played_source(video.clone(), identity)
                .expect("ignored, not failed");
            assert_eq!(core.played_source(video).expect("reads"), None);
        }
        assert_eq!(core.forget_played_sources().expect("clears"), 0);
    }
}
