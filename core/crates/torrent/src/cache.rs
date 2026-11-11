// SPDX-License-Identifier: GPL-3.0-or-later
//! Downloaded torrent data on disk: one folder per info hash, removed oldest first.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

/// Empty file rewritten whenever a torrent is played, so that playing from the cache counts as
/// use even when nothing new is downloaded.
const MARKER: &str = ".last-used";

/// One torrent's folder in the cache.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct CacheEntry {
    pub(crate) path: PathBuf,
    /// Folder name: the info hash in lowercase hex.
    pub(crate) name: String,
    /// Space taken on disk.
    pub(crate) bytes: u64,
    /// Newest modification time of anything inside.
    pub(crate) last_used: SystemTime,
}

/// Creates `dir` if needed and marks it as just used.
pub(crate) fn touch(dir: &Path) -> io::Result<()> {
    fs::create_dir_all(dir)?;
    fs::write(dir.join(MARKER), b"")
}

/// Every entry directly under `root`. A missing `root` is an empty cache.
pub(crate) fn scan(root: &Path) -> io::Result<Vec<CacheEntry>> {
    let children = match fs::read_dir(root) {
        Ok(children) => children,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(error),
    };
    let mut entries = Vec::new();
    for child in children {
        let child = child?;
        let (bytes, last_used) = usage(&child.path())?;
        entries.push(CacheEntry {
            path: child.path(),
            name: child.file_name().to_string_lossy().into_owned(),
            bytes,
            last_used,
        });
    }
    Ok(entries)
}

/// Total disk usage and newest modification time under `path`, without following links.
/// Walks with an explicit stack so that deep trees cannot overflow the thread's stack.
fn usage(path: &Path) -> io::Result<(u64, SystemTime)> {
    let mut bytes = 0u64;
    let mut newest = SystemTime::UNIX_EPOCH;
    let mut pending = vec![path.to_path_buf()];
    while let Some(path) = pending.pop() {
        let metadata = fs::symlink_metadata(&path)?;
        newest = newest.max(metadata.modified()?);
        if metadata.is_dir() {
            for child in fs::read_dir(&path)? {
                pending.push(child?.path());
            }
        } else {
            bytes = bytes.saturating_add(allocated_bytes(&metadata));
        }
    }
    Ok((bytes, newest))
}

/// Space a file really takes. librqbit creates sparse files, so on Unix the logical length of a
/// partly downloaded file overstates it.
#[cfg(unix)]
fn allocated_bytes(metadata: &fs::Metadata) -> u64 {
    use std::os::unix::fs::MetadataExt;
    metadata.blocks().saturating_mul(512)
}

#[cfg(not(unix))]
fn allocated_bytes(metadata: &fs::Metadata) -> u64 {
    metadata.len()
}

/// Entries to delete, least recently used first, until the cache fits in `max_bytes`.
/// Entries for which `in_use` is true are never chosen.
pub(crate) fn plan(
    mut entries: Vec<CacheEntry>,
    max_bytes: u64,
    in_use: impl Fn(&str) -> bool,
) -> Vec<CacheEntry> {
    let mut total = entries
        .iter()
        .fold(0u64, |total, entry| total.saturating_add(entry.bytes));
    entries.sort_by_key(|entry| entry.last_used);
    let mut doomed = Vec::new();
    for entry in entries {
        if total <= max_bytes {
            break;
        }
        if in_use(&entry.name) {
            continue;
        }
        total = total.saturating_sub(entry.bytes);
        doomed.push(entry);
    }
    doomed
}

/// Deletes an entry, whether it is a folder or a stray file.
pub(crate) fn remove(entry: &CacheEntry) -> io::Result<()> {
    if fs::symlink_metadata(&entry.path)?.is_dir() {
        fs::remove_dir_all(&entry.path)
    } else {
        fs::remove_file(&entry.path)
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    fn entry(name: &str, bytes: u64, age_secs: u64) -> CacheEntry {
        CacheEntry {
            path: PathBuf::from(name),
            name: name.to_owned(),
            bytes,
            last_used: SystemTime::UNIX_EPOCH + Duration::from_secs(1_000_000 - age_secs),
        }
    }

    fn names(entries: &[CacheEntry]) -> Vec<&str> {
        entries.iter().map(|entry| entry.name.as_str()).collect()
    }

    #[test]
    fn plan_removes_oldest_until_it_fits() {
        let entries = vec![
            entry("new", 100, 1),
            entry("old", 100, 30),
            entry("mid", 100, 10),
        ];
        assert_eq!(
            names(&plan(entries.clone(), 150, |_| false)),
            ["old", "mid"]
        );
        assert_eq!(
            names(&plan(entries.clone(), 300, |_| false)),
            Vec::<&str>::new()
        );
        assert_eq!(names(&plan(entries, 0, |_| false)), ["old", "mid", "new"]);
    }

    #[test]
    fn plan_skips_torrents_in_use() {
        let entries = vec![
            entry("new", 100, 1),
            entry("old", 100, 30),
            entry("mid", 100, 10),
        ];
        assert_eq!(
            names(&plan(entries, 150, |name| name == "old")),
            ["mid", "new"]
        );
    }

    #[test]
    fn scan_measures_folders_and_remove_deletes_them() {
        let root = tempfile::tempdir().expect("temp dir");
        let torrent = root.path().join("aa");
        fs::create_dir_all(torrent.join("Season 1")).expect("create dirs");
        fs::write(torrent.join("Season 1/episode.mkv"), vec![1u8; 10_000]).expect("write");
        touch(&torrent).expect("touch");
        fs::write(root.path().join("stray.part"), b"x").expect("write");

        let mut entries = scan(root.path()).expect("scan");
        entries.sort_by(|a, b| a.name.cmp(&b.name));
        assert_eq!(names(&entries), ["aa", "stray.part"]);
        assert!(entries[0].bytes > 0, "{entries:?}");

        for entry in &entries {
            remove(entry).expect("remove");
        }
        assert!(scan(root.path()).expect("scan").is_empty());
    }

    #[test]
    fn missing_cache_is_empty() {
        let root = tempfile::tempdir().expect("temp dir");
        assert!(scan(&root.path().join("absent")).expect("scan").is_empty());
    }
}
