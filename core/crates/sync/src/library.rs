// SPDX-License-Identifier: GPL-3.0-or-later
//! Library items as stored in the account's `libraryItem` datastore collection.
//!
//! Mirrors stremio-core's `LibraryItem` and `LibraryItemState`
//! (`src/types/library/library_item.rs`) and `MetaItemBehaviorHints`
//! (`src/types/resource/meta_item.rs`). Every struct also keeps unknown fields, so an item read
//! from the server and written back is unchanged apart from the fields the app edited.

use serde::{Deserialize, Deserializer, Serialize};
use serde_json::{Map, Value};

use crate::time::{format_timestamp, parse_timestamp};

/// One entry of the account library: an item in the library, a removed one (kept as a
/// tombstone so the removal syncs), or a temporary one created by watching without adding.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteLibraryItem {
    /// Meta id, for example `tt0111161`.
    #[serde(rename = "_id")]
    pub id: String,
    #[serde(default)]
    pub name: String,
    /// Content type, for example `movie` or `series`.
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub poster: Option<String>,
    #[serde(default)]
    pub poster_shape: PosterShape,
    #[serde(default)]
    pub removed: bool,
    #[serde(default)]
    pub temp: bool,
    /// Creation time, RFC 3339.
    #[serde(default, rename = "_ctime")]
    pub ctime: Option<String>,
    /// Last modification time, RFC 3339. Decides which side wins in [`crate::merge_library`].
    #[serde(rename = "_mtime")]
    pub mtime: String,
    #[serde(default)]
    pub state: LibraryItemState,
    #[serde(default)]
    pub behavior_hints: BehaviorHints,
    /// Fields this crate does not model, written back unchanged.
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl RemoteLibraryItem {
    /// A new item added to the library at `now_ms` (milliseconds since the Unix epoch).
    pub fn new(
        id: impl Into<String>,
        kind: impl Into<String>,
        name: impl Into<String>,
        poster: Option<String>,
        now_ms: i64,
    ) -> Self {
        let now = format_timestamp(now_ms);
        Self {
            id: id.into(),
            name: name.into(),
            kind: kind.into(),
            poster,
            poster_shape: PosterShape::default(),
            removed: false,
            temp: false,
            ctime: Some(now.clone()),
            mtime: now,
            state: LibraryItemState::default(),
            behavior_hints: BehaviorHints::default(),
            extra: Map::new(),
        }
    }

    /// Whether the user sees the item in their library (neither removed nor temporary).
    pub fn in_library(&self) -> bool {
        !self.removed && !self.temp
    }

    /// Resume point as `(video_id, offset_ms, duration_ms)`, or `None` if nothing is in
    /// progress. Without a recorded video (movies written by some clients) the item id is the
    /// video id, as in stremio-core. `duration_ms` is 0 when unknown.
    pub fn progress(&self) -> Option<(&str, u64, u64)> {
        if self.state.time_offset == 0 {
            return None;
        }
        let video_id = self
            .state
            .video_id
            .as_deref()
            .filter(|id| !id.is_empty())
            .unwrap_or(&self.id);
        Some((video_id, self.state.time_offset, self.state.duration))
    }

    /// `_mtime` in milliseconds since the Unix epoch, or `None` if it is not a valid timestamp.
    pub fn mtime_ms(&self) -> Option<i64> {
        parse_timestamp(&self.mtime)
    }

    /// Marks the item as modified at `now_ms`. Call after every change that should sync.
    pub fn touch(&mut self, now_ms: i64) {
        self.mtime = format_timestamp(now_ms);
    }

    /// Keeps the fields of `raw`, the account's copy of this item as JSON, that this item does
    /// not set, at the top level, in `state` and in `behaviorHints`. For an upload that replaces
    /// an account item this crate could not read: what it had is not lost.
    pub fn keep_fields_of(&mut self, raw: &Value) {
        let Some(raw) = raw.as_object() else { return };
        let Ok(Value::Object(current)) = serde_json::to_value(&*self) else {
            return;
        };
        for (key, value) in raw {
            match key.as_str() {
                "state" => keep_missing(&mut self.state.extra, value, current.get(key)),
                "behaviorHints" => {
                    keep_missing(&mut self.behavior_hints.extra, value, current.get(key));
                }
                _ if !current.contains_key(key) => {
                    self.extra.insert(key.clone(), value.clone());
                }
                _ => {}
            }
        }
    }
}

/// Copies into `extra` the fields of `raw` that `current` lacks.
fn keep_missing(extra: &mut Map<String, Value>, raw: &Value, current: Option<&Value>) {
    let (Some(raw), Some(Value::Object(current))) = (raw.as_object(), current) else {
        return;
    };
    for (key, value) in raw {
        if !current.contains_key(key) {
            extra.insert(key.clone(), value.clone());
        }
    }
}

/// How a poster is drawn (stremio-core `PosterShape`). Unknown values read as `Poster`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PosterShape {
    Square,
    Landscape,
    #[default]
    #[serde(other)]
    Poster,
}

/// Watch state of a library item. Times are in milliseconds.
///
/// Numbers are read leniently (null, negative or fractional values from old clients become
/// whole non-negative numbers) instead of rejecting the whole item.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryItemState {
    /// Last time the item was played, RFC 3339.
    #[serde(default)]
    pub last_watched: Option<String>,
    /// Time watched of the current video.
    #[serde(default, deserialize_with = "lenient_u64")]
    pub time_watched: u64,
    /// Resume position in the current video.
    #[serde(default, deserialize_with = "lenient_u64")]
    pub time_offset: u64,
    /// Time watched across all videos.
    #[serde(default, deserialize_with = "lenient_u64")]
    pub overall_time_watched: u64,
    /// Number of times the item (or one of its videos) was watched to the end.
    #[serde(default, deserialize_with = "lenient_u32")]
    pub times_watched: u32,
    /// Non-zero once the current video counts as watched.
    #[serde(default, deserialize_with = "lenient_u32")]
    pub flagged_watched: u32,
    /// Length of the current video.
    #[serde(default, deserialize_with = "lenient_u64")]
    pub duration: u64,
    /// The video being watched (episode id for series).
    #[serde(default, rename = "video_id")]
    pub video_id: Option<String>,
    /// Watched videos of a series, as `<anchor id>:<anchor length>:<base64>`; read and written
    /// with [`crate::decode_watched`] and [`crate::encode_watched`]. A value that is not a
    /// string reads as none rather than making the whole item unreadable.
    #[serde(default, deserialize_with = "lenient_string")]
    pub watched: Option<String>,
    /// Do not notify about new episodes.
    #[serde(default)]
    pub no_notif: bool,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

/// Behavior hints copied from the item's meta (stremio-core `MetaItemBehaviorHints`).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BehaviorHints {
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub is_live: bool,
    #[serde(default)]
    pub default_video_id: Option<String>,
    #[serde(default)]
    pub featured_video_id: Option<String>,
    #[serde(default)]
    pub has_scheduled_videos: bool,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

fn lenient_number<'de, D: Deserializer<'de>>(deserializer: D) -> Result<f64, D::Error> {
    let value = Option::<Value>::deserialize(deserializer)?;
    Ok(value
        .as_ref()
        .and_then(Value::as_f64)
        .filter(|n| n.is_finite() && *n > 0.0)
        .unwrap_or(0.0))
}

fn lenient_string<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<String>, D::Error> {
    Ok(match Option::<Value>::deserialize(deserializer)? {
        Some(Value::String(text)) => Some(text),
        _ => None,
    })
}

fn lenient_u64<'de, D: Deserializer<'de>>(deserializer: D) -> Result<u64, D::Error> {
    // Exact for integers up to 2^53, far beyond any duration in milliseconds.
    lenient_number(deserializer).map(|n| n as u64)
}

fn lenient_u32<'de, D: Deserializer<'de>>(deserializer: D) -> Result<u32, D::Error> {
    // `as` saturates at u32::MAX.
    lenient_number(deserializer).map(|n| n as u32)
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn official_item() -> Value {
        json!({
            "_id": "tt0944947",
            "name": "Game of Thrones",
            "type": "series",
            "poster": "https://images.example/got.jpg",
            "posterShape": "poster",
            "removed": false,
            "temp": false,
            "_ctime": "2020-01-01T00:00:00.000Z",
            "_mtime": "2021-06-01T12:00:00.000Z",
            "state": {
                "lastWatched": "2021-06-01T12:00:00.000Z",
                "timeWatched": 120000,
                "timeOffset": 600000,
                "overallTimeWatched": 9000000,
                "timesWatched": 3,
                "flaggedWatched": 0,
                "duration": 3600000,
                "video_id": "tt0944947:1:4",
                "watched": "tt0944947:1:1:eJwDAAAAAAE=",
                "noNotif": false,
                "season": 1,
                "episode": 4
            },
            "behaviorHints": {
                "defaultVideoId": null,
                "featuredVideoId": null,
                "hasScheduledVideos": false,
                "privacy": "public"
            },
            "year": "2011-2019"
        })
    }

    #[test]
    fn round_trips_every_field() {
        let item: RemoteLibraryItem = serde_json::from_value(official_item()).expect("valid item");
        assert_eq!(item.state.video_id.as_deref(), Some("tt0944947:1:4"));
        assert_eq!(item.extra["year"], "2011-2019");
        assert_eq!(
            serde_json::to_value(&item).expect("serializable"),
            official_item()
        );
    }

    #[test]
    fn reads_minimal_and_sloppy_items() {
        let item: RemoteLibraryItem = serde_json::from_value(json!({
            "_id": "tt1",
            "type": "movie",
            "_mtime": "2021-06-01T12:00:00Z",
            "posterShape": "unknownShape",
            "state": { "timeOffset": 1500.7, "duration": null, "timesWatched": -2 }
        }))
        .expect("lenient item");
        assert_eq!(item.poster_shape, PosterShape::Poster);
        assert_eq!(item.state.time_offset, 1500);
        assert_eq!(item.state.duration, 0);
        assert_eq!(item.state.times_watched, 0);
    }

    #[test]
    fn a_watched_field_that_is_not_a_string_reads_as_none() {
        for watched in [json!(5), json!({"bits": 1}), json!(null), json!([1])] {
            let item: RemoteLibraryItem = serde_json::from_value(json!({
                "_id": "tt1", "type": "series", "_mtime": "2021-06-01T12:00:00Z",
                "state": { "watched": watched }
            }))
            .expect("the item stays readable");
            assert_eq!(item.state.watched, None);
        }
    }

    #[test]
    fn new_item_is_in_library() {
        let item = RemoteLibraryItem::new("tt1", "movie", "Movie", None, 1_577_836_800_000);
        assert!(item.in_library());
        assert_eq!(item.mtime, "2020-01-01T00:00:00.000Z");
        assert_eq!(item.ctime.as_deref(), Some("2020-01-01T00:00:00.000Z"));
        assert_eq!(item.mtime_ms(), Some(1_577_836_800_000));
        assert_eq!(item.progress(), None);
    }

    #[test]
    fn removed_and_temp_items_are_not_in_library() {
        let mut item = RemoteLibraryItem::new("tt1", "movie", "Movie", None, 0);
        item.temp = true;
        assert!(!item.in_library());
        item.temp = false;
        item.removed = true;
        assert!(!item.in_library());
    }

    #[test]
    fn progress_falls_back_to_the_item_id() {
        let mut item = RemoteLibraryItem::new("tt1", "movie", "Movie", None, 0);
        item.state.time_offset = 5_000;
        item.state.duration = 60_000;
        assert_eq!(item.progress(), Some(("tt1", 5_000, 60_000)));
        item.state.video_id = Some("tt1:2".to_owned());
        assert_eq!(item.progress(), Some(("tt1:2", 5_000, 60_000)));
    }

    #[test]
    fn keeps_the_fields_of_an_unreadable_account_copy() {
        let raw = json!({
            "_id": "tt1", "name": "Old name", "removed": false,
            "state": {"timeOffset": 5, "season": 2, "customState": [1]},
            "behaviorHints": {"privacy": "public"},
            "year": "2011"
        });
        let mut item = RemoteLibraryItem::new("tt1", "movie", "New name", None, 0);
        item.keep_fields_of(&raw);
        let value = serde_json::to_value(&item).expect("serializable");
        assert_eq!(value["name"], "New name", "the item's own fields win");
        assert_eq!(value["type"], "movie");
        assert_eq!(value["state"]["timeOffset"], 0);
        assert_eq!(value["state"]["season"], 2);
        assert_eq!(value["state"]["customState"], json!([1]));
        assert_eq!(value["behaviorHints"]["privacy"], "public");
        assert_eq!(value["year"], "2011");
        item.keep_fields_of(&json!("not an object"));
    }

    #[test]
    fn touch_updates_mtime() {
        let mut item = RemoteLibraryItem::new("tt1", "movie", "Movie", None, 0);
        item.touch(1_000);
        assert_eq!(item.mtime_ms(), Some(1_000));
        assert_eq!(item.ctime.as_deref(), Some("1970-01-01T00:00:00.000Z"));
    }
}
