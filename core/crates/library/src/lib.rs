// SPDX-License-Identifier: GPL-3.0-or-later
//! Local SQLite store: installed addons, my list, watch progress, settings and a response cache.
//!
//! The schema is versioned with SQLite's `user_version`; [`Store::open`] applies pending
//! migrations in order inside a transaction.

use std::path::Path;

use rusqlite::{Connection, OptionalExtension, params};

/// Why a store operation failed.
#[derive(Debug, thiserror::Error)]
#[error("local storage error: {0}")]
pub struct StoreError(#[from] rusqlite::Error);

/// An installed addon as stored, in display order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredAddon {
    pub manifest_url: String,
    pub manifest_json: String,
    pub enabled: bool,
}

/// A title saved to "My list".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LibraryEntry {
    pub id: String,
    pub kind: String,
    pub name: String,
    pub poster: Option<String>,
    /// Unix time in milliseconds.
    pub added_at: i64,
}

/// Where the user stopped in a title: the video last played and the position in it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProgressEntry {
    /// Title id, the same as in "My list".
    pub id: String,
    pub kind: String,
    pub name: String,
    pub poster: Option<String>,
    pub background: Option<String>,
    /// The episode or movie video that was playing.
    pub video_id: String,
    pub season: Option<u32>,
    pub episode: Option<u32>,
    pub position_ms: i64,
    pub duration_ms: i64,
    /// Unix time in milliseconds.
    pub updated_at: i64,
}

/// How much a title has been watched, kept per title whether or not it is in "My list", as
/// the account library keeps it (stremio-core `LibraryItemState`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WatchStats {
    pub id: String,
    /// Times the title, or one of its videos, was watched to the end (`timesWatched`).
    pub times_watched: u32,
    /// Last time it was played or marked watched, Unix milliseconds (`lastWatched`).
    pub last_watched: Option<i64>,
    /// Last change to these numbers, Unix milliseconds; decides which side wins on sync.
    pub updated_at: i64,
    /// When the times watched were last set back to zero here (marked unwatched), Unix
    /// milliseconds. Until then a sync never lowers the account's count.
    pub reset_at: Option<i64>,
}

/// A title the user has finished videos of.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WatchedTitle {
    pub id: String,
    /// The video finished last: the movie itself, or an episode such as `tt0903747:1:2`.
    pub last_video_id: String,
    /// Unix time in milliseconds.
    pub watched_at: i64,
    /// How many of its videos are watched.
    pub videos: u32,
}

/// What a title's watched videos were on the account when it last synced, to tell which side
/// changed since (ADR 0012). Empty for a title that never synced.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct WatchedSync {
    /// The account's `state.watched` string as last read or written; `None` when it had none.
    pub remote: Option<String>,
    /// The title's watched videos both sides agreed on, sorted.
    pub videos: Vec<String>,
}

const MIGRATIONS: &[&str] = &[
    // 1: settings, addons, my list and response cache.
    r"
    CREATE TABLE settings (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
    );
    CREATE TABLE addons (
        manifest_url TEXT PRIMARY KEY,
        manifest_json TEXT NOT NULL,
        position INTEGER NOT NULL,
        enabled INTEGER NOT NULL DEFAULT 1
    );
    CREATE TABLE library (
        id TEXT PRIMARY KEY,
        kind TEXT NOT NULL,
        name TEXT NOT NULL,
        poster TEXT,
        added_at INTEGER NOT NULL
    );
    CREATE INDEX library_added_at ON library (added_at DESC);
    CREATE TABLE cache (
        key TEXT PRIMARY KEY,
        body BLOB NOT NULL,
        fetched_at INTEGER NOT NULL
    );
    ",
    // 2: playback progress for "Continue watching" and watched episodes.
    r"
    CREATE TABLE progress (
        id TEXT PRIMARY KEY,
        kind TEXT NOT NULL,
        name TEXT NOT NULL,
        poster TEXT,
        background TEXT,
        video_id TEXT NOT NULL,
        season INTEGER,
        episode INTEGER,
        position_ms INTEGER NOT NULL,
        duration_ms INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
    );
    CREATE INDEX progress_updated_at ON progress (updated_at DESC);
    CREATE TABLE watched (
        video_id TEXT PRIMARY KEY,
        id TEXT NOT NULL,
        watched_at INTEGER NOT NULL
    );
    CREATE INDEX watched_id ON watched (id);
    ",
    // 3: times watched and last watched per title, for sorting "My list" as Stremio does.
    // Titles watched before start with one watch per finished video.
    r"
    CREATE TABLE watch_stats (
        id TEXT PRIMARY KEY,
        times_watched INTEGER NOT NULL DEFAULT 0,
        last_watched INTEGER,
        updated_at INTEGER NOT NULL,
        reset_at INTEGER
    );
    INSERT INTO watch_stats (id, times_watched, last_watched, updated_at)
    SELECT id, SUM(times), MAX(at), MAX(at) FROM (
        SELECT id, COUNT(*) AS times, MAX(watched_at) AS at FROM watched GROUP BY id
        UNION ALL
        SELECT id, 0, updated_at FROM progress
    ) GROUP BY id;
    ",
    // 4: what the account had of each title's watched videos at the last sync (ADR 0012).
    r"
    CREATE TABLE watched_sync (
        id TEXT PRIMARY KEY,
        remote TEXT
    );
    CREATE TABLE watched_sync_videos (
        id TEXT NOT NULL,
        video_id TEXT NOT NULL,
        PRIMARY KEY (id, video_id)
    );
    ",
    // 5: watched videos keyed by title and video. Keyed by the video alone, a video id shared
    // by two titles (a special listed under two series, a movie under two ids) was filed under
    // whichever title marked it first.
    r"
    CREATE TABLE watched_by_title (
        id TEXT NOT NULL,
        video_id TEXT NOT NULL,
        watched_at INTEGER NOT NULL,
        PRIMARY KEY (id, video_id)
    );
    INSERT INTO watched_by_title (id, video_id, watched_at)
    SELECT id, video_id, watched_at FROM watched;
    DROP TABLE watched;
    ALTER TABLE watched_by_title RENAME TO watched;
    ",
    // 6: when each title last left Continue watching here (dismissed, finished or marked
    // watched), so the next sync takes it out of the account's too instead of bringing the
    // account's progress back.
    r"
    CREATE TABLE progress_removed (
        id TEXT PRIMARY KEY,
        removed_at INTEGER NOT NULL
    );
    ",
];

/// The local database. Not thread-safe; wrap it in a mutex to share it.
pub struct Store {
    connection: Connection,
}

impl Store {
    /// Opens (or creates) the database at `path` and migrates it to the latest schema.
    pub fn open(path: &Path) -> Result<Self, StoreError> {
        Self::migrate(Connection::open(path)?)
    }

    /// Opens a temporary in-memory database, for tests.
    pub fn open_in_memory() -> Result<Self, StoreError> {
        Self::migrate(Connection::open_in_memory()?)
    }

    fn migrate(mut connection: Connection) -> Result<Self, StoreError> {
        connection.pragma_update(None, "journal_mode", "WAL")?;
        connection.pragma_update(None, "foreign_keys", true)?;
        let version: i64 = connection.pragma_query_value(None, "user_version", |row| row.get(0))?;
        // A negative or unknown future version is left alone rather than re-migrated.
        let current = usize::try_from(version).unwrap_or(usize::MAX);
        if current < MIGRATIONS.len() {
            let transaction = connection.transaction()?;
            for migration in &MIGRATIONS[current..] {
                transaction.execute_batch(migration)?;
            }
            transaction.pragma_update(None, "user_version", MIGRATIONS.len() as i64)?;
            transaction.commit()?;
        }
        Ok(Self { connection })
    }

    // Settings

    pub fn setting(&self, key: &str) -> Result<Option<String>, StoreError> {
        Ok(self
            .connection
            .query_row("SELECT value FROM settings WHERE key = ?1", [key], |row| {
                row.get(0)
            })
            .optional()?)
    }

    pub fn set_setting(&self, key: &str, value: &str) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO settings (key, value) VALUES (?1, ?2)
             ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    /// Writes several settings in one transaction, so a reader never sees half of them and a
    /// failure leaves the earlier values whole.
    pub fn set_settings<V: AsRef<str>>(&mut self, entries: &[(&str, V)]) -> Result<(), StoreError> {
        let transaction = self.connection.transaction()?;
        for (key, value) in entries {
            transaction.execute(
                "INSERT INTO settings (key, value) VALUES (?1, ?2)
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                params![key, value.as_ref()],
            )?;
        }
        transaction.commit()?;
        Ok(())
    }

    /// Number of settings whose key starts with `prefix`.
    pub fn count_settings_with_prefix(&self, prefix: &str) -> Result<u32, StoreError> {
        Ok(self.connection.query_row(
            "SELECT COUNT(*) FROM settings WHERE substr(key, 1, length(?1)) = ?1",
            [prefix],
            |row| row.get(0),
        )?)
    }

    /// Every setting whose key starts with `prefix`, as `(key, value)`, by key.
    pub fn settings_with_prefix(&self, prefix: &str) -> Result<Vec<(String, String)>, StoreError> {
        let mut statement = self.connection.prepare(
            "SELECT key, value FROM settings WHERE substr(key, 1, length(?1)) = ?1 ORDER BY key",
        )?;
        let rows = statement.query_map([prefix], |row| Ok((row.get(0)?, row.get(1)?)))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Removes every setting whose key starts with `prefix`; returns how many there were.
    pub fn delete_settings_with_prefix(&self, prefix: &str) -> Result<u32, StoreError> {
        let removed = self.connection.execute(
            "DELETE FROM settings WHERE substr(key, 1, length(?1)) = ?1",
            [prefix],
        )?;
        Ok(u32::try_from(removed).unwrap_or(u32::MAX))
    }

    pub fn delete_setting(&self, key: &str) -> Result<(), StoreError> {
        self.connection
            .execute("DELETE FROM settings WHERE key = ?1", [key])?;
        Ok(())
    }

    // Addons

    /// Installed addons in display order.
    pub fn addons(&self) -> Result<Vec<StoredAddon>, StoreError> {
        let mut statement = self.connection.prepare(
            "SELECT manifest_url, manifest_json, enabled FROM addons ORDER BY position, manifest_url",
        )?;
        let rows = statement.query_map([], |row| {
            Ok(StoredAddon {
                manifest_url: row.get(0)?,
                manifest_json: row.get(1)?,
                enabled: row.get(2)?,
            })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Adds an addon at the end of the list, or refreshes its manifest if already installed.
    pub fn upsert_addon(&self, manifest_url: &str, manifest_json: &str) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO addons (manifest_url, manifest_json, position)
             VALUES (?1, ?2, (SELECT COALESCE(MAX(position), -1) + 1 FROM addons))
             ON CONFLICT (manifest_url) DO UPDATE SET manifest_json = excluded.manifest_json",
            params![manifest_url, manifest_json],
        )?;
        Ok(())
    }

    /// Puts the addon at `new_url` in the place of the one at `old_url`, keeping its position
    /// and enabled state, as when an addon is configured again and gets a new address. Any
    /// other entry already at `new_url` goes away, so the addon is listed once. Adds the addon
    /// at the end when nothing is installed at `old_url`.
    pub fn replace_addon(
        &mut self,
        old_url: &str,
        new_url: &str,
        manifest_json: &str,
    ) -> Result<(), StoreError> {
        let transaction = self.connection.transaction()?;
        if old_url != new_url {
            transaction.execute("DELETE FROM addons WHERE manifest_url = ?1", [new_url])?;
        }
        let replaced = transaction.execute(
            "UPDATE addons SET manifest_url = ?2, manifest_json = ?3 WHERE manifest_url = ?1",
            params![old_url, new_url, manifest_json],
        )?;
        if replaced == 0 {
            transaction.execute(
                "INSERT INTO addons (manifest_url, manifest_json, position)
                 VALUES (?1, ?2, (SELECT COALESCE(MAX(position), -1) + 1 FROM addons))",
                params![new_url, manifest_json],
            )?;
        }
        transaction.commit()?;
        Ok(())
    }

    /// Replaces the whole addon list with `addons` (manifest URL and manifest JSON, in order),
    /// in one transaction so an interruption never leaves a half-replaced list. An addon that
    /// stays keeps its enabled state; the others start enabled. A URL listed twice is kept
    /// once, at its first place, with the last manifest.
    pub fn replace_addons(&mut self, addons: &[(String, String)]) -> Result<(), StoreError> {
        let transaction = self.connection.transaction()?;
        let disabled: std::collections::HashSet<String> = {
            let mut statement =
                transaction.prepare("SELECT manifest_url FROM addons WHERE enabled = 0")?;
            let rows = statement.query_map([], |row| row.get(0))?;
            rows.collect::<Result<_, _>>()?
        };
        transaction.execute("DELETE FROM addons", [])?;
        for (position, (url, manifest)) in addons.iter().enumerate() {
            transaction.execute(
                "INSERT INTO addons (manifest_url, manifest_json, position, enabled)
                 VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT (manifest_url) DO UPDATE SET manifest_json = excluded.manifest_json",
                params![
                    url,
                    manifest,
                    i64::try_from(position).unwrap_or(i64::MAX),
                    !disabled.contains(url)
                ],
            )?;
        }
        transaction.commit()?;
        Ok(())
    }

    /// Removes an addon. Returns whether it was installed.
    pub fn remove_addon(&self, manifest_url: &str) -> Result<bool, StoreError> {
        let removed = self
            .connection
            .execute("DELETE FROM addons WHERE manifest_url = ?1", [manifest_url])?;
        Ok(removed > 0)
    }

    pub fn set_addon_enabled(&self, manifest_url: &str, enabled: bool) -> Result<(), StoreError> {
        self.connection.execute(
            "UPDATE addons SET enabled = ?2 WHERE manifest_url = ?1",
            params![manifest_url, enabled],
        )?;
        Ok(())
    }

    /// Moves an addon to `index` in the display order, clamped to the list bounds.
    pub fn move_addon(&mut self, manifest_url: &str, index: usize) -> Result<(), StoreError> {
        let mut order: Vec<String> = self.addons()?.into_iter().map(|a| a.manifest_url).collect();
        let Some(from) = order.iter().position(|url| url == manifest_url) else {
            return Ok(());
        };
        let url = order.remove(from);
        order.insert(index.min(order.len()), url);

        let transaction = self.connection.transaction()?;
        for (position, url) in order.iter().enumerate() {
            transaction.execute(
                "UPDATE addons SET position = ?2 WHERE manifest_url = ?1",
                params![url, position as i64],
            )?;
        }
        transaction.commit()?;
        Ok(())
    }

    // My list

    /// Saved titles, most recently added first.
    pub fn library(&self) -> Result<Vec<LibraryEntry>, StoreError> {
        let mut statement = self.connection.prepare(
            "SELECT id, kind, name, poster, added_at FROM library ORDER BY added_at DESC, id",
        )?;
        let rows = statement.query_map([], |row| {
            Ok(LibraryEntry {
                id: row.get(0)?,
                kind: row.get(1)?,
                name: row.get(2)?,
                poster: row.get(3)?,
                added_at: row.get(4)?,
            })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Saves a title; saving it again keeps the original date.
    pub fn add_to_library(&self, entry: &LibraryEntry) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO library (id, kind, name, poster, added_at) VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT (id) DO UPDATE SET name = excluded.name, poster = excluded.poster",
            params![
                entry.id,
                entry.kind,
                entry.name,
                entry.poster,
                entry.added_at
            ],
        )?;
        Ok(())
    }

    pub fn remove_from_library(&self, id: &str) -> Result<(), StoreError> {
        self.connection
            .execute("DELETE FROM library WHERE id = ?1", [id])?;
        Ok(())
    }

    pub fn is_in_library(&self, id: &str) -> Result<bool, StoreError> {
        Ok(self
            .connection
            .query_row("SELECT 1 FROM library WHERE id = ?1", [id], |_| Ok(()))
            .optional()?
            .is_some())
    }

    // Playback progress

    /// Records where playback is in a title, replacing what was saved for it before.
    pub fn save_progress(&self, entry: &ProgressEntry) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO progress (id, kind, name, poster, background, video_id, season, episode,
                                   position_ms, duration_ms, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
             ON CONFLICT (id) DO UPDATE SET
                kind = excluded.kind, name = excluded.name, poster = excluded.poster,
                background = excluded.background, video_id = excluded.video_id,
                season = excluded.season, episode = excluded.episode,
                position_ms = excluded.position_ms, duration_ms = excluded.duration_ms,
                updated_at = excluded.updated_at",
            params![
                entry.id,
                entry.kind,
                entry.name,
                entry.poster,
                entry.background,
                entry.video_id,
                entry.season,
                entry.episode,
                entry.position_ms,
                entry.duration_ms,
                entry.updated_at
            ],
        )?;
        // Back in progress: an earlier removal no longer needs to reach the account.
        self.connection
            .execute("DELETE FROM progress_removed WHERE id = ?1", [&entry.id])?;
        Ok(())
    }

    pub fn progress(&self, id: &str) -> Result<Option<ProgressEntry>, StoreError> {
        Ok(self
            .connection
            .query_row(
                &format!("{PROGRESS_SELECT} WHERE id = ?1"),
                [id],
                progress_row,
            )
            .optional()?)
    }

    /// Titles in progress, most recently watched first.
    pub fn progress_list(&self, limit: usize) -> Result<Vec<ProgressEntry>, StoreError> {
        let mut statement = self.connection.prepare(&format!(
            "{PROGRESS_SELECT} ORDER BY updated_at DESC, id LIMIT ?1"
        ))?;
        let rows = statement.query_map([i64::try_from(limit).unwrap_or(i64::MAX)], progress_row)?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Takes a title out of "Continue watching" here (dismissed, finished, marked watched) and
    /// remembers when, so the next sync takes it out of the account's too. The time is the
    /// database's clock, the same system clock the rest of the app reads.
    pub fn remove_progress(&self, id: &str) -> Result<(), StoreError> {
        let removed = self
            .connection
            .execute("DELETE FROM progress WHERE id = ?1", [id])?;
        if removed > 0 {
            self.connection.execute(
                "INSERT INTO progress_removed (id, removed_at)
                 VALUES (?1, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
                 ON CONFLICT (id) DO UPDATE SET removed_at = excluded.removed_at",
                [id],
            )?;
        }
        Ok(())
    }

    /// Takes a title out of "Continue watching" because the account says so, without
    /// recording a removal to send back.
    pub fn forget_progress(&self, id: &str) -> Result<(), StoreError> {
        self.connection
            .execute("DELETE FROM progress WHERE id = ?1", [id])?;
        Ok(())
    }

    /// Titles taken out of "Continue watching" here since the last sync, with when.
    pub fn progress_removals(&self) -> Result<Vec<(String, i64)>, StoreError> {
        let mut statement = self
            .connection
            .prepare("SELECT id, removed_at FROM progress_removed ORDER BY id")?;
        let rows = statement.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Forgets the given removals once the account has them; one made again since (another
    /// time) stays for the next sync.
    pub fn clear_progress_removals(
        &mut self,
        removals: &[(String, i64)],
    ) -> Result<(), StoreError> {
        let transaction = self.connection.transaction()?;
        for (id, at) in removals {
            transaction.execute(
                "DELETE FROM progress_removed WHERE id = ?1 AND removed_at = ?2",
                params![id, at],
            )?;
        }
        transaction.commit()?;
        Ok(())
    }

    /// Forgets every removal, as when another account signs in.
    pub fn clear_all_progress_removals(&self) -> Result<(), StoreError> {
        self.connection
            .execute("DELETE FROM progress_removed", [])?;
        Ok(())
    }

    /// Marks a video of title `id` as watched.
    pub fn mark_watched(&self, id: &str, video_id: &str, at: i64) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO watched (video_id, id, watched_at) VALUES (?1, ?2, ?3)
             ON CONFLICT (id, video_id) DO UPDATE SET watched_at = excluded.watched_at",
            params![video_id, id, at],
        )?;
        Ok(())
    }

    /// Marks a video of title `id` as not watched. Does nothing when it was not watched.
    pub fn unmark_watched(&self, id: &str, video_id: &str) -> Result<(), StoreError> {
        self.connection.execute(
            "DELETE FROM watched WHERE video_id = ?1 AND id = ?2",
            params![video_id, id],
        )?;
        Ok(())
    }

    /// Ids of the watched videos of title `id`.
    pub fn watched_videos(&self, id: &str) -> Result<Vec<String>, StoreError> {
        let mut statement = self
            .connection
            .prepare("SELECT video_id FROM watched WHERE id = ?1 ORDER BY video_id")?;
        let rows = statement.query_map([id], |row| row.get(0))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Titles with watched videos, most recently watched first, at most `limit`: for each,
    /// the video watched last, when, and how many of its videos are watched.
    pub fn watched_titles(&self, limit: usize) -> Result<Vec<WatchedTitle>, StoreError> {
        // SQLite fills the bare `video_id` from the row that holds the MAX.
        let mut statement = self.connection.prepare(
            "SELECT id, video_id, MAX(watched_at) AS last, COUNT(*) FROM watched
             GROUP BY id ORDER BY last DESC, id LIMIT ?1",
        )?;
        let rows = statement.query_map([i64::try_from(limit).unwrap_or(i64::MAX)], |row| {
            Ok(WatchedTitle {
                id: row.get(0)?,
                last_video_id: row.get(1)?,
                watched_at: row.get(2)?,
                videos: row.get(3)?,
            })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    // Watch statistics

    /// How much each title has been watched, for every title with statistics.
    pub fn all_watch_stats(&self) -> Result<Vec<WatchStats>, StoreError> {
        let mut statement = self
            .connection
            .prepare(&format!("{STATS_SELECT} ORDER BY id"))?;
        let rows = statement.query_map([], stats_row)?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    pub fn watch_stats(&self, id: &str) -> Result<Option<WatchStats>, StoreError> {
        Ok(self
            .connection
            .query_row(&format!("{STATS_SELECT} WHERE id = ?1"), [id], stats_row)
            .optional()?)
    }

    /// Records that title `id` was being played at `at`.
    pub fn note_watching(&self, id: &str, at: i64) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO watch_stats (id, times_watched, last_watched, updated_at)
             VALUES (?1, 0, ?2, ?2)
             ON CONFLICT (id) DO UPDATE SET
                last_watched = MAX(COALESCE(last_watched, excluded.last_watched),
                                   excluded.last_watched),
                updated_at = MAX(updated_at, excluded.updated_at)",
            params![id, at],
        )?;
        Ok(())
    }

    /// Counts one more watch of title `id`, finished or marked at `at`.
    pub fn count_watch(&self, id: &str, at: i64) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO watch_stats (id, times_watched, last_watched, updated_at)
             VALUES (?1, 1, ?2, ?2)
             ON CONFLICT (id) DO UPDATE SET
                times_watched = MIN(times_watched + 1, 4294967295),
                last_watched = MAX(COALESCE(last_watched, excluded.last_watched),
                                   excluded.last_watched),
                updated_at = MAX(updated_at, excluded.updated_at)",
            params![id, at],
        )?;
        Ok(())
    }

    /// Forgets how many times title `id` was watched, as marking it unwatched does in Stremio.
    /// When it was last watched stays. The reset is kept even for a title without numbers yet,
    /// so the next sync takes the account's count down too.
    pub fn reset_times_watched(&self, id: &str, at: i64) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO watch_stats (id, times_watched, last_watched, updated_at, reset_at)
             VALUES (?1, 0, NULL, ?2, ?2)
             ON CONFLICT (id) DO UPDATE SET
                times_watched = 0, updated_at = MAX(updated_at, excluded.updated_at),
                reset_at = excluded.reset_at",
            params![id, at],
        )?;
        Ok(())
    }

    /// Records that title `id` changed at `at` without new numbers, as when a sync sends the
    /// account a copy changed at `at`, so the next sync does not take that copy for a change
    /// made elsewhere.
    pub fn note_synced_change(&self, id: &str, at: i64) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO watch_stats (id, times_watched, last_watched, updated_at)
             VALUES (?1, 0, NULL, ?2)
             ON CONFLICT (id) DO UPDATE SET updated_at = MAX(updated_at, excluded.updated_at)",
            params![id, at],
        )?;
        Ok(())
    }

    /// Replaces a title's statistics, as when the account's copy is newer.
    pub fn set_watch_stats(&self, stats: &WatchStats) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO watch_stats (id, times_watched, last_watched, updated_at, reset_at)
             VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT (id) DO UPDATE SET
                times_watched = excluded.times_watched, last_watched = excluded.last_watched,
                updated_at = excluded.updated_at, reset_at = excluded.reset_at",
            params![
                stats.id,
                stats.times_watched,
                stats.last_watched,
                stats.updated_at,
                stats.reset_at
            ],
        )?;
        Ok(())
    }

    // Watched videos on the account

    /// What title `id`'s watched videos were at the last sync; empty if it never synced.
    pub fn watched_sync(&self, id: &str) -> Result<WatchedSync, StoreError> {
        let remote = self
            .connection
            .query_row(
                "SELECT remote FROM watched_sync WHERE id = ?1",
                [id],
                |row| row.get(0),
            )
            .optional()?
            .flatten();
        let mut statement = self
            .connection
            .prepare("SELECT video_id FROM watched_sync_videos WHERE id = ?1 ORDER BY video_id")?;
        let videos = statement
            .query_map([id], |row| row.get(0))?
            .collect::<Result<_, _>>()?;
        Ok(WatchedSync { remote, videos })
    }

    /// Records what title `id`'s watched videos are on the account after a sync. An empty
    /// record forgets the title.
    pub fn save_watched_sync(&mut self, id: &str, sync: &WatchedSync) -> Result<(), StoreError> {
        let transaction = self.connection.transaction()?;
        transaction.execute("DELETE FROM watched_sync WHERE id = ?1", [id])?;
        transaction.execute("DELETE FROM watched_sync_videos WHERE id = ?1", [id])?;
        if *sync != WatchedSync::default() {
            transaction.execute(
                "INSERT INTO watched_sync (id, remote) VALUES (?1, ?2)",
                params![id, sync.remote],
            )?;
            for video_id in &sync.videos {
                transaction.execute(
                    "INSERT OR IGNORE INTO watched_sync_videos (id, video_id) VALUES (?1, ?2)",
                    params![id, video_id],
                )?;
            }
        }
        transaction.commit()?;
        Ok(())
    }

    /// Forgets what every title had on the account, as when signing out.
    pub fn clear_watched_sync(&self) -> Result<(), StoreError> {
        self.connection
            .execute_batch("DELETE FROM watched_sync; DELETE FROM watched_sync_videos;")?;
        Ok(())
    }

    // Response cache

    /// A cached body and the time it was fetched (Unix milliseconds).
    pub fn cached(&self, key: &str) -> Result<Option<(Vec<u8>, i64)>, StoreError> {
        Ok(self
            .connection
            .query_row(
                "SELECT body, fetched_at FROM cache WHERE key = ?1",
                [key],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()?)
    }

    pub fn put_cached(&self, key: &str, body: &[u8], fetched_at: i64) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO cache (key, body, fetched_at) VALUES (?1, ?2, ?3)
             ON CONFLICT (key) DO UPDATE SET body = excluded.body, fetched_at = excluded.fetched_at",
            params![key, body, fetched_at],
        )?;
        Ok(())
    }

    /// Deletes cache entries fetched before `older_than` (Unix milliseconds).
    pub fn prune_cache(&self, older_than: i64) -> Result<usize, StoreError> {
        Ok(self
            .connection
            .execute("DELETE FROM cache WHERE fetched_at < ?1", [older_than])?)
    }
}

const STATS_SELECT: &str =
    "SELECT id, times_watched, last_watched, updated_at, reset_at FROM watch_stats";

fn stats_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<WatchStats> {
    Ok(WatchStats {
        id: row.get(0)?,
        times_watched: row.get(1)?,
        last_watched: row.get(2)?,
        updated_at: row.get(3)?,
        reset_at: row.get(4)?,
    })
}

const PROGRESS_SELECT: &str = "SELECT id, kind, name, poster, background, video_id, season, \
     episode, position_ms, duration_ms, updated_at FROM progress";

fn progress_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<ProgressEntry> {
    Ok(ProgressEntry {
        id: row.get(0)?,
        kind: row.get(1)?,
        name: row.get(2)?,
        poster: row.get(3)?,
        background: row.get(4)?,
        video_id: row.get(5)?,
        season: row.get(6)?,
        episode: row.get(7)?,
        position_ms: row.get(8)?,
        duration_ms: row.get(9)?,
        updated_at: row.get(10)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(id: &str, added_at: i64) -> LibraryEntry {
        LibraryEntry {
            id: id.into(),
            kind: "movie".into(),
            name: format!("Title {id}"),
            poster: None,
            added_at,
        }
    }

    #[test]
    fn migrates_once_and_reopens() {
        let directory = tempfile::tempdir().expect("temp dir");
        let path = directory.path().join("store.db");
        {
            let store = Store::open(&path).expect("opens");
            store.set_setting("k", "v").expect("writes");
        }
        let store = Store::open(&path).expect("reopens");
        assert_eq!(store.setting("k").expect("reads").as_deref(), Some("v"));
        assert_eq!(store.setting("missing").expect("reads"), None);
        store.set_setting("p:b", "2").expect("writes");
        store.set_setting("p:a", "1").expect("writes");
        assert_eq!(
            store.settings_with_prefix("p:").expect("lists"),
            [("p:a".into(), "1".into()), ("p:b".into(), "2".into())]
        );
    }

    #[test]
    fn writes_several_settings_together() {
        let mut store = Store::open_in_memory().expect("opens");
        store.set_setting("a", "old").expect("writes");
        store
            .set_settings(&[("a", "1"), ("b", "2")])
            .expect("writes both");
        assert_eq!(store.setting("a").expect("reads").as_deref(), Some("1"));
        assert_eq!(store.setting("b").expect("reads").as_deref(), Some("2"));
    }

    #[test]
    fn keeps_addon_order_and_moves() {
        let mut store = Store::open_in_memory().expect("opens");
        for url in ["a", "b", "c"] {
            store.upsert_addon(url, "{}").expect("adds");
        }
        store.upsert_addon("a", r#"{"v":2}"#).expect("refreshes");
        let urls = |s: &Store| -> Vec<String> {
            s.addons()
                .expect("lists")
                .into_iter()
                .map(|a| a.manifest_url)
                .collect()
        };
        assert_eq!(urls(&store), ["a", "b", "c"], "refresh keeps position");
        assert_eq!(
            store.addons().expect("lists")[0].manifest_json,
            r#"{"v":2}"#
        );

        store.move_addon("c", 0).expect("moves");
        assert_eq!(urls(&store), ["c", "a", "b"]);
        store.move_addon("c", 99).expect("clamps");
        assert_eq!(urls(&store), ["a", "b", "c"]);
        store.move_addon("missing", 0).expect("ignores unknown");

        store.set_addon_enabled("b", false).expect("disables");
        assert!(!store.addons().expect("lists")[1].enabled);
        assert!(store.remove_addon("b").expect("removes"));
        assert!(!store.remove_addon("b").expect("second remove is a no-op"));
        store.upsert_addon("d", "{}").expect("adds after removal");
        assert_eq!(urls(&store), ["a", "c", "d"]);
    }

    #[test]
    fn replaces_an_addon_in_place() {
        let mut store = Store::open_in_memory().expect("opens");
        for url in ["a", "b", "c", "b2"] {
            store.upsert_addon(url, "{}").expect("adds");
        }
        store.set_addon_enabled("b", false).expect("disables");
        store
            .replace_addon("b", "b2", r#"{"v":2}"#)
            .expect("replaces");
        let addons = store.addons().expect("lists");
        let urls: Vec<_> = addons.iter().map(|a| a.manifest_url.as_str()).collect();
        assert_eq!(
            urls,
            ["a", "b2", "c"],
            "keeps the place, drops the duplicate"
        );
        assert!(!addons[1].enabled, "keeps the enabled state");
        assert_eq!(addons[1].manifest_json, r#"{"v":2}"#);

        store
            .replace_addon("b2", "b2", r#"{"v":3}"#)
            .expect("refreshes");
        assert_eq!(
            store.addons().expect("lists")[1].manifest_json,
            r#"{"v":3}"#
        );
        store.replace_addon("missing", "d", "{}").expect("adds");
        assert_eq!(store.addons().expect("lists")[3].manifest_url, "d");
    }

    #[test]
    fn library_orders_by_date_and_keeps_first_date() {
        let store = Store::open_in_memory().expect("opens");
        store.add_to_library(&entry("tt1", 100)).expect("adds");
        store.add_to_library(&entry("tt2", 200)).expect("adds");
        store.add_to_library(&entry("tt1", 300)).expect("re-adds");
        let items = store.library().expect("lists");
        assert_eq!(items[0].id, "tt2");
        assert_eq!(items[1].added_at, 100, "re-adding keeps the original date");
        assert!(store.is_in_library("tt1").expect("checks"));
        store.remove_from_library("tt1").expect("removes");
        assert!(!store.is_in_library("tt1").expect("checks"));
    }

    #[test]
    fn cache_round_trip_and_prune() {
        let store = Store::open_in_memory().expect("opens");
        store.put_cached("u", b"one", 10).expect("puts");
        store.put_cached("u", b"two", 20).expect("replaces");
        assert_eq!(
            store.cached("u").expect("gets"),
            Some((b"two".to_vec(), 20))
        );
        assert_eq!(store.prune_cache(30).expect("prunes"), 1);
        assert_eq!(store.cached("u").expect("gets"), None);
    }

    #[test]
    fn stores_hostile_text_as_data() {
        let store = Store::open_in_memory().expect("opens");
        let mut hostile = entry("x'); DROP TABLE library; --", 1);
        hostile.name = "名前 \u{0} \"quoted\"".into();
        store.add_to_library(&hostile).expect("adds");
        assert_eq!(store.library().expect("lists")[0].name, hostile.name);
    }

    fn progress(id: &str, video_id: &str, updated_at: i64) -> ProgressEntry {
        ProgressEntry {
            id: id.into(),
            kind: "series".into(),
            name: format!("Title {id}"),
            poster: None,
            background: None,
            video_id: video_id.into(),
            season: Some(1),
            episode: Some(2),
            position_ms: 60_000,
            duration_ms: 3_000_000,
            updated_at,
        }
    }

    #[test]
    fn progress_keeps_one_row_per_title_and_orders_by_recency() {
        let store = Store::open_in_memory().expect("store");
        store
            .save_progress(&progress("a", "a:1:1", 1))
            .expect("saves");
        store
            .save_progress(&progress("b", "b:1:1", 2))
            .expect("saves");
        store
            .save_progress(&progress("a", "a:1:2", 3))
            .expect("updates");

        let list = store.progress_list(10).expect("lists");
        assert_eq!(
            list.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(),
            ["a", "b"]
        );
        assert_eq!(list[0].video_id, "a:1:2");
        assert_eq!(store.progress_list(1).expect("limits").len(), 1);

        store.remove_progress("a").expect("removes");
        assert!(store.progress("a").expect("reads").is_none());
    }

    #[test]
    fn lists_watched_titles_by_their_last_watch() {
        let store = Store::open_in_memory().expect("opens");
        store.mark_watched("tt1", "tt1:1:1", 10).expect("marks");
        store.mark_watched("tt1", "tt1:1:2", 30).expect("marks");
        store.mark_watched("tt2", "tt2", 20).expect("marks");
        let titles = store.watched_titles(10).expect("lists");
        assert_eq!(
            titles,
            [
                WatchedTitle {
                    id: "tt1".into(),
                    last_video_id: "tt1:1:2".into(),
                    watched_at: 30,
                    videos: 2,
                },
                WatchedTitle {
                    id: "tt2".into(),
                    last_video_id: "tt2".into(),
                    watched_at: 20,
                    videos: 1,
                },
            ]
        );
        assert_eq!(store.watched_titles(1).expect("lists").len(), 1);
    }

    #[test]
    fn watched_videos_are_per_title() {
        let store = Store::open_in_memory().expect("store");
        store.mark_watched("a", "a:1:1", 1).expect("marks");
        store.mark_watched("a", "a:1:1", 2).expect("marks again");
        store.mark_watched("b", "b:1:1", 3).expect("marks");
        assert_eq!(store.watched_videos("a").expect("reads"), ["a:1:1"]);
    }

    #[test]
    fn a_video_id_shared_by_two_titles_counts_for_both() {
        let store = Store::open_in_memory().expect("store");
        store.mark_watched("a", "special", 1).expect("marks");
        store.mark_watched("b", "special", 2).expect("marks");
        assert_eq!(store.watched_videos("a").expect("reads"), ["special"]);
        assert_eq!(store.watched_videos("b").expect("reads"), ["special"]);
        store.unmark_watched("a", "special").expect("unmarks");
        assert!(store.watched_videos("a").expect("reads").is_empty());
        assert_eq!(store.watched_videos("b").expect("reads"), ["special"]);
        assert_eq!(store.watched_titles(10).expect("lists").len(), 1);
    }

    #[test]
    fn replacing_the_addon_list_keeps_what_was_disabled() {
        let mut store = Store::open_in_memory().expect("store");
        for url in ["a", "b", "c"] {
            store.upsert_addon(url, "{}").expect("adds");
        }
        store.set_addon_enabled("b", false).expect("disables");
        let list = |urls: &[&str]| -> Vec<(String, String)> {
            urls.iter()
                .map(|url| ((*url).to_owned(), format!("{{\"{url}\":1}}")))
                .collect()
        };
        store
            .replace_addons(&list(&["d", "b", "a", "d"]))
            .expect("replaces");
        let addons = store.addons().expect("lists");
        let urls: Vec<_> = addons.iter().map(|a| a.manifest_url.as_str()).collect();
        assert_eq!(urls, ["d", "b", "a"]);
        assert!(addons[0].enabled, "a new addon starts enabled");
        assert!(!addons[1].enabled, "a disabled one stays disabled");
        assert!(addons[2].enabled);
        assert_eq!(addons[1].manifest_json, r#"{"b":1}"#);
    }

    #[test]
    fn remembers_titles_taken_out_of_continue_watching() {
        let mut store = Store::open_in_memory().expect("store");
        store.remove_progress("none").expect("nothing to remove");
        store
            .save_progress(&progress("a", "a:1:1", 1))
            .expect("saves");
        store
            .save_progress(&progress("b", "b:1:1", 1))
            .expect("saves");
        store.remove_progress("a").expect("removes");
        store.forget_progress("b").expect("forgets");
        let removals = store.progress_removals().expect("lists");
        assert_eq!(
            removals.len(),
            1,
            "only the removal made here: {removals:?}"
        );
        assert_eq!(removals[0].0, "a");
        assert!(removals[0].1 > 1_600_000_000_000, "a time in milliseconds");

        store
            .clear_progress_removals(&[("a".into(), removals[0].1 - 1)])
            .expect("clears another removal");
        assert_eq!(store.progress_removals().expect("lists").len(), 1);
        store.clear_progress_removals(&removals).expect("clears");
        assert!(store.progress_removals().expect("lists").is_empty());

        store.remove_progress("a").expect("nothing left to remove");
        store
            .save_progress(&progress("a", "a:1:2", 2))
            .expect("saves");
        store.remove_progress("a").expect("removes");
        store
            .save_progress(&progress("a", "a:1:3", 3))
            .expect("plays again");
        assert!(
            store.progress_removals().expect("lists").is_empty(),
            "playing again cancels the removal"
        );
        store.remove_progress("a").expect("removes");
        store.clear_all_progress_removals().expect("clears");
        assert!(store.progress_removals().expect("lists").is_empty());
    }

    #[test]
    fn unmarking_forgets_only_that_video() {
        let store = Store::open_in_memory().expect("store");
        store.mark_watched("a", "a:1:1", 1).expect("marks");
        store.mark_watched("a", "a:1:2", 1).expect("marks");
        store.unmark_watched("a", "a:1:1").expect("unmarks");
        store
            .unmark_watched("b", "a:1:2")
            .expect("another title's id leaves it alone");
        store
            .unmark_watched("a", "a:9:9")
            .expect("unwatched is fine");
        assert_eq!(store.watched_videos("a").expect("reads"), ["a:1:2"]);
    }
    #[test]
    fn counts_watches_and_keeps_the_latest_time() {
        let store = Store::open_in_memory().expect("opens");
        assert_eq!(store.watch_stats("a").expect("reads"), None);
        store.note_watching("a", 50).expect("notes");
        store.count_watch("a", 100).expect("counts");
        store.count_watch("a", 80).expect("counts a late report");
        store
            .note_watching("a", 90)
            .expect("an older tick changes nothing");
        assert_eq!(
            store.watch_stats("a").expect("reads"),
            Some(WatchStats {
                id: "a".into(),
                times_watched: 2,
                last_watched: Some(100),
                updated_at: 100,
                reset_at: None,
            })
        );

        store.reset_times_watched("a", 200).expect("resets");
        let reset = store.watch_stats("a").expect("reads").expect("kept");
        assert_eq!(reset.times_watched, 0);
        assert_eq!(reset.last_watched, Some(100), "the last watch stays");
        assert_eq!(reset.updated_at, 200);
        assert_eq!(reset.reset_at, Some(200));
        store.count_watch("a", 300).expect("counts after the reset");
        let counted = store.watch_stats("a").expect("reads").expect("kept");
        assert_eq!(counted.times_watched, 1);
        assert_eq!(counted.reset_at, Some(200), "the reset is remembered");

        store
            .reset_times_watched("new", 400)
            .expect("a title without numbers");
        assert_eq!(
            store.watch_stats("new").expect("reads"),
            Some(WatchStats {
                id: "new".into(),
                times_watched: 0,
                last_watched: None,
                updated_at: 400,
                reset_at: Some(400),
            })
        );
    }

    #[test]
    fn replaces_stats_from_the_account() {
        let store = Store::open_in_memory().expect("opens");
        store.count_watch("a", 100).expect("counts");
        let remote = WatchStats {
            id: "a".into(),
            times_watched: 7,
            last_watched: None,
            updated_at: 300,
            reset_at: None,
        };
        store.set_watch_stats(&remote).expect("replaces");
        store.note_watching("b", 10).expect("notes");
        assert_eq!(
            store.all_watch_stats().expect("lists"),
            [
                remote,
                WatchStats {
                    id: "b".into(),
                    times_watched: 0,
                    last_watched: Some(10),
                    updated_at: 10,
                    reset_at: None,
                }
            ]
        );
    }

    #[test]
    fn keeps_what_the_account_had_per_title() {
        let mut store = Store::open_in_memory().expect("store");
        assert_eq!(
            store.watched_sync("a").expect("reads"),
            WatchedSync::default()
        );
        let synced = WatchedSync {
            remote: Some("a:1:2:2:eJxjAgAAAwAC".into()),
            videos: vec!["a:1:1".into(), "a:1:2".into()],
        };
        store.save_watched_sync("a", &synced).expect("saves");
        store
            .save_watched_sync(
                "b",
                &WatchedSync {
                    remote: None,
                    videos: vec!["b".into()],
                },
            )
            .expect("saves");
        assert_eq!(store.watched_sync("a").expect("reads"), synced);

        let fewer = WatchedSync {
            remote: Some("a:1:1:1:eJxjBAAAAgAC".into()),
            videos: vec!["a:1:1".into()],
        };
        store.save_watched_sync("a", &fewer).expect("replaces");
        assert_eq!(store.watched_sync("a").expect("reads"), fewer);
        store
            .save_watched_sync("a", &WatchedSync::default())
            .expect("forgets");
        assert_eq!(
            store.watched_sync("a").expect("reads"),
            WatchedSync::default()
        );
        assert_eq!(store.watched_sync("b").expect("reads").videos, ["b"]);

        store.clear_watched_sync().expect("clears");
        assert_eq!(
            store.watched_sync("b").expect("reads"),
            WatchedSync::default()
        );
    }

    #[test]
    fn migration_counts_what_was_watched_before() {
        let connection = Connection::open_in_memory().expect("opens");
        for migration in &MIGRATIONS[..2] {
            connection.execute_batch(migration).expect("migrates");
        }
        connection
            .pragma_update(None, "user_version", 2)
            .expect("versions");
        // Written as version 2 wrote them: the store's methods expect the latest schema.
        connection
            .execute_batch(
                "INSERT INTO watched (video_id, id, watched_at) VALUES ('tt1:1:1', 'tt1', 10);
                 INSERT INTO watched (video_id, id, watched_at) VALUES ('tt1:1:2', 'tt1', 30);
                 INSERT INTO progress (id, kind, name, video_id, position_ms, duration_ms, updated_at)
                 VALUES ('tt1', 'series', 'One', 'tt1:1:3', 1, 2, 20);
                 INSERT INTO progress (id, kind, name, video_id, position_ms, duration_ms, updated_at)
                 VALUES ('tt2', 'series', 'Two', 'tt2:1:1', 1, 2, 40);",
            )
            .expect("writes");

        let store = Store::migrate(connection).expect("migrates to the latest");
        let stats = store.all_watch_stats().expect("lists");
        assert_eq!(
            stats,
            [
                WatchStats {
                    id: "tt1".into(),
                    times_watched: 2,
                    last_watched: Some(30),
                    updated_at: 30,
                    reset_at: None,
                },
                WatchStats {
                    id: "tt2".into(),
                    times_watched: 0,
                    last_watched: Some(40),
                    updated_at: 40,
                    reset_at: None,
                },
            ]
        );
    }

    #[test]
    fn migrates_a_version_2_database_and_keeps_its_marks() {
        let directory = tempfile::tempdir().expect("temp dir");
        let path = directory.path().join("store.db");
        {
            let connection = Connection::open(&path).expect("opens");
            for migration in &MIGRATIONS[..2] {
                connection.execute_batch(migration).expect("migrates");
            }
            connection
                .pragma_update(None, "user_version", 2)
                .expect("versions");
            connection
                .execute(
                    "INSERT INTO watched (video_id, id, watched_at) VALUES ('a:1:1', 'a', 1)",
                    [],
                )
                .expect("marks");
        }
        let store = Store::open(&path).expect("migrates to the latest");
        assert_eq!(store.watched_videos("a").expect("reads"), ["a:1:1"]);
        assert_eq!(store.watched_titles(10).expect("lists")[0].watched_at, 1);
        store
            .mark_watched("b", "a:1:1", 2)
            .expect("marks under another title");
        assert_eq!(store.watched_videos("a").expect("reads"), ["a:1:1"]);
        assert_eq!(
            store.watched_sync("a").expect("reads"),
            WatchedSync::default()
        );
    }
}
