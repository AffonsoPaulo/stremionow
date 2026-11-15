// SPDX-License-Identifier: GPL-3.0-or-later
//! Everything around watching: sources from stream addons, subtitles and playback progress.

use std::collections::HashSet;

use futures::future::join_all;
use stremionow_addons::InstalledAddon;
use stremionow_library::ProgressEntry;
use stremionow_protocol::{Stream, Subtitle};

use stremionow_ranking::{Preferences, Resolution, SourceText, badges, format_size, parse};

use crate::core::{StremioCore, now_millis};
use crate::records::{AddonInfo, CoreError};
use crate::settings::AppSettings;

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
    /// The manifest id of the addon that offered it, which survives reinstalling and
    /// reconfiguring the addon, to ask the same addon again (see `source_memory.rs`).
    pub addon_id: String,
    /// Which installed addon offered it: a digest of its address (see [`addon_key`]). Two
    /// installs of one addon, such as Torrentio with and without a debrid service, share the
    /// manifest id; this tells them apart.
    pub addon_key: String,
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
    /// Technical labels for the source picker, like `4K` or `HDR10`; the size is the last one.
    pub badges: Vec<String>,
    /// Resolution label, such as `4K` or `1080p`, when the text names one.
    pub quality: Option<String>,
    /// Seeders the addon reported, for torrents.
    pub seeders: Option<u32>,
    /// File size for display, such as `14.2 GB`, as in [`StreamSource::badges`].
    pub size: Option<String>,
    /// Group that made the release, from a scene-style file name.
    pub release_group: Option<String>,
    /// Subtitles that come with this source.
    pub subtitles: Vec<SubtitleTrack>,
    /// Sources of the same group can replace each other from one episode to the next.
    pub binge_group: Option<String>,
    /// Trackers and DHT hints for torrents, as the addon sent them.
    pub trackers: Vec<String>,
    /// What recognizes this source in a later search, to offer it again: the addon's id with
    /// the torrent's info hash and file, or with a digest of the address (debrid addresses
    /// carry the user's key, which is never kept). None for links that open outside the app.
    pub identity: Option<String>,
    /// ISO 639-1 codes of the languages the text mentions, audio or subtitles, in order.
    pub languages: Vec<String>,
    /// HDR formats, such as `DV` or `HDR10+`; empty when the text names none.
    pub hdr: Vec<String>,
    /// Audio formats, such as `Atmos` or `DD+`.
    pub audio: Vec<String>,
    /// Video codec, such as `HEVC`.
    pub codec: Option<String>,
    /// Kind of release, such as `WEB-DL` or `Remux`.
    pub release_quality: Option<String>,
    /// File size in bytes, when known, for sorting; [`StreamSource::size`] is for display.
    pub size_bytes: Option<u64>,
    /// Debrid cache state when the addon tells it: true when it plays at once, false when
    /// the service must download it first.
    pub cached: Option<bool>,
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

/// What [`StremioCore::source_ready`] found: whether the source is ready, whether it failed for
/// good, and the size of the file its address leads to, when the server tells (for the debug
/// log).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SourceCheck {
    pub ready: bool,
    pub size_bytes: Option<u64>,
    /// Waiting will not help: the address answers that there is no such file (an HTTP client
    /// error other than a timeout or too many requests), or it leads into the home network
    /// from an addon that is not there. Stop asking and show the source as failed.
    pub failed: bool,
}

/// The episode after the one playing, for "Continue watching" to move on to it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NextVideo {
    pub video_id: String,
    pub season: Option<u32>,
    pub episode: Option<u32>,
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

    /// Whether a source that was not in its debrid service's cache is ready to play: its
    /// address now leads to the file rather than to the addon's short "being downloaded" clip,
    /// told by size (see [`READY_MIN_BYTES`]). Asking also has the service start the download,
    /// as playing would. A source without an address, or whose size the server does not tell,
    /// counts as ready. Errors are worth asking again; a definite failure is in
    /// [`SourceCheck::failed`].
    pub async fn source_ready(&self, source: StreamSource) -> Result<SourceCheck, CoreError> {
        let Some(url) = source.url else {
            return Ok(SourceCheck {
                ready: true,
                size_bytes: None,
                failed: false,
            });
        };
        let private_allowed = self.private_addon(&source.addon_key);
        check_source(&self.client, &url, source.size_bytes, private_allowed).await
    }

    /// Orders sources best first for the user's preferences.
    pub fn rank_streams(&self, sources: Vec<StreamSource>) -> Vec<StreamSource> {
        let preferences = self
            .settings()
            .map(|settings| ranking_preferences(&settings))
            .unwrap_or_default();
        rank(sources, &preferences)
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
    ///
    /// Each time a video is watched to the end counts once in the title's times watched
    /// (Stremio's `timesWatched`): finishing a video that was in progress, or one not watched
    /// before. Reports that keep coming after the end do not count again.
    pub fn save_progress(&self, progress: WatchProgress) -> Result<(), CoreError> {
        self.save_progress_with_next(progress, None)
    }

    /// As [`StremioCore::save_progress`], and when the video is finished with a `next` one
    /// released, the title stays in "Continue watching" on the next video, at its start, as
    /// stremio-core's `advance_to_video` does: a time offset of 1 ms and the finished
    /// video's length, so the official apps list it there too.
    pub fn save_progress_with_next(
        &self,
        progress: WatchProgress,
        next: Option<NextVideo>,
    ) -> Result<(), CoreError> {
        let store = self.store()?;
        let now = now_millis();
        let same_video = store
            .progress(&progress.id)?
            .is_some_and(|saved| saved.video_id == progress.video_id);
        if is_finished(progress.position_ms, progress.duration_ms) {
            let watched_before = store
                .watched_videos(&progress.id)?
                .contains(&progress.video_id);
            if same_video || !watched_before {
                store.count_watch(&progress.id, now)?;
            } else {
                store.note_watching(&progress.id, now)?;
            }
            store.mark_watched(&progress.id, &progress.video_id, now)?;
            match next {
                Some(next) => store.save_progress(&ProgressEntry {
                    id: progress.id,
                    kind: progress.kind,
                    name: progress.name,
                    poster: progress.poster,
                    background: progress.background,
                    video_id: next.video_id,
                    season: next.season,
                    episode: next.episode,
                    position_ms: 1,
                    duration_ms: i64::try_from(progress.duration_ms).unwrap_or(i64::MAX),
                    updated_at: now,
                })?,
                None => store.remove_progress(&progress.id)?,
            }
            return Ok(());
        }
        if progress.position_ms < MIN_PROGRESS_MS && !same_video {
            return Ok(());
        }
        store.note_watching(&progress.id, now)?;
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

    /// Marks videos of title `id` as watched or not, as "Mark as watched" does: `video_ids`
    /// are episode ids, or the title's own id for a movie. A video marked watched while in
    /// progress leaves "Continue watching"; unmarking leaves the progress alone.
    ///
    /// As in Stremio, marking a movie watched counts one watch of it, and marking episodes
    /// does not. Unmarking a movie, or the last watched videos of a series, forgets its
    /// times watched, so it reads as not watched again.
    pub fn set_watched(
        &self,
        id: String,
        video_ids: Vec<String>,
        watched: bool,
    ) -> Result<(), CoreError> {
        self.set_watched_with_next(id, video_ids, watched, None)
    }

    /// As [`StremioCore::set_watched`], and when the video in "Continue watching" is among
    /// those marked watched, the title moves on to `next` there, at its start, instead of
    /// leaving, as stremio-core's `reconcile_series_resume_after_watched_change` does: `next` is
    /// the first released episode after it that is not watched, or none when nothing is left.
    pub fn set_watched_with_next(
        &self,
        id: String,
        video_ids: Vec<String>,
        watched: bool,
        next: Option<NextVideo>,
    ) -> Result<(), CoreError> {
        let store = self.store()?;
        let now = now_millis();
        let video_ids: Vec<String> = video_ids.into_iter().take(MAX_MARKED_VIDEOS).collect();
        let before: HashSet<String> = store.watched_videos(&id)?.into_iter().collect();
        if watched && video_ids.contains(&id) && !before.contains(&id) {
            store.count_watch(&id, now)?;
        }
        let unmarked_movie = !watched && video_ids.contains(&id);
        let unmarked_any = !watched && video_ids.iter().any(|video| before.contains(video));
        for video_id in &video_ids {
            if watched {
                store.mark_watched(&id, video_id, now)?;
            } else {
                store.unmark_watched(&id, video_id)?;
            }
        }
        if let Some(saved) = store
            .progress(&id)?
            .filter(|saved| watched && video_ids.contains(&saved.video_id))
        {
            // A next video that was just marked too is not one to continue with.
            match next.filter(|next| !video_ids.contains(&next.video_id)) {
                Some(next) => store.save_progress(&ProgressEntry {
                    video_id: next.video_id,
                    season: next.season,
                    episode: next.episode,
                    position_ms: 1,
                    updated_at: now,
                    ..saved
                })?,
                None => store.remove_progress(&id)?,
            }
        }
        if unmarked_movie || (unmarked_any && store.watched_videos(&id)?.is_empty()) {
            store.reset_times_watched(&id, now)?;
        }
        Ok(())
    }
}

/// Most videos marked at once; the longest-running series have a few thousand episodes.
const MAX_MARKED_VIDEOS: usize = 20_000;

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
        addon_id: addon.manifest.id.clone(),
        addon_key: addon_key(addon.url.manifest_url()),
        name: stream.name.unwrap_or_else(|| addon.manifest.name.clone()),
        description,
        url,
        info_hash,
        file_idx: stream.file_idx,
        external_url,
        yt_id,
        badges: Vec::new(),
        quality: None,
        seeders: None,
        size: None,
        release_group: None,
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
        identity: None,
        languages: Vec::new(),
        hdr: Vec::new(),
        audio: Vec::new(),
        codec: None,
        release_quality: None,
        size_bytes: None,
        cached: None,
    };
    source.identity = source_identity(&addon.manifest.id, &source);
    let traits = parse(&source_text(&source));
    source.badges = badges(&traits);
    source.quality = traits.resolution.map(|r| r.label().to_owned());
    source.seeders = traits.seeders;
    source.size_bytes = traits.size_bytes.or(stream.behavior_hints.video_size);
    source.size = source.size_bytes.map(format_size);
    source.hdr = labels(&traits.hdr, |hdr| hdr.label());
    source.audio = labels(&traits.audio, |audio| audio.label());
    source.codec = traits.codec.map(|codec| codec.label().to_owned());
    source.release_quality = traits.quality.map(|quality| quality.label().to_owned());
    source.cached = traits.cached;
    source.languages = traits.languages;
    source.release_group = traits.release_group.or_else(|| {
        let filename = stream.behavior_hints.filename.as_deref()?;
        parse(&SourceText {
            name: "",
            title: filename,
            description: "",
        })
        .release_group
    });
    Some(source)
}

fn labels<T: Copy>(values: &[T], label: impl Fn(T) -> &'static str) -> Vec<String> {
    values
        .iter()
        .map(|&value| label(value).to_owned())
        .collect()
}

/// Longest addon id kept in an identity; manifest ids are short (`com.stremio.torrentio`).
pub(crate) const MAX_ADDON_ID_LEN: usize = 200;

/// What recognizes a source the app plays in a later search: the addon's id with the info
/// hash and file of a torrent, or with a SHA-256 digest of a web address. The address itself
/// is never kept, since debrid addresses carry the user's key. None for links out.
///
/// A debrid address that names its torrent (`debrid|hash|file`) is told apart from the torrent
/// itself (`bt|hash|file`): addons offer both for the same file, and the one played over the
/// debrid service must not be found again as the torrent, which plays from peers.
/// A name for an installed addon that tells two installs of it apart and survives restarts:
/// the start of a SHA-256 digest of its manifest address. The address itself is never kept or
/// shown, since configured addons carry the user's keys in it, and a digest does not give it
/// back.
pub(crate) fn addon_key(manifest_url: &str) -> String {
    let digest = ring::digest::digest(&ring::digest::SHA256, manifest_url.as_bytes());
    digest.as_ref()[..ADDON_KEY_BYTES]
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// Bytes of the digest in [`addon_key`]: 128 bits, far more than addons anyone installs.
const ADDON_KEY_BYTES: usize = 16;

/// Size from which a source that was not in its debrid service's cache counts as ready. Until
/// then, debrid addons send a short clip saying the file is being downloaded, a few MB; an
/// episode or a film is hundreds of MB. A smaller file counts as ready too when it is at least
/// half the size the addon announced, which a clip never is.
const READY_MIN_BYTES: u64 = 64 * 1024 * 1024;

/// What [`StremioCore::source_ready`] answers for `url`; `expected` is the size the addon
/// announced, if any.
async fn check_source(
    client: &stremionow_addons::AddonClient,
    url: &str,
    expected: Option<u64>,
    private_allowed: bool,
) -> Result<SourceCheck, CoreError> {
    let failed = SourceCheck {
        ready: false,
        size_bytes: None,
        failed: true,
    };
    // The request below is made by the addon client, which does not vet addresses as the
    // relay does: an addon on the internet must not make the device probe its own network.
    if !private_allowed {
        let private = stremionow_torrent::reaches_private_network(url)
            .await
            .map_err(|error| CoreError::Playback {
                reason: format!("cannot resolve the source: {error}"),
            })?;
        if private {
            return Ok(failed);
        }
    }
    let size = match client.file_size(url).await {
        Ok(size) => size,
        // 408 and 429 ask to try again later; other client errors will not change.
        Err(stremionow_addons::AddonError::Status(status))
            if (400..500).contains(&status) && status != 408 && status != 429 =>
        {
            return Ok(failed);
        }
        Err(error) => return Err(error.into()),
    };
    let ready = size.is_none_or(|size| {
        size >= READY_MIN_BYTES || expected.is_some_and(|expected| size >= expected.div_ceil(2))
    });
    Ok(SourceCheck {
        ready,
        size_bytes: size,
        failed: false,
    })
}

fn source_identity(addon_id: &str, source: &StreamSource) -> Option<String> {
    let addon: String = addon_id
        .chars()
        .filter(|c| !c.is_control() && *c != '|')
        .take(MAX_ADDON_ID_LEN)
        .collect();
    if let Some(hash) = &source.info_hash {
        let file = source
            .file_idx
            .map(|idx| idx.to_string())
            .unwrap_or_default();
        let kind = if source.url.is_some() { "debrid" } else { "bt" };
        return Some(format!(
            "{addon}|{kind}|{}|{file}",
            hash.to_ascii_lowercase()
        ));
    }
    let url = source.url.as_deref()?;
    let digest = ring::digest::digest(&ring::digest::SHA256, url.as_bytes());
    let hex: String = digest.as_ref().iter().map(|b| format!("{b:02x}")).collect();
    Some(format!("{addon}|url|{hex}"))
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

/// The ranking preferences that follow from the user's settings.
fn ranking_preferences(settings: &AppSettings) -> Preferences {
    Preferences {
        max_resolution: match settings.max_resolution {
            2160 => Some(Resolution::P2160),
            1080 => Some(Resolution::P1080),
            720 => Some(Resolution::P720),
            _ => None,
        },
        preferred_languages: [&settings.audio_language]
            .into_iter()
            .filter(|language| !language.is_empty())
            .cloned()
            .collect(),
        ..Preferences::default()
    }
}

/// Orders sources best first for the given preferences.
fn rank(mut sources: Vec<StreamSource>, preferences: &Preferences) -> Vec<StreamSource> {
    stremionow_ranking::rank(&mut sources, preferences, source_text);
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
    fn marking_the_episode_in_progress_moves_continue_watching_on() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("opens");
        let next = |video: &str, episode| NextVideo {
            video_id: video.into(),
            season: Some(1),
            episode: Some(episode),
        };
        core.save_progress(progress("tt1:1:1", 300_000))
            .expect("saves");
        core.set_watched_with_next(
            "tt1".into(),
            vec!["tt1:1:1".into()],
            true,
            Some(next("tt1:1:2", 2)),
        )
        .expect("marks");
        let saved = core.progress("tt1".into()).expect("reads").expect("kept");
        assert_eq!(saved.video_id, "tt1:1:2");
        assert_eq!(saved.position_ms, 1);
        assert_eq!(saved.duration_ms, 1_000_000);

        // Marking another episode leaves it where it is.
        core.set_watched_with_next("tt1".into(), vec!["tt1:1:5".into()], true, None)
            .expect("marks");
        assert_eq!(
            core.progress("tt1".into())
                .expect("reads")
                .expect("kept")
                .video_id,
            "tt1:1:2"
        );

        // A next video marked in the same go, or none left, takes the title out.
        core.set_watched_with_next(
            "tt1".into(),
            vec!["tt1:1:2".into(), "tt1:1:3".into()],
            true,
            Some(next("tt1:1:3", 3)),
        )
        .expect("marks");
        assert!(core.progress("tt1".into()).expect("reads").is_none());
    }

    #[test]
    fn a_finished_episode_moves_continue_watching_to_the_next() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("opens");
        let next = NextVideo {
            video_id: "tt1:1:2".into(),
            season: Some(1),
            episode: Some(2),
        };
        core.save_progress_with_next(progress("tt1:1:1", 300_000), Some(next.clone()))
            .expect("saves");
        core.save_progress_with_next(progress("tt1:1:1", 960_000), Some(next.clone()))
            .expect("saves");
        let listed = core.continue_watching().expect("lists");
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].video_id, "tt1:1:2");
        assert_eq!(listed[0].episode, Some(2));
        assert_eq!(listed[0].position_ms, 1);
        assert_eq!(
            core.watched_videos("tt1".into()).expect("reads"),
            vec!["tt1:1:1".to_string()]
        );
        // Reports that keep coming from the end of the finished episode change nothing.
        core.save_progress_with_next(progress("tt1:1:1", 990_000), Some(next))
            .expect("saves");
        assert_eq!(
            core.continue_watching().expect("lists")[0].video_id,
            "tt1:1:2"
        );
        // The last episode leaves the row.
        core.save_progress(progress("tt1:1:2", 960_000))
            .expect("saves");
        assert!(core.continue_watching().expect("lists").is_empty());
    }

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

    #[test]
    fn marks_and_unmarks_videos() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        core.save_progress(progress("tt1:1:2", 300_000))
            .expect("saves");

        core.set_watched("tt1".into(), vec!["tt1:1:1".into()], true)
            .expect("marks");
        assert!(
            core.progress("tt1".into()).expect("reads").is_some(),
            "another episode keeps its progress"
        );
        core.set_watched("tt1".into(), vec!["tt1:1:2".into(), "tt1:1:3".into()], true)
            .expect("marks");
        assert_eq!(
            core.watched_videos("tt1".into()).expect("reads"),
            ["tt1:1:1", "tt1:1:2", "tt1:1:3"]
        );
        assert!(
            core.progress("tt1".into()).expect("reads").is_none(),
            "the episode in progress is done"
        );

        core.set_watched(
            "tt1".into(),
            vec!["tt1:1:1".into(), "tt1:1:3".into()],
            false,
        )
        .expect("unmarks");
        assert_eq!(
            core.watched_videos("tt1".into()).expect("reads"),
            ["tt1:1:2"]
        );
    }

    fn times_watched(core: &StremioCore, id: &str) -> u32 {
        core.store()
            .expect("store")
            .watch_stats(id)
            .expect("reads")
            .map_or(0, |stats| stats.times_watched)
    }

    #[test]
    fn finishing_a_video_counts_one_watch() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");

        core.save_progress(progress("tt1:1:1", 300_000))
            .expect("saves");
        assert_eq!(times_watched(&core, "tt1"), 0, "playing is not a watch");
        let last = core
            .store()
            .expect("store")
            .watch_stats("tt1")
            .expect("reads")
            .and_then(|stats| stats.last_watched);
        assert!(last.is_some(), "playing sets the last watch");

        core.save_progress(progress("tt1:1:1", 950_000))
            .expect("finishes");
        core.save_progress(progress("tt1:1:1", 990_000))
            .expect("reports after the end");
        assert_eq!(times_watched(&core, "tt1"), 1, "the end counts once");

        // Watching it again from the start counts again.
        core.save_progress(progress("tt1:1:1", 60_000))
            .expect("rewatches");
        core.save_progress(progress("tt1:1:1", 960_000))
            .expect("finishes again");
        assert_eq!(times_watched(&core, "tt1"), 2);

        // Jumping straight to the end of a new episode counts too.
        core.save_progress(progress("tt1:1:2", 960_000))
            .expect("finishes");
        assert_eq!(times_watched(&core, "tt1"), 3);
    }

    #[test]
    fn marking_counts_movies_and_unmarking_forgets() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");

        core.set_watched("tt9".into(), vec!["tt9".into()], true)
            .expect("marks a movie");
        core.set_watched("tt9".into(), vec!["tt9".into()], true)
            .expect("marks it again");
        assert_eq!(times_watched(&core, "tt9"), 1, "an already watched movie");
        core.set_watched("tt9".into(), vec!["tt9".into()], false)
            .expect("unmarks");
        assert_eq!(times_watched(&core, "tt9"), 0);

        // Episodes marked by hand are watched without counting as watches, as in Stremio.
        core.save_progress(progress("tt1:1:1", 960_000))
            .expect("finishes");
        core.set_watched("tt1".into(), vec!["tt1:1:2".into(), "tt1:1:3".into()], true)
            .expect("marks episodes");
        assert_eq!(times_watched(&core, "tt1"), 1);
        core.set_watched("tt1".into(), vec!["tt1:1:1".into()], false)
            .expect("unmarks one");
        assert_eq!(times_watched(&core, "tt1"), 1, "others are still watched");
        core.set_watched(
            "tt1".into(),
            vec!["tt1:1:2".into(), "tt1:1:3".into()],
            false,
        )
        .expect("unmarks the rest");
        assert_eq!(times_watched(&core, "tt1"), 0);
    }

    fn source(name: &str, url: Option<&str>, external: Option<&str>) -> StreamSource {
        StreamSource {
            addon_name: "A".into(),
            addon_id: "a".into(),
            addon_key: "k".into(),
            name: name.into(),
            description: String::new(),
            url: url.map(Into::into),
            info_hash: None,
            file_idx: None,
            external_url: external.map(Into::into),
            yt_id: None,
            badges: Vec::new(),
            quality: None,
            seeders: None,
            size: None,
            release_group: None,
            subtitles: Vec::new(),
            binge_group: None,
            trackers: Vec::new(),
            identity: None,
            languages: Vec::new(),
            hdr: Vec::new(),
            audio: Vec::new(),
            codec: None,
            release_quality: None,
            size_bytes: None,
            cached: None,
        }
    }

    fn addon() -> InstalledAddon {
        InstalledAddon {
            url: stremionow_protocol::AddonUrl::parse("https://addon.example/manifest.json")
                .expect("valid url"),
            manifest: serde_json::from_value(serde_json::json!({
                "id": "a", "version": "1.0.0", "name": "Addon", "resources": ["stream"]
            }))
            .expect("valid manifest"),
        }
    }

    #[test]
    fn sources_carry_their_parsed_details() {
        let stream: Stream = serde_json::from_value(serde_json::json!({
            "name": "Addon\n4k",
            "title": "Movie.2023.2160p.WEB-DL.DV.H265-GRP\n👤 154 💾 14.2 GB",
            "infoHash": "0123456789abcdef0123456789abcdef01234567"
        }))
        .expect("valid stream");
        let source = stream_source(&addon(), stream).expect("usable");
        assert_eq!(source.quality.as_deref(), Some("4K"));
        assert_eq!(source.seeders, Some(154));
        assert_eq!(source.size.as_deref(), Some("14.2 GB"));
        assert_eq!(source.release_group.as_deref(), Some("GRP"));
        assert_eq!(source.badges.last(), source.size.as_ref());
        assert_eq!(source.hdr, ["DV"]);
        assert_eq!(source.codec.as_deref(), Some("HEVC"));
        assert_eq!(source.release_quality.as_deref(), Some("WEB-DL"));
        assert!(source.size_bytes.is_some());
        assert_eq!(
            source.identity.as_deref(),
            Some("a|bt|0123456789abcdef0123456789abcdef01234567|")
        );

        // A web source tells its size and file name in its hints instead.
        let stream: Stream = serde_json::from_value(serde_json::json!({
            "url": "https://cdn.example/v.mkv",
            "behaviorHints": {"videoSize": 1_073_741_824u64, "filename": "Movie.2023.1080p.WEB.x264-OTHER.mkv"}
        }))
        .expect("valid stream");
        let source = stream_source(&addon(), stream).expect("usable");
        assert_eq!(source.size.as_deref(), Some("1.0 GB"));
        assert_eq!(source.release_group.as_deref(), Some("OTHER"));
        assert_eq!(source.seeders, None);
        assert_eq!(source.size_bytes, Some(1_073_741_824));
    }

    fn source_from(value: serde_json::Value) -> StreamSource {
        stream_source(
            &addon(),
            serde_json::from_value(value).expect("valid stream"),
        )
        .expect("usable")
    }

    #[test]
    fn installs_of_one_addon_have_their_own_key() {
        let debrid = addon_key("https://torrentio.strem.fun/torbox=SECRETKEY/manifest.json");
        let plain = addon_key("https://torrentio.strem.fun/manifest.json");
        assert_ne!(debrid, plain);
        assert_eq!(
            debrid,
            addon_key("https://torrentio.strem.fun/torbox=SECRETKEY/manifest.json")
        );
        assert_eq!(debrid.len(), ADDON_KEY_BYTES * 2);
        assert!(debrid.chars().all(|c| c.is_ascii_hexdigit()));
        assert!(!debrid.contains("SECRETKEY"));
    }

    #[tokio::test]
    async fn a_source_is_ready_once_it_leads_to_more_than_a_clip() {
        use wiremock::matchers::{header, method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/clip"))
            .and(header("range", "bytes=0-0"))
            .respond_with(
                ResponseTemplate::new(206).insert_header("content-range", "bytes 0-0/4200000"),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/episode"))
            .respond_with(
                ResponseTemplate::new(206).insert_header("content-range", "bytes 0-0/734003200"),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/short"))
            .respond_with(
                ResponseTemplate::new(206).insert_header("content-range", "bytes 0-0/30000000"),
            )
            .mount(&server)
            .await;
        for (route, status) in [("/gone", 404), ("/busy", 429), ("/broken", 503)] {
            Mock::given(method("GET"))
                .and(path(route))
                .respond_with(ResponseTemplate::new(status))
                .mount(&server)
                .await;
        }
        let client =
            stremionow_addons::AddonClient::new(std::time::Duration::from_secs(5)).expect("client");
        // The mock server is on loopback, as an addon on the home network would be.
        let check = |route: &str, expected: Option<u64>| {
            let url = format!("{}{route}", server.uri());
            let client = client.clone();
            async move { check_source(&client, &url, expected, true).await }
        };
        let clip = check("/clip", None).await.expect("asks");
        assert!(!clip.ready && !clip.failed);
        assert_eq!(clip.size_bytes, Some(4_200_000));
        // A clip stays one even when the addon announced a film.
        assert!(!check("/clip", Some(734_003_200)).await.expect("asks").ready);
        assert!(check("/episode", None).await.expect("asks").ready);
        // A real file under the size of a typical episode is ready once it is about as large
        // as announced; without an announced size it cannot be told from a clip.
        assert!(check("/short", Some(31_000_000)).await.expect("asks").ready);
        assert!(
            !check("/short", Some(200_000_000))
                .await
                .expect("asks")
                .ready
        );
        assert!(!check("/short", None).await.expect("asks").ready);
        // A missing file fails for good; being busy or broken may pass.
        let gone = check("/gone", None).await.expect("asks");
        assert!(gone.failed && !gone.ready);
        assert!(check("/busy", None).await.is_err());
        assert!(check("/broken", None).await.is_err());

        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        let no_address = core.source_ready(source("A", None, None)).await;
        let no_address = no_address.expect("no address");
        assert!(no_address.ready && !no_address.failed);
        // The source's addon is not installed on the home network, so the address there is
        // refused without asking it.
        let into_network = StreamSource {
            url: Some(format!("{}/episode", server.uri())),
            ..source("A", None, None)
        };
        let refused = core.source_ready(into_network).await.expect("asks");
        assert!(refused.failed && !refused.ready);
        assert_eq!(
            server
                .received_requests()
                .await
                .expect("recorded")
                .iter()
                .filter(|request| request.url.path() == "/episode")
                .count(),
            1,
            "only the check with permission reached the server"
        );
    }

    #[test]
    fn identities_never_carry_the_address() {
        let debrid = "https://debrid.example/resolve/SECRETKEY/0123/1/Movie.mkv";
        let link = source_from(serde_json::json!({ "url": debrid, "name": "[RD+] Addon" }));
        let identity = link.identity.expect("a link the app plays has one");
        assert!(identity.starts_with("a|url|"), "{identity}");
        assert_eq!(identity.len(), "a|url|".len() + 64);
        assert!(!identity.contains("SECRETKEY") && !identity.contains("debrid.example"));
        assert_eq!(link.cached, Some(true));
        // The same address gives the same identity; another one, another.
        let again = source_from(serde_json::json!({ "url": debrid }));
        assert_eq!(again.identity.as_deref(), Some(identity.as_str()));
        let other = source_from(serde_json::json!({ "url": "https://debrid.example/other.mkv" }));
        assert_ne!(other.identity.as_deref(), Some(identity.as_str()));

        let torrent = source_from(serde_json::json!({
            "infoHash": "0123456789ABCDEF0123456789ABCDEF01234567", "fileIdx": 3
        }));
        assert_eq!(
            torrent.identity.as_deref(),
            Some("a|bt|0123456789abcdef0123456789abcdef01234567|3")
        );
        // The same file over a debrid service is another source than its torrent.
        let debrid_torrent = source_from(serde_json::json!({
            "url": debrid, "infoHash": "0123456789ABCDEF0123456789ABCDEF01234567", "fileIdx": 3
        }));
        assert_eq!(
            debrid_torrent.identity.as_deref(),
            Some("a|debrid|0123456789abcdef0123456789abcdef01234567|3")
        );
        let outside =
            source_from(serde_json::json!({ "externalUrl": "https://service.example/t" }));
        assert_eq!(outside.identity, None);
    }

    #[test]
    fn sources_tell_their_languages_and_formats() {
        let source = source_from(serde_json::json!({
            "name": "Addon\n4k",
            "title": "Hokum.2026.2160p.BDRemux.HDR10.DTS-HD.MA.Eng.Sub.Ita",
            "infoHash": "0123456789abcdef0123456789abcdef01234567"
        }));
        assert_eq!(source.languages, ["en", "it"]);
        assert_eq!(source.hdr, ["HDR10"]);
        assert_eq!(source.audio, ["DTS-HD MA"]);
        assert_eq!(source.release_quality.as_deref(), Some("Remux"));
        assert_eq!(source.cached, None);
    }

    #[test]
    fn playable_sources_come_before_links_out() {
        let ranked = rank(
            vec![
                source("4k remux", None, Some("https://service.example/title")),
                source("480p", Some("https://cdn.example/a.mp4"), None),
                source("1080p", Some("https://cdn.example/b.mp4"), None),
            ],
            &Preferences::default(),
        );
        let names: Vec<_> = ranked.iter().map(|s| s.name.as_str()).collect();
        assert_eq!(names, ["1080p", "480p", "4k remux"]);
    }

    #[test]
    fn settings_limit_resolution_and_prefer_language() {
        let preferences = ranking_preferences(&AppSettings {
            max_resolution: 1080,
            audio_language: "pt".into(),
            ..AppSettings::default()
        });
        let ranked = rank(
            vec![
                source("4k", Some("https://cdn.example/4k.mkv"), None),
                source("1080p", Some("https://cdn.example/hd.mkv"), None),
            ],
            &preferences,
        );
        assert_eq!(ranked[0].name, "1080p", "sources above the limit sink");
        assert_eq!(preferences.preferred_languages, ["pt"]);
    }

    #[test]
    fn finishing_needs_a_duration() {
        assert!(is_finished(93, 100));
        assert!(!is_finished(50, 100));
        assert!(!is_finished(10, 0));
    }
}
