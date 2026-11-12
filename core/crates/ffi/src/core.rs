// SPDX-License-Identifier: GPL-3.0-or-later
//! The core service the app interfaces talk to.

use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures::future::join_all;
use serde::de::DeserializeOwned;
use stremionow_addons::{
    AddonClient, AddonError, AddonList, DISCOVER_SOURCE, InstalledAddon, discoverable,
    manifest_fingerprint,
};
use stremionow_library::{LibraryEntry, Store};
use stremionow_markers::AniSkip;
use stremionow_protocol::{
    AddonCatalogResponse, AddonUrl, CatalogResponse, Extra, Manifest, MetaResponse,
};

use crate::account::Account;
use crate::home::RecentHome;
use crate::records::{
    AddonInfo, AddonPreview, CatalogAddon, CatalogInfo, CatalogRow, CoreError, LibraryItem,
    MetaDetails, MetaItem,
};
use crate::skips;
use crate::streaming::Streaming;

/// Addons installed by default, the same ones the official Stremio app starts with, each with
/// the version of this list that added it. Stremio's "Local Files" needs its local streaming
/// server and the old OpenSubtitles v1 speaks a legacy protocol, so neither is included.
const DEFAULT_ADDONS: &[(&str, u32)] = &[
    ("https://v3-cinemeta.strem.io/manifest.json", 1),
    ("https://v3-channels.strem.io/manifest.json", 2),
    ("https://watchhub.strem.io/manifest.json", 2),
    (
        "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json",
        2,
    ),
    ("https://opensubtitles-v3.strem.io/manifest.json", 1),
];
/// Current version of [`DEFAULT_ADDONS`].
pub(crate) const DEFAULTS_VERSION: u32 = 2;
/// Setting that holds the version of the defaults already installed.
pub(crate) const DEFAULTS_INSTALLED: &str = "default_addons_installed";

/// How long each addon may take before it is skipped.
/// Generous enough for slow mobile networks; stale cached data is shown meanwhile.
const ADDON_TIMEOUT: Duration = Duration::from_secs(15);
/// Catalog pages younger than this are served from the cache without a network request.
pub(crate) const CATALOG_FRESHNESS: Duration = Duration::from_secs(30 * 60);
/// Title details younger than this are served from the cache.
pub(crate) const META_FRESHNESS: Duration = Duration::from_secs(6 * 60 * 60);
/// Discover's addon lists younger than this are served from the cache. The lists change
/// rarely, so a day keeps the screen instant without going stale.
const DISCOVER_FRESHNESS: Duration = Duration::from_secs(24 * 60 * 60);
/// Previewed manifests kept for the user to confirm; more are never on screen at once.
const MAX_PREVIEWS: usize = 8;
/// Cache entries older than this are deleted on startup.
const CACHE_RETENTION: Duration = Duration::from_secs(14 * 24 * 60 * 60);

/// Entry point of the core. Create one per app process and share it.
#[derive(uniffi::Object)]
pub struct StremioCore {
    store: Mutex<Store>,
    pub(crate) client: AddonClient,
    pub(crate) streaming: Streaming,
    pub(crate) account: Account,
    pub(crate) aniskip: AniSkip,
    /// Manifests downloaded for the user to confirm, oldest first.
    previews: Mutex<Vec<InstalledAddon>>,
    /// The last home screen worked out, for a while (see `home.rs`).
    pub(crate) recent_home: Mutex<Option<RecentHome>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// Opens the core with its database inside `data_dir`, creating the directory if needed.
    #[uniffi::constructor]
    pub fn new(data_dir: String) -> Result<Arc<Self>, CoreError> {
        Self::open(data_dir, Account::new()?)
    }

    /// Installs the default addons the first time the app runs, and the ones a newer version
    /// of the list added after an update. Safe to call on every launch: it does nothing once
    /// they are installed, and retries on the next launch after a failure.
    pub async fn ensure_default_addons(&self) -> Result<(), CoreError> {
        let installed_version = self
            .store()?
            .setting(DEFAULTS_INSTALLED)?
            .and_then(|value| value.parse().ok())
            .unwrap_or(0);
        if installed_version >= DEFAULTS_VERSION {
            return Ok(());
        }
        let present: Vec<String> = self
            .store()?
            .addons()?
            .into_iter()
            .map(|addon| addon.manifest_url)
            .collect();
        let wanted = defaults_to_install(installed_version, &present);
        // Download the manifests at once, then save them in list order so Cinemeta comes
        // first. The ones that worked are kept; a failure is retried on the next launch.
        let fetched = join_all(wanted.iter().map(|url| self.client.install(url))).await;
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
        self.store()?
            .set_setting(DEFAULTS_INSTALLED, &DEFAULTS_VERSION.to_string())?;
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

    /// Downloads the manifest at `url` without installing it, so the user can see what the
    /// addon provides first, and whether it would replace one they have.
    /// The manifest is kept for a while, so installing it after the user confirms installs
    /// exactly what they saw (see [`StremioCore::install_addon`]).
    pub async fn preview_addon(&self, url: String) -> Result<AddonPreview, CoreError> {
        let addon = self.client.install(&url).await?;
        let preview = self.describe_preview(&addon)?;
        self.keep_preview(addon)?;
        Ok(preview)
    }

    /// Installs the addon at `url` that the user confirmed, whose [`AddonInfo::fingerprint`] is
    /// `confirmed`: from a preview (installed as previewed, without asking the addon again) or
    /// from a list of addons to discover (asked again, and installed only if it still does
    /// what the list said). When the addon now differs from what was confirmed, nothing is
    /// installed and the call fails with [`CoreError::AddonChanged`]; the fresh manifest is
    /// kept, so a [`StremioCore::preview_addon`] to show it again and an install of that
    /// preview need no further surprise.
    ///
    /// An addon already installed with the same id, at this address or another one (it was
    /// configured again), is replaced in its place, keeping its priority and enabled state,
    /// so the addon is never listed twice.
    pub async fn install_addon(
        &self,
        url: String,
        confirmed: String,
    ) -> Result<AddonInfo, CoreError> {
        let manifest_url = AddonUrl::parse(&url)
            .map_err(AddonError::from)?
            .manifest_url()
            .to_owned();
        let addon = match self.take_preview(&manifest_url, &confirmed)? {
            Some(previewed) => previewed,
            None => {
                let fetched = self.client.install(&url).await?;
                if manifest_fingerprint(&fetched.manifest) != confirmed {
                    self.keep_preview(fetched)?;
                    return Err(CoreError::AddonChanged);
                }
                fetched
            }
        };
        let json = manifest_json(&addon)?;
        let new_url = addon.url.manifest_url();
        let old_url = self
            .same_addon(&addon)?
            .map(|(installed, _)| installed.url.manifest_url().to_owned());
        let enabled = {
            let mut store = self.store()?;
            store.replace_addon(old_url.as_deref().unwrap_or(new_url), new_url, &json)?;
            store
                .addons()?
                .into_iter()
                .find(|a| a.manifest_url == new_url)
                .is_none_or(|a| a.enabled)
        };
        self.addons_changed()?;
        Ok(AddonInfo::new(&addon, enabled))
    }

    /// Addons to discover from Stremio's official and community lists, official first, each
    /// once. The lists are cached for a while and served from the cache when offline; the
    /// call fails only when neither list can be had.
    pub async fn discover_addons(&self) -> Result<Vec<CatalogAddon>, CoreError> {
        self.discover_addons_from(DISCOVER_SOURCE).await
    }

    pub fn remove_addon(&self, manifest_url: String) -> Result<(), CoreError> {
        self.store()?.remove_addon(&manifest_url)?;
        self.addons_changed()
    }

    pub fn set_addon_enabled(&self, manifest_url: String, enabled: bool) -> Result<(), CoreError> {
        self.store()?.set_addon_enabled(&manifest_url, enabled)?;
        self.addons_changed()
    }

    /// Moves an addon to `index` in the order; the order sets catalog and source priority.
    pub fn move_addon(&self, manifest_url: String, index: u32) -> Result<(), CoreError> {
        self.store()?.move_addon(&manifest_url, index as usize)?;
        self.addons_changed()
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

    /// Catalogs that can search, in addon order. Ask each with
    /// [`StremioCore::search_catalog`] to show results as they arrive.
    pub fn search_catalogs(&self) -> Result<Vec<CatalogInfo>, CoreError> {
        Ok(self
            .enabled()?
            .iter()
            .flat_map(|addon| {
                addon
                    .manifest
                    .catalogs
                    .iter()
                    .filter(|c| c.supports_extra("search"))
                    .map(move |c| CatalogInfo::new(addon, c))
            })
            .collect())
    }

    /// Results of one catalog for `query`; an empty list for a blank query.
    pub async fn search_catalog(
        &self,
        catalog: CatalogInfo,
        query: String,
    ) -> Result<Vec<MetaItem>, CoreError> {
        let query = query.trim();
        if query.is_empty() {
            return Ok(Vec::new());
        }
        let addon = self.find_addon(&catalog.addon_manifest_url)?;
        let descriptor = addon
            .manifest
            .catalogs
            .iter()
            .find(|c| c.id == catalog.catalog_id && c.kind == catalog.kind)
            .ok_or(CoreError::NotFound)?;
        let metas = self
            .client
            .catalog(&addon, descriptor, None, Some(query), 0)
            .await?;
        Ok(dedupe(metas.into_iter().map(MetaItem::from)))
    }

    /// Searches every catalog that supports search. Each catalog gives one row, in addon
    /// order, so results stay grouped by type the way the addons describe them; catalogs
    /// with no match are left out.
    pub async fn search(&self, query: String) -> Result<Vec<CatalogRow>, CoreError> {
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
        let mut rows = Vec::new();
        for ((addon, catalog), result) in targets.iter().zip(results) {
            match result {
                Ok(metas) => {
                    let items = dedupe(metas.into_iter().map(MetaItem::from));
                    if !items.is_empty() {
                        rows.push(CatalogRow {
                            catalog: CatalogInfo::new(addon, catalog),
                            items,
                        });
                    }
                }
                Err(error) => {
                    first_error.get_or_insert(error);
                }
            }
        }
        match (rows.is_empty(), first_error) {
            (true, Some(error)) => Err(error.into()),
            _ => Ok(rows),
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
        let store = self.store()?;
        store.remove_from_library(&id)?;
        self.note_removal(&store, &id)?;
        Ok(())
    }

    pub fn is_in_library(&self, id: String) -> Result<bool, CoreError> {
        Ok(self.store()?.is_in_library(&id)?)
    }
}

impl StremioCore {
    /// Opens the core with the given account client; tests point it at a fake server.
    pub(crate) fn open(data_dir: String, account: Account) -> Result<Arc<Self>, CoreError> {
        Self::open_with(data_dir, account, skips::new_client()?)
    }

    /// Opens the core with the given account and AniSkip clients; tests point them at fakes.
    pub(crate) fn open_with(
        data_dir: String,
        account: Account,
        aniskip: AniSkip,
    ) -> Result<Arc<Self>, CoreError> {
        let directory = PathBuf::from(data_dir);
        std::fs::create_dir_all(&directory).map_err(|e| CoreError::Storage {
            reason: e.to_string(),
        })?;
        let store = Store::open(&directory.join("stremionow.db"))?;
        let _ = store.prune_cache(now_millis() - millis(CACHE_RETENTION));
        Ok(Arc::new(Self {
            store: Mutex::new(store),
            client: AddonClient::new(ADDON_TIMEOUT)?,
            streaming: Streaming::new(directory.join("streams"))?,
            account,
            aniskip,
            previews: Mutex::new(Vec::new()),
            recent_home: Mutex::new(None),
        }))
    }

    /// Discover's lists, from the addon at `source`; tests point it at a fake server.
    async fn discover_addons_from(&self, source: &str) -> Result<Vec<CatalogAddon>, CoreError> {
        let source = AddonUrl::parse(source).map_err(AddonError::from)?;
        let official_url = AddonList::Official.url(&source);
        let community_url = AddonList::Community.url(&source);
        let (official, community) = futures::future::join(
            self.cached_json::<AddonCatalogResponse>(&official_url, DISCOVER_FRESHNESS),
            self.cached_json::<AddonCatalogResponse>(&community_url, DISCOVER_FRESHNESS),
        )
        .await;
        let (official, community) = match (official, community) {
            (Err(error), Err(_)) => return Err(error),
            (official, community) => (official.unwrap_or_default(), community.unwrap_or_default()),
        };
        Ok(discoverable(official, community)
            .iter()
            .map(|entry| CatalogAddon {
                addon: AddonInfo::new(&entry.addon, false),
                official: entry.official,
            })
            .collect())
    }

    fn describe_preview(&self, addon: &InstalledAddon) -> Result<AddonPreview, CoreError> {
        let replaces = self
            .same_addon(addon)?
            .map(|(installed, enabled)| AddonInfo::new(&installed, enabled));
        Ok(AddonPreview {
            addon: AddonInfo::new(addon, true),
            replaces,
        })
    }

    /// Remembers a downloaded manifest the user may confirm, replacing an older one from the
    /// same address and forgetting the oldest beyond [`MAX_PREVIEWS`].
    fn keep_preview(&self, addon: InstalledAddon) -> Result<(), CoreError> {
        let mut previews = self.previews()?;
        previews.retain(|kept| kept.url != addon.url);
        previews.push(addon);
        let excess = previews.len().saturating_sub(MAX_PREVIEWS);
        previews.drain(..excess);
        Ok(())
    }

    /// The previewed manifest from `manifest_url` if it is the one the user confirmed.
    fn take_preview(
        &self,
        manifest_url: &str,
        confirmed: &str,
    ) -> Result<Option<InstalledAddon>, CoreError> {
        let mut previews = self.previews()?;
        let position = previews.iter().position(|kept| {
            kept.url.manifest_url() == manifest_url
                && manifest_fingerprint(&kept.manifest) == confirmed
        });
        Ok(position.map(|index| previews.remove(index)))
    }

    fn previews(&self) -> Result<MutexGuard<'_, Vec<InstalledAddon>>, CoreError> {
        self.previews.lock().map_err(|_| CoreError::Storage {
            reason: "the preview lock was poisoned".into(),
        })
    }

    /// The installed addon with the same manifest id as `addon`, with its enabled flag.
    fn same_addon(
        &self,
        addon: &InstalledAddon,
    ) -> Result<Option<(InstalledAddon, bool)>, CoreError> {
        let id = &addon.manifest.id;
        let installed = self.installed()?;
        // The same address first, in case an older copy shares the id under another one.
        let exact = installed
            .iter()
            .position(|(a, _)| a.url == addon.url && &a.manifest.id == id);
        let any = installed.iter().position(|(a, _)| &a.manifest.id == id);
        Ok(exact
            .or(any)
            .and_then(|index| installed.into_iter().nth(index)))
    }

    /// Saves a downloaded addon, keeping its position and enabled state if it was installed.
    fn save_addon(&self, addon: &InstalledAddon) -> Result<AddonInfo, CoreError> {
        let json = manifest_json(addon)?;
        let store = self.store()?;
        store.upsert_addon(addon.url.manifest_url(), &json)?;
        let enabled = store
            .addons()?
            .into_iter()
            .find(|a| a.manifest_url == addon.url.manifest_url())
            .is_none_or(|a| a.enabled);
        Ok(AddonInfo::new(addon, enabled))
    }

    pub(crate) fn store(&self) -> Result<MutexGuard<'_, Store>, CoreError> {
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

    pub(crate) fn enabled(&self) -> Result<Vec<InstalledAddon>, CoreError> {
        Ok(self
            .installed()?
            .into_iter()
            .filter_map(|(addon, enabled)| enabled.then_some(addon))
            .collect())
    }

    pub(crate) fn find_addon(&self, manifest_url: &str) -> Result<InstalledAddon, CoreError> {
        self.enabled()?
            .into_iter()
            .find(|addon| addon.url.manifest_url() == manifest_url)
            .ok_or(CoreError::NotFound)
    }

    /// Fetches JSON through the cache: fresh entries skip the network, and stale entries are
    /// used when the network fails, so browsing keeps working offline.
    pub(crate) async fn cached_json<T: DeserializeOwned>(
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

/// Default addons to install when the list at `installed_version` is already in place:
/// the ones added later that are not installed yet, in list order.
fn defaults_to_install(installed_version: u32, present: &[String]) -> Vec<&'static str> {
    DEFAULT_ADDONS
        .iter()
        .filter(|(url, since)| *since > installed_version && !present.iter().any(|p| p == url))
        .map(|(url, _)| *url)
        .collect()
}

/// The manifest as stored in the database.
fn manifest_json(addon: &InstalledAddon) -> Result<String, CoreError> {
    serde_json::to_string(&addon.manifest).map_err(|e| CoreError::InvalidAddon {
        reason: e.to_string(),
    })
}

/// Removes repeated ids, keeping the first occurrence.
pub(crate) fn dedupe(items: impl IntoIterator<Item = MetaItem>) -> Vec<MetaItem> {
    let mut seen = std::collections::HashSet::new();
    items
        .into_iter()
        .filter(|item| !item.id.is_empty() && seen.insert((item.kind.clone(), item.id.clone())))
        .collect()
}

pub(crate) fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(millis)
        .unwrap_or_default()
}

pub(crate) fn millis(duration: Duration) -> i64 {
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

    /// Installs the addon at `url` as the app does: preview, then install what was previewed.
    async fn install(core: &StremioCore, url: String) -> Result<AddonInfo, CoreError> {
        let preview = core.preview_addon(url.clone()).await?;
        core.install_addon(url, preview.addon.fingerprint).await
    }

    fn core() -> (Arc<StremioCore>, tempfile::TempDir) {
        let directory = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(directory.path().to_string_lossy().into_owned()).expect("core");
        (core, directory)
    }

    #[tokio::test]
    async fn disabled_and_removed_addons_are_ignored() {
        let server = addon_server("A").await;
        let (core, _dir) = core();
        let info = install(&core, format!("{}/manifest.json", server.uri()))
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
        install(&core, format!("{}/manifest.json", server.uri()))
            .await
            .expect("installs");
        assert!(core.search("   ".into()).await.expect("blank").is_empty());
        let rows = core.search("  office ".into()).await.expect("search");
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].catalog.catalog_id, "search");
        assert_eq!(rows[0].items[0].id, "tt9");
    }

    #[tokio::test]
    async fn searches_one_catalog_at_a_time() {
        let server = addon_server("A").await;
        Mock::given(method("GET"))
            .and(path("/catalog/movie/search/search=office.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "metas": [{"id": "tt9", "type": "movie", "name": "Office"}]
            })))
            .mount(&server)
            .await;
        let (core, _dir) = core();
        install(&core, format!("{}/manifest.json", server.uri()))
            .await
            .expect("installs");
        let catalogs = core.search_catalogs().expect("lists");
        assert_eq!(catalogs.len(), 1);
        let items = core
            .search_catalog(catalogs[0].clone(), " office ".into())
            .await
            .expect("searches");
        assert_eq!(items[0].id, "tt9");
        assert!(
            core.search_catalog(catalogs[0].clone(), "  ".into())
                .await
                .expect("blank")
                .is_empty()
        );
    }

    #[test]
    fn defaults_cover_new_installs_and_updates() {
        let all = defaults_to_install(0, &[]);
        assert_eq!(all.len(), DEFAULT_ADDONS.len());
        assert!(all[0].contains("cinemeta"), "Cinemeta comes first");

        // An install from version 1 only gets the additions, minus any already present.
        let present = vec!["https://watchhub.strem.io/manifest.json".to_owned()];
        let update = defaults_to_install(1, &present);
        assert_eq!(
            update,
            vec![
                "https://v3-channels.strem.io/manifest.json",
                "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json",
            ]
        );
        assert!(defaults_to_install(DEFAULTS_VERSION, &[]).is_empty());
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

    /// A server for Discover's lists, answering the official and community lists.
    async fn list_server(official: ResponseTemplate, community: ResponseTemplate) -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/addon_catalog/all/official.json"))
            .respond_with(official)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/addon_catalog/all/community.json"))
            .respond_with(community)
            .mount(&server)
            .await;
        server
    }

    fn entry(url: &str, id: &str, extra: serde_json::Value) -> serde_json::Value {
        let mut manifest = serde_json::json!({"id": id, "version": "1.0.0", "name": id});
        if let (Some(target), Some(fields)) = (manifest.as_object_mut(), extra.as_object()) {
            target.extend(fields.clone());
        }
        serde_json::json!({"transportUrl": url, "transportName": "http", "manifest": manifest})
    }

    fn list(entries: Vec<serde_json::Value>) -> ResponseTemplate {
        ResponseTemplate::new(200).set_body_json(serde_json::json!({ "addons": entries }))
    }

    #[tokio::test]
    async fn discovers_addons_and_serves_the_lists_from_the_cache() {
        let none = serde_json::json!({});
        let server = list_server(
            list(vec![
                entry(
                    "https://cinemeta.example/manifest.json",
                    "cinemeta",
                    none.clone(),
                ),
                entry(
                    "http://127.0.0.1:11470/local-addon/manifest.json",
                    "local",
                    none.clone(),
                ),
            ]),
            list(vec![
                entry(
                    "https://debrid.example/manifest.json",
                    "debrid",
                    serde_json::json!({
                        "types": ["movie", "anime"], "logo": "https://debrid.example/logo.png",
                        "behaviorHints": {"configurable": true, "configurationRequired": true}
                    }),
                ),
                entry("https://cinemeta.example/manifest.json", "cinemeta", none),
                serde_json::json!({"transportUrl": "https://broken.example/manifest.json"}),
            ]),
        )
        .await;
        let (core, _dir) = core();
        let source = format!("{}/manifest.json", server.uri());
        let found = core.discover_addons_from(&source).await.expect("lists");
        let ids: Vec<_> = found
            .iter()
            .map(|a| (a.addon.id.as_str(), a.official))
            .collect();
        assert_eq!(ids, [("cinemeta", true), ("debrid", false)]);
        let debrid = &found[1].addon;
        assert_eq!(debrid.types, ["movie", "anime"]);
        assert!(debrid.configuration_required);
        assert_eq!(
            debrid.configure_url.as_deref(),
            Some("https://debrid.example/configure")
        );

        drop(server);
        let cached = core.discover_addons_from(&source).await.expect("cached");
        assert_eq!(cached, found);
    }

    #[tokio::test]
    async fn discover_keeps_the_list_that_answers() {
        let server = list_server(
            ResponseTemplate::new(503),
            list(vec![entry(
                "https://a.example/manifest.json",
                "a",
                serde_json::json!({}),
            )]),
        )
        .await;
        let (core, _dir) = core();
        let found = core
            .discover_addons_from(&format!("{}/manifest.json", server.uri()))
            .await
            .expect("partial");
        assert_eq!(found.len(), 1);
        assert!(!found[0].official);
    }

    #[tokio::test]
    async fn discover_fails_when_no_list_can_be_read() {
        let (core, _dir) = core();
        let down = list_server(ResponseTemplate::new(500), ResponseTemplate::new(404)).await;
        assert!(matches!(
            core.discover_addons_from(&format!("{}/manifest.json", down.uri()))
                .await,
            Err(CoreError::Network { .. })
        ));
        let garbage = list_server(
            ResponseTemplate::new(200).set_body_string("<html>"),
            ResponseTemplate::new(200).set_body_json(serde_json::json!([1, 2])),
        )
        .await;
        assert!(matches!(
            core.discover_addons_from(&format!("{}/manifest.json", garbage.uri()))
                .await,
            Err(CoreError::InvalidAddon { .. })
        ));
        let empty = list_server(list(vec![]), list(vec![])).await;
        assert!(
            core.discover_addons_from(&format!("{}/manifest.json", empty.uri()))
                .await
                .expect("empty lists")
                .is_empty()
        );
    }

    #[tokio::test]
    async fn previews_and_replaces_a_reconfigured_addon_in_place() {
        let first = addon_server("A").await;
        let other = addon_server("B").await;
        let reconfigured = addon_server("A").await;
        let (core, _dir) = core();
        let first_url = format!("{}/manifest.json", first.uri());
        let other_url = format!("{}/manifest.json", other.uri());
        install(&core, first_url.clone()).await.expect("installs");
        install(&core, other_url.clone()).await.expect("installs");
        core.set_addon_enabled(first_url.clone(), false)
            .expect("disables");

        let new_url = format!("{}/manifest.json", reconfigured.uri());
        let preview = core.preview_addon(new_url.clone()).await.expect("previews");
        assert_eq!(preview.addon.id, "test.A");
        let replaces = preview.replaces.expect("same id is installed");
        assert_eq!(replaces.manifest_url, first_url);
        assert_eq!(
            core.addons().expect("lists").len(),
            2,
            "preview installs nothing"
        );

        let installed = core
            .install_addon(new_url.clone(), preview.addon.fingerprint.clone())
            .await
            .expect("replaces");
        assert!(!installed.enabled, "keeps the enabled state");
        let urls: Vec<_> = core
            .addons()
            .expect("lists")
            .into_iter()
            .map(|a| a.manifest_url)
            .collect();
        assert_eq!(urls, [new_url.clone(), other_url]);

        let again = core.preview_addon(new_url.clone()).await.expect("previews");
        assert_eq!(
            again.replaces.map(|a| a.manifest_url),
            Some(new_url),
            "the same address only refreshes"
        );
        let unrelated = addon_server("C").await;
        let fresh = core
            .preview_addon(format!("{}/manifest.json", unrelated.uri()))
            .await
            .expect("previews");
        assert!(fresh.replaces.is_none());
    }

    /// Serves a manifest with `resources` at /manifest.json.
    async fn serve_manifest(server: &MockServer, resources: serde_json::Value) {
        server.reset().await;
        Mock::given(method("GET"))
            .and(path("/manifest.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "id": "test.shifty", "version": "1.0.0", "name": "Shifty",
                "resources": resources, "types": ["movie"]
            })))
            .mount(server)
            .await;
    }

    #[tokio::test]
    async fn installs_exactly_the_previewed_manifest() {
        let server = MockServer::start().await;
        serve_manifest(&server, serde_json::json!(["subtitles"])).await;
        let (core, _dir) = core();
        let url = format!("{}/manifest.json", server.uri());
        let preview = core.preview_addon(url.clone()).await.expect("previews");

        // The addon changes its mind between the confirmation and the install.
        serve_manifest(&server, serde_json::json!(["subtitles", "stream", "meta"])).await;
        let installed = core
            .install_addon(url.clone(), preview.addon.fingerprint.clone())
            .await
            .expect("installs");
        assert!(installed.provides_subtitles);
        assert!(
            !installed.provides_streams && !installed.provides_metadata,
            "what was confirmed, not what the addon serves now"
        );
        assert!(
            !core.addons().expect("lists")[0].provides_streams,
            "and that is what is stored"
        );
    }

    #[tokio::test]
    async fn refuses_an_addon_that_changed_since_it_was_listed() {
        let server = MockServer::start().await;
        serve_manifest(&server, serde_json::json!(["stream"])).await;
        let (core, _dir) = core();
        let url = format!("{}/manifest.json", server.uri());
        // What a list showed, without a preview: the addon is asked again at install.
        let listed = core
            .client
            .install(&url)
            .await
            .map(|addon| AddonInfo::new(&addon, false))
            .expect("fetches");
        let installed = core
            .install_addon(url.clone(), listed.fingerprint.clone())
            .await
            .expect("unchanged, so it installs");
        assert!(installed.provides_streams);
        core.remove_addon(installed.manifest_url).expect("removes");

        serve_manifest(&server, serde_json::json!(["stream", "meta"])).await;
        let result = core.install_addon(url.clone(), listed.fingerprint).await;
        assert!(matches!(result, Err(CoreError::AddonChanged)), "{result:?}");
        assert!(
            core.addons().expect("lists").is_empty(),
            "nothing installed"
        );

        // Shown again and confirmed, the new manifest installs as shown.
        let again = core.preview_addon(url.clone()).await.expect("previews");
        assert!(again.addon.provides_metadata);
        core.install_addon(url.clone(), again.addon.fingerprint)
            .await
            .expect("installs");
        assert!(
            matches!(
                core.install_addon(url, "not a fingerprint".into()).await,
                Err(CoreError::AddonChanged)
            ),
            "an install nobody confirmed is refused"
        );
    }

    #[tokio::test]
    async fn rejects_invalid_addon_urls() {
        let (core, _dir) = core();
        let result = core
            .install_addon("file:///etc/passwd".into(), String::new())
            .await;
        assert!(matches!(result, Err(CoreError::InvalidAddon { .. })));
    }
}
