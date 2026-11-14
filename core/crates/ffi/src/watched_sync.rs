// SPDX-License-Identifier: GPL-3.0-or-later
//! Watched videos synced with the account, in the official apps' format (ADR 0012).
//!
//! Marks live in the local `watched` table as before; a library sync combines each title's
//! marks with the account's:
//!
//! - movies with the item's `state.timesWatched` (watched when above 0), which the official
//!   apps' "Mark as watched" raises by one and "Mark as unwatched" sets to 0;
//! - other titles with `state.watched`, the bitfield of watched videos, which needs the title's
//!   video list, read from its metadata through the response cache.
//!
//! Both are three-way merges against what the account had at the last sync, kept in the store
//! ([`stremionow_library::WatchedSync`]), so a change on either side reaches the other and
//! nothing conflicts (see [`stremionow_sync::merge_watched`]). The rest of each item follows
//! [`stremionow_sync::merge_library`] as before: the newer one wins.

use std::collections::{HashMap, HashSet};

use futures::stream::{self, StreamExt};
use stremionow_library::{Store, WatchedSync};
use stremionow_protocol::MetaDetail;
use stremionow_sync::{
    RemoteLibraryItem, WatchedVideo, format_timestamp, merge_mark, merge_watched, parse_timestamp,
    watched_order,
};

use crate::account::SKIPPED_KIND;
use crate::core::{StremioCore, now_millis};
use crate::records::CoreError;

/// Titles of this type keep their watched state in `timesWatched`.
const MOVIE: &str = "movie";
/// Kind guessed for a title known only from its watched episodes.
const SERIES: &str = "series";
/// Metadata requests at the same time while syncing.
const META_CONCURRENCY: usize = 4;
/// Most titles whose metadata one sync reads; the others wait for the next sync.
const MAX_METAS_PER_SYNC: usize = 300;

/// Watched marks brought in line with the account during a sync.
pub(crate) struct WatchedPlan {
    /// What each title has on the account once the upload is done; save it only then.
    pub(crate) synced: Vec<(String, WatchedSync)>,
    /// Titles whose local marks changed to follow the account.
    pub(crate) downloaded: HashSet<String>,
}

impl WatchedPlan {
    /// Notes what `title` has on the account after this sync, when that changed.
    fn record(&mut self, title: &Title<'_>, synced: WatchedSync) {
        if synced != title.synced {
            self.synced.push((title.id.clone(), synced));
        }
    }

    /// Records what the account now has, after a successful upload.
    pub(crate) fn save(self, store: &mut Store) -> Result<(), CoreError> {
        for (id, synced) in &self.synced {
            store.save_watched_sync(id, synced)?;
        }
        Ok(())
    }
}

/// One title's marks on both sides.
struct Title<'a> {
    id: String,
    kind: String,
    /// The account's copy, if it has one.
    remote: Option<&'a RemoteLibraryItem>,
    /// The merged item, when the title is in the library on either side.
    merged: Option<&'a RemoteLibraryItem>,
    local: HashSet<String>,
    synced: WatchedSync,
}

impl Title<'_> {
    fn base(&self) -> HashSet<String> {
        self.synced.videos.iter().cloned().collect()
    }

    /// The account's `state.watched`, empty strings read as none (as stremio-core does).
    fn remote_field(&self) -> Option<&str> {
        self.remote
            .and_then(|item| item.state.watched.as_deref())
            .filter(|field| !field.is_empty())
    }

    /// When marks brought from the account count as watched: its last watch, else its last
    /// change.
    fn remote_time(&self) -> i64 {
        self.remote
            .and_then(|item| {
                item.state
                    .last_watched
                    .as_deref()
                    .and_then(parse_timestamp)
                    .or_else(|| item.mtime_ms())
            })
            .unwrap_or_else(now_millis)
    }
}

impl StremioCore {
    /// Combines every title's watched marks with the account's: changes the local marks that
    /// the account changed, and adds to `upload` (or edits there) the items whose watched
    /// state must change on the account. `merged` and `upload` come from
    /// [`stremionow_sync::merge_library`], without the items that are never uploaded.
    pub(crate) async fn reconcile_watched(
        &self,
        remote: &[RemoteLibraryItem],
        merged: &[RemoteLibraryItem],
        upload: &mut Vec<RemoteLibraryItem>,
    ) -> Result<WatchedPlan, CoreError> {
        let titles = self.watched_titles_to_sync(remote, merged)?;
        let mut plan = WatchedPlan {
            synced: Vec::new(),
            downloaded: HashSet::new(),
        };
        let now = now_millis();

        // Movies need no metadata, unless the account has never seen the title: its item is
        // made from the metadata's name and poster.
        let mut needs_meta = Vec::new();
        for (index, title) in titles.iter().enumerate() {
            if title.kind == MOVIE {
                if title.merged.is_none() && movie_result(title) {
                    needs_meta.push(index);
                } else {
                    self.sync_movie(title, None, upload, &mut plan, now)?;
                }
                continue;
            }
            let remote_changed = title.remote_field() != title.synced.remote.as_deref();
            let local_changed = title.local != title.base();
            if remote_changed || local_changed {
                needs_meta.push(index);
            }
        }

        // Titles changed here first, then the latest changes on the account.
        needs_meta.sort_by_key(|&index| {
            let title = &titles[index];
            let local_changed = title.local != title.base();
            let mtime = title.remote.and_then(|item| item.mtime_ms()).unwrap_or(0);
            (!local_changed, std::cmp::Reverse(mtime))
        });
        needs_meta.truncate(MAX_METAS_PER_SYNC);
        let metas: Vec<(usize, Option<MetaDetail>)> = stream::iter(needs_meta)
            .map(|index| {
                let title = &titles[index];
                async move {
                    let meta = self.meta_detail(&title.kind, &title.id).await;
                    if let Err(error) = &meta {
                        log::debug!(
                            "no metadata to sync the watched videos of {}: {error}",
                            title.id
                        );
                    }
                    (index, meta.ok())
                }
            })
            .buffer_unordered(META_CONCURRENCY)
            .collect()
            .await;

        for (index, meta) in metas {
            // Without metadata the title waits for the next sync; its marks stay as they are.
            let Some(meta) = meta else { continue };
            let title = &titles[index];
            if title.kind == MOVIE {
                self.sync_movie(title, Some(&meta), upload, &mut plan, now)?;
            } else {
                self.sync_videos(title, &meta, upload, &mut plan, now)?;
            }
        }
        Ok(plan)
    }

    /// Every title whose marks may need syncing: those in the merged library and those with
    /// local marks only.
    fn watched_titles_to_sync<'a>(
        &self,
        remote: &'a [RemoteLibraryItem],
        merged: &'a [RemoteLibraryItem],
    ) -> Result<Vec<Title<'a>>, CoreError> {
        let store = self.store()?;
        let remote_by_id: HashMap<&str, &RemoteLibraryItem> =
            remote.iter().map(|item| (item.id.as_str(), item)).collect();
        let mut titles = Vec::new();
        let mut known = HashSet::new();
        for item in merged {
            known.insert(item.id.as_str());
            if item.kind == SKIPPED_KIND {
                continue;
            }
            titles.push(Title {
                id: item.id.clone(),
                kind: item.kind.clone(),
                remote: remote_by_id.get(item.id.as_str()).copied(),
                merged: Some(item),
                local: store.watched_videos(&item.id)?.into_iter().collect(),
                synced: store.watched_sync(&item.id)?,
            });
        }
        for marked in store.watched_titles(usize::MAX)? {
            if known.contains(marked.id.as_str()) {
                continue;
            }
            let local: HashSet<String> = store.watched_videos(&marked.id)?.into_iter().collect();
            // Marks name the movie itself, or the episodes of a series.
            let kind = if local.contains(&marked.id) {
                MOVIE
            } else {
                SERIES
            };
            titles.push(Title {
                kind: kind.to_owned(),
                remote: None,
                merged: None,
                local,
                synced: store.watched_sync(&marked.id)?,
                id: marked.id,
            });
        }
        Ok(titles)
    }

    /// A movie: watched here (its own id is marked) against `timesWatched` on the account.
    fn sync_movie(
        &self,
        title: &Title<'_>,
        meta: Option<&MetaDetail>,
        upload: &mut Vec<RemoteLibraryItem>,
        plan: &mut WatchedPlan,
        now: i64,
    ) -> Result<(), CoreError> {
        let watched = movie_result(title);
        let account = title.remote.map(|item| item.state.times_watched > 0);
        if account.unwrap_or(false) != watched {
            let Some(item) = target(upload, title, meta, now) else {
                return Ok(());
            };
            if watched {
                // As stremio-core's `LibraryItem::mark_as_watched`, which adds one watch. The
                // watch statistics already counted this one when it was marked here (and
                // wrote the count into the item), so only make sure the item reads watched.
                item.state.times_watched = item.state.times_watched.max(1);
                item.state.last_watched = Some(format_timestamp(now));
                item.state.time_offset = 0;
            } else {
                item.state.times_watched = 0;
            }
        }
        if watched != title.local.contains(&title.id) {
            let store = self.store()?;
            if watched {
                store.mark_watched(&title.id, &title.id, title.remote_time())?;
            } else {
                store.unmark_watched(&title.id, &title.id)?;
            }
            plan.downloaded.insert(title.id.clone());
        }
        let mut videos: Vec<String> = title
            .local
            .iter()
            .filter(|video| **video != title.id)
            .cloned()
            .collect();
        if watched {
            videos.push(title.id.clone());
        }
        videos.sort();
        plan.record(
            title,
            WatchedSync {
                remote: title.synced.remote.clone(),
                videos,
            },
        );
        Ok(())
    }

    /// A series or other title with videos: the local marks against the account's bitfield.
    fn sync_videos(
        &self,
        title: &Title<'_>,
        meta: &MetaDetail,
        upload: &mut Vec<RemoteLibraryItem>,
        plan: &mut WatchedPlan,
        now: i64,
    ) -> Result<(), CoreError> {
        let videos: Vec<WatchedVideo<'_>> = meta
            .videos
            .iter()
            .map(|video| WatchedVideo {
                id: &video.id,
                season: video.season,
                episode: video.episode,
                released_ms: video.released.as_deref().and_then(parse_timestamp),
            })
            .collect();
        let order = watched_order(&videos);
        if order.is_empty() {
            return Ok(());
        }
        let remote_field = title.remote_field();
        let merge = merge_watched(&order, &title.base(), &title.local, remote_field);
        if merge.remote_unreadable {
            log::warn!(
                "ignored the account's watched videos of {}: they could not be read",
                title.id
            );
        }

        let listed: HashSet<&str> = order.iter().map(String::as_str).collect();
        let mut now_watched: Vec<String> = title
            .local
            .iter()
            .filter(|video| !listed.contains(video.as_str()))
            .cloned()
            .collect();
        {
            let store = self.store()?;
            let at = title.remote_time();
            let mut changed = false;
            for (video, &watched) in order.iter().zip(&merge.watched) {
                let was = title.local.contains(video);
                if watched && !was {
                    store.mark_watched(&title.id, video, at)?;
                    changed = true;
                } else if !watched && was {
                    store.unmark_watched(&title.id, video)?;
                    changed = true;
                }
                if watched {
                    now_watched.push(video.clone());
                }
            }
            if changed {
                plan.downloaded.insert(title.id.clone());
            }
        }

        let mut account = remote_field.map(str::to_owned);
        if let Some(field) = merge.upload {
            let Some(item) = target(upload, title, Some(meta), now) else {
                return Ok(());
            };
            item.state.watched = Some(field.clone());
            account = Some(field);
        }
        now_watched.sort();
        plan.record(
            title,
            WatchedSync {
                remote: account,
                videos: now_watched,
            },
        );
        Ok(())
    }
}

/// Whether a movie is watched after merging this device, the account and the last sync.
fn movie_result(title: &Title<'_>) -> bool {
    let base = title.synced.videos.contains(&title.id);
    let local = title.local.contains(&title.id);
    // Without an account copy, the account counts as unchanged.
    let remote = title
        .remote
        .map_or(base, |item| item.state.times_watched > 0);
    merge_mark(base, local, remote)
}

/// The item to change on the account for `title`: the one already being uploaded, else a copy
/// of the merged one marked as changed now, else a new temporary item made from the metadata,
/// as the official apps make one for a title watched without adding it to the library.
fn target<'u>(
    upload: &'u mut Vec<RemoteLibraryItem>,
    title: &Title<'_>,
    meta: Option<&MetaDetail>,
    now: i64,
) -> Option<&'u mut RemoteLibraryItem> {
    if let Some(index) = upload.iter().position(|item| item.id == title.id) {
        return upload.get_mut(index);
    }
    let item = match (title.merged, meta) {
        (Some(merged), _) => {
            let mut item = merged.clone();
            item.touch(now);
            item
        }
        (None, Some(meta)) => {
            let preview = &meta.preview;
            let mut item = RemoteLibraryItem::new(
                &title.id,
                &preview.kind,
                &preview.name,
                preview.poster.clone(),
                now,
            );
            item.removed = true;
            item.temp = true;
            item
        }
        (None, None) => return None,
    };
    upload.push(item);
    upload.last_mut()
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use serde_json::{Value, json};
    use stremionow_library::WatchedSync;
    use stremionow_sync::{decode_watched, encode_watched};
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use crate::account::Account;
    use crate::core::StremioCore;
    use crate::playback::WatchProgress;

    const CREATED: &str = "2026-01-01T00:00:00.000Z";
    const OLD: &str = "2026-01-02T00:00:00.000Z";
    const NEWER: &str = "2026-01-03T00:00:00.000Z";

    fn episodes(title: &str) -> Vec<String> {
        (1..=4)
            .map(|episode| format!("{title}:1:{episode}"))
            .collect()
    }

    fn field(title: &str, bits: &[bool]) -> String {
        encode_watched(bits, &episodes(title))
    }

    fn bits(item: &Value, title: &str) -> Vec<bool> {
        let text = item["state"]["watched"].as_str().expect("a watched field");
        decode_watched(text, &episodes(title)).expect("readable in the official format")
    }

    fn series(id: &str, watched: Value, mtime: &str) -> Value {
        json!({
            "_id": id, "name": "Remote show", "type": "series", "removed": false, "temp": false,
            "_ctime": CREATED, "_mtime": mtime,
            "state": {"watched": watched, "timesWatched": 2, "lastWatched": mtime},
            "futureField": 7
        })
    }

    fn movie(id: &str, times_watched: u32) -> Value {
        json!({
            "_id": id, "name": "Remote movie", "type": "movie", "removed": false, "temp": false,
            "_ctime": CREATED, "_mtime": OLD, "state": {"timesWatched": times_watched}
        })
    }

    /// A series meta whose episodes are listed out of order: the bits follow the official
    /// order, not the addon's.
    fn series_meta(id: &str) -> Value {
        let videos: Vec<Value> = [4, 2, 1, 3]
            .iter()
            .map(|episode| {
                json!({
                    "id": format!("{id}:1:{episode}"), "season": 1, "episode": episode,
                    "released": format!("2020-01-{:02}T00:00:00.000Z", episode * 7)
                })
            })
            .collect();
        json!({"meta": {
            "id": id, "type": "series", "name": format!("Show {id}"),
            "poster": "https://img.example/poster.jpg", "videos": videos
        }})
    }

    fn ok() -> ResponseTemplate {
        ResponseTemplate::new(200).set_body_json(json!({"result": {"success": true}}))
    }

    fn library(items: Vec<Value>) -> ResponseTemplate {
        ResponseTemplate::new(200).set_body_json(json!({ "result": items }))
    }

    /// The account, with `items` in its library and one addon, served by the same fake
    /// server, that has the metadata of the series `tt1` and `tt2` and the movie `tt9`.
    async fn account(items: Vec<Value>) -> MockServer {
        let server = MockServer::start().await;
        let manifest = json!({
            "id": "test.meta", "version": "1.0.0", "name": "Meta",
            "resources": [{"name": "meta", "types": ["movie", "series"], "idPrefixes": ["tt"]}],
            "types": ["movie", "series"], "catalogs": []
        });
        Mock::given(method("POST"))
            .and(path("/addonCollectionGet"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "result": {"addons": [{
                    "transportUrl": format!("{}/manifest.json", server.uri()),
                    "manifest": manifest, "flags": {}
                }]}
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/datastoreGet"))
            .respond_with(library(items))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        for id in ["tt1", "tt2"] {
            Mock::given(method("GET"))
                .and(path(format!("/meta/series/{id}.json")))
                .respond_with(ResponseTemplate::new(200).set_body_json(series_meta(id)))
                .mount(&server)
                .await;
        }
        Mock::given(method("GET"))
            .and(path("/meta/movie/tt9.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "meta": {"id": "tt9", "type": "movie", "name": "A movie"}
            })))
            .mount(&server)
            .await;
        server
    }

    /// From now on the account's library holds `items`.
    async fn account_now_has(server: &MockServer, items: Vec<Value>) {
        Mock::given(method("POST"))
            .and(path("/datastoreGet"))
            .respond_with(library(items))
            .with_priority(1)
            .mount(server)
            .await;
    }

    fn core_for(server: &MockServer, dir: &tempfile::TempDir) -> Arc<StremioCore> {
        let core = StremioCore::open(
            dir.path().to_string_lossy().into_owned(),
            Account::with_base_url(&server.uri()).expect("account"),
        )
        .expect("core");
        core.restore_session("secret-key".into());
        core
    }

    /// Every item sent to the account so far, in order.
    async fn uploaded(server: &MockServer) -> Vec<Value> {
        server
            .received_requests()
            .await
            .expect("recorded")
            .iter()
            .filter(|request| request.url.path() == "/datastorePut")
            .flat_map(|request| {
                let body: Value = serde_json::from_slice(&request.body).expect("json");
                body["changes"].as_array().cloned().unwrap_or_default()
            })
            .collect()
    }

    fn sent<'a>(items: &'a [Value], id: &str) -> &'a Value {
        items
            .iter()
            .rev()
            .find(|item| item["_id"] == id)
            .unwrap_or_else(|| panic!("{id} was not uploaded"))
    }

    async fn meta_requests(server: &MockServer) -> usize {
        server
            .received_requests()
            .await
            .expect("recorded")
            .iter()
            .filter(|request| request.url.path().starts_with("/meta/"))
            .count()
    }

    fn watched(core: &StremioCore, id: &str) -> Vec<String> {
        core.watched_videos(id.into()).expect("reads")
    }

    fn finished_or_at(video_id: &str, position_ms: u64) -> WatchProgress {
        WatchProgress {
            id: "tt1".into(),
            kind: "series".into(),
            name: "Remote show".into(),
            poster: None,
            background: None,
            video_id: video_id.into(),
            season: Some(1),
            episode: None,
            position_ms,
            duration_ms: 3_000_000,
            updated_at: 0,
        }
    }

    #[tokio::test]
    async fn brings_the_accounts_watched_episodes_and_movies() {
        let remote = field("tt1", &[true, true, false, false]);
        let server = account(vec![series("tt1", json!(remote), OLD), movie("tt9", 1)]).await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);

        let summary = core.sync().await.expect("syncs");
        assert_eq!(watched(&core, "tt1"), ["tt1:1:1", "tt1:1:2"]);
        assert_eq!(watched(&core, "tt9"), ["tt9"]);
        assert_eq!(summary.downloaded, 2);
        assert!(uploaded(&server).await.is_empty(), "the account has them");

        core.sync().await.expect("syncs again");
        assert_eq!(
            meta_requests(&server).await,
            1,
            "metadata is read again only when the marks changed"
        );
        assert!(uploaded(&server).await.is_empty());

        core.sign_out().await.expect("signs out");
        assert_eq!(watched(&core, "tt1"), ["tt1:1:1", "tt1:1:2"], "marks stay");
        assert_eq!(
            core.store()
                .expect("store")
                .watched_sync("tt1")
                .expect("reads"),
            WatchedSync::default(),
            "the next account starts afresh"
        );
    }

    #[tokio::test]
    async fn sends_marks_made_here_in_the_official_format() {
        let remote = field("tt1", &[true, false, false, false]);
        let server = account(vec![series("tt1", json!(remote), OLD), movie("tt9", 3)]).await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.sync().await.expect("syncs");

        core.set_watched("tt1".into(), vec!["tt1:1:3".into()], true)
            .expect("marks");
        core.set_watched("tt9".into(), vec!["tt9".into()], false)
            .expect("unmarks");
        // A series the account has never seen, watched without adding it to the list.
        core.set_watched("tt2".into(), vec!["tt2:1:2".into()], true)
            .expect("marks");
        // Playing an episode to the end marks it too.
        core.save_progress(finished_or_at("tt1:1:4", 2_900_000))
            .expect("saves");
        let summary = core.sync().await.expect("syncs");
        assert_eq!(summary.uploaded, 3);

        let changes = uploaded(&server).await;
        let show = sent(&changes, "tt1");
        assert_eq!(bits(show, "tt1"), [true, false, true, true]);
        assert!(
            show["state"]["watched"]
                .as_str()
                .is_some_and(|text| text.starts_with("tt1:1:4:4:")),
            "anchored on the last watched episode: {show}"
        );
        assert_eq!(show["futureField"], 7, "the account's other fields stay");
        assert_eq!(show["removed"], false);
        assert!(
            show["_mtime"].as_str() > Some(OLD),
            "newer than the account's"
        );

        let film = sent(&changes, "tt9");
        assert_eq!(
            film["state"]["timesWatched"], 0,
            "unmarked as stremio-core does"
        );

        let new = sent(&changes, "tt2");
        assert_eq!(bits(new, "tt2"), [false, true, false, false]);
        assert_eq!(new["type"], "series");
        assert_eq!(new["name"], "Show tt2");
        assert_eq!(new["removed"], true, "not in the official apps' library");
        assert_eq!(new["temp"], true);
    }

    #[tokio::test]
    async fn marks_a_movie_watched_as_the_official_app() {
        let server = account(vec![movie("tt9", 0)]).await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.sync().await.expect("syncs");
        assert!(watched(&core, "tt9").is_empty());

        core.set_watched("tt9".into(), vec!["tt9".into()], true)
            .expect("marks");
        core.sync().await.expect("syncs");
        let changes = uploaded(&server).await;
        let film = sent(&changes, "tt9");
        assert_eq!(film["state"]["timesWatched"], 1);
        assert_eq!(film["state"]["timeOffset"], 0);
        assert!(film["state"]["lastWatched"].is_string());
        assert!(
            film["state"]["watched"].is_null(),
            "movies keep no bitfield"
        );
    }

    #[tokio::test]
    async fn combines_changes_from_both_sides_when_the_account_is_newer() {
        let remote = field("tt1", &[true, false, false, false]);
        let server = account(vec![series("tt1", json!(remote), OLD)]).await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.sync().await.expect("syncs");

        // Here episode 2 is marked; meanwhile the official app unmarked episode 1, marked
        // episode 3 and renamed the item, which makes its copy the newer one.
        core.set_watched("tt1".into(), vec!["tt1:1:2".into()], true)
            .expect("marks");
        let mut there = series(
            "tt1",
            json!(field("tt1", &[false, false, true, false])),
            NEWER,
        );
        there["name"] = json!("Renamed there");
        account_now_has(&server, vec![there]).await;

        core.sync().await.expect("syncs");
        assert_eq!(watched(&core, "tt1"), ["tt1:1:2", "tt1:1:3"]);
        let changes = uploaded(&server).await;
        let show = sent(&changes, "tt1");
        assert_eq!(bits(show, "tt1"), [false, true, true, false]);
        assert_eq!(
            show["name"], "Renamed there",
            "the newer item wins its other fields"
        );
        assert!(show["_mtime"].as_str() > Some(NEWER));
    }

    #[tokio::test]
    async fn combines_changes_from_both_sides_when_this_device_is_newer() {
        let remote = field("tt1", &[true, true, false, false]);
        let server = account(vec![series("tt1", json!(remote), OLD)]).await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.sync().await.expect("syncs");

        core.set_watched("tt1".into(), vec!["tt1:1:1".into()], false)
            .expect("unmarks");
        account_now_has(
            &server,
            vec![series(
                "tt1",
                json!(field("tt1", &[true, true, false, true])),
                NEWER,
            )],
        )
        .await;
        // Progress saved after the account's change makes this device's item the newer one.
        core.save_progress(finished_or_at("tt1:1:3", 600_000))
            .expect("saves");

        core.sync().await.expect("syncs");
        assert_eq!(watched(&core, "tt1"), ["tt1:1:2", "tt1:1:4"]);
        let changes = uploaded(&server).await;
        let show = sent(&changes, "tt1");
        assert_eq!(bits(show, "tt1"), [false, true, false, true]);
        assert_eq!(
            show["state"]["timeOffset"], 600_000,
            "this device's progress wins"
        );
        assert_eq!(show["state"]["video_id"], "tt1:1:3");
    }

    #[tokio::test]
    async fn an_unreadable_watched_field_never_wipes_local_marks() {
        for garbled in [
            json!("garbage"),
            json!("tt1:9:9:1:eJyTZwAAAEAAIA=="),
            json!("tt1:1:1:1:not-base64!"),
            json!("tt1:1:1:x:eJyTZwAAAEAAIA=="),
            json!("tt1:1:1:1:aGVsbG8="),
            json!(5),
        ] {
            let valid = field("tt1", &[true, true, false, false]);
            let server = account(vec![series("tt1", json!(valid), OLD)]).await;
            let dir = tempfile::tempdir().expect("temp dir");
            let core = core_for(&server, &dir);
            core.sync().await.expect("syncs");
            assert_eq!(watched(&core, "tt1"), ["tt1:1:1", "tt1:1:2"]);

            account_now_has(&server, vec![series("tt1", garbled.clone(), NEWER)]).await;
            core.sync().await.expect("a garbled field is not an error");
            assert_eq!(
                watched(&core, "tt1"),
                ["tt1:1:1", "tt1:1:2"],
                "{garbled} kept the marks"
            );
            core.sync().await.expect("syncs again");
            assert_eq!(watched(&core, "tt1"), ["tt1:1:1", "tt1:1:2"]);
            if garbled.is_string() {
                assert!(
                    uploaded(&server).await.is_empty(),
                    "{garbled} is left alone while nothing changed here"
                );
            }
        }
    }

    #[tokio::test]
    async fn an_unreadable_field_on_first_sync_keeps_and_sends_local_marks() {
        let server = account(vec![series("tt1", json!("tt1:1:1:1:%%%"), OLD)]).await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        // Marked on this device before signing in.
        core.set_watched("tt1".into(), vec!["tt1:1:3".into()], true)
            .expect("marks");
        core.sync().await.expect("syncs");
        assert_eq!(watched(&core, "tt1"), ["tt1:1:3"]);
        let changes = uploaded(&server).await;
        assert_eq!(
            bits(sent(&changes, "tt1"), "tt1"),
            [false, false, true, false]
        );
    }

    #[tokio::test]
    async fn marks_wait_when_the_metadata_cannot_be_read() {
        let remote = encode_watched(&[true, false], &["tt3:1:1".into(), "tt3:1:2".into()]);
        let server = account(vec![series("tt3", json!(remote), OLD)]).await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.set_watched("tt3".into(), vec!["tt3:1:2".into()], true)
            .expect("marks");

        // The addon has no metadata for tt3: nothing about its marks changes on either side.
        core.sync().await.expect("syncs");
        assert_eq!(watched(&core, "tt3"), ["tt3:1:2"]);
        assert!(uploaded(&server).await.is_empty());
        assert_eq!(
            core.store()
                .expect("store")
                .watched_sync("tt3")
                .expect("reads"),
            WatchedSync::default(),
            "retried on the next sync"
        );
    }
}
