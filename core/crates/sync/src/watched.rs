// SPDX-License-Identifier: GPL-3.0-or-later
//! Watched videos of a title in the official format: the `state.watched` string of a library
//! item (ADR 0012).
//!
//! Ported from stremio-core's `stremio-watched-bitfield` crate (`BitField8`, `WatchedField`,
//! `WatchedBitField`) and `LibraryItemState::watched_bitfield`
//! (`src/types/library/library_item.rs`), so the official apps read what is written here and
//! the other way round:
//!
//! - The string is `{anchor video id}:{anchor length}:{bits}`. The id may itself contain `:`
//!   (`tt0944947:1:5`), so the last two parts are split off and the rest is the id.
//! - `bits` is standard base64 (with padding) of the zlib stream (level 6) of a bit array, one
//!   bit per video in [`watched_order`]: bit `i` is bit `i % 8` of byte `i / 8`, least
//!   significant first, in `ceil(videos / 8)` bytes.
//! - The anchor is the last watched video and the anchor length its position plus one (the
//!   first video and 1 when none is watched, `undefined` when the title has no videos). When
//!   the video list changes, the bits are shifted so the anchor stays at its new position: new
//!   episodes keep the marks of the old ones.
//!
//! One deliberate difference: where stremio-core reads a string whose anchor video is not in
//! the list as "nothing watched", [`decode_watched`] reports [`WatchedError::AnchorNotFound`],
//! so that a string this app cannot place never unmarks anything.

use std::collections::HashSet;
use std::io::{Read, Write};

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, STANDARD_PAD_INDIFFERENT};
use flate2::Compression;
use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;

/// Anchor id written for a title without videos, as stremio-core does.
const NO_VIDEO: &str = "undefined";
/// zlib level used by stremio-core (`Compression::new(6)`).
const ZLIB_LEVEL: u32 = 6;
/// Most bytes a bit array may inflate to: 8 million videos, far beyond any title, so that a
/// hostile string cannot exhaust memory.
const MAX_BITFIELD_BYTES: u64 = 1 << 20;

/// Why a `watched` string could not be read.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum WatchedError {
    /// Not `{id}:{length}:{bits}`.
    #[error("the watched field is not in the expected format")]
    Format,
    /// The bits are not valid base64.
    #[error("the watched field is not valid base64")]
    Base64,
    /// The bits are not a valid zlib stream, or inflate to more than the limit.
    #[error("the watched field could not be decompressed")]
    Zlib,
    /// The anchor video is not among the title's videos.
    #[error("the watched field refers to a video the title does not have")]
    AnchorNotFound,
}

/// What [`watched_order`] needs of a video, as the title's metadata gives it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WatchedVideo<'a> {
    pub id: &'a str,
    pub season: Option<u32>,
    pub episode: Option<u32>,
    /// Release time in milliseconds since the Unix epoch.
    pub released_ms: Option<i64>,
}

impl WatchedVideo<'_> {
    /// Season and episode, only when both are known (stremio-core's `SeriesInfo`).
    fn series(&self) -> Option<(u32, u32)> {
        self.season.zip(self.episode)
    }
}

/// The title's video ids in the order the bits follow, as stremio-core arranges them:
///
/// 1. duplicate ids dropped, the first kept (`MetaItem.videos`' `UniqueVec`);
/// 2. stably sorted as `MetaItem.videos` is (`VideoSortedVecAdapter`): videos with a season and
///    episode first, by season (season 0, the specials, last) and episode; then the others by
///    release, oldest first in a series and newest first otherwise, undated ones last;
/// 3. stably sorted again by season, episode and release, a missing value first
///    (`LibraryItemState::watched_bitfield`). Specials end up first.
pub fn watched_order(videos: &[WatchedVideo<'_>]) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut videos: Vec<WatchedVideo<'_>> = videos
        .iter()
        .copied()
        .filter(|video| seen.insert(video.id))
        .collect();
    let is_series = videos.iter().any(|video| video.series().is_some());
    videos.sort_by(|a, b| meta_order(a, b, is_series));
    videos.sort_by_key(|video| {
        let (season, episode) = video
            .series()
            .map_or((i64::MIN, i64::MIN), |(s, e)| (i64::from(s), i64::from(e)));
        (season, episode, video.released_ms.unwrap_or(i64::MIN))
    });
    videos
        .into_iter()
        .map(|video| video.id.to_owned())
        .collect()
}

/// stremio-core's `VideoSortedVecAdapter::cmp`.
fn meta_order(a: &WatchedVideo<'_>, b: &WatchedVideo<'_>, is_series: bool) -> std::cmp::Ordering {
    use std::cmp::Ordering;
    let specials_last = |season: u32| if season == 0 { u32::MAX } else { season };
    match (a.series(), b.series()) {
        (Some((a_season, a_episode)), Some((b_season, b_episode))) => specials_last(a_season)
            .cmp(&specials_last(b_season))
            .then(a_episode.cmp(&b_episode)),
        (Some(_), None) => Ordering::Less,
        (None, Some(_)) => Ordering::Greater,
        (None, None) => match (a.released_ms, b.released_ms) {
            (Some(a), Some(b)) if is_series => a.cmp(&b),
            (Some(a), Some(b)) => b.cmp(&a),
            (Some(_), None) => Ordering::Less,
            (None, Some(_)) => Ordering::Greater,
            (None, None) => Ordering::Equal,
        },
    }
}

/// A parsed `watched` string, before it is placed on a video list (stremio-core `WatchedField`).
#[derive(Debug, Clone, PartialEq, Eq)]
struct WatchedField {
    anchor_video: String,
    anchor_length: usize,
    bytes: Vec<u8>,
}

impl WatchedField {
    fn parse(text: &str) -> Result<Self, WatchedError> {
        let mut parts: Vec<&str> = text.split(':').collect();
        if parts.len() < 3 {
            return Err(WatchedError::Format);
        }
        let packed = parts.pop().ok_or(WatchedError::Format)?;
        let anchor_length = parts
            .pop()
            .ok_or(WatchedError::Format)?
            .parse::<usize>()
            .map_err(|_| WatchedError::Format)?;
        Ok(Self {
            anchor_video: parts.join(":"),
            anchor_length,
            bytes: unpack(packed)?,
        })
    }

    fn bit(&self, index: usize) -> bool {
        self.bytes
            .get(index / 8)
            .is_some_and(|byte| (byte >> (index % 8)) & 1 != 0)
    }
}

/// Reads a `watched` string against the title's videos in [`watched_order`]: whether each is
/// watched. The bits are shifted so that the anchor video lands where it now is, and bits that
/// fall outside the list are dropped, as stremio-core's `WatchedBitField::construct_with_videos`
/// does.
pub fn decode_watched(text: &str, video_ids: &[String]) -> Result<Vec<bool>, WatchedError> {
    let field = WatchedField::parse(text)?;
    let anchor = video_ids
        .iter()
        .position(|id| *id == field.anchor_video)
        .ok_or(WatchedError::AnchorNotFound)?;
    // Wide enough for any usize, so a made-up length cannot overflow.
    let offset = field.anchor_length as i128 - anchor as i128 - 1;
    let count = video_ids.len() as i128;
    Ok((0..video_ids.len())
        .map(|index| {
            let previous = index as i128 + offset;
            (0..count).contains(&previous) && field.bit(previous as usize)
        })
        .collect())
}

/// Writes the `watched` string for `watched[i]` of each video `video_ids[i]` in
/// [`watched_order`], exactly as stremio-core's `WatchedBitField` displays it. Extra flags
/// beyond the list are ignored.
pub fn encode_watched(watched: &[bool], video_ids: &[String]) -> String {
    let count = video_ids.len();
    let mut bytes = vec![0_u8; count.div_ceil(8)];
    for (index, _) in watched.iter().take(count).enumerate().filter(|(_, w)| **w) {
        bytes[index / 8] |= 1 << (index % 8);
    }
    let last = watched.iter().take(count).rposition(|w| *w).unwrap_or(0);
    let anchor = video_ids.get(last).map_or(NO_VIDEO, String::as_str);
    format!("{anchor}:{}:{}", last + 1, pack(&bytes))
}

/// zlib, then base64 with padding (stremio-core `String::try_from(&BitField8)`).
fn pack(bytes: &[u8]) -> String {
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::new(ZLIB_LEVEL));
    // Writing to a Vec cannot fail.
    let compressed = encoder
        .write_all(bytes)
        .and_then(|()| encoder.finish())
        .unwrap_or_default();
    STANDARD.encode(compressed)
}

/// The inverse of [`pack`]. Padding is optional, as some base64 writers leave it out.
fn unpack(packed: &str) -> Result<Vec<u8>, WatchedError> {
    let compressed = STANDARD_PAD_INDIFFERENT
        .decode(packed)
        .map_err(|_| WatchedError::Base64)?;
    let mut bytes = Vec::new();
    ZlibDecoder::new(compressed.as_slice())
        .take(MAX_BITFIELD_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| WatchedError::Zlib)?;
    if bytes.len() as u64 > MAX_BITFIELD_BYTES {
        return Err(WatchedError::Zlib);
    }
    Ok(bytes)
}

/// Outcome of [`merge_watched`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WatchedMerge {
    /// Whether each video of the list is watched after the merge.
    pub watched: Vec<bool>,
    /// The string to write to the account, when the account's must change.
    pub upload: Option<String>,
    /// The account had a string that could not be read against this list; it was ignored.
    pub remote_unreadable: bool,
}

/// Combines the watched marks of one title from this device and the account, video by video,
/// against `base`, the watched videos both sides had at the last sync (empty when they never
/// synced):
///
/// - a video changed on one side only since the last sync takes that side's value;
/// - changed on both sides, they agree (a mark is yes or no), so nothing conflicts;
/// - with no base, a video watched on either side stays watched.
///
/// `remote` is the account's `state.watched`. When it is absent or cannot be read (an unknown
/// anchor, garbage) the account counts as unchanged, so the local marks are kept; an
/// unreadable string is replaced only when the marks changed here since the last sync.
pub fn merge_watched(
    video_ids: &[String],
    base: &HashSet<String>,
    local: &HashSet<String>,
    remote: Option<&str>,
) -> WatchedMerge {
    let flags = |set: &HashSet<String>| -> Vec<bool> {
        video_ids.iter().map(|id| set.contains(id)).collect()
    };
    let base = flags(base);
    let local = flags(local);
    let decoded = remote
        .filter(|text| !text.is_empty())
        .map(|text| decode_watched(text, video_ids));
    let remote_unreadable = matches!(decoded, Some(Err(_)));
    let remote = match decoded {
        Some(Ok(bits)) => Some(bits),
        _ => None,
    };
    let account = remote.as_ref().unwrap_or(&base);
    let watched: Vec<bool> = local
        .iter()
        .zip(&base)
        .zip(account)
        .map(|((&local, &base), &remote)| merge_mark(base, local, remote))
        .collect();
    let must_write = match &remote {
        Some(bits) => watched != *bits,
        None if remote_unreadable => local != base,
        None => watched.iter().any(|w| *w),
    };
    WatchedMerge {
        upload: must_write.then(|| encode_watched(&watched, video_ids)),
        watched,
        remote_unreadable,
    }
}

/// Three-way merge of one yes-or-no mark: whichever side changed since `base` wins; if both
/// changed they agree.
pub fn merge_mark(base: bool, local: bool, remote: bool) -> bool {
    if local == base { remote } else { local }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(list: &[&str]) -> Vec<String> {
        list.iter().map(|id| (*id).to_owned()).collect()
    }

    fn set(list: &[&str]) -> HashSet<String> {
        list.iter().map(|id| (*id).to_owned()).collect()
    }

    fn episodes(title: &str, season: u32, count: u32) -> Vec<String> {
        (1..=count)
            .map(|e| format!("{title}:{season}:{e}"))
            .collect()
    }

    fn video(
        id: &str,
        season: Option<u32>,
        episode: Option<u32>,
        released: Option<i64>,
    ) -> WatchedVideo<'_> {
        WatchedVideo {
            id,
            season,
            episode,
            released_ms: released,
        }
    }

    // From stremio-watched-bitfield's `bitfield8::tests::test_de_serialize`: bytes [31, 0].
    #[test]
    fn packs_bytes_exactly_as_the_official_crate() {
        assert_eq!(pack(&[31, 0]), "eJyTZwAAAEAAIA==");
        assert_eq!(unpack("eJyTZwAAAEAAIA==").expect("valid"), [31, 0]);
        assert_eq!(pack(&[]), "eJwDAAAAAAE=");
    }

    // From `watched_bitfield::tests::parse_and_modify`.
    #[test]
    fn reads_and_rewrites_the_official_example() {
        let videos = episodes("tt2934286", 1, 9);
        let watched = "tt2934286:1:5:5:eJyTZwAAAEAAIA==";
        let bits = decode_watched(watched, &videos).expect("valid");
        assert_eq!(
            bits,
            [true, true, true, true, true, false, false, false, false]
        );
        assert_eq!(encode_watched(&bits, &videos), watched);

        let mut bits = bits;
        bits[5] = true;
        let rewritten = encode_watched(&bits, &videos);
        assert!(rewritten.starts_with("tt2934286:1:6:6:"), "{rewritten}");
        assert_eq!(decode_watched(&rewritten, &videos).expect("valid"), bits);
    }

    // From `watched_bitfield::tests::test_watched_field_de_serialize`.
    #[test]
    fn parses_an_id_with_colons() {
        let field = WatchedField::parse("tt7767422:3:8:24:eJz7//8/AAX9Av4=").expect("valid");
        assert_eq!(field.anchor_video, "tt7767422:3:8");
        assert_eq!(field.anchor_length, 24);
        assert_eq!(pack(&field.bytes), "eJz7//8/AAX9Av4=");
    }

    // From `watched_bitfield::tests::to_string_empty` and `deserialize_empty`.
    #[test]
    fn a_title_without_videos() {
        assert_eq!(encode_watched(&[], &[]), "undefined:1:eJwDAAAAAAE=");
        assert_eq!(
            decode_watched("undefined:1:eJwDAAAAAAE=", &[]),
            Err(WatchedError::AnchorNotFound),
            "the official crate reads it as nothing watched; here it is left alone"
        );
    }

    // From `watched_bitfield::tests::construct_from_array`.
    #[test]
    fn round_trips_every_other_episode_of_a_long_series() {
        let videos: Vec<String> = (1..500).map(|i| format!("tt2934286:1:{i}")).collect();
        let bits: Vec<bool> = (0..videos.len()).map(|i| i % 2 == 0).collect();
        let text = encode_watched(&bits, &videos);
        assert_eq!(decode_watched(&text, &videos).expect("valid"), bits);
    }

    // From `watched_bitfield::tests::watched_field_anchor_points_to_last_watched_video`.
    #[test]
    fn the_anchor_is_the_last_watched_video() {
        let videos: Vec<String> = (1..=20).map(|i| format!("tt0:1:{i}")).collect();
        let mut bits = vec![false; 20];
        bits[15] = true;
        let text = encode_watched(&bits, &videos);
        assert!(text.starts_with("tt0:1:16:16:"), "{text}");
    }

    #[test]
    fn nothing_watched_anchors_on_the_first_video() {
        let videos = episodes("tt1", 1, 3);
        let text = encode_watched(&[false; 3], &videos);
        assert!(text.starts_with("tt1:1:1:1:"), "{text}");
        assert_eq!(decode_watched(&text, &videos).expect("valid"), [false; 3]);
    }

    #[test]
    fn marks_survive_new_episodes() {
        let old = episodes("tt1", 1, 3);
        let text = encode_watched(&[true, true, false], &old);
        // A new episode added at the end keeps the positions.
        let mut more = old.clone();
        more.push("tt1:1:4".into());
        assert_eq!(
            decode_watched(&text, &more).expect("valid"),
            [true, true, false, false]
        );
        // Specials added before shift the bits so the anchor stays on its episode.
        let mut specials = ids(&["tt1:0:1", "tt1:0:2"]);
        specials.extend(more);
        assert_eq!(
            decode_watched(&text, &specials).expect("valid"),
            [false, false, true, true, false, false]
        );
    }

    #[test]
    fn marks_shift_when_episodes_before_the_anchor_go_away() {
        let old = episodes("tt1", 1, 4);
        let text = encode_watched(&[true, false, true, false], &old);
        let fewer = ids(&["tt1:1:2", "tt1:1:3", "tt1:1:4"]);
        assert_eq!(
            decode_watched(&text, &fewer).expect("valid"),
            [false, true, false]
        );
    }

    #[test]
    fn garbage_is_an_error_never_a_panic() {
        let videos = episodes("tt1", 1, 3);
        for text in [
            "",
            "tt1",
            "tt1:1",
            "tt1:1:1:x:eJyTZwAAAEAAIA==",
            "tt1:1:1:-1:eJyTZwAAAEAAIA==",
            "tt1:1:1:1:not base64!",
            "tt1:1:1:1:aGVsbG8=",
            "tt1:1:1:99999999999999999999999:eJyTZwAAAEAAIA==",
            "tt1:9:9:1:eJyTZwAAAEAAIA==",
            "::::",
        ] {
            assert!(decode_watched(text, &videos).is_err(), "{text:?}");
        }
        // A huge but valid length only shifts everything out of the list.
        let far = "tt1:1:1:18446744073709551615:eJyTZwAAAEAAIA==";
        assert_eq!(decode_watched(far, &videos).expect("valid"), [false; 3]);
    }

    #[test]
    fn refuses_a_zlib_bomb() {
        let bomb = pack(&vec![0xFF; (MAX_BITFIELD_BYTES + 1) as usize]);
        assert_eq!(
            decode_watched(&format!("tt1:1:1:1:{bomb}"), &episodes("tt1", 1, 1)),
            Err(WatchedError::Zlib)
        );
    }

    #[test]
    fn accepts_base64_without_padding() {
        let videos = episodes("tt2934286", 1, 9);
        let bits = decode_watched("tt2934286:1:5:5:eJyTZwAAAEAAIA", &videos).expect("valid");
        assert_eq!(bits.iter().filter(|w| **w).count(), 5);
    }

    #[test]
    fn orders_episodes_as_the_official_app() {
        let videos = [
            video("s2e1", Some(2), Some(1), None),
            video("s0e1", Some(0), Some(1), None),
            video("s1e2", Some(1), Some(2), None),
            video("s1e1", Some(1), Some(1), None),
            video("s1e1", Some(9), Some(9), None),
            video("trailer", None, None, Some(5)),
            video("extra", None, None, None),
        ];
        // Videos without an episode number sort first (undated before dated), then the
        // specials, then by season. The duplicate id keeps its first entry.
        assert_eq!(
            watched_order(&videos),
            ids(&["extra", "trailer", "s0e1", "s1e1", "s1e2", "s2e1"])
        );
    }

    #[test]
    fn orders_undated_and_dated_videos_without_episodes() {
        let videos = [
            video("c", None, None, None),
            video("new", None, None, Some(300)),
            video("old", None, None, Some(100)),
        ];
        // Not a series: the meta order puts the newest first, then the bitfield order sorts
        // by release with undated first.
        assert_eq!(watched_order(&videos), ids(&["c", "old", "new"]));
    }

    #[test]
    fn same_episode_number_keeps_the_release_then_meta_order() {
        let videos = [
            video("b", Some(1), Some(1), Some(200)),
            video("a", Some(1), Some(1), Some(100)),
            video("c", Some(1), Some(1), Some(100)),
        ];
        assert_eq!(watched_order(&videos), ids(&["a", "c", "b"]));
    }

    #[test]
    fn three_way_mark_merge() {
        // (base, local, remote) -> merged
        for (base, local, remote, merged) in [
            (false, false, false, false),
            (false, true, false, true),
            (false, false, true, true),
            (false, true, true, true),
            (true, true, true, true),
            (true, false, true, false),
            (true, true, false, false),
            (true, false, false, false),
        ] {
            assert_eq!(
                merge_mark(base, local, remote),
                merged,
                "{base} {local} {remote}"
            );
        }
    }

    #[test]
    fn merge_combines_both_sides() {
        let videos = episodes("tt1", 1, 4);
        let remote = encode_watched(&[true, false, true, false], &videos);
        let merge = merge_watched(
            &videos,
            &set(&["tt1:1:1"]),
            &set(&["tt1:1:1", "tt1:1:2"]),
            Some(&remote),
        );
        // Here episode 2 was marked; there episode 3.
        assert_eq!(merge.watched, [true, true, true, false]);
        assert_eq!(
            decode_watched(
                merge.upload.as_deref().expect("changes the account"),
                &videos
            ),
            Ok(merge.watched.clone())
        );
        assert!(!merge.remote_unreadable);
    }

    #[test]
    fn merge_takes_an_unmark_from_either_side() {
        let videos = episodes("tt1", 1, 2);
        let both = set(&["tt1:1:1", "tt1:1:2"]);
        let remote = encode_watched(&[false, true], &videos);
        let merge = merge_watched(&videos, &both, &both, Some(&remote));
        assert_eq!(merge.watched, [false, true], "unmarked there");
        assert_eq!(merge.upload, None, "the account already has it");

        let remote = encode_watched(&[true, true], &videos);
        let merge = merge_watched(&videos, &both, &set(&["tt1:1:2"]), Some(&remote));
        assert_eq!(merge.watched, [false, true], "unmarked here");
        assert!(merge.upload.is_some());
    }

    #[test]
    fn first_merge_keeps_marks_of_both_sides() {
        let videos = episodes("tt1", 1, 3);
        let remote = encode_watched(&[false, false, true], &videos);
        let merge = merge_watched(&videos, &set(&[]), &set(&["tt1:1:1"]), Some(&remote));
        assert_eq!(merge.watched, [true, false, true]);
        assert!(merge.upload.is_some());
    }

    #[test]
    fn unreadable_remote_never_unmarks() {
        let videos = episodes("tt1", 1, 2);
        let marks = set(&["tt1:1:1"]);
        for remote in ["garbage", "tt9:1:1:1:eJyTZwAAAEAAIA==", "a:b:c"] {
            let merge = merge_watched(&videos, &marks, &marks, Some(remote));
            assert_eq!(merge.watched, [true, false], "{remote}");
            assert!(merge.remote_unreadable);
            assert_eq!(merge.upload, None, "left alone while nothing changed here");
        }
        let merge = merge_watched(
            &videos,
            &marks,
            &set(&["tt1:1:1", "tt1:1:2"]),
            Some("garbage"),
        );
        assert_eq!(merge.watched, [true, true]);
        assert!(merge.upload.is_some(), "a change made here replaces it");
    }

    #[test]
    fn missing_remote_keeps_local_marks_and_writes_them() {
        let videos = episodes("tt1", 1, 2);
        let marks = set(&["tt1:1:2"]);
        let merge = merge_watched(&videos, &marks, &marks, None);
        assert_eq!(merge.watched, [false, true]);
        assert_eq!(
            merge.upload.as_deref().map(|t| decode_watched(t, &videos)),
            Some(Ok(vec![false, true]))
        );
        let merge = merge_watched(&videos, &set(&[]), &set(&[]), None);
        assert_eq!(
            merge.upload, None,
            "nothing to write for a title never watched"
        );
    }
}
