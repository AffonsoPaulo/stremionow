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

    pub fn remove_progress(&self, id: &str) -> Result<(), StoreError> {
        self.connection
            .execute("DELETE FROM progress WHERE id = ?1", [id])?;
        Ok(())
    }

    /// Marks a video of title `id` as watched.
    pub fn mark_watched(&self, id: &str, video_id: &str, at: i64) -> Result<(), StoreError> {
        self.connection.execute(
            "INSERT INTO watched (video_id, id, watched_at) VALUES (?1, ?2, ?3)
             ON CONFLICT (video_id) DO UPDATE SET watched_at = excluded.watched_at",
            params![video_id, id, at],
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
    fn watched_videos_are_per_title() {
        let store = Store::open_in_memory().expect("store");
        store.mark_watched("a", "a:1:1", 1).expect("marks");
        store.mark_watched("a", "a:1:1", 2).expect("marks again");
        store.mark_watched("b", "b:1:1", 3).expect("marks");
        assert_eq!(store.watched_videos("a").expect("reads"), ["a:1:1"]);
    }
}
