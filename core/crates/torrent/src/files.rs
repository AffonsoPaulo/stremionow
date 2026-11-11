// SPDX-License-Identifier: GPL-3.0-or-later
//! Choosing the file to play inside a torrent and describing its type.

use std::path::Path;

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

/// Index of the file to stream: `requested` when it names a real file, otherwise the largest
/// video. `None` when the torrent holds no video at all.
pub(crate) fn pick_file(files: &[TorrentFile], requested: Option<usize>) -> Option<usize> {
    requested
        .filter(|&index| files.get(index).is_some_and(|file| !file.padding))
        .or_else(|| largest_video(files))
}

fn largest_video(files: &[TorrentFile]) -> Option<usize> {
    files
        .iter()
        .enumerate()
        .filter(|(_, file)| !file.padding && is_video(&file.path))
        .max_by_key(|(index, file)| (file.len, std::cmp::Reverse(*index)))
        .map(|(index, _)| index)
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
            file("Show/S01E01.mkv", 700),
            file("Show/S01E02.MP4", 900),
            file("Show/extras.iso", 5_000),
            file("Show/Sample/sample.mkv", 50),
        ]
    }

    #[test]
    fn picks_the_largest_video_by_default() {
        assert_eq!(pick_file(&season(), None), Some(2));
    }

    #[test]
    fn honours_a_valid_requested_index() {
        assert_eq!(pick_file(&season(), Some(1)), Some(1));
        assert_eq!(pick_file(&season(), Some(0)), Some(0));
    }

    #[test]
    fn falls_back_when_the_index_is_out_of_range() {
        assert_eq!(pick_file(&season(), Some(5)), Some(2));
        assert_eq!(pick_file(&season(), Some(usize::MAX)), Some(2));
    }

    #[test]
    fn never_picks_padding_files() {
        let mut files = season();
        files.push(TorrentFile {
            path: ".pad/1048576.mkv".to_owned(),
            len: 1_048_576,
            padding: true,
        });
        assert_eq!(pick_file(&files, None), Some(2));
        assert_eq!(pick_file(&files, Some(5)), Some(2));
    }

    #[test]
    fn equal_sizes_prefer_the_first_file() {
        let files = vec![file("a.mkv", 10), file("b.mkv", 10)];
        assert_eq!(pick_file(&files, None), Some(0));
    }

    #[test]
    fn torrents_without_video_have_no_default() {
        let files = vec![file("album/01.flac", 10), file("cover.jpg", 20)];
        assert_eq!(pick_file(&files, None), None);
        assert_eq!(pick_file(&[], None), None);
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
