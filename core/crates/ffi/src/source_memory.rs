// SPDX-License-Identifier: GPL-3.0-or-later
//! The sources played before: for each video, so the details page can offer it first next time
//! ("Continue with this source"), and for each title, so "Resume" and "Continue watching" can
//! go straight to the player with the source chosen last time.
//!
//! Kept in the local settings store, one setting per video and one per title, each under its
//! own prefix. What is kept is a source's [`StreamSource::identity`], its binge group and the
//! manifest id of its addon, never its address: debrid addresses carry the user's key. The
//! source is looked up again when it is needed, which keeps working when links expire.

use serde::{Deserialize, Serialize};

use crate::core::StremioCore;
use crate::playback::{MAX_ADDON_ID_LEN, StreamSource};
use crate::records::CoreError;

/// One setting per video: the prefix and the video id (a movie's id, or an episode's).
const PLAYED_SOURCE_PREFIX: &str = "played_source:";
/// One setting per title: the prefix and the title's id.
const LAST_SOURCE_PREFIX: &str = "resume_source:";
/// The first version's per-title prefix, whose identities could not tell a debrid source from
/// its torrent; never read, only forgotten.
const LEGACY_LAST_SOURCE_PREFIX: &str = "last_source:";
/// Longest video or title id remembered; ids are short (`tt0944947:1:5`, `kitsu:1:12`).
const MAX_VIDEO_ID_LEN: usize = 200;
/// Longest identity kept: an addon id and a digest or an info hash.
const MAX_IDENTITY_LEN: usize = 512;
/// Longest binge group kept; addons use short ones, such as `torrentio|1080p|HDR`.
const MAX_BINGE_GROUP_LEN: usize = 512;

/// The source last played for a title, as much of it as finds it again: never its address.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct LastSource {
    /// The video it played: the movie's id, or the episode's. Another video of the title
    /// (the next episode, once this one was finished) is found by [`LastSource::binge_group`].
    pub video_id: String,
    /// The manifest id of the addon that offered it, to ask that addon again.
    pub addon_id: String,
    /// The source's [`StreamSource::identity`], to find the same source for the same video.
    pub identity: String,
    /// The source's binge group, to find a source of the same kind for another video, or for
    /// the same one when the addon signs its links anew on every request.
    pub binge_group: Option<String>,
}

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

    /// Remembers `source` as the one played for video `video_id` of title `title_id`: for the
    /// video, as [`StremioCore::remember_played_source`] does, and for the title, as its
    /// [`LastSource`]. Only the source's identity, binge group and addon id are kept. Links that
    /// open outside the app have no identity and are ignored, as are unusable ids.
    pub fn remember_last_source(
        &self,
        title_id: String,
        video_id: String,
        source: StreamSource,
    ) -> Result<(), CoreError> {
        let Some(identity) = source.identity.filter(|identity| is_identity(identity)) else {
            return Ok(());
        };
        let last = LastSource {
            video_id,
            addon_id: source.addon_id,
            identity,
            // A group that cannot be kept only means the next episode is not found by it.
            binge_group: source.binge_group.filter(|group| is_binge_group(group)),
        };
        let (Some(title_key), Some(video_key)) = (
            last_source_key(&title_id),
            played_source_key(&last.video_id),
        ) else {
            return Ok(());
        };
        if !is_addon_id(&last.addon_id) {
            return Ok(());
        }
        let value = serde_json::to_string(&last).map_err(|error| CoreError::Storage {
            reason: error.to_string(),
        })?;
        let store = self.store()?;
        store.set_setting(&video_key, &last.identity)?;
        store.set_setting(&title_key, &value)?;
        Ok(())
    }

    /// The source last played for a title, if any. What was stored is checked again, as
    /// anything read back from disk.
    pub fn last_source(&self, title_id: String) -> Result<Option<LastSource>, CoreError> {
        let Some(key) = last_source_key(&title_id) else {
            return Ok(None);
        };
        Ok(self
            .store()?
            .setting(&key)?
            .and_then(|value| serde_json::from_str::<LastSource>(&value).ok())
            .filter(is_last_source))
    }

    /// Forgets the sources played for every video and title; returns how many were remembered.
    pub fn forget_played_sources(&self) -> Result<u32, CoreError> {
        let store = self.store()?;
        let videos = store.delete_settings_with_prefix(PLAYED_SOURCE_PREFIX)?;
        let titles = store.delete_settings_with_prefix(LAST_SOURCE_PREFIX)?;
        let legacy = store.delete_settings_with_prefix(LEGACY_LAST_SOURCE_PREFIX)?;
        Ok(videos.saturating_add(titles).saturating_add(legacy))
    }
}

/// The setting key for a video's source, or None for an empty, overlong or control-character id.
fn played_source_key(video_id: &str) -> Option<String> {
    is_id(video_id).then(|| format!("{PLAYED_SOURCE_PREFIX}{video_id}"))
}

/// The setting key for a title's last source, or None for an unusable id.
fn last_source_key(title_id: &str) -> Option<String> {
    is_id(title_id).then(|| format!("{LAST_SOURCE_PREFIX}{title_id}"))
}

fn is_id(id: &str) -> bool {
    is_text(id, MAX_VIDEO_ID_LEN)
}

fn is_identity(identity: &str) -> bool {
    is_text(identity, MAX_IDENTITY_LEN)
}

fn is_addon_id(addon_id: &str) -> bool {
    is_text(addon_id, MAX_ADDON_ID_LEN)
}

fn is_binge_group(group: &str) -> bool {
    is_text(group, MAX_BINGE_GROUP_LEN)
}

fn is_text(text: &str, max_len: usize) -> bool {
    !text.is_empty() && text.len() <= max_len && !text.chars().any(char::is_control)
}

fn is_last_source(last: &LastSource) -> bool {
    is_id(&last.video_id)
        && is_addon_id(&last.addon_id)
        && is_identity(&last.identity)
        && last.binge_group.as_deref().is_none_or(is_binge_group)
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

    /// A debrid source as an addon sends it: the address carries the user's key.
    fn debrid_source(group: Option<&str>) -> StreamSource {
        StreamSource {
            addon_name: "Torrentio".into(),
            addon_id: "com.stremio.torrentio.addon".into(),
            name: "[RD+] Torrentio\n1080p".into(),
            description: "Show.S01E05.1080p.WEB-DL-GRP".into(),
            url: Some("https://debrid.example/resolve/SECRETKEY/0123/Show.mkv".into()),
            info_hash: Some("0123456789abcdef0123456789abcdef01234567".into()),
            file_idx: Some(4),
            external_url: None,
            yt_id: None,
            badges: vec!["1080p".into()],
            quality: Some("1080p".into()),
            seeders: Some(12),
            size: None,
            release_group: Some("GRP".into()),
            subtitles: Vec::new(),
            binge_group: group.map(Into::into),
            trackers: vec!["udp://tracker.example:1337".into()],
            identity: Some(
                "com.stremio.torrentio.addon|bt|0123456789abcdef0123456789abcdef01234567|4".into(),
            ),
            languages: Vec::new(),
            hdr: Vec::new(),
            audio: Vec::new(),
            codec: None,
            release_quality: None,
            size_bytes: None,
            cached: Some(true),
        }
    }

    #[test]
    fn remembers_the_last_source_of_each_title() {
        let (_dir, core) = core();
        assert_eq!(core.last_source("tt1".into()).expect("reads"), None);

        core.remember_last_source(
            "tt1".into(),
            "tt1:1:5".into(),
            debrid_source(Some("torrentio|1080p")),
        )
        .expect("saves");
        let last = core
            .last_source("tt1".into())
            .expect("reads")
            .expect("kept");
        assert_eq!(
            last,
            LastSource {
                video_id: "tt1:1:5".into(),
                addon_id: "com.stremio.torrentio.addon".into(),
                identity:
                    "com.stremio.torrentio.addon|bt|0123456789abcdef0123456789abcdef01234567|4"
                        .into(),
                binge_group: Some("torrentio|1080p".into()),
            }
        );
        // The video's own memory, for the source list, follows.
        assert_eq!(
            core.played_source("tt1:1:5".into()).expect("reads"),
            Some(last.identity.clone())
        );

        // The next episode replaces it; a group that cannot be kept is dropped alone.
        let mut next = debrid_source(Some(&"g".repeat(MAX_BINGE_GROUP_LEN + 1)));
        next.identity = Some("com.stremio.torrentio.addon|bt|other|5".into());
        core.remember_last_source("tt1".into(), "tt1:1:6".into(), next)
            .expect("saves");
        let last = core
            .last_source("tt1".into())
            .expect("reads")
            .expect("kept");
        assert_eq!(last.video_id, "tt1:1:6");
        assert_eq!(last.binge_group, None);
        assert!(
            core.played_source("tt1:1:5".into())
                .expect("reads")
                .is_some(),
            "the episode before keeps its own"
        );

        assert_eq!(core.forget_played_sources().expect("clears"), 3);
        assert_eq!(core.last_source("tt1".into()).expect("reads"), None);
    }

    #[test]
    fn nothing_but_identity_group_and_addon_is_kept() {
        let (_dir, core) = core();
        core.remember_last_source("tt1".into(), "tt1".into(), debrid_source(Some("g")))
            .expect("saves");
        let stored = core
            .store()
            .expect("store")
            .setting("resume_source:tt1")
            .expect("reads")
            .expect("stored");
        assert!(
            !stored.contains("SECRETKEY") && !stored.contains("debrid.example"),
            "{stored}"
        );
        assert!(!stored.contains("tracker.example"), "{stored}");
        let fields: serde_json::Map<String, serde_json::Value> =
            serde_json::from_str(&stored).expect("json");
        let mut keys: Vec<&str> = fields.keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(keys, ["addon_id", "binge_group", "identity", "video_id"]);
    }

    #[test]
    fn unusable_last_sources_are_ignored() {
        let (_dir, core) = core();
        // Links out have no identity; ids and addon ids must be usable.
        let mut outside = debrid_source(None);
        outside.identity = None;
        let mut no_addon = debrid_source(None);
        no_addon.addon_id = String::new();
        let mut control = debrid_source(None);
        control.addon_id = "a\u{7}".into();
        for (title, video, source) in [
            ("tt1", "tt1", outside),
            ("tt1", "tt1", no_addon),
            ("tt1", "tt1", control),
            ("", "tt1", debrid_source(None)),
            ("tt1", "", debrid_source(None)),
        ] {
            core.remember_last_source(title.into(), video.into(), source)
                .expect("ignored, not failed");
        }
        assert_eq!(core.last_source("tt1".into()).expect("reads"), None);
        assert_eq!(core.forget_played_sources().expect("clears"), 0);

        // Whatever else is found on disk under the key reads as nothing.
        for value in [
            "not json",
            r#"{"video_id":"tt1","addon_id":"","identity":"a|bt|h|","binge_group":null}"#,
            r#"{"video_id":"tt1","addon_id":"a","identity":"a\n","binge_group":null}"#,
            r#"{"video_id":"tt1","addon_id":"a","identity":"a|bt|h|","binge_group":""}"#,
            r#"{"video_id":"","addon_id":"a","identity":"a|bt|h|","binge_group":null}"#,
        ] {
            core.store()
                .expect("store")
                .set_setting("last_source:tt1", value)
                .expect("writes");
            assert_eq!(
                core.last_source("tt1".into()).expect("reads"),
                None,
                "{value}"
            );
        }
    }
}
