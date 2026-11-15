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

/// Every entry directly under `root`. A missing `root` is an empty cache, and entries removed
/// while they are measured are left out.
pub(crate) fn scan(root: &Path) -> io::Result<Vec<CacheEntry>> {
    let children = match fs::read_dir(root) {
        Ok(children) => children,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(error),
    };
    let mut entries = Vec::new();
    for child in children {
        let child = child?;
        let (bytes, last_used) = match usage(&child.path()) {
            Ok(usage) => usage,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error),
        };
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
/// Walks with an explicit stack so that deep trees cannot overflow the thread's stack. Files
/// removed during the walk are skipped; `path` itself missing is an error.
fn usage(path: &Path) -> io::Result<(u64, SystemTime)> {
    let mut bytes = 0u64;
    let mut newest = SystemTime::UNIX_EPOCH;
    let mut pending = vec![path.to_path_buf()];
    while let Some(current) = pending.pop() {
        let gone = |error: &io::Error| error.kind() == io::ErrorKind::NotFound && current != path;
        let metadata = match fs::symlink_metadata(&current) {
            Ok(metadata) => metadata,
            Err(error) if gone(&error) => continue,
            Err(error) => return Err(error),
        };
        newest = newest.max(metadata.modified()?);
        if metadata.is_dir() {
            let children = match fs::read_dir(&current) {
                Ok(children) => children,
                Err(error) if gone(&error) => continue,
                Err(error) => return Err(error),
            };
            for child in children {
                pending.push(child?.path());
            }
        } else {
            bytes = bytes.saturating_add(allocated_bytes(&current, &metadata));
        }
    }
    Ok((bytes, newest))
}

/// Space the file at `path` takes on disk; `None` if there is none.
pub(crate) fn allocated_len(path: &Path) -> Option<u64> {
    let metadata = fs::symlink_metadata(path).ok()?;
    metadata.is_file().then(|| allocated_bytes(path, &metadata))
}

/// Space a file really takes. librqbit creates sparse files, so the logical length of a partly
/// downloaded file overstates it: five minutes of a 15 GB film would count as 15 GB.
#[cfg(unix)]
fn allocated_bytes(_path: &Path, metadata: &fs::Metadata) -> u64 {
    use std::os::unix::fs::MetadataExt;
    metadata.blocks().saturating_mul(512)
}

/// Space a file really takes: on Windows, the size the file system reports for sparse and
/// compressed files (`GetCompressedFileSizeW`). Falls back to the logical length.
#[cfg(windows)]
fn allocated_bytes(path: &Path, metadata: &fs::Metadata) -> u64 {
    windows::compressed_size(path).unwrap_or(metadata.len())
}

#[cfg(not(any(unix, windows)))]
fn allocated_bytes(_path: &Path, metadata: &fs::Metadata) -> u64 {
    metadata.len()
}

/// Free space on the disk holding `path`, available to this app.
#[cfg(unix)]
pub(crate) fn free_bytes(path: &Path) -> io::Result<u64> {
    let stats = rustix::fs::statvfs(path)?;
    Ok(stats.f_bavail.saturating_mul(stats.f_frsize))
}

/// Free space on the disk holding `path`, available to this app.
#[cfg(windows)]
pub(crate) fn free_bytes(path: &Path) -> io::Result<u64> {
    windows::free_space(path)
}

#[cfg(not(any(unix, windows)))]
pub(crate) fn free_bytes(_path: &Path) -> io::Result<u64> {
    Ok(u64::MAX)
}

#[cfg(windows)]
mod windows {
    #![allow(unsafe_code)]

    use std::io;
    use std::os::windows::ffi::OsStrExt;
    use std::path::Path;

    use windows_sys::Win32::Foundation::{GetLastError, NO_ERROR, SetLastError};
    use windows_sys::Win32::Storage::FileSystem::{
        GetCompressedFileSizeW, GetDiskFreeSpaceExW, INVALID_FILE_SIZE,
    };

    /// `path` as a NUL-terminated UTF-16 string.
    fn wide(path: &Path) -> Vec<u16> {
        path.as_os_str().encode_wide().chain([0]).collect()
    }

    pub(super) fn compressed_size(path: &Path) -> Option<u64> {
        let name = wide(path);
        let mut high = 0u32;
        // INVALID_FILE_SIZE is also a valid low half; only the last error tells them apart,
        // so it is cleared first.
        // SAFETY: no preconditions.
        unsafe { SetLastError(NO_ERROR) };
        // SAFETY: `name` is NUL-terminated and outlives the call; `high` is a valid u32.
        let low = unsafe { GetCompressedFileSizeW(name.as_ptr(), &mut high) };
        // SAFETY: no preconditions.
        if low == INVALID_FILE_SIZE && unsafe { GetLastError() } != NO_ERROR {
            return None;
        }
        Some((u64::from(high) << 32) | u64::from(low))
    }

    pub(super) fn free_space(path: &Path) -> io::Result<u64> {
        let name = wide(path);
        let mut available = 0u64;
        // SAFETY: `name` is NUL-terminated and outlives the call; the totals may be null.
        let ok = unsafe {
            GetDiskFreeSpaceExW(
                name.as_ptr(),
                &mut available,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            )
        };
        if ok == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(available)
    }
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
    fn written_files_count_what_they_take_and_disks_report_free_space() {
        let root = tempfile::tempdir().expect("temp dir");
        let file = root.path().join("episode.mkv");
        fs::write(&file, vec![1u8; 100_000]).expect("write");
        let taken = allocated_len(&file).expect("a file");
        assert!(taken >= 100_000, "{taken}");
        assert_eq!(allocated_len(&root.path().join("absent")), None);
        assert_eq!(allocated_len(root.path()), None, "a folder is no file");
        assert!(free_bytes(root.path()).expect("free space") > 0);
    }

    /// librqbit sizes files to their full length up front and marks them sparse; on Windows the
    /// cache must count only what was written.
    #[cfg(windows)]
    #[test]
    fn sparse_files_count_only_what_was_written_on_windows() {
        let root = tempfile::tempdir().expect("temp dir");
        let path = root.path().join("film.mkv");
        fs::write(&path, b"").expect("create");
        let marked = std::process::Command::new("fsutil")
            .args(["sparse", "setflag"])
            .arg(&path)
            .status()
            .expect("run fsutil");
        assert!(marked.success(), "fsutil could not mark the file sparse");
        let file = fs::OpenOptions::new()
            .write(true)
            .open(&path)
            .expect("open");
        file.set_len(4 << 30).expect("size the file");
        drop(file);

        let taken = allocated_len(&path).expect("a file");
        assert!(taken < 1 << 20, "a sparse 4 GiB file takes {taken} bytes");
        let entries = scan(root.path()).expect("scan");
        assert!(entries[0].bytes < 1 << 20, "{entries:?}");
    }

    #[test]
    fn entries_removed_while_scanning_are_left_out() {
        let root = tempfile::tempdir().expect("temp dir");
        let gone = root.path().join("gone");
        assert!(matches!(
            usage(&gone),
            Err(error) if error.kind() == io::ErrorKind::NotFound
        ));
        // A file inside that disappears is skipped rather than failing the scan.
        fs::create_dir_all(root.path().join("kept")).expect("create");
        fs::write(root.path().join("kept/a.mkv"), b"abc").expect("write");
        let (bytes, _) = usage(&root.path().join("kept")).expect("usage");
        assert!(bytes > 0);
    }

    #[test]
    fn missing_cache_is_empty() {
        let root = tempfile::tempdir().expect("temp dir");
        assert!(scan(&root.path().join("absent")).expect("scan").is_empty());
    }
}
