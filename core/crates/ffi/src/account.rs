// SPDX-License-Identifier: GPL-3.0-or-later
//! Optional Stremio account: sign-in, addons and library sync.
//!
//! The session key lives in memory only; the app keeps it encrypted (Android Keystore) and
//! hands it back with [`StremioCore::restore_session`] on launch. Without an account nothing
//! here runs and the app works locally.
//!
//! Besides email and password, the account can be signed in to elsewhere: with a link code
//! confirmed on another device, or with Facebook or Apple in the system browser. Those poll
//! the official services until the sign-in is done, a limit is reached or the app cancels.

use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::sync::Mutex;
use std::time::Duration;

use stremionow_library::{LibraryEntry, ProgressEntry, Store};
use stremionow_protocol::AddonUrl;
use stremionow_sync::{RemoteLibraryItem, Session, StremioApi, SyncError, merge_library};
use tokio::sync::watch;

use crate::core::{StremioCore, now_millis};
use crate::records::CoreError;

/// Requests to the Stremio API give up after this long.
const API_TIMEOUT: Duration = Duration::from_secs(20);
/// Setting with the signed-in email, shown in the profile. Not a secret.
const ACCOUNT_EMAIL: &str = "account_email";
/// Setting set when the addon list changed here and the account has not received it yet.
const ADDONS_CHANGED: &str = "sync_addons_changed";
/// Setting with titles removed from My list since the last sync, as `{id: removed_at}`.
const PENDING_REMOVALS: &str = "sync_pending_removals";
/// Setting with the time of the last successful sync, in milliseconds since the epoch.
const LAST_SYNC: &str = "sync_last_at";
/// Library items of this type are never uploaded, as in the official apps.
const SKIPPED_KIND: &str = "other";

/// How a sign-in that finishes elsewhere is waited for: a request every `interval`, at most
/// `tries` times.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Polling {
    interval: Duration,
    tries: u32,
}

/// Facebook in the browser: every second, 25 times, as stremio-web (`useSocialLogin.ts`).
const FACEBOOK_POLLING: Polling = Polling {
    interval: Duration::from_secs(1),
    tries: 25,
};
/// Apple in the browser: every 2 seconds, 25 times, as stremio-web.
const APPLE_POLLING: Polling = Polling {
    interval: Duration::from_secs(2),
    tries: 25,
};
/// A link code: the official core leaves polling to the apps. Every 5 seconds for 5 minutes
/// gives time to pick up a phone, scan the code and sign in there; after that the app offers
/// a new code.
const LINK_POLLING: Polling = Polling {
    interval: Duration::from_secs(5),
    tries: 60,
};

/// The account half of the core.
pub(crate) struct Account {
    api: StremioApi,
    auth_key: Mutex<Option<String>>,
    /// Bumped by `cancel_sign_in`; every sign-in that is waiting stops.
    cancel: watch::Sender<u64>,
    facebook_polling: Polling,
    apple_polling: Polling,
    link_polling: Polling,
}

impl Account {
    pub(crate) fn new() -> Result<Self, CoreError> {
        Ok(Self::with_api(StremioApi::new(API_TIMEOUT)?))
    }

    fn with_api(api: StremioApi) -> Self {
        Self {
            api,
            auth_key: Mutex::new(None),
            cancel: watch::Sender::new(0),
            facebook_polling: FACEBOOK_POLLING,
            apple_polling: APPLE_POLLING,
            link_polling: LINK_POLLING,
        }
    }

    /// An account client talking to another server for every service, polling quickly, for
    /// tests.
    #[cfg(test)]
    pub(crate) fn with_base_url(url: &str) -> Result<Self, CoreError> {
        let api = StremioApi::new(API_TIMEOUT)?
            .with_base_url(url)
            .with_web_url(url)
            .with_link_url(url);
        let quick = Polling {
            interval: Duration::from_millis(10),
            tries: 5,
        };
        Ok(Self {
            facebook_polling: quick,
            apple_polling: quick,
            link_polling: quick,
            ..Self::with_api(api)
        })
    }

    fn auth_key(&self) -> Option<String> {
        self.auth_key.lock().ok().and_then(|key| key.clone())
    }

    fn set_auth_key(&self, key: Option<String>) {
        if let Ok(mut current) = self.auth_key.lock() {
            *current = key;
        }
    }

    pub(crate) fn signed_in(&self) -> bool {
        self.auth_key().is_some()
    }
}

impl From<SyncError> for CoreError {
    fn from(error: SyncError) -> Self {
        match error {
            SyncError::InvalidCredentials => Self::SignIn {
                reason: "wrong email or password".into(),
            },
            SyncError::SessionExpired => Self::SignIn {
                reason: "the session has expired".into(),
            },
            other => Self::Network {
                reason: other.to_string(),
            },
        }
    }
}

/// Result of a sync, for the profile screen.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SyncSummary {
    pub downloaded: u32,
    pub uploaded: u32,
}

/// A code to confirm on another device where the user is signed in to Stremio.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct LinkCode {
    /// The code, shown on screen.
    pub code: String,
    /// The page where it is confirmed, always `https`: shown as a QR code and opened in the
    /// browser.
    pub link: String,
}

impl std::fmt::Debug for LinkCode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("LinkCode { .. }")
    }
}

/// A sign-in service that runs in the system browser.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BrowserSignInProvider {
    Facebook,
    Apple,
}

/// A browser sign-in in progress: the page to open, and the random state to wait with.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct BrowserSignIn {
    /// The `https` page to open in the system browser (never in a web view inside the app).
    pub url: String,
    /// 128 random bits as hex; whoever knows it can collect the sign-in, so it is never logged.
    pub state: String,
}

impl std::fmt::Debug for BrowserSignIn {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("BrowserSignIn { .. }")
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// Signs in to Stremio, replaces the addons with the account's and merges the library.
    /// Returns the session key for the app to keep encrypted; it is never stored here.
    pub async fn sign_in(&self, email: String, password: String) -> Result<String, CoreError> {
        let session = self.account.api.login(email.trim(), &password).await?;
        self.signed_in(session).await
    }

    /// Asks for a code to confirm on another device, the main way to sign in on TV. Wait for
    /// the confirmation with [`StremioCore::sign_in_with_link_code`].
    pub async fn create_link_code(&self) -> Result<LinkCode, CoreError> {
        let code = self.account.api.create_link_code().await?;
        // The link becomes a QR code and is opened in the browser: web pages only.
        if !code.link.starts_with("https://") || code.code.is_empty() {
            return Err(CoreError::Network {
                reason: "the link-code service sent an unusable code".into(),
            });
        }
        Ok(LinkCode {
            code: code.code,
            link: code.link,
        })
    }

    /// Waits until `code` is confirmed on another device, then signs in as
    /// [`StremioCore::sign_in`] does. Fails with [`CoreError::SignInNotConfirmed`] after a few
    /// minutes and with [`CoreError::SignInCancelled`] after [`StremioCore::cancel_sign_in`].
    pub async fn sign_in_with_link_code(&self, code: String) -> Result<String, CoreError> {
        let api = &self.account.api;
        let key = self
            .wait_for(self.account.link_polling, || api.read_link_code(&code))
            .await?;
        // The service gives only the key; the account's email comes from the API.
        let user = api.user(&key).await?;
        self.signed_in(Session {
            auth_key: key,
            user,
        })
        .await
    }

    /// Starts a sign-in with Facebook or Apple: the page to open in the system browser, with a
    /// new random state. Wait for it with [`StremioCore::sign_in_with_browser`].
    pub fn start_browser_sign_in(
        &self,
        provider: BrowserSignInProvider,
    ) -> Result<BrowserSignIn, CoreError> {
        let mut bytes = [0_u8; 16];
        getrandom::fill(&mut bytes).map_err(|e| CoreError::SignIn {
            reason: format!("no randomness: {e}"),
        })?;
        let state = stremionow_sync::state_from_bytes(bytes);
        let url = match provider {
            BrowserSignInProvider::Facebook => self.account.api.facebook_login_url(&state),
            BrowserSignInProvider::Apple => self.account.api.apple_login_url(&state),
        };
        Ok(BrowserSignIn { url, state })
    }

    /// Waits until the browser sign-in started with `state` is done, then signs in as
    /// [`StremioCore::sign_in`] does. Fails with [`CoreError::SignInNotConfirmed`] after the
    /// official app's 25 tries and with [`CoreError::SignInCancelled`] after
    /// [`StremioCore::cancel_sign_in`].
    pub async fn sign_in_with_browser(
        &self,
        provider: BrowserSignInProvider,
        state: String,
    ) -> Result<String, CoreError> {
        if !stremionow_sync::is_valid_state(&state) {
            return Err(CoreError::SignIn {
                reason: "invalid sign-in state".into(),
            });
        }
        let api = &self.account.api;
        let session = match provider {
            BrowserSignInProvider::Facebook => {
                let credentials = self
                    .wait_for(self.account.facebook_polling, || {
                        api.facebook_credentials(&state)
                    })
                    .await?;
                api.login_with_facebook(&credentials).await?
            }
            BrowserSignInProvider::Apple => {
                let credentials = self
                    .wait_for(self.account.apple_polling, || api.apple_credentials(&state))
                    .await?;
                api.login_with_apple(&credentials).await?
            }
        };
        self.signed_in(session).await
    }

    /// Stops every sign-in that is waiting for a confirmation, as when its dialog closes. One
    /// that already has the account goes on and finishes.
    pub fn cancel_sign_in(&self) {
        self.account.cancel.send_modify(|generation| {
            *generation = generation.wrapping_add(1);
        });
    }

    /// Resumes a session saved by the app, without contacting the server.
    pub fn restore_session(&self, auth_key: String) {
        self.account
            .set_auth_key(Some(auth_key).filter(|key| !key.is_empty()));
    }

    /// The signed-in account's email, if any.
    pub fn account_email(&self) -> Result<Option<String>, CoreError> {
        if !self.account.signed_in() {
            return Ok(None);
        }
        Ok(self.store()?.setting(ACCOUNT_EMAIL)?)
    }

    /// When the account last synced, in milliseconds since the epoch; `None` when signed out or
    /// never synced.
    pub fn last_synced_at(&self) -> Result<Option<i64>, CoreError> {
        if !self.account.signed_in() {
            return Ok(None);
        }
        Ok(self
            .store()?
            .setting(LAST_SYNC)?
            .and_then(|value| value.parse().ok())
            .filter(|&time: &i64| time > 0))
    }

    /// Signs out. Local addons, list and progress stay on the device.
    pub async fn sign_out(&self) -> Result<(), CoreError> {
        if let Some(key) = self.account.auth_key() {
            // Best effort: the session is forgotten here even if the server cannot be reached.
            let _ = self.account.api.logout(&key).await;
        }
        self.account.set_auth_key(None);
        let store = self.store()?;
        store.set_setting(ACCOUNT_EMAIL, "")?;
        store.set_setting(PENDING_REMOVALS, "{}")?;
        store.set_setting(ADDONS_CHANGED, "0")?;
        store.set_setting(LAST_SYNC, "")?;
        Ok(())
    }

    /// Brings the account's addon list, or sends this device's if it changed here since
    /// the last sync, and merges the library. Does nothing when signed out.
    pub async fn sync(&self) -> Result<SyncSummary, CoreError> {
        let Some(key) = self.account.auth_key() else {
            return Ok(SyncSummary {
                downloaded: 0,
                uploaded: 0,
            });
        };
        if self.store()?.setting(ADDONS_CHANGED)?.as_deref() == Some("1") {
            self.push_addons(&key).await?;
            self.store()?.set_setting(ADDONS_CHANGED, "0")?;
        } else {
            self.pull_addons(&key).await?;
        }
        self.sync_library(&key).await
    }
}

impl StremioCore {
    /// Keeps the session and brings the account's addons and library, after any way of
    /// signing in. Returns the key for the app to keep in the platform's secret store.
    async fn signed_in(&self, session: Session) -> Result<String, CoreError> {
        self.account.set_auth_key(Some(session.auth_key.clone()));
        self.store()?
            .set_setting(ACCOUNT_EMAIL, &session.user.email)?;
        // Signed in from here on. A first sync that fails (offline, server hiccup) must not
        // look like a failed sign-in, or the app would drop a session that is still live;
        // the next sync retries.
        if let Err(error) = self.pull_addons(&session.auth_key).await {
            log::warn!("first addon sync failed: {error}");
        } else if let Err(error) = self.sync_library(&session.auth_key).await {
            log::warn!("first library sync failed: {error}");
        }
        Ok(session.auth_key)
    }

    /// Calls `attempt` every `polling.interval` until it gives a value, `polling.tries` times
    /// at most, or until [`StremioCore::cancel_sign_in`]. Failed attempts count as "not yet",
    /// as in the official app: the user may still be signing in.
    async fn wait_for<T, F, Fut>(&self, polling: Polling, mut attempt: F) -> Result<T, CoreError>
    where
        F: FnMut() -> Fut,
        Fut: Future<Output = Result<Option<T>, SyncError>>,
    {
        let mut cancelled = self.account.cancel.subscribe();
        cancelled.borrow_and_update();
        let waiting = async {
            for _ in 0..polling.tries {
                tokio::time::sleep(polling.interval).await;
                match attempt().await {
                    Ok(Some(value)) => return Ok(value),
                    Ok(None) => {}
                    Err(error) => log::debug!("sign-in not confirmed yet: {error}"),
                }
            }
            Err(CoreError::SignInNotConfirmed)
        };
        tokio::select! {
            result = waiting => result,
            _ = cancelled.changed() => Err(CoreError::SignInCancelled),
        }
    }

    /// Marks the addon list as changed here, so the next sync sends it instead of
    /// replacing it with the account's.
    pub(crate) fn addons_changed(&self) -> Result<(), CoreError> {
        if self.account.signed_in() {
            self.store()?.set_setting(ADDONS_CHANGED, "1")?;
        }
        Ok(())
    }

    /// Records a removal from My list so the next sync removes it from the account too.
    pub(crate) fn note_removal(&self, store: &Store, id: &str) -> Result<(), CoreError> {
        if !self.account.signed_in() {
            return Ok(());
        }
        let mut pending = pending_removals(store)?;
        pending.insert(id.to_owned(), now_millis());
        store.set_setting(
            PENDING_REMOVALS,
            &serde_json::to_string(&pending).unwrap_or_default(),
        )?;
        Ok(())
    }

    /// Replaces the local addons with the account's, from the manifests it stores, so no
    /// addon has to be reached. Entries this app cannot use (legacy or local-only) are skipped.
    async fn pull_addons(&self, key: &str) -> Result<(), CoreError> {
        let remote = self.account.api.addon_collection(key).await?;
        let usable: Vec<(String, String)> = remote
            .iter()
            .filter(|addon| addon.parsed_manifest().is_ok())
            .filter_map(|addon| {
                let url = AddonUrl::parse(&addon.transport_url).ok()?;
                let manifest = serde_json::to_string(&addon.manifest).ok()?;
                Some((url.manifest_url().to_owned(), manifest))
            })
            .collect();
        if usable.is_empty() {
            return Ok(());
        }
        let store = self.store()?;
        for addon in store.addons()? {
            store.remove_addon(&addon.manifest_url)?;
        }
        for (url, manifest) in &usable {
            store.upsert_addon(url, manifest)?;
        }
        // The account brings its own addons; do not add the local defaults on top.
        store.set_setting(
            crate::core::DEFAULTS_INSTALLED,
            &crate::core::DEFAULTS_VERSION.to_string(),
        )?;
        Ok(())
    }

    /// Uploads the local addon list, in order, as the account's collection.
    async fn push_addons(&self, key: &str) -> Result<(), CoreError> {
        let addons: Vec<stremionow_sync::RemoteAddon> = self
            .store()?
            .addons()?
            .into_iter()
            .filter_map(|addon| {
                let manifest = serde_json::from_str(&addon.manifest_json).ok()?;
                Some(stremionow_sync::RemoteAddon::new(
                    addon.manifest_url,
                    manifest,
                ))
            })
            .collect();
        if addons.is_empty() {
            return Ok(());
        }
        Ok(self.account.api.set_addon_collection(key, &addons).await?)
    }

    /// Two-way library merge: the newest change to each title wins, on either side.
    async fn sync_library(&self, key: &str) -> Result<SyncSummary, CoreError> {
        let remote = self.account.api.library(key).await?;
        let (local, local_mtimes) = self.local_items(&remote)?;
        let result = merge_library(&local, &remote);

        let mut downloaded = 0;
        {
            let store = self.store()?;
            for item in &result.merged {
                let local_mtime = local_mtimes.get(&item.id).copied();
                let remote_newer = item.mtime_ms().unwrap_or(0) > local_mtime.unwrap_or(i64::MIN);
                if remote_newer {
                    apply_remote(&store, item)?;
                    downloaded += 1;
                }
            }
        }

        let upload: Vec<RemoteLibraryItem> = result
            .to_upload
            .into_iter()
            .filter(|item| item.kind != SKIPPED_KIND)
            .collect();
        if !upload.is_empty() {
            self.account.api.put_library(key, &upload).await?;
        }
        let store = self.store()?;
        store.set_setting(PENDING_REMOVALS, "{}")?;
        store.set_setting(LAST_SYNC, &now_millis().to_string())?;
        Ok(SyncSummary {
            downloaded,
            uploaded: u32::try_from(upload.len()).unwrap_or(u32::MAX),
        })
    }

    /// Local titles as account library items, built on the account's copy when there is one
    /// so fields this app does not use survive. Also returns each title's last local change.
    fn local_items(
        &self,
        remote: &[RemoteLibraryItem],
    ) -> Result<(Vec<RemoteLibraryItem>, HashMap<String, i64>), CoreError> {
        let store = self.store()?;
        let by_id: HashMap<&str, &RemoteLibraryItem> =
            remote.iter().map(|item| (item.id.as_str(), item)).collect();
        let library = store.library()?;
        let saved: HashSet<&str> = library.iter().map(|entry| entry.id.as_str()).collect();
        let progress: HashMap<String, ProgressEntry> = store
            .progress_list(usize::MAX)?
            .into_iter()
            .map(|entry| (entry.id.clone(), entry))
            .collect();

        let mut items = Vec::new();
        let mut mtimes = HashMap::new();
        for entry in &library {
            let saved_progress = progress.get(&entry.id);
            let mtime = saved_progress.map_or(entry.added_at, |p| p.updated_at.max(entry.added_at));
            let mut item = base_item(
                &by_id,
                &entry.id,
                &entry.kind,
                &entry.name,
                &entry.poster,
                entry.added_at,
            );
            item.removed = false;
            item.temp = false;
            if let Some(p) = saved_progress {
                set_progress(&mut item, p);
            }
            item.touch(mtime);
            mtimes.insert(entry.id.clone(), mtime);
            items.push(item);
        }
        for (id, p) in &progress {
            if saved.contains(id.as_str()) {
                continue;
            }
            // Watched but not saved: Stremio keeps these as temporary items.
            let mut item = base_item(&by_id, id, &p.kind, &p.name, &p.poster, p.updated_at);
            item.temp = true;
            set_progress(&mut item, p);
            item.touch(p.updated_at);
            mtimes.insert(id.clone(), p.updated_at);
            items.push(item);
        }
        for (id, removed_at) in pending_removals(&store)? {
            if saved.contains(id.as_str()) {
                continue;
            }
            if let Some(remote_item) = by_id.get(id.as_str()) {
                let mut item = (*remote_item).clone();
                item.removed = true;
                item.touch(removed_at);
                mtimes.insert(id, removed_at);
                items.push(item);
            }
        }
        Ok((items, mtimes))
    }
}

/// The account's copy of a title, or a new item for it.
fn base_item(
    by_id: &HashMap<&str, &RemoteLibraryItem>,
    id: &str,
    kind: &str,
    name: &str,
    poster: &Option<String>,
    created_at: i64,
) -> RemoteLibraryItem {
    by_id.get(id).map_or_else(
        || RemoteLibraryItem::new(id, kind, name, poster.clone(), created_at),
        |item| (*item).clone(),
    )
}

fn set_progress(item: &mut RemoteLibraryItem, progress: &ProgressEntry) {
    item.state.video_id = Some(progress.video_id.clone());
    item.state.time_offset = u64::try_from(progress.position_ms).unwrap_or(0);
    item.state.duration = u64::try_from(progress.duration_ms).unwrap_or(0);
    item.state.last_watched = Some(stremionow_sync::format_timestamp(progress.updated_at));
}

/// Brings a title's account state into the local list and progress.
fn apply_remote(store: &Store, item: &RemoteLibraryItem) -> Result<(), CoreError> {
    let mtime = item.mtime_ms().unwrap_or(0);
    if item.in_library() {
        let added_at = item
            .ctime
            .as_deref()
            .and_then(stremionow_sync::parse_timestamp)
            .unwrap_or(mtime);
        store.add_to_library(&LibraryEntry {
            id: item.id.clone(),
            kind: item.kind.clone(),
            name: item.name.clone(),
            poster: item.poster.clone(),
            added_at,
        })?;
    } else if store.is_in_library(&item.id)? {
        store.remove_from_library(&item.id)?;
    }
    if let Some((video_id, offset, duration)) = item.progress() {
        // u128: the numbers come from other clients and may be anything.
        let unfinished =
            duration > 0 && offset > 0 && u128::from(offset) * 100 < u128::from(duration) * 92;
        if unfinished && !item.removed {
            store.save_progress(&ProgressEntry {
                id: item.id.clone(),
                kind: item.kind.clone(),
                name: item.name.clone(),
                poster: item.poster.clone(),
                background: None,
                video_id: video_id.to_owned(),
                season: None,
                episode: None,
                position_ms: i64::try_from(offset).unwrap_or(i64::MAX),
                duration_ms: i64::try_from(duration).unwrap_or(i64::MAX),
                updated_at: mtime,
            })?;
        }
    }
    Ok(())
}

fn pending_removals(store: &Store) -> Result<HashMap<String, i64>, CoreError> {
    Ok(store
        .setting(PENDING_REMOVALS)?
        .and_then(|json| serde_json::from_str(&json).ok())
        .unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{body_partial_json, method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn manifest(id: &str) -> serde_json::Value {
        serde_json::json!({
            "id": id, "version": "1.0.0", "name": id, "resources": ["catalog"],
            "types": ["movie"], "catalogs": []
        })
    }

    fn ok() -> ResponseTemplate {
        ResponseTemplate::new(200).set_body_json(serde_json::json!({"result": {"success": true}}))
    }

    async fn server() -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"authKey": "secret-key", "user": {"_id": "u1", "email": "me@example.com"}}
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/addonCollectionGet"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"addons": [
                    {"transportUrl": "https://a.example/manifest.json", "manifest": manifest("a"), "flags": {}},
                    {"transportUrl": "https://opensubtitles.strem.io/stremio/v1", "manifest": {}, "flags": {}}
                ]}
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/datastoreGet"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": [{
                    "_id": "tt1", "name": "Remote", "type": "movie", "removed": false, "temp": false,
                    "_ctime": "2026-01-01T00:00:00.000Z", "_mtime": "2026-01-02T00:00:00.000Z",
                    "state": {"timeOffset": 600000, "duration": 6000000, "video_id": "tt1"},
                    "futureField": 7
                }]
            })))
            .mount(&server)
            .await;
        server
    }

    fn core_for(server: &MockServer, dir: &tempfile::TempDir) -> std::sync::Arc<StremioCore> {
        StremioCore::open(
            dir.path().to_string_lossy().into_owned(),
            Account::with_base_url(&server.uri()).expect("account"),
        )
        .expect("core")
    }

    #[tokio::test]
    async fn sign_in_brings_addons_list_and_progress() {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);

        let key = core
            .sign_in("me@example.com".into(), "pw".into())
            .await
            .expect("signs in");
        assert_eq!(key, "secret-key");
        assert_eq!(
            core.account_email().expect("reads").as_deref(),
            Some("me@example.com")
        );

        let addons = core.addons().expect("lists");
        assert_eq!(addons.len(), 1, "the legacy addon is skipped");
        assert_eq!(addons[0].manifest_url, "https://a.example/manifest.json");

        assert!(core.is_in_library("tt1".into()).expect("reads"));
        let progress = core.progress("tt1".into()).expect("reads").expect("synced");
        assert_eq!(progress.position_ms, 600_000);

        core.sign_out().await.expect("signs out");
        assert_eq!(core.account_email().expect("reads"), None);
        assert!(
            core.is_in_library("tt1".into()).expect("reads"),
            "local data stays"
        );
    }

    #[tokio::test]
    async fn removals_reach_the_account() {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .and(body_partial_json(serde_json::json!({
                "changes": [{"_id": "tt1", "removed": true, "futureField": 7}]
            })))
            .respond_with(ok())
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/addonCollectionSet"))
            .respond_with(ok())
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());

        // The title is on the account but was just removed here, so the removal must win.
        core.remove_from_library("tt1".into()).expect("removes");
        core.sync().await.expect("syncs");
        assert!(!core.is_in_library("tt1".into()).expect("reads"));
    }

    #[tokio::test]
    async fn addons_upload_only_after_a_local_change() {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        let uploads = Mock::given(method("POST"))
            .and(path("/addonCollectionSet"))
            .respond_with(ok())
            .expect(1)
            .mount_as_scoped(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());

        // Nothing changed here: the account's list wins and nothing is uploaded.
        core.sync().await.expect("syncs");
        assert_eq!(core.addons().expect("lists").len(), 1);

        // A local change is sent on the next sync, once.
        let url = core.addons().expect("lists")[0].manifest_url.clone();
        core.set_addon_enabled(url, false).expect("disables");
        core.sync().await.expect("syncs");
        core.sync().await.expect("syncs again");
        drop(uploads);
    }

    #[tokio::test]
    async fn a_failed_first_sync_still_signs_in() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"authKey": "secret-key", "user": {"_id": "u1", "email": "me@example.com"}}
            })))
            .mount(&server)
            .await;
        // Everything else fails, as when the connection drops right after logging in.
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(503))
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);

        let key = core
            .sign_in("me@example.com".into(), "pw".into())
            .await
            .expect("signed in despite the failed sync");
        assert_eq!(key, "secret-key");
        assert_eq!(
            core.account_email().expect("reads").as_deref(),
            Some("me@example.com")
        );
    }

    /// The usual account mocks plus `getUser` and an accepting `datastorePut`.
    async fn account_server() -> MockServer {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/getUser"))
            .and(body_partial_json(
                serde_json::json!({"authKey": "link-key"}),
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"_id": "u1", "email": "linked@example.com"}
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        server
    }

    fn not_confirmed() -> ResponseTemplate {
        ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "error": {"code": 101, "message": "not confirmed"}
        }))
    }

    async fn link_code(server: &MockServer, link: &str) {
        Mock::given(method("GET"))
            .and(path("/api/v2/create"))
            .and(query_param("type", "Create"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"code": "CODE1", "link": link, "qrcode": ""}
            })))
            .mount(server)
            .await;
    }

    #[tokio::test]
    async fn link_code_signs_in_once_confirmed() {
        let server = account_server().await;
        link_code(&server, "https://www.stremio.com/link?code=CODE1").await;
        // Two polls before the user confirms on the phone.
        Mock::given(method("GET"))
            .and(path("/api/v2/read"))
            .respond_with(not_confirmed())
            .up_to_n_times(2)
            .with_priority(1)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/v2/read"))
            .and(query_param("type", "Read"))
            .and(query_param("code", "CODE1"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"authKey": "link-key"}
            })))
            .expect(1)
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        assert_eq!(core.last_synced_at().expect("reads"), None);

        let code = core.create_link_code().await.expect("creates");
        assert_eq!(code.code, "CODE1");
        let key = core
            .sign_in_with_link_code(code.code)
            .await
            .expect("signs in");
        assert_eq!(key, "link-key");
        assert_eq!(
            core.account_email().expect("reads").as_deref(),
            Some("linked@example.com")
        );
        assert_eq!(core.addons().expect("lists").len(), 1, "addons came along");
        assert!(
            core.is_in_library("tt1".into()).expect("reads"),
            "and the library"
        );
        assert!(core.last_synced_at().expect("reads").is_some());

        core.sign_out().await.expect("signs out");
        assert_eq!(core.last_synced_at().expect("reads"), None);
    }

    #[tokio::test]
    async fn link_codes_must_link_to_a_web_page() {
        let server = account_server().await;
        link_code(&server, "javascript:alert(1)").await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        assert!(matches!(
            core.create_link_code().await,
            Err(CoreError::Network { .. })
        ));
    }

    #[tokio::test]
    async fn waiting_stops_after_the_limit() {
        let server = account_server().await;
        Mock::given(method("GET"))
            .and(path("/api/v2/read"))
            .respond_with(not_confirmed())
            .expect(5)
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);

        let result = core.sign_in_with_link_code("CODE1".into()).await;
        assert!(
            matches!(result, Err(CoreError::SignInNotConfirmed)),
            "{result:?}"
        );
        assert_eq!(core.account_email().expect("reads"), None);
    }

    #[tokio::test]
    async fn waiting_stops_when_cancelled() {
        let server = account_server().await;
        Mock::given(method("GET"))
            .and(path("/api/v2/read"))
            .respond_with(not_confirmed())
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);

        let waiting = tokio::spawn({
            let core = std::sync::Arc::clone(&core);
            async move { core.sign_in_with_link_code("CODE1".into()).await }
        });
        // Cancel once it is surely waiting: after its first poll.
        while server
            .received_requests()
            .await
            .expect("recorded")
            .is_empty()
        {
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
        core.cancel_sign_in();
        let result = waiting.await.expect("joins");
        assert!(
            matches!(result, Err(CoreError::SignInCancelled)),
            "{result:?}"
        );
        let polls = server.received_requests().await.expect("recorded").len();
        assert!(polls < 5, "stopped before the limit, after {polls} polls");

        // A cancel only stops what was waiting: the next sign-in still works.
        Mock::given(method("GET"))
            .and(path("/api/v2/read"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"authKey": "link-key"}
            })))
            .with_priority(1)
            .mount(&server)
            .await;
        let key = core
            .sign_in_with_link_code("CODE1".into())
            .await
            .expect("signs in");
        assert_eq!(key, "link-key");
    }

    #[tokio::test]
    async fn facebook_in_the_browser_signs_in() {
        let server = account_server().await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);

        let started = core
            .start_browser_sign_in(BrowserSignInProvider::Facebook)
            .expect("starts");
        let again = core
            .start_browser_sign_in(BrowserSignInProvider::Facebook)
            .expect("starts");
        assert!(stremionow_sync::is_valid_state(&started.state));
        assert_ne!(started.state, again.state, "each sign-in has its own state");
        assert_eq!(
            started.url,
            format!("{}/login-fb/{}", server.uri(), started.state)
        );

        let poll_path = format!("/login-fb-get-acc/{}", started.state);
        Mock::given(method("GET"))
            .and(path(poll_path.as_str()))
            .respond_with(ResponseTemplate::new(404))
            .up_to_n_times(2)
            .with_priority(1)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path(poll_path.as_str()))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "user": {"email": "me@example.com", "fbLoginToken": "fb-token"}
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/login"))
            .and(body_partial_json(serde_json::json!({
                "email": "me@example.com", "password": "fb-token", "facebook": true
            })))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"authKey": "fb-session", "user": {"_id": "u1", "email": "me@example.com"}}
            })))
            .with_priority(1)
            .expect(1)
            .mount(&server)
            .await;

        let key = core
            .sign_in_with_browser(BrowserSignInProvider::Facebook, started.state)
            .await
            .expect("signs in");
        assert_eq!(key, "fb-session");
        assert_eq!(
            core.account_email().expect("reads").as_deref(),
            Some("me@example.com")
        );
        assert_eq!(core.addons().expect("lists").len(), 1);
    }

    #[tokio::test]
    async fn apple_in_the_browser_signs_in() {
        let server = account_server().await;
        Mock::given(method("POST"))
            .and(path("/authWithApple"))
            .and(body_partial_json(serde_json::json!({
                "type": "Apple", "token": "apple-token", "sub": "sub-1"
            })))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"authKey": "apple-session", "user": {"_id": "u1", "email": "me@icloud.example"}}
            })))
            .expect(1)
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        let started = core
            .start_browser_sign_in(BrowserSignInProvider::Apple)
            .expect("starts");
        assert!(started.url.contains("/login-apple/"));
        Mock::given(method("GET"))
            .and(path(format!("/login-apple-get-acc/{}", started.state)))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "user": {"token": "apple-token", "sub": "sub-1", "email": "me@icloud.example"}
            })))
            .mount(&server)
            .await;

        let key = core
            .sign_in_with_browser(BrowserSignInProvider::Apple, started.state)
            .await
            .expect("signs in");
        assert_eq!(key, "apple-session");
    }

    #[tokio::test]
    async fn browser_sign_in_refuses_a_made_up_state() {
        let server = account_server().await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        let result = core
            .sign_in_with_browser(BrowserSignInProvider::Facebook, "../login".into())
            .await;
        assert!(
            matches!(result, Err(CoreError::SignIn { .. })),
            "{result:?}"
        );
        assert!(
            server
                .received_requests()
                .await
                .expect("recorded")
                .is_empty()
        );
    }

    #[test]
    fn sign_in_records_do_not_print_secrets() {
        let code = LinkCode {
            code: "CODE1".into(),
            link: "https://www.stremio.com/link?code=CODE1".into(),
        };
        let browser = BrowserSignIn {
            url: "https://www.strem.io/login-fb/abc".into(),
            state: "abc".into(),
        };
        let debug = format!("{code:?} {browser:?}");
        assert!(
            !debug.contains("CODE1") && !debug.contains("abc"),
            "{debug}"
        );
    }
}
