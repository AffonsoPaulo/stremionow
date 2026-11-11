// SPDX-License-Identifier: GPL-3.0-or-later
//! The core service the app interfaces talk to.

use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures::future::join_all;
use futures::{StreamExt, stream};
use serde::de::DeserializeOwned;
use stremionow_addons::{AddonClient, AddonError, InstalledAddon};
use stremionow_library::{LibraryEntry, Store};
use stremionow_protocol::{AddonUrl, CatalogResponse, Extra, Manifest, MetaResponse};

use crate::records::{
    AddonInfo, CatalogInfo, CatalogRow, CoreError, LibraryItem, MetaDetails, MetaItem,
};

/// Addons installed on first launch: catalogs and metadata, and subtitles.
const DEFAULT_ADDONS: &[&str] = &[
    "https://v3-cinemeta.strem.io/manifest.json",
    "https://opensubtitles-v3.strem.io/manifest.json",
];
const DEFAULTS_INSTALLED: &str = "default_addons_installed";

/// How long each addon may take before it is skipped.
/// Generous enough for slow mobile networks; stale cached data is shown meanwhile.
const ADDON_TIMEOUT: Duration = Duration::from_secs(15);
/// Catalog pages younger than this are served from the cache without a network request.
const CATALOG_FRESHNESS: Duration = Duration::from_secs(30 * 60);
/// Title details younger than this are served from the cache.
const META_FRESHNESS: Duration = Duration::from_secs(6 * 60 * 60);
/// Cache entries older than this are deleted on startup.
const CACHE_RETENTION: Duration = Duration::from_secs(14 * 24 * 60 * 60);
/// Maximum number of home rows, to keep the first screen fast.
const MAX_HOME_ROWS: usize = 12;
/// Home rows downloaded at the same time.
const HOME_CONCURRENCY: usize = 4;

/// Entry point of the core. Create one per app process and share it.
#[derive(uniffi::Object)]
pub struct StremioCore {
    store: Mutex<Store>,
    client: AddonClient,
}

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// Opens the core with its database inside `data_dir`, creating the directory if needed.
    #[uniffi::constructor]
    pub fn new(data_dir: String) -> Result<Arc<Self>, CoreError> {
        let directory = PathBuf::from(data_dir);
        std::fs::create_dir_all(&directory).map_err(|e| CoreError::Storage {
            reason: e.to_string(),
        })?;
        let store = Store::open(&directory.join("stremionow.db"))?;
        let _ = store.prune_cache(now_millis() - millis(CACHE_RETENTION));
        Ok(Arc::new(Self {
            store: Mutex::new(store),
            client: AddonClient::new(ADDON_TIMEOUT)?,
        }))
    }

    /// Installs the default addons the first time the app runs. Safe to call on every launch;
    /// it does nothing once they are installed, and retries on the next launch after a failure.
    pub async fn ensure_default_addons(&self) -> Result<(), CoreError> {
        if self.store()?.setting(DEFAULTS_INSTALLED)?.is_some() {
            return Ok(());
        }
        // Download the manifests at once, then save them in list order so Cinemeta comes
        // first. The ones that worked are kept; a failure is retried on the next launch.
        let fetched = join_all(DEFAULT_ADDONS.iter().map(|url| self.client.install(url))).await;
        let mut first_error = None;
        for result in fetched {
            match result {
                Ok(addon) => {
                    self.save_addon(&addon)?;
                }
                Err(error) => {
                    first_error.get_or_insert(error);
                }
            }
        }
        if let Some(error) = first_error {
            return Err(error.into());
        }
        self.store()?.set_setting(DEFAULTS_INSTALLED, "1")?;
        Ok(())
    }

    /// Installed addons in display order.
    pub fn addons(&self) -> Result<Vec<AddonInfo>, CoreError> {
        Ok(self
            .installed()?
            .iter()
            .map(|(addon, enabled)| AddonInfo::new(addon, *enabled))
            .collect())
    }

    /// Installs the addon whose manifest is at `url`, or refreshes it if already installed.
    pub async fn install_addon(&self, url: String) -> Result<AddonInfo, CoreError> {
        let addon = self.client.install(&url).await?;
        self.save_addon(&addon)
    }

    pub fn remove_addon(&self, manifest_url: String) -> Result<(), CoreError> {
        self.store()?.remove_addon(&manifest_url)?;
        Ok(())
    }

    pub fn set_addon_enabled(&self, manifest_url: String, enabled: bool) -> Result<(), CoreError> {
        self.store()?.set_addon_enabled(&manifest_url, enabled)?;
        Ok(())
    }

    /// Moves an addon to `index` in the order; the order sets catalog and source priority.
    pub fn move_addon(&self, manifest_url: String, index: u32) -> Result<(), CoreError> {
        self.store()?.move_addon(&manifest_url, index as usize)?;
        Ok(())
    }

    /// Rows of the home screen: the first page of every browsable catalog, in addon order.
    /// Rows whose addon fails are left out; the call fails only if every row fails.
    pub async fn home(&self) -> Result<Vec<CatalogRow>, CoreError> {
        let catalogs: Vec<_> = self
            .enabled()?
            .into_iter()
            .flat_map(|addon| {
                addon
                    .manifest
                    .catalogs
                    .iter()
                    .filter(|c| !c.requires_extra())
                    .map(|c| CatalogInfo::new(&addon, c))
                    .collect::<Vec<_>>()
            })
            .take(MAX_HOME_ROWS)
            .collect();
        if catalogs.is_empty() {
            return Ok(Vec::new());
        }

        // A few requests at a time: firing every row at once on a cold, slow connection makes
        // them all compete and time out together. `buffered` keeps the rows in order.
        let pages: Vec<_> = stream::iter(catalogs.iter().cloned())
            .map(|catalog| self.catalog_page(catalog, None, 0))
            .buffered(HOME_CONCURRENCY)
            .collect()
            .await;

        let mut first_error = None;
        let rows: Vec<CatalogRow> = catalogs
            .into_iter()
            .zip(pages)
            .filter_map(|(catalog, page)| match page {
                Ok(items) if !items.is_empty() => Some(CatalogRow { catalog, items }),
                Ok(_) => None,
                Err(error) => {
                    log::warn!("home row {} failed: {error}", catalog.catalog_id);
                    first_error.get_or_insert(error);
                    None
                }
            })
            .collect();
        match (rows.is_empty(), first_error) {
            (true, Some(error)) => Err(error),
            _ => Ok(rows),
        }
    }

    /// Catalogs of `kind` that can be browsed without a search term.
    pub fn catalogs(&self, kind: String) -> Result<Vec<CatalogInfo>, CoreError> {
        Ok(self
            .enabled()?
            .iter()
            .flat_map(|addon| {
                addon
                    .manifest
                    .catalogs
                    .iter()
                    .filter(|c| c.kind == kind && !c.requires_extra())
                    .map(|c| CatalogInfo::new(addon, c))
                    .collect::<Vec<_>>()
            })
            .collect())
    }

    /// A page of `catalog`, optionally filtered by `genre`, after skipping `skip` items.
    pub async fn catalog_page(
        &self,
        catalog: CatalogInfo,
        genre: Option<String>,
        skip: u32,
    ) -> Result<Vec<MetaItem>, CoreError> {
        let addon = self.find_addon(&catalog.addon_manifest_url)?;
        let mut extra = Extra::new();
        if let Some(genre) = genre.as_deref() {
            extra = extra.with("genre", genre);
        }
        if skip > 0 {
            extra = extra.with("skip", skip.to_string());
        }
        let url = addon
            .url
            .resource("catalog", &catalog.kind, &catalog.catalog_id, &extra);
        let response: CatalogResponse = self.cached_json(&url, CATALOG_FRESHNESS).await?;
        Ok(dedupe(response.metas.into_iter().map(MetaItem::from)))
    }

    /// Searches every catalog that supports search and merges the results in addon order.
    pub async fn search(&self, query: String) -> Result<Vec<MetaItem>, CoreError> {
        let query = query.trim().to_owned();
        if query.is_empty() {
            return Ok(Vec::new());
        }
        let addons = self.enabled()?;
        let targets: Vec<_> = addons
            .iter()
            .flat_map(|addon| {
                addon
                    .manifest
                    .catalogs
                    .iter()
                    .filter(|c| c.supports_extra("search"))
                    .map(move |c| (addon, c))
            })
            .collect();
        let results =
            join_all(targets.iter().map(|(addon, catalog)| {
                self.client.catalog(addon, catalog, None, Some(&query), 0)
            }))
            .await;

        let mut first_error = None;
        let mut items = Vec::new();
        for result in results {
            match result {
                Ok(metas) => items.extend(metas.into_iter().map(MetaItem::from)),
                Err(error) => {
                    first_error.get_or_insert(error);
                }
            }
        }
        match (items.is_empty(), first_error) {
            (true, Some(error)) => Err(error.into()),
            _ => Ok(dedupe(items)),
        }
    }

    /// Full details of a title from the first addon that provides metadata for it.
    pub async fn meta(&self, kind: String, id: String) -> Result<MetaDetails, CoreError> {
        let addon = self
            .enabled()?
            .into_iter()
            .find(|addon| addon.manifest.supports("meta", &kind, &id))
            .ok_or(CoreError::NotFound)?;
        let url = addon.url.resource("meta", &kind, &id, &Extra::new());
        let response: MetaResponse = self.cached_json(&url, META_FRESHNESS).await?;
        Ok(response.meta.into())
    }

    /// Titles saved to "My list", most recent first.
    pub fn library(&self) -> Result<Vec<LibraryItem>, CoreError> {
        Ok(self
            .store()?
            .library()?
            .into_iter()
            .map(|entry| LibraryItem {
                id: entry.id,
                kind: entry.kind,
                name: entry.name,
                poster: entry.poster,
                added_at: entry.added_at,
            })
            .collect())
    }

    pub fn add_to_library(&self, item: MetaItem) -> Result<(), CoreError> {
        self.store()?.add_to_library(&LibraryEntry {
            id: item.id,
            kind: item.kind,
            name: item.name,
            poster: item.poster,
            added_at: now_millis(),
        })?;
        Ok(())
    }

    pub fn remove_from_library(&self, id: String) -> Result<(), CoreError> {
        self.store()?.remove_from_library(&id)?;
        Ok(())
    }

    pub fn is_in_library(&self, id: String) -> Result<bool, CoreError> {
        Ok(self.store()?.is_in_library(&id)?)
    }
}

impl StremioCore {
    /// Saves a downloaded addon, keeping its position and enabled state if it was installed.
    fn save_addon(&self, addon: &InstalledAddon) -> Result<AddonInfo, CoreError> {
        let json = serde_json::to_string(&addon.manifest).map_err(|e| CoreError::InvalidAddon {
            reason: e.to_string(),
        })?;
        let store = self.store()?;
        store.upsert_addon(addon.url.manifest_url(), &json)?;
        let enabled = store
            .addons()?
            .into_iter()
            .find(|a| a.manifest_url == addon.url.manifest_url())
            .is_none_or(|a| a.enabled);
        Ok(AddonInfo::new(addon, enabled))
    }

    fn store(&self) -> Result<MutexGuard<'_, Store>, CoreError> {
        self.store.lock().map_err(|_| CoreError::Storage {
            reason: "the database lock was poisoned".into(),
        })
    }

    /// Installed addons with their enabled flag. Entries whose stored manifest no longer parses
    /// are skipped rather than failing everything.
    fn installed(&self) -> Result<Vec<(InstalledAddon, bool)>, CoreError> {
        Ok(self
            .store()?
            .addons()?
            .into_iter()
            .filter_map(|stored| {
                let url = AddonUrl::parse(&stored.manifest_url).ok()?;
                let manifest: Manifest = serde_json::from_str(&stored.manifest_json).ok()?;
                Some((InstalledAddon { url, manifest }, stored.enabled))
            })
            .collect())
    }

    fn enabled(&self) -> Result<Vec<InstalledAddon>, CoreError> {
        Ok(self
            .installed()?
            .into_iter()
            .filter_map(|(addon, enabled)| enabled.then_some(addon))
            .collect())
    }

    fn find_addon(&self, manifest_url: &str) -> Result<InstalledAddon, CoreError> {
        self.enabled()?
            .into_iter()
            .find(|addon| addon.url.manifest_url() == manifest_url)
            .ok_or(CoreError::NotFound)
    }

    /// Fetches JSON through the cache: fresh entries skip the network, and stale entries are
    /// used when the network fails, so browsing keeps working offline.
    async fn cached_json<T: DeserializeOwned>(
        &self,
        url: &str,
        freshness: Duration,
    ) -> Result<T, CoreError> {
        let cached = self.store()?.cached(url)?;
        if let Some((body, fetched_at)) = &cached
            && now_millis() - fetched_at < millis(freshness)
            && let Ok(value) = serde_json::from_slice(body)
        {
            return Ok(value);
        }

        match self.fetch_json::<T>(url).await {
            Ok((value, body)) => {
                self.store()?.put_cached(url, &body, now_millis())?;
                Ok(value)
            }
            Err(error) => cached
                .and_then(|(body, _)| serde_json::from_slice(&body).ok())
                .ok_or_else(|| error.into()),
        }
    }

    async fn fetch_json<T: DeserializeOwned>(&self, url: &str) -> Result<(T, Vec<u8>), AddonError> {
        let body = self.client.get_bytes(url).await?;
        let value = serde_json::from_slice(&body)
            .map_err(|e| AddonError::InvalidResponse(e.to_string()))?;
        Ok((value, body))
    }
}

/// Removes repeated ids, keeping the first occurrence.
fn dedupe(items: impl IntoIterator<Item = MetaItem>) -> Vec<MetaItem> {
    let mut seen = std::collections::HashSet::new();
    items
        .into_iter()
        .filter(|item| !item.id.is_empty() && seen.insert((item.kind.clone(), item.id.clone())))
        .collect()
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(millis)
        .unwrap_or_default()
}

fn millis(duration: Duration) -> i64 {
    i64::try_from(duration.as_millis()).unwrap_or(i64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    async fn addon_server(name: &str) -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/manifest.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "id": format!("test.{name}"), "version": "1.0.0", "name": name,
                "resources": ["catalog", {"name": "meta", "types": ["movie"], "idPrefixes": ["tt"]}],
                "types": ["movie"],
                "catalogs": [
                    {"type": "movie", "id": "top", "name": "Top", "extra": [{"name": "genre", "options": ["Drama"]}]},
                    {"type": "movie", "id": "search", "extra": [{"name": "search", "isRequired": true}]}
                ]
            })))
            .mount(&server)
            .await;
        server
    }

    fn core() -> (Arc<StremioCore>, tempfile::TempDir) {
        let directory = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(directory.path().to_string_lossy().into_owned()).expect("core");
        (core, directory)
    }

    #[tokio::test]
    async fn builds_home_rows_and_serves_them_offline() {
        let server = addon_server("A").await;
        Mock::given(method("GET"))
            .and(path("/catalog/movie/top.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "metas": [
                    {"id": "tt1", "type": "movie", "name": "One", "poster": "http://insecure/p.jpg"},
                    {"id": "tt1", "type": "movie", "name": "Duplicate"},
                    {"id": "", "type": "movie", "name": "No id"}
                ]
            })))
            .mount(&server)
            .await;

        let (core, _dir) = core();
        core.install_addon(format!("{}/manifest.json", server.uri()))
            .await
            .expect("installs");
        let rows = core.home().await.expect("home");
        assert_eq!(rows.len(), 1, "the search-only catalog is not a home row");
        assert_eq!(rows[0].catalog.genres, ["Drama"]);
        assert_eq!(
            rows[0].items.len(),
            1,
            "duplicates and empty ids are dropped"
        );
        assert_eq!(
            rows[0].items[0].poster, None,
            "plain HTTP images are dropped"
        );

        drop(server);
        let rows = core.home().await.expect("served from cache");
        assert_eq!(rows[0].items[0].name, "One");
    }

    #[tokio::test]
    async fn home_skips_failing_addons() {
        let good = addon_server("Good").await;
        Mock::given(method("GET"))
            .and(path("/catalog/movie/top.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "metas": [{"id": "tt1", "type": "movie", "name": "One"}]
            })))
            .mount(&good)
            .await;
        let bad = addon_server("Bad").await;
        Mock::given(method("GET"))
            .and(path("/catalog/movie/top.json"))
            .respond_with(ResponseTemplate::new(500))
            .mount(&bad)
            .await;

        let (core, _dir) = core();
        for server in [&bad, &good] {
            core.install_addon(format!("{}/manifest.json", server.uri()))
                .await
                .expect("installs");
        }
        let rows = core.home().await.expect("partial home");
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].catalog.addon_name, "Good");
    }

    #[tokio::test]
    async fn disabled_and_removed_addons_are_ignored() {
        let server = addon_server("A").await;
        let (core, _dir) = core();
        let info = core
            .install_addon(format!("{}/manifest.json", server.uri()))
            .await
            .expect("installs");
        core.set_addon_enabled(info.manifest_url.clone(), false)
            .expect("disables");
        assert!(core.catalogs("movie".into()).expect("lists").is_empty());
        assert!(matches!(
            core.meta("movie".into(), "tt1".into()).await,
            Err(CoreError::NotFound)
        ));
        core.remove_addon(info.manifest_url).expect("removes");
        assert!(core.addons().expect("lists").is_empty());
    }

    #[tokio::test]
    async fn search_trims_and_ignores_blank_queries() {
        let server = addon_server("A").await;
        Mock::given(method("GET"))
            .and(path("/catalog/movie/search/search=office.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "metas": [{"id": "tt9", "type": "movie", "name": "Office"}]
            })))
            .mount(&server)
            .await;
        let (core, _dir) = core();
        core.install_addon(format!("{}/manifest.json", server.uri()))
            .await
            .expect("installs");
        assert!(core.search("   ".into()).await.expect("blank").is_empty());
        let results = core.search("  office ".into()).await.expect("search");
        assert_eq!(results[0].id, "tt9");
    }

    #[tokio::test]
    async fn library_round_trip() {
        let (core, _dir) = core();
        let item = MetaItem {
            id: "tt1".into(),
            kind: "movie".into(),
            name: "One".into(),
            poster: None,
            background: None,
            logo: None,
            description: None,
            release_info: None,
            imdb_rating: None,
            genres: vec![],
        };
        core.add_to_library(item).expect("adds");
        assert!(core.is_in_library("tt1".into()).expect("checks"));
        assert_eq!(core.library().expect("lists").len(), 1);
        core.remove_from_library("tt1".into()).expect("removes");
        assert!(core.library().expect("lists").is_empty());
    }

    #[tokio::test]
    async fn rejects_invalid_addon_urls() {
        let (core, _dir) = core();
        let result = core.install_addon("file:///etc/passwd".into()).await;
        assert!(matches!(result, Err(CoreError::InvalidAddon { .. })));
    }
}
