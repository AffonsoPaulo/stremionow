// SPDX-License-Identifier: GPL-3.0-or-later
//! Everything around watching: sources from stream addons, subtitles and playback progress.

use futures::future::join_all;
use stremionow_addons::InstalledAddon;
use stremionow_library::ProgressEntry;
use stremionow_protocol::{Stream, Subtitle};

use stremionow_ranking::{Preferences, SourceText, badges, parse};

use crate::core::{StremioCore, now_millis};
use crate::records::{AddonInfo, CoreError};

/// Progress below this is not worth a "Continue watching" entry.
const MIN_PROGRESS_MS: u64 = 30_000;
/// Share of a video after which it counts as watched.
const WATCHED_RATIO: f64 = 0.92;
/// Entries shown in "Continue watching".
const CONTINUE_WATCHING_LIMIT: usize = 20;

/// A playable source offered by a stream addon.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StreamSource {
    pub addon_name: String,
    /// Short label from the addon, often its name and the quality (`Torrentio\n4k`).
    pub name: String,
    /// Longer description: release name, size, seeders, languages.
    pub description: String,
    /// Direct HTTP(S) address, for debrid and web sources.
    pub url: Option<String>,
    /// BitTorrent info hash, for torrent sources.
    pub info_hash: Option<String>,
    pub file_idx: Option<u32>,
    /// HTTP(S) page to open outside the app.
    pub external_url: Option<String>,
    pub yt_id: Option<String>,
    /// Technical labels for the source picker, like `4K` or `HDR10`.
    pub badges: Vec<String>,
    /// Subtitles that come with this source.
    pub subtitles: Vec<SubtitleTrack>,
    /// Sources of the same group can replace each other from one episode to the next.
    pub binge_group: Option<String>,
    /// Trackers and DHT hints for torrents, as the addon sent them.
    pub trackers: Vec<String>,
}

/// An external subtitle file.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SubtitleTrack {
    pub id: String,
    /// HTTP(S) address of the file.
    pub url: String,
    /// Language as sent by the addon, usually ISO 639-2 (`por`, `eng`).
    pub lang: String,
    pub addon_name: String,
}

/// Where the user stopped in a title.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WatchProgress {
    pub id: String,
    pub kind: String,
    pub name: String,
    pub poster: Option<String>,
    pub background: Option<String>,
    pub video_id: String,
    pub season: Option<u32>,
    pub episode: Option<u32>,
    pub position_ms: u64,
    pub duration_ms: u64,
    /// Unix time in milliseconds; set by the core when saving.
    pub updated_at: i64,
}

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// Enabled addons that can offer sources for this video, in priority order. Ask each one
    /// with [`StremioCore::streams_from`] to show sources as they arrive.
    pub fn stream_addons(
        &self,
        kind: String,
        video_id: String,
    ) -> Result<Vec<AddonInfo>, CoreError> {
        Ok(self
            .enabled()?
            .iter()
            .filter(|addon| addon.manifest.supports("stream", &kind, &video_id))
            .map(|addon| AddonInfo::new(addon, true))
            .collect())
    }

    /// Sources one addon offers for a video. Sources the app could never use are dropped.
    pub async fn streams_from(
        &self,
        manifest_url: String,
        kind: String,
        video_id: String,
    ) -> Result<Vec<StreamSource>, CoreError> {
        let addon = self.find_addon(&manifest_url)?;
        let streams = self.client.streams(&addon, &kind, &video_id).await?;
        Ok(streams
            .into_iter()
            .filter_map(|stream| stream_source(&addon, stream))
            .collect())
    }

    /// Orders sources best first for the user's preferences.
    pub fn rank_streams(&self, sources: Vec<StreamSource>) -> Vec<StreamSource> {
        rank(sources)
    }

    /// Subtitles for a video from every subtitle addon. Addons that fail are skipped.
    pub async fn subtitles(
        &self,
        kind: String,
        video_id: String,
    ) -> Result<Vec<SubtitleTrack>, CoreError> {
        let addons: Vec<InstalledAddon> = self
            .enabled()?
            .into_iter()
            .filter(|addon| addon.manifest.supports("subtitles", &kind, &video_id))
            .collect();
        let results = join_all(
            addons
                .iter()
                .map(|addon| self.client.subtitles(addon, &kind, &video_id)),
        )
        .await;
        Ok(addons
            .iter()
            .zip(results)
            .filter_map(|(addon, result)| result.ok().map(|subtitles| (addon, subtitles)))
            .flat_map(|(addon, subtitles)| {
                subtitles
                    .into_iter()
                    .filter_map(move |subtitle| subtitle_track(&addon.manifest.name, subtitle))
            })
            .collect())
    }

    /// Records playback position. Near the end the video counts as watched and leaves
    /// "Continue watching"; the first seconds are not worth an entry.
    pub fn save_progress(&self, progress: WatchProgress) -> Result<(), CoreError> {
        let store = self.store()?;
        let now = now_millis();
        if is_finished(progress.position_ms, progress.duration_ms) {
            store.mark_watched(&progress.id, &progress.video_id, now)?;
            store.remove_progress(&progress.id)?;
            return Ok(());
        }
        let same_video = store
            .progress(&progress.id)?
            .is_some_and(|saved| saved.video_id == progress.video_id);
        if progress.position_ms < MIN_PROGRESS_MS && !same_video {
            return Ok(());
        }
        store.save_progress(&ProgressEntry {
            id: progress.id,
            kind: progress.kind,
            name: progress.name,
            poster: progress.poster,
            background: progress.background,
            video_id: progress.video_id,
            season: progress.season,
            episode: progress.episode,
            position_ms: i64::try_from(progress.position_ms).unwrap_or(i64::MAX),
            duration_ms: i64::try_from(progress.duration_ms).unwrap_or(i64::MAX),
            updated_at: now,
        })?;
        Ok(())
    }

    /// Where the user stopped in a title, if anywhere.
    pub fn progress(&self, id: String) -> Result<Option<WatchProgress>, CoreError> {
        Ok(self.store()?.progress(&id)?.map(WatchProgress::from))
    }

    /// Titles in progress, most recently watched first.
    pub fn continue_watching(&self) -> Result<Vec<WatchProgress>, CoreError> {
        Ok(self
            .store()?
            .progress_list(CONTINUE_WATCHING_LIMIT)?
            .into_iter()
            .map(WatchProgress::from)
            .collect())
    }

    /// Removes a title from "Continue watching".
    pub fn remove_progress(&self, id: String) -> Result<(), CoreError> {
        self.store()?.remove_progress(&id)?;
        Ok(())
    }

    /// Ids of the videos of a title the user has finished.
    pub fn watched_videos(&self, id: String) -> Result<Vec<String>, CoreError> {
        Ok(self.store()?.watched_videos(&id)?)
    }
}

impl From<ProgressEntry> for WatchProgress {
    fn from(entry: ProgressEntry) -> Self {
        Self {
            id: entry.id,
            kind: entry.kind,
            name: entry.name,
            poster: entry.poster,
            background: entry.background,
            video_id: entry.video_id,
            season: entry.season,
            episode: entry.episode,
            position_ms: u64::try_from(entry.position_ms).unwrap_or(0),
            duration_ms: u64::try_from(entry.duration_ms).unwrap_or(0),
            updated_at: entry.updated_at,
        }
    }
}

fn is_finished(position_ms: u64, duration_ms: u64) -> bool {
    // Durations are far below 2^52 ms, so converting to f64 is exact.
    duration_ms > 0 && position_ms as f64 >= duration_ms as f64 * WATCHED_RATIO
}

/// Converts an addon stream, keeping only addresses the player may open. Players such as
/// mpv also read local files and internal protocols (`file://`, `edl://`, `lavfi://`), so an
/// addon must not be able to point them anywhere but the web.
fn stream_source(addon: &InstalledAddon, stream: Stream) -> Option<StreamSource> {
    let url = web_url(stream.url);
    let external_url = web_url(stream.external_url);
    let info_hash = stream.info_hash.filter(|hash| is_info_hash(hash));
    let yt_id = stream.yt_id.filter(|id| {
        !id.is_empty()
            && id
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    });
    if url.is_none() && info_hash.is_none() && external_url.is_none() && yt_id.is_none() {
        return None;
    }
    let description = [stream.title, stream.description]
        .into_iter()
        .flatten()
        .find(|text| !text.trim().is_empty())
        .unwrap_or_default();
    let mut source = StreamSource {
        addon_name: addon.manifest.name.clone(),
        name: stream.name.unwrap_or_else(|| addon.manifest.name.clone()),
        description,
        url,
        info_hash,
        file_idx: stream.file_idx,
        external_url,
        yt_id,
        badges: Vec::new(),
        subtitles: stream
            .subtitles
            .into_iter()
            .filter_map(|subtitle| subtitle_track(&addon.manifest.name, subtitle))
            .collect(),
        binge_group: stream.behavior_hints.binge_group,
        trackers: stream
            .sources
            .into_iter()
            .filter(|tracker| tracker.len() <= 512)
            .collect(),
    };
    source.badges = badges(&parse(&source_text(&source)));
    Some(source)
}

fn subtitle_track(addon_name: &str, subtitle: Subtitle) -> Option<SubtitleTrack> {
    let url = web_url(Some(subtitle.url))?;
    Some(SubtitleTrack {
        id: subtitle.id.unwrap_or_else(|| url.clone()),
        url,
        lang: subtitle.lang,
        addon_name: addon_name.to_owned(),
    })
}

/// Keeps `http` and `https` addresses without whitespace or control characters.
fn web_url(url: Option<String>) -> Option<String> {
    url.filter(|url| {
        let lower = url.get(..8).unwrap_or(url).to_ascii_lowercase();
        (lower.starts_with("https://") || lower.starts_with("http://"))
            && url.len() > "http://".len()
            && !url.chars().any(|c| c.is_whitespace() || c.is_control())
    })
}

/// A BitTorrent v1 info hash: 40 hexadecimal digits (or 32 base32 characters).
fn is_info_hash(hash: &str) -> bool {
    (hash.len() == 40 && hash.chars().all(|c| c.is_ascii_hexdigit()))
        || (hash.len() == 32 && hash.chars().all(|c| c.is_ascii_alphanumeric()))
}

/// Orders sources best first. Preferences are the defaults until the settings screen lets
/// users change them.
fn rank(mut sources: Vec<StreamSource>) -> Vec<StreamSource> {
    stremionow_ranking::rank(&mut sources, &Preferences::default(), source_text);
    // Sources the app plays itself come before links to other apps; the sort is stable,
    // so each group keeps its ranking.
    sources.sort_by_key(|source| !plays_in_app(source));
    sources
}

/// Whether the app can play the source itself rather than handing it to another app.
pub(crate) fn plays_in_app(source: &StreamSource) -> bool {
    source.url.is_some() || source.info_hash.is_some()
}

fn source_text(source: &StreamSource) -> SourceText<'_> {
    SourceText {
        name: &source.name,
        title: "",
        description: &source.description,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_web_addresses_reach_the_player() {
        assert_eq!(
            web_url(Some("https://cdn.example/v.mkv".into())).as_deref(),
            Some("https://cdn.example/v.mkv")
        );
        assert!(web_url(Some("HTTP://cdn.example/v.mkv".into())).is_some());
        for hostile in [
            "file:///data/data/app/databases/x.db",
            "edl://%10%/etc/passwd",
            "lavfi://anullsrc",
            "javascript:alert(1)",
            "http://",
            "https://cdn.example/a b.mkv",
            "https://cdn.example/\u{0}x",
            "",
        ] {
            assert_eq!(web_url(Some(hostile.into())), None, "{hostile}");
        }
    }

    #[test]
    fn recognizes_info_hashes() {
        assert!(is_info_hash("0123456789abcdef0123456789ABCDEF01234567"));
        assert!(!is_info_hash("0123456789abcdef"));
        assert!(!is_info_hash("../../../../etc/passwd/0123456789abcdef01"));
    }

    fn progress(video_id: &str, position_ms: u64) -> WatchProgress {
        WatchProgress {
            id: "tt1".into(),
            kind: "series".into(),
            name: "Show".into(),
            poster: None,
            background: None,
            video_id: video_id.into(),
            season: Some(1),
            episode: Some(1),
            position_ms,
            duration_ms: 1_000_000,
            updated_at: 0,
        }
    }

    #[test]
    fn progress_lifecycle() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");

        // A few seconds in is not worth an entry.
        core.save_progress(progress("tt1:1:1", 5_000))
            .expect("saves");
        assert!(core.continue_watching().expect("lists").is_empty());

        core.save_progress(progress("tt1:1:1", 300_000))
            .expect("saves");
        let saved = core.progress("tt1".into()).expect("reads").expect("exists");
        assert_eq!(saved.position_ms, 300_000);
        assert!(saved.updated_at > 0, "the core stamps the time");

        // Seeking back to the start of the same episode still updates it.
        core.save_progress(progress("tt1:1:1", 1_000))
            .expect("saves");
        assert_eq!(
            core.progress("tt1".into())
                .expect("reads")
                .expect("exists")
                .position_ms,
            1_000
        );

        // Near the end it counts as watched and leaves the row.
        core.save_progress(progress("tt1:1:1", 950_000))
            .expect("saves");
        assert!(core.continue_watching().expect("lists").is_empty());
        assert_eq!(
            core.watched_videos("tt1".into()).expect("reads"),
            ["tt1:1:1"]
        );
    }

    fn source(name: &str, url: Option<&str>, external: Option<&str>) -> StreamSource {
        StreamSource {
            addon_name: "A".into(),
            name: name.into(),
            description: String::new(),
            url: url.map(Into::into),
            info_hash: None,
            file_idx: None,
            external_url: external.map(Into::into),
            yt_id: None,
            badges: Vec::new(),
            subtitles: Vec::new(),
            binge_group: None,
            trackers: Vec::new(),
        }
    }

    #[test]
    fn playable_sources_come_before_links_out() {
        let ranked = rank(vec![
            source("4k remux", None, Some("https://service.example/title")),
            source("480p", Some("https://cdn.example/a.mp4"), None),
            source("1080p", Some("https://cdn.example/b.mp4"), None),
        ]);
        let names: Vec<_> = ranked.iter().map(|s| s.name.as_str()).collect();
        assert_eq!(names, ["1080p", "480p", "4k remux"]);
    }

    #[test]
    fn finishing_needs_a_duration() {
        assert!(is_finished(93, 100));
        assert!(!is_finished(50, 100));
        assert!(!is_finished(10, 0));
    }
}
