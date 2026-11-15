// SPDX-License-Identifier: GPL-3.0-or-later
//! Choosing the file to play inside a torrent and describing its type.

use std::path::Path;

use crate::TorrentError;

/// Extensions players handle, lowercase, with the MIME type sent for each.
const VIDEO_TYPES: &[(&str, &str)] = &[
    ("mkv", "video/x-matroska"),
    ("mk3d", "video/x-matroska"),
    ("mp4", "video/mp4"),
    ("m4v", "video/mp4"),
    ("webm", "video/webm"),
    ("avi", "video/x-msvideo"),
    ("divx", "video/x-msvideo"),
    ("mov", "video/quicktime"),
    ("wmv", "video/x-ms-wmv"),
    ("asf", "video/x-ms-asf"),
    ("flv", "video/x-flv"),
    ("ts", "video/mp2t"),
    ("m2ts", "video/mp2t"),
    ("mts", "video/mp2t"),
    ("mpg", "video/mpeg"),
    ("mpeg", "video/mpeg"),
    ("vob", "video/mpeg"),
    ("ogv", "video/ogg"),
    ("3gp", "video/3gpp"),
    ("rmvb", "application/vnd.rn-realmedia-vbr"),
];

/// Other types worth naming, so a player picking a file by hand gets a useful header.
const OTHER_TYPES: &[(&str, &str)] = &[
    ("mp3", "audio/mpeg"),
    ("m4a", "audio/mp4"),
    ("aac", "audio/aac"),
    ("flac", "audio/flac"),
    ("ogg", "audio/ogg"),
    ("opus", "audio/opus"),
    ("srt", "application/x-subrip"),
    ("vtt", "text/vtt"),
    ("ass", "text/x-ssa"),
    ("ssa", "text/x-ssa"),
];

const DEFAULT_TYPE: &str = "application/octet-stream";

/// A file listed in torrent metadata.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct TorrentFile {
    /// Path inside the torrent.
    pub(crate) path: String,
    pub(crate) len: u64,
    /// BEP 47 padding files only align pieces; they hold zeros and are never played.
    pub(crate) padding: bool,
}

/// Longest file hint kept: a release name and a file name fit easily.
pub(crate) const MAX_HINT_BYTES: usize = 512;

/// Which file of a torrent to play.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct FileRequest<'a> {
    /// The index the addon sent (`fileIdx`).
    pub(crate) index: Option<usize>,
    /// Text describing the file, such as the source's title, used when there is no index: it
    /// often names the file, or at least the episode (`S01E02`).
    pub(crate) hint: Option<&'a str>,
}

/// The first [`MAX_HINT_BYTES`] of `hint`, cut at a character boundary.
pub(crate) fn bounded_hint(hint: &str) -> &str {
    let mut end = hint.len().min(MAX_HINT_BYTES);
    while !hint.is_char_boundary(end) {
        end -= 1;
    }
    &hint[..end]
}

/// Index of the file to stream: the requested index, which must name a real file; without
/// one, the video the hint names, or else the largest video.
pub(crate) fn pick_file(
    files: &[TorrentFile],
    request: FileRequest<'_>,
) -> Result<usize, TorrentError> {
    if let Some(index) = request.index {
        // Playing another file than the one asked for would play the wrong episode.
        return match files.get(index) {
            Some(file) if !file.padding => Ok(index),
            _ => Err(TorrentError::NoSuchFile(index)),
        };
    }
    let videos = || {
        files
            .iter()
            .enumerate()
            .filter(|(_, file)| !file.padding && is_video(&file.path))
    };
    let hinted = request.hint.and_then(|hint| hinted_video(videos(), hint));
    hinted
        .or_else(|| largest(videos()))
        .ok_or(TorrentError::NoVideoFile)
}

/// The video `hint` names: one whose file name it contains (the longest such name), or else,
/// when it names exactly one episode, the largest video of that episode.
fn hinted_video<'a>(
    videos: impl Iterator<Item = (usize, &'a TorrentFile)> + Clone,
    hint: &str,
) -> Option<usize> {
    let hint = hint.to_lowercase();
    let named = videos
        .clone()
        .filter(|(_, file)| {
            let name = file_name(&file.path).to_lowercase();
            // Short names ("1.mkv") would match by accident.
            name.len() >= 8 && hint.contains(&name)
        })
        .max_by_key(|(index, file)| (file_name(&file.path).len(), std::cmp::Reverse(*index)))
        .map(|(index, _)| index);
    if named.is_some() {
        return named;
    }
    let mut episodes = episode_tags(&hint);
    episodes.dedup();
    let [episode] = episodes.as_slice() else {
        return None;
    };
    largest(
        videos.filter(|(_, file)| {
            episode_tags(&file_name(&file.path).to_lowercase()).contains(episode)
        }),
    )
}

fn largest<'a>(videos: impl Iterator<Item = (usize, &'a TorrentFile)>) -> Option<usize> {
    videos
        .max_by_key(|(index, file)| (file.len, std::cmp::Reverse(*index)))
        .map(|(index, _)| index)
}

/// Every `sNNeNN` tag in lowercase `text`, as (season, episode), in order. A tag must not be
/// part of a longer word.
fn episode_tags(text: &str) -> Vec<(u32, u32)> {
    let bytes = text.as_bytes();
    let digits = |from: usize, most: usize| {
        let len = bytes[from..]
            .iter()
            .take(most + 1)
            .take_while(|b| b.is_ascii_digit())
            .count();
        (1..=most).contains(&len).then_some(len)
    };
    let mut tags = Vec::new();
    for start in 0..bytes.len() {
        if bytes[start] != b's' || (start > 0 && bytes[start - 1].is_ascii_alphanumeric()) {
            continue;
        }
        let Some(season_len) = digits(start + 1, 2) else {
            continue;
        };
        let e = start + 1 + season_len;
        if bytes.get(e) != Some(&b'e') {
            continue;
        }
        let Some(episode_len) = digits(e + 1, 3) else {
            continue;
        };
        let end = e + 1 + episode_len;
        if bytes.get(end).is_some_and(u8::is_ascii_alphabetic) {
            continue;
        }
        let number = |from: usize, len: usize| text[from..from + len].parse::<u32>().ok();
        if let (Some(season), Some(episode)) =
            (number(start + 1, season_len), number(e + 1, episode_len))
        {
            tags.push((season, episode));
        }
    }
    tags
}

fn file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

/// Windows device names, which open a device instead of a file whatever the folder or the
/// extension.
const WINDOWS_DEVICES: &[&str] = &[
    "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8",
    "com9", "com¹", "com²", "com³", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8",
    "lpt9", "lpt¹", "lpt²", "lpt³",
];

/// Whether every file of a torrent can be written inside the torrent's folder on this system.
pub(crate) fn paths_are_safe(files: &[TorrentFile]) -> bool {
    files
        .iter()
        .all(|file| path_is_safe(&file.path, cfg!(windows)))
}

/// Whether `path`, components joined with `/`, stays inside the folder it is written to. With
/// `windows`, also refuses what Windows reads as a drive (`C:x`), a stream (`name:stream`) or a
/// device (`CON`, `nul.txt`), and names it would change by dropping trailing dots and spaces.
fn path_is_safe(path: &str, windows: bool) -> bool {
    path.split('/').all(|component| {
        let mut parts = Path::new(component).components();
        let one_normal = matches!(
            (parts.next(), parts.next()),
            (Some(std::path::Component::Normal(_)), None)
        );
        let plain = one_normal
            && !matches!(component, "" | "." | "..")
            && !component.contains(['/', '\\', '\0']);
        plain && (!windows || windows_name_is_safe(component))
    })
}

fn windows_name_is_safe(name: &str) -> bool {
    if name.contains(':') || name.ends_with(['.', ' ']) {
        return false;
    }
    let stem = name.split('.').next().unwrap_or(name).trim_end_matches(' ');
    !WINDOWS_DEVICES
        .iter()
        .any(|device| stem.to_lowercase() == *device)
}

/// Whether the extension of `path` is one players handle as video.
pub(crate) fn is_video(path: &str) -> bool {
    lookup(VIDEO_TYPES, path).is_some()
}

/// MIME type for `path`, from its extension.
pub(crate) fn content_type(path: &str) -> &'static str {
    lookup(VIDEO_TYPES, path)
        .or_else(|| lookup(OTHER_TYPES, path))
        .unwrap_or(DEFAULT_TYPE)
}

fn lookup(table: &[(&str, &'static str)], path: &str) -> Option<&'static str> {
    let extension = Path::new(path).extension()?.to_str()?;
    table
        .iter()
        .find(|(known, _)| known.eq_ignore_ascii_case(extension))
        .map(|(_, mime)| *mime)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(path: &str, len: u64) -> TorrentFile {
        TorrentFile {
            path: path.to_owned(),
            len,
            padding: false,
        }
    }

    fn season() -> Vec<TorrentFile> {
        vec![
            file("Show/README.txt", 2_000),
            file("Show/Show.S01E01.1080p.mkv", 700),
            file("Show/Show.S01E02.1080p.MP4", 900),
            file("Show/extras.iso", 5_000),
            file("Show/Sample/sample.mkv", 50),
        ]
    }

    fn pick(files: &[TorrentFile], index: Option<usize>, hint: Option<&str>) -> Option<usize> {
        pick_file(files, FileRequest { index, hint }).ok()
    }

    #[test]
    fn picks_the_largest_video_by_default() {
        assert_eq!(pick(&season(), None, None), Some(2));
    }

    #[test]
    fn honours_a_valid_requested_index() {
        assert_eq!(pick(&season(), Some(1), None), Some(1));
        assert_eq!(pick(&season(), Some(0), None), Some(0));
        // The index wins over the hint.
        assert_eq!(pick(&season(), Some(1), Some("S01E02")), Some(1));
    }

    #[test]
    fn an_index_out_of_range_is_an_error() {
        for index in [5, usize::MAX] {
            let picked = pick_file(
                &season(),
                FileRequest {
                    index: Some(index),
                    hint: None,
                },
            );
            assert!(
                matches!(picked, Err(TorrentError::NoSuchFile(i)) if i == index),
                "{picked:?}"
            );
        }
    }

    #[test]
    fn never_picks_padding_files() {
        let mut files = season();
        files.push(TorrentFile {
            path: ".pad/1048576.mkv".to_owned(),
            len: 1_048_576,
            padding: true,
        });
        assert_eq!(pick(&files, None, None), Some(2));
        assert_eq!(pick(&files, Some(5), None), None);
    }

    #[test]
    fn without_an_index_the_hint_names_the_file() {
        let title = "Show S01 1080p\nShow.S01E01.1080p.mkv\n👤 12 💾 700 MB";
        assert_eq!(pick(&season(), None, Some(title)), Some(1));
        assert_eq!(pick(&season(), None, Some("Show s01e01 1080p")), Some(1));
        assert_eq!(pick(&season(), None, Some("S1E2")), Some(2));
    }

    #[test]
    fn hints_that_do_not_tell_fall_back_to_the_largest_video() {
        for hint in [
            "Show S01 Complete",
            "Show S01E01-S01E02",
            "S01E09",
            "Classes1E01",
            "",
        ] {
            assert_eq!(pick(&season(), None, Some(hint)), Some(2), "{hint}");
        }
    }

    #[test]
    fn episode_tags_are_whole_words() {
        assert_eq!(episode_tags("show.s01e02.mkv"), [(1, 2)]);
        assert_eq!(episode_tags("s1e2 and s10e100"), [(1, 2), (10, 100)]);
        assert!(episode_tags("classes1e01").is_empty());
        assert!(episode_tags("s01e02x").is_empty());
        assert!(episode_tags("s123e01 s01e1234 s01").is_empty());
        assert!(episode_tags("s01e").is_empty());
    }

    #[test]
    fn equal_sizes_prefer_the_first_file() {
        let files = vec![file("a.mkv", 10), file("b.mkv", 10)];
        assert_eq!(pick(&files, None, None), Some(0));
    }

    #[test]
    fn torrents_without_video_have_no_default() {
        let files = vec![file("album/01.flac", 10), file("cover.jpg", 20)];
        assert!(matches!(
            pick_file(&files, FileRequest::default()),
            Err(TorrentError::NoVideoFile)
        ));
        assert_eq!(pick(&[], None, None), None);
    }

    #[test]
    fn ordinary_paths_are_safe_everywhere() {
        for path in [
            "movie.mkv",
            "Show/Season 1/S01E01.mkv",
            ".pad/1048576",
            "Ünïcode/字幕.srt",
            "a..b/c.d.mkv",
        ] {
            assert!(path_is_safe(path, false), "{path}");
            assert!(path_is_safe(path, true), "{path}");
        }
    }

    #[test]
    fn paths_leaving_the_folder_are_unsafe_everywhere() {
        for path in ["", "..", "a/../b", "./a", "a//b", "a\\b", "nul\0byte"] {
            assert!(!path_is_safe(path, false), "{path:?}");
            assert!(!path_is_safe(path, true), "{path:?}");
        }
    }

    #[test]
    fn names_windows_reads_otherwise_are_unsafe_there() {
        for path in [
            "C:evil.dll",
            "C:..",
            "Show/C:/x.mkv",
            "movie.mkv:stream",
            "CON",
            "con.txt",
            "Show/nul.mkv",
            "Aux .srt",
            "COM1",
            "lpt9.log",
            "COM¹",
            "folder./movie.mkv",
            "movie.mkv ",
            "...",
        ] {
            assert!(!path_is_safe(path, true), "{path}");
        }
        for path in [
            "CONSOLE.mkv",
            "com10.mkv",
            "lpt.mkv",
            "nul_.mkv",
            "Show/a.con",
        ] {
            assert!(path_is_safe(path, true), "{path}");
        }
    }

    #[test]
    fn torrents_are_checked_with_the_rules_of_this_system() {
        let files = vec![file("Show/S01E01.mkv", 1), file("Show/CON.txt", 1)];
        assert_eq!(paths_are_safe(&files), !cfg!(windows));
        assert!(!paths_are_safe(&[file("../escape.mkv", 1)]));
    }

    #[test]
    fn content_types_follow_the_extension() {
        assert_eq!(content_type("Movie.MKV"), "video/x-matroska");
        assert_eq!(content_type("dir/movie.mp4"), "video/mp4");
        assert_eq!(content_type("movie.webm"), "video/webm");
        assert_eq!(content_type("movie.avi"), "video/x-msvideo");
        assert_eq!(content_type("subs.srt"), "application/x-subrip");
        assert_eq!(content_type("archive.rar"), DEFAULT_TYPE);
        assert_eq!(content_type("no_extension"), DEFAULT_TYPE);
    }
}
