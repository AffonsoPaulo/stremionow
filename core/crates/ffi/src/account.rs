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

use std::collections::{BTreeSet, HashMap, HashSet};
use std::future::Future;
use std::sync::Mutex;
use std::sync::atomic::AtomicU64;
use std::time::Duration;

use serde_json::Value;
use stremionow_library::{LibraryEntry, ProgressEntry, Store, StoredAddon, WatchStats};
use stremionow_protocol::AddonUrl;
use stremionow_sync::{
    LikesApi, RemoteAddon, RemoteLibraryItem, Session, StremioApi, SyncError, merge_library,
};
use tokio::sync::watch;

use crate::core::{StremioCore, now_millis};
use crate::records::CoreError;

/// Requests to the Stremio API give up after this long.
const API_TIMEOUT: Duration = Duration::from_secs(20);
/// Setting with the signed-in email, shown in the profile. Not a secret.
const ACCOUNT_EMAIL: &str = "account_email";
/// Setting with the id of the account last signed in to here. It outlives signing out, so
/// changes not sent yet (an expired session) still reach that account when it signs in again,
/// and never reach another one.
const ACCOUNT_USER: &str = "sync_account_user";
/// Setting counting the changes to the addon list made here that the account has not received
/// yet; "0" or missing when there are none.
const ADDONS_CHANGED: &str = "sync_addons_changed";
/// Setting with titles removed from My list since the last sync, as `{id: removed_at}`.
const PENDING_REMOVALS: &str = "sync_pending_removals";
/// Setting with the time of the last successful sync, in milliseconds since the epoch.
const LAST_SYNC: &str = "sync_last_at";
/// Library items of this type are never uploaded, as in the official apps.
pub(crate) const SKIPPED_KIND: &str = "other";

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

/// The session key, and a count of its changes.
#[derive(Default)]
struct SessionState {
    key: Option<String>,
    /// Bumped whenever the key changes (signing in, out, restoring), so a sync that started
    /// with another session does not save what it brought.
    generation: u64,
}

/// The account half of the core.
pub(crate) struct Account {
    api: StremioApi,
    pub(crate) likes: LikesApi,
    session: Mutex<SessionState>,
    /// Held for a whole sync, so two never run at once.
    sync_running: tokio::sync::Mutex<()>,
    /// Bumped by every rating set here, so a refresh that asked the account before does not
    /// overwrite it with the account's older answer.
    pub(crate) ratings_changed: AtomicU64,
    /// Bumped by `cancel_sign_in`; every sign-in that is waiting stops.
    cancel: watch::Sender<u64>,
    facebook_polling: Polling,
    apple_polling: Polling,
    link_polling: Polling,
}

impl Account {
    pub(crate) fn new() -> Result<Self, CoreError> {
        Ok(Self::with_api(
            StremioApi::new(API_TIMEOUT)?,
            LikesApi::new(API_TIMEOUT)?,
        ))
    }

    fn with_api(api: StremioApi, likes: LikesApi) -> Self {
        Self {
            api,
            likes,
            session: Mutex::new(SessionState::default()),
            sync_running: tokio::sync::Mutex::new(()),
            ratings_changed: AtomicU64::new(0),
            cancel: watch::Sender::new(0),
            facebook_polling: FACEBOOK_POLLING,
            apple_polling: APPLE_POLLING,
            link_polling: LINK_POLLING,
        }
    }

    /// An account client talking to another server for every service (the account API, the
    /// website, link codes and the likes service), polling quickly, for tests.
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
            ..Self::with_api(api, LikesApi::new(API_TIMEOUT)?.with_base_url(url))
        })
    }

    pub(crate) fn auth_key(&self) -> Option<String> {
        self.session.lock().ok().and_then(|state| state.key.clone())
    }

    /// The key and its generation, read together.
    fn current_session(&self) -> Option<(String, u64)> {
        let state = self.session.lock().ok()?;
        state.key.clone().map(|key| (key, state.generation))
    }

    /// Whether the session is still the one of `generation`.
    pub(crate) fn is_current(&self, generation: u64) -> bool {
        self.session
            .lock()
            .is_ok_and(|state| state.key.is_some() && state.generation == generation)
    }

    fn set_auth_key(&self, key: Option<String>) {
        if let Ok(mut state) = self.session.lock() {
            state.key = key;
            state.generation = state.generation.wrapping_add(1);
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
            refused @ SyncError::SignInRefused { .. } => Self::SignIn {
                reason: refused.to_string(),
            },
            // The app offers a new code, as when one was not confirmed in time.
            SyncError::LinkCodeExpired => Self::SignInNotConfirmed,
            other => Self::Network {
                reason: other.to_string(),
            },
        }
    }
}

/// Result of a sync, for the profile screen.
#[derive(Debug, Clone, Default, PartialEq, Eq, uniffi::Record)]
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
    /// Signs in to Stremio and returns the session key for the app to keep encrypted; it is
    /// never stored here. Call [`StremioCore::sync`] next: the first sync replaces the addons
    /// with the account's and merges the library. The key comes first so that quitting during
    /// that sync, or a sync that fails, leaves the user signed in.
    pub async fn sign_in(&self, email: String, password: String) -> Result<String, CoreError> {
        let session = self.account.api.login(email.trim(), &password).await?;
        self.signed_in(session)
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
        self.signed_in(session)
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
    ///
    /// Changes not sent yet (removals, ratings, what the account had of the watched marks)
    /// stay too, for this account only: the app also signs out when the session expires, and
    /// signing in again to the same account then sends them. Another account signing in here
    /// forgets them first.
    pub async fn sign_out(&self) -> Result<(), CoreError> {
        if let Some(key) = self.account.auth_key() {
            // Best effort: the session is forgotten here even if the server cannot be reached.
            let _ = self.account.api.logout(&key).await;
        }
        // From here on a sync still running saves nothing it brought (see `generation`).
        self.account.set_auth_key(None);
        let store = self.store()?;
        store.set_setting(ACCOUNT_EMAIL, "")?;
        store.set_setting(LAST_SYNC, "")?;
        Ok(())
    }

    /// Brings the account's addon list, or sends this device's if it changed here since
    /// the last sync, and merges the library. Does nothing when signed out. One sync runs at
    /// a time; a second call waits for the first.
    pub async fn sync(&self) -> Result<SyncSummary, CoreError> {
        let _running = self.account.sync_running.lock().await;
        let Some((key, generation)) = self.account.current_session() else {
            return Ok(SyncSummary::default());
        };
        let changes = addon_changes(&*self.store()?)?;
        if changes > 0 {
            self.push_addons(&key, generation, changes).await?;
        } else {
            self.pull_addons(&key, generation).await?;
        }
        if !self.account.is_current(generation) {
            return Ok(SyncSummary::default());
        }
        self.sync_library(&key, generation).await
    }
}

impl StremioCore {
    /// Keeps the session after any way of signing in and returns the key for the app to keep
    /// in the platform's secret store. Nothing is synced yet: the app saves the key, then
    /// calls [`StremioCore::sync`].
    fn signed_in(&self, session: Session) -> Result<String, CoreError> {
        self.account.set_auth_key(Some(session.auth_key.clone()));
        let store = self.store()?;
        if store.setting(ACCOUNT_USER)?.as_deref() != Some(session.user.id.as_str()) {
            forget_account_changes(&store)?;
            store.set_setting(ACCOUNT_USER, &session.user.id)?;
        }
        // Signing in takes the account's addon list, never sends this device's.
        store.set_setting(ADDONS_CHANGED, "0")?;
        store.set_setting(ACCOUNT_EMAIL, &session.user.email)?;
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
                    // A code that expired will never be confirmed: no use waiting on.
                    Err(SyncError::LinkCodeExpired) => return Err(CoreError::SignInNotConfirmed),
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
    /// replacing it with the account's. Each change counts, so a sync that sent the list
    /// before this change does not take it for sent.
    pub(crate) fn addons_changed(&self) -> Result<(), CoreError> {
        if self.account.signed_in() {
            let store = self.store()?;
            let changes = addon_changes(&store)?.saturating_add(1);
            store.set_setting(ADDONS_CHANGED, &changes.to_string())?;
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
        save_pending_removals(store, &pending)
    }

    /// Replaces the local addons with the account's, from the manifests it stores, so no
    /// addon has to be reached. Entries this app cannot use (legacy or local-only) are skipped
    /// here and kept on the account (see [`collection_to_send`]).
    async fn pull_addons(&self, key: &str, generation: u64) -> Result<(), CoreError> {
        let remote = self.account.api.addon_collection(key).await?;
        let usable: Vec<(String, String)> = remote
            .iter()
            .filter_map(|addon| {
                let url = usable_url(addon)?;
                let manifest = serde_json::to_string(&addon.manifest).ok()?;
                Some((url, manifest))
            })
            .collect();
        if usable.is_empty() {
            return Ok(());
        }
        let mut store = self.store()?;
        if !self.account.is_current(generation) {
            return Ok(());
        }
        // Changed here while the account's list was on its way: the change wins, and the
        // next sync sends it.
        if addon_changes(&store)? > 0 {
            return Ok(());
        }
        store.replace_addons(&usable)?;
        // The account brings its own addons; do not add the local defaults on top.
        store.set_setting(
            crate::core::DEFAULTS_INSTALLED,
            &crate::core::DEFAULTS_VERSION.to_string(),
        )?;
        Ok(())
    }

    /// Uploads the local addon list, in order, as the account's collection, keeping what the
    /// account has that this app cannot show (see [`collection_to_send`]). `changes` is the
    /// count read before: only those changes count as sent.
    async fn push_addons(&self, key: &str, generation: u64, changes: u64) -> Result<(), CoreError> {
        let remote = self.account.api.addon_collection(key).await?;
        let local = self.store()?.addons()?;
        let addons = collection_to_send(&local, &remote);
        if !addons.is_empty() {
            self.account.api.set_addon_collection(key, &addons).await?;
        }
        let store = self.store()?;
        if self.account.is_current(generation) && addon_changes(&store)? == changes {
            store.set_setting(ADDONS_CHANGED, "0")?;
        }
        Ok(())
    }

    /// Two-way library merge: the newest change to each title wins, on either side, and the
    /// watched videos of both sides are combined (see [`crate::watched_sync`]). Nothing it
    /// brought is saved when the session changed meanwhile (signed out, another account).
    async fn sync_library(&self, key: &str, generation: u64) -> Result<SyncSummary, CoreError> {
        let account = self.account.api.library(key).await?;
        let remote = account.items;
        let now = now_millis();
        // What is pending now; changes made during the sync stay pending for the next one.
        let (pending, dismissed) = {
            let store = self.store()?;
            let dismissed: HashMap<String, i64> = store.progress_removals()?.into_iter().collect();
            (pending_removals(&store)?, dismissed)
        };
        let (local, local_mtimes) = self.local_items(&remote, &pending, &dismissed, now)?;
        let result = merge_library(&local, &remote);

        let mut upload: Vec<RemoteLibraryItem> = result
            .to_upload
            .into_iter()
            .filter(|item| item.kind != SKIPPED_KIND)
            .collect();
        let watched = self
            .reconcile_watched(&remote, &result.merged, &mut upload, generation)
            .await?;

        let mut downloaded: HashSet<String> = watched.downloaded.clone();
        {
            let store = self.store()?;
            if !self.account.is_current(generation) {
                return Ok(SyncSummary::default());
            }
            for item in &result.merged {
                let local_mtime = local_mtimes.get(&item.id).copied();
                let remote_newer = item.mtime_ms().unwrap_or(0) > local_mtime.unwrap_or(i64::MIN);
                if remote_newer {
                    apply_remote(&store, item, now)?;
                    downloaded.insert(item.id.clone());
                } else if item.in_library()
                    && !pending.contains_key(&item.id)
                    && !store.is_in_library(&item.id)?
                {
                    // In the account's library (added on another device) while this device
                    // only had progress for it, which won: it joins My list here too.
                    store.add_to_library(&library_entry(item))?;
                    downloaded.insert(item.id.clone());
                }
            }
        }

        for item in &mut upload {
            if let Some(raw) = account.unreadable.get(&item.id) {
                item.keep_fields_of(raw);
            }
        }
        if !upload.is_empty() {
            self.account.api.put_library(key, &upload).await?;
        }
        let mut store = self.store()?;
        if !self.account.is_current(generation) {
            return Ok(SyncSummary::default());
        }
        watched.save(&mut store)?;
        for item in &upload {
            // The account's copy now dates from this upload, later than anything recorded
            // here when the sync itself changed it (watched marks) or when what dated it here
            // is gone (a removal from Continue watching, now sent). It must not read as a
            // change made elsewhere next time, which would bring it back as new.
            if let Some(at) = item.mtime_ms().filter(|&at| at <= now_millis()) {
                store.note_synced_change(&item.id, at)?;
            }
        }
        let mut still_pending = pending_removals(&store)?;
        still_pending.retain(|id, at| pending.get(id) != Some(at));
        save_pending_removals(&store, &still_pending)?;
        let sent: Vec<(String, i64)> = dismissed.into_iter().collect();
        store.clear_progress_removals(&sent)?;
        store.set_setting(LAST_SYNC, &now_millis().to_string())?;
        Ok(SyncSummary {
            downloaded: u32::try_from(downloaded.len()).unwrap_or(u32::MAX),
            uploaded: u32::try_from(upload.len()).unwrap_or(u32::MAX),
        })
    }

    /// Local titles as account library items, built on the account's copy when there is one
    /// so fields this app does not use survive. Also returns each title's last local change.
    /// `pending` are the removals from My list and `dismissed` the removals from Continue
    /// watching not sent yet.
    fn local_items(
        &self,
        remote: &[RemoteLibraryItem],
        pending: &HashMap<String, i64>,
        dismissed: &HashMap<String, i64>,
        now: i64,
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
        let stats: HashMap<String, WatchStats> = store
            .all_watch_stats()?
            .into_iter()
            .map(|stats| (stats.id.clone(), stats))
            .collect();
        let last_sync: i64 = store
            .setting(LAST_SYNC)?
            .and_then(|value| value.parse().ok())
            .unwrap_or(0);
        let stats_changed = |id: &str| stats.get(id).map_or(i64::MIN, |s| s.updated_at);
        // A removal from Continue watching here counts when it is newer than the account's
        // copy; an older one lost to progress made elsewhere since.
        let dismissal = |id: &str| -> Option<i64> {
            dismissed.get(id).copied().filter(|&at| {
                by_id
                    .get(id)
                    .is_none_or(|item| item.mtime_ms().is_none_or(|mtime| mtime < at))
            })
        };

        let mut items = Vec::new();
        let mut mtimes = HashMap::new();
        for entry in &library {
            let saved_progress = progress.get(&entry.id);
            let dismissed_at = saved_progress.map_or_else(|| dismissal(&entry.id), |_| None);
            let mtime = saved_progress
                .map_or(entry.added_at, |p| p.updated_at.max(entry.added_at))
                .max(stats_changed(&entry.id))
                .max(dismissed_at.unwrap_or(i64::MIN));
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
            } else if dismissed_at.is_some() {
                item.state.time_offset = 0;
            }
            if let Some(s) = stats.get(&entry.id) {
                set_stats(&mut item, s);
            }
            item.touch(mtime);
            mtimes.insert(entry.id.clone(), mtime);
            items.push(item);
        }
        for (id, p) in &progress {
            if saved.contains(id.as_str()) {
                continue;
            }
            let mut item = base_item(&by_id, id, &p.kind, &p.name, &p.poster, p.updated_at);
            match by_id.get(id.as_str()) {
                // In the account's library, saved there from another device since this one
                // last synced: watching it here keeps it there.
                Some(account) if account.in_library() && !pending.contains_key(id) => {}
                // Watched but not saved: Stremio keeps these as temporary items (stremio-core
                // `LibraryItem::from(&MetaItemPreview)`), so the official apps do not list
                // them in the library. One the account never had starts removed.
                Some(_) => item.temp = true,
                None => {
                    item.removed = true;
                    item.temp = true;
                }
            }
            set_progress(&mut item, p);
            if let Some(s) = stats.get(id) {
                set_stats(&mut item, s);
            }
            let mtime = p.updated_at.max(stats_changed(id));
            item.touch(mtime);
            mtimes.insert(id.clone(), mtime);
            items.push(item);
        }
        let unsaved_idle: BTreeSet<&String> = stats
            .keys()
            .chain(dismissed.keys())
            .filter(|id| !saved.contains(id.as_str()) && !progress.contains_key(*id))
            .collect();
        for id in unsaved_idle {
            // Neither saved nor in progress, but watched or taken out of Continue watching
            // here: the account's copy gets the new numbers, so a title finished here counts
            // on the other devices too, and leaves Continue watching there.
            let Some(remote_item) = by_id.get(id.as_str()) else {
                continue;
            };
            let dismissed_at = dismissal(id);
            let changed = stats
                .get(id)
                .map(|s| s.updated_at)
                .max(dismissed_at)
                .unwrap_or(i64::MIN);
            if remote_item.mtime_ms().unwrap_or(i64::MIN) >= changed {
                continue;
            }
            let mut item = (*remote_item).clone();
            if let Some(s) = stats.get(id) {
                set_stats(&mut item, s);
            }
            if dismissed_at.is_some() {
                item.state.time_offset = 0;
            }
            item.touch(changed);
            mtimes.insert(id.clone(), changed);
            items.push(item);
        }
        for (id, &removed_at) in pending {
            if saved.contains(id.as_str()) {
                continue;
            }
            if let Some(remote_item) = by_id.get(id.as_str()) {
                let mut item = (*remote_item).clone();
                item.removed = true;
                item.touch(removed_at);
                mtimes.insert(id.clone(), removed_at);
                items.push(item);
            }
        }
        // A device whose clock runs ahead dates its changes in the future, where no change
        // made here could beat them. A title changed here since the last sync goes just past
        // such a date, so the change still wins.
        for item in &mut items {
            let account_mtime = by_id.get(item.id.as_str()).and_then(|r| r.mtime_ms());
            let local = mtimes.get(&item.id).copied().unwrap_or(i64::MIN);
            if let Some(future) = account_mtime.filter(|&at| at > now && at >= local)
                && local > last_sync
            {
                let past = future.saturating_add(1);
                item.touch(past);
                mtimes.insert(item.id.clone(), past);
            }
        }
        Ok((items, mtimes))
    }
}

/// How many changes to the addon list wait to be sent.
fn addon_changes(store: &Store) -> Result<u64, CoreError> {
    Ok(store
        .setting(ADDONS_CHANGED)?
        .and_then(|value| value.parse().ok())
        .unwrap_or(0))
}

/// The manifest URL of an account addon this app can install, or `None` for one it skips
/// (a legacy transport such as OpenSubtitles v1, a manifest it cannot read).
fn usable_url(addon: &RemoteAddon) -> Option<String> {
    addon.parsed_manifest().ok()?;
    Some(
        AddonUrl::parse(&addon.transport_url)
            .ok()?
            .manifest_url()
            .to_owned(),
    )
}

/// The collection to upload: the local addons in their order, each with what the account
/// keeps for it (flags such as official or protected, fields this app does not use), and the
/// account's entries this app skipped, untouched, at their places. Those were never listed
/// here, so the user could not have removed them. Empty when there are no local addons.
fn collection_to_send(local: &[StoredAddon], remote: &[RemoteAddon]) -> Vec<RemoteAddon> {
    let mut known: HashMap<String, &RemoteAddon> = HashMap::new();
    let mut skipped: Vec<(usize, &RemoteAddon)> = Vec::new();
    for (index, addon) in remote.iter().enumerate() {
        match usable_url(addon) {
            Some(url) => {
                known.entry(url).or_insert(addon);
            }
            None => skipped.push((index, addon)),
        }
    }
    let mut addons: Vec<RemoteAddon> = local
        .iter()
        .filter_map(|addon| {
            let manifest: Value = serde_json::from_str(&addon.manifest_json).ok()?;
            Some(match known.get(&addon.manifest_url) {
                Some(account) => RemoteAddon {
                    manifest,
                    ..(*account).clone()
                },
                None => RemoteAddon::new(addon.manifest_url.clone(), manifest),
            })
        })
        .collect();
    if addons.is_empty() {
        return addons;
    }
    for (index, addon) in skipped {
        addons.insert(index.min(addons.len()), addon.clone());
    }
    addons
}

/// Forgets what this device kept for the account last signed in, before another one signs
/// in: it must not receive the other's removals, ratings or watched history.
fn forget_account_changes(store: &Store) -> Result<(), CoreError> {
    store.set_setting(PENDING_REMOVALS, "{}")?;
    store.clear_all_progress_removals()?;
    crate::ratings::forget_account_ratings(store)?;
    store.clear_watched_sync()?;
    store.set_setting(LAST_SYNC, "")?;
    Ok(())
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

/// Writes how much a title was watched here into its account item (still carrying the
/// account's `_mtime`): the later of the two `lastWatched`, and the higher `timesWatched`,
/// unless the title was marked unwatched here after the account's copy changed, so a count
/// never goes down just because this device has not heard of some watches.
fn set_stats(item: &mut RemoteLibraryItem, stats: &WatchStats) {
    let reset_since = stats
        .reset_at
        .is_some_and(|reset| Some(reset) > item.mtime_ms());
    item.state.times_watched = if reset_since {
        stats.times_watched
    } else {
        stats.times_watched.max(item.state.times_watched)
    };
    let account_last = item
        .state
        .last_watched
        .as_deref()
        .and_then(stremionow_sync::parse_timestamp);
    if let Some(last) = stats.last_watched.filter(|&last| Some(last) > account_last) {
        item.state.last_watched = Some(stremionow_sync::format_timestamp(last));
    }
}

/// Brings a title's account state into the local list, progress and watch statistics.
/// Times from the account are taken no later than `now`: a device whose clock runs ahead
/// must not leave dates here that no later change could beat.
fn apply_remote(store: &Store, item: &RemoteLibraryItem, now: i64) -> Result<(), CoreError> {
    let mtime = item.mtime_ms().unwrap_or(0).min(now);
    let last_watched = item
        .state
        .last_watched
        .as_deref()
        .and_then(stremionow_sync::parse_timestamp)
        .map(|at| at.min(now));
    if item.state.times_watched > 0
        || last_watched.is_some()
        || store.watch_stats(&item.id)?.is_some()
    {
        store.set_watch_stats(&WatchStats {
            id: item.id.clone(),
            times_watched: item.state.times_watched,
            last_watched,
            updated_at: mtime,
            reset_at: None,
        })?;
    }
    if item.in_library() {
        store.add_to_library(&library_entry(item))?;
    } else if store.is_in_library(&item.id)? {
        store.remove_from_library(&item.id)?;
    }
    let saved = store.progress(&item.id)?;
    // u128: the numbers come from other clients and may be anything.
    let unfinished = item.progress().filter(|&(_, offset, duration)| {
        duration > 0 && offset > 0 && u128::from(offset) * 100 < u128::from(duration) * 92
    });
    // Kept for an item in the library or a temporary one, as stremio-core's
    // `is_in_continue_watching`; not for one removed from the library.
    match unfinished.filter(|_| !item.removed || item.temp) {
        Some((video_id, offset, duration)) => {
            let position_ms = i64::try_from(offset).unwrap_or(i64::MAX);
            let duration_ms = i64::try_from(duration).unwrap_or(i64::MAX);
            let same_video = saved.as_ref().filter(|saved| saved.video_id == video_id);
            if same_video.is_some_and(|saved| {
                saved.position_ms == position_ms && saved.duration_ms == duration_ms
            }) {
                // Nothing new (the account has what this device sent): the title keeps its
                // place in Continue watching.
                return Ok(());
            }
            // The account has no background or episode numbers for the card: keep this
            // device's, or read them from the item.
            let (season, episode) = match same_video {
                Some(saved) if saved.season.is_some() || saved.episode.is_some() => {
                    (saved.season, saved.episode)
                }
                _ => episode_of(item, video_id),
            };
            store.save_progress(&ProgressEntry {
                id: item.id.clone(),
                kind: item.kind.clone(),
                name: item.name.clone(),
                poster: item.poster.clone(),
                background: saved.and_then(|saved| saved.background),
                video_id: video_id.to_owned(),
                season,
                episode,
                position_ms,
                duration_ms,
                updated_at: mtime,
            })?;
        }
        // Finished, taken out of Continue watching or removed on another device since.
        None if saved.is_some() => store.forget_progress(&item.id)?,
        None => {}
    }
    Ok(())
}

/// A title of the account as an entry of My list.
fn library_entry(item: &RemoteLibraryItem) -> LibraryEntry {
    let mtime = item.mtime_ms().unwrap_or(0);
    LibraryEntry {
        id: item.id.clone(),
        kind: item.kind.clone(),
        name: item.name.clone(),
        poster: item.poster.clone(),
        added_at: item
            .ctime
            .as_deref()
            .and_then(stremionow_sync::parse_timestamp)
            .unwrap_or(mtime)
            .min(now_millis()),
    }
}

/// The season and episode of `video_id`: the item's `state.season` and `state.episode`, which
/// the official apps write for the video in progress, else the `<title>:<season>:<episode>`
/// form of the id that Cinemeta uses.
fn episode_of(item: &RemoteLibraryItem, video_id: &str) -> (Option<u32>, Option<u32>) {
    let number = |key: &str| {
        item.state
            .extra
            .get(key)
            .and_then(Value::as_u64)
            .and_then(|n| u32::try_from(n).ok())
    };
    if item.state.video_id.as_deref() == Some(video_id)
        && let (Some(season), Some(episode)) = (number("season"), number("episode"))
    {
        return (Some(season), Some(episode));
    }
    let numbers = video_id
        .strip_prefix(item.id.as_str())
        .and_then(|rest| rest.strip_prefix(':'))
        .and_then(|rest| rest.split_once(':'))
        .and_then(|(season, episode)| Some((season.parse().ok()?, episode.parse().ok()?)));
    match numbers {
        Some((season, episode)) => (Some(season), Some(episode)),
        None => (None, None),
    }
}

fn pending_removals(store: &Store) -> Result<HashMap<String, i64>, CoreError> {
    let Some(json) = store.setting(PENDING_REMOVALS)? else {
        return Ok(HashMap::new());
    };
    Ok(serde_json::from_str(&json).unwrap_or_else(|error: serde_json::Error| {
        // Never expected; the removals cannot be read back, so they will not reach the account.
        log::warn!(
            "forgot the unsent removals from My list: they could not be read ({:?} at column {})",
            error.classify(),
            error.column()
        );
        HashMap::new()
    }))
}

fn save_pending_removals(store: &Store, pending: &HashMap<String, i64>) -> Result<(), CoreError> {
    let json = serde_json::to_string(pending).map_err(|e| CoreError::Storage {
        reason: e.to_string(),
    })?;
    store.set_setting(PENDING_REMOVALS, &json)?;
    Ok(())
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
        let requests = server.received_requests().await.expect("recorded");
        assert!(
            requests.iter().all(|r| r.url.path() == "/login"),
            "the key comes back before anything syncs"
        );

        core.sync().await.expect("the first sync");
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

    /// The `changes` of every library upload the server received, in order.
    async fn uploads(server: &MockServer) -> Vec<Vec<serde_json::Value>> {
        server
            .received_requests()
            .await
            .unwrap_or_default()
            .iter()
            .filter(|request| request.url.path() == "/datastorePut")
            .map(|request| {
                let body: serde_json::Value =
                    serde_json::from_slice(&request.body).expect("json body");
                body["changes"].as_array().cloned().unwrap_or_default()
            })
            .collect()
    }

    fn uploaded<'a>(changes: &'a [serde_json::Value], id: &str) -> &'a serde_json::Value {
        changes
            .iter()
            .find(|item| item["_id"] == id)
            .unwrap_or_else(|| panic!("{id} was uploaded"))
    }

    #[tokio::test]
    async fn watch_counts_sync_both_ways_without_going_down() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/addonCollectionGet"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(serde_json::json!({"result": {"addons": []}})),
            )
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/datastoreGet"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": [
                    {
                        "_id": "tt1", "name": "Movie", "type": "movie",
                        "_ctime": "2026-01-01T00:00:00.000Z", "_mtime": "2026-01-02T00:00:00.000Z",
                        "state": {"timesWatched": 5, "lastWatched": "2026-01-02T00:00:00.000Z"}
                    },
                    {
                        "_id": "tt2", "name": "Series", "type": "series",
                        "_ctime": "2026-01-01T00:00:00.000Z", "_mtime": "2026-01-03T00:00:00.000Z",
                        "state": {
                            "timesWatched": 2, "overallTimeWatched": 7200000,
                            "lastWatched": "2026-01-03T00:00:00.000Z"
                        }
                    }
                ]
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        let movie = crate::records::MetaItem {
            id: "tt1".into(),
            kind: "movie".into(),
            name: "Movie".into(),
            poster: None,
            background: None,
            logo: None,
            description: None,
            release_info: None,
            imdb_rating: None,
            genres: vec![],
        };
        let playing = |video_id: &str, position_ms: u64| crate::playback::WatchProgress {
            id: video_id.split(':').next().unwrap_or_default().into(),
            kind: "movie".into(),
            name: "Title".into(),
            poster: None,
            background: None,
            video_id: video_id.into(),
            season: None,
            episode: None,
            position_ms,
            duration_ms: 1_000_000,
            updated_at: 0,
        };

        // Played here before signing in, without ever finishing: newer than the account's
        // copy, but it has not heard of the five watches, so it must not erase them.
        core.add_to_library(movie).expect("adds");
        core.save_progress(playing("tt1", 300_000)).expect("plays");
        core.restore_session("secret-key".into());
        core.sync().await.expect("syncs");

        let first = uploads(&server).await;
        let tt1 = uploaded(&first[0], "tt1");
        assert_eq!(tt1["state"]["timesWatched"], 5);
        assert_ne!(tt1["state"]["lastWatched"], "2026-01-02T00:00:00.000Z");
        let items = core.library().expect("lists");
        let series = items.iter().find(|i| i.id == "tt2").expect("downloaded");
        assert_eq!(series.times_watched, 2);
        assert!(series.watched);
        assert_eq!(
            series.last_watched_at,
            stremionow_sync::parse_timestamp("2026-01-03T00:00:00.000Z")
        );

        // Finishing an episode here counts on top of the account's watches.
        core.save_progress(playing("tt2:1:1", 960_000))
            .expect("finishes");
        core.sync().await.expect("syncs");
        let second = uploads(&server).await;
        let tt2 = uploaded(&second[1], "tt2");
        assert_eq!(tt2["state"]["timesWatched"], 3);
        assert_eq!(
            tt2["state"]["overallTimeWatched"], 7_200_000,
            "fields not counted here stay"
        );

        // Marking the movie unwatched here takes the account's count down.
        core.set_watched("tt1".into(), vec!["tt1".into()], false)
            .expect("unmarks");
        core.sync().await.expect("syncs");
        let third = uploads(&server).await;
        assert_eq!(uploaded(&third[2], "tt1")["state"]["timesWatched"], 0);
    }

    #[tokio::test]
    async fn titles_watched_without_saving_them_stay_out_of_the_library() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/addonCollectionGet"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"addons": []}
            })))
            .mount(&server)
            .await;
        // The official apps keep a title watched without adding it as removed and temporary.
        Mock::given(method("POST"))
            .and(path("/datastoreGet"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": [{
                    "_id": "tt2", "name": "Watched there", "type": "movie",
                    "removed": true, "temp": true,
                    "_ctime": "2026-01-01T00:00:00.000Z", "_mtime": "2026-01-02T00:00:00.000Z",
                    "state": {"timeOffset": 600000, "duration": 6000000, "video_id": "tt2"}
                }]
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .and(body_partial_json(serde_json::json!({
                "changes": [{"_id": "tt3", "removed": true, "temp": true}]
            })))
            .respond_with(ok())
            .expect(1)
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        core.save_progress(crate::playback::WatchProgress {
            id: "tt3".into(),
            kind: "movie".into(),
            name: "Watched here".into(),
            poster: None,
            background: None,
            video_id: "tt3".into(),
            season: None,
            episode: None,
            position_ms: 600_000,
            duration_ms: 6_000_000,
            updated_at: 0,
        })
        .expect("saves");

        core.sync().await.expect("syncs");
        assert!(!core.is_in_library("tt2".into()).expect("reads"));
        assert!(
            core.progress("tt2".into()).expect("reads").is_some(),
            "it continues here"
        );
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
        assert!(core.sync().await.is_err(), "the first sync fails");
        assert_eq!(
            core.account_email().expect("reads").as_deref(),
            Some("me@example.com"),
            "and the session stays"
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
        core.sync().await.expect("the first sync");
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
        core.sync().await.expect("the first sync");
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

    /// Waits until the server has received `count` requests to `path`.
    async fn wait_for_requests(server: &MockServer, path: &str, count: usize) {
        loop {
            let seen = server
                .received_requests()
                .await
                .unwrap_or_default()
                .iter()
                .filter(|request| request.url.path() == path)
                .count();
            if seen >= count {
                return;
            }
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
    }

    /// The bodies the server received at `path`, in order.
    async fn bodies(server: &MockServer, path: &str) -> Vec<serde_json::Value> {
        server
            .received_requests()
            .await
            .unwrap_or_default()
            .iter()
            .filter(|request| request.url.path() == path)
            .map(|request| serde_json::from_slice(&request.body).expect("json body"))
            .collect()
    }

    /// An account whose collection holds a flagged addon with fields this app does not use and
    /// a legacy one this app skips, and whose library is empty.
    async fn addon_account() -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/addonCollectionGet"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"addons": [
                    {
                        "transportUrl": "https://a.example/manifest.json", "manifest": manifest("a"),
                        "flags": {"official": true, "protected": true}, "installedAt": 5
                    },
                    {
                        "transportUrl": "https://opensubtitles.strem.io/stremio/v1",
                        "manifest": {"id": "org.stremio.opensubtitles"}, "flags": {"official": true}
                    }
                ]}
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/datastoreGet"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"result": []})),
            )
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        server
    }

    #[tokio::test]
    async fn pushing_addons_keeps_what_this_app_skips_and_the_flags() {
        let server = addon_account().await;
        Mock::given(method("POST"))
            .and(path("/addonCollectionSet"))
            .respond_with(ok())
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        core.sync().await.expect("syncs");
        assert_eq!(core.addons().expect("lists").len(), 1);

        let url = "https://a.example/manifest.json".to_owned();
        core.set_addon_enabled(url.clone(), false)
            .expect("disables");
        core.sync().await.expect("sends the list");
        let sent = bodies(&server, "/addonCollectionSet").await;
        assert_eq!(sent.len(), 1);
        let addons = sent[0]["addons"].as_array().expect("addons");
        assert_eq!(addons.len(), 2, "the skipped addon stays on the account");
        assert_eq!(addons[0]["transportUrl"], url.as_str());
        assert_eq!(addons[0]["flags"]["official"], true);
        assert_eq!(addons[0]["flags"]["protected"], true);
        assert_eq!(addons[0]["installedAt"], 5);
        assert_eq!(
            addons[1]["transportUrl"],
            "https://opensubtitles.strem.io/stremio/v1"
        );
        assert_eq!(addons[1]["flags"]["official"], true);

        // The account's list comes back on the next sync; the addon stays disabled here.
        core.sync().await.expect("syncs");
        let addons = core.addons().expect("lists");
        assert_eq!(addons.len(), 1);
        assert!(!addons[0].enabled, "disabled here, disabled after the sync");
    }

    #[test]
    fn the_sent_collection_keeps_skipped_entries_in_place() {
        let stored = |url: &str| StoredAddon {
            manifest_url: url.to_owned(),
            manifest_json: manifest(url).to_string(),
            enabled: true,
        };
        let remote: Vec<RemoteAddon> = serde_json::from_value(serde_json::json!([
            {"transportUrl": "https://x.example/manifest.json", "manifest": manifest("x")},
            {"transportUrl": "https://legacy.example/stremio/v1", "manifest": {}},
            {"transportUrl": "https://gone.example/manifest.json", "manifest": manifest("gone")}
        ]))
        .expect("addons");
        let sent = collection_to_send(
            &[
                stored("https://new.example/manifest.json"),
                stored("https://x.example/manifest.json"),
            ],
            &remote,
        );
        let urls: Vec<&str> = sent.iter().map(|a| a.transport_url.as_str()).collect();
        assert_eq!(
            urls,
            [
                "https://new.example/manifest.json",
                "https://legacy.example/stremio/v1",
                "https://x.example/manifest.json",
            ],
            "removed here leaves; skipped stays at its place"
        );
        assert!(collection_to_send(&[], &remote).is_empty());
    }

    #[tokio::test]
    async fn an_addon_change_during_a_sync_is_not_lost() {
        let server = addon_account().await;
        Mock::given(method("POST"))
            .and(path("/addonCollectionSet"))
            .respond_with(ok().set_delay(Duration::from_millis(200)))
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        core.sync().await.expect("syncs");
        let url = "https://a.example/manifest.json".to_owned();

        // Changed while the account's list is on its way: the change is kept.
        Mock::given(method("POST"))
            .and(path("/addonCollectionGet"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(serde_json::json!({"result": {"addons": [
                        {"transportUrl": "https://b.example/manifest.json", "manifest": manifest("b")}
                    ]}}))
                    .set_delay(Duration::from_millis(200)),
            )
            .with_priority(1)
            .up_to_n_times(1)
            .mount(&server)
            .await;
        let syncing = tokio::spawn({
            let core = std::sync::Arc::clone(&core);
            async move { core.sync().await }
        });
        wait_for_requests(&server, "/addonCollectionGet", 2).await;
        core.set_addon_enabled(url.clone(), false)
            .expect("disables");
        syncing.await.expect("joins").expect("syncs");
        let addons = core.addons().expect("lists");
        assert_eq!(addons.len(), 1);
        assert_eq!(addons[0].manifest_url, url, "not replaced by the account's");
        assert!(!addons[0].enabled);

        // Changed again while the list is being sent: that change goes on the next sync.
        let syncing = tokio::spawn({
            let core = std::sync::Arc::clone(&core);
            async move { core.sync().await }
        });
        wait_for_requests(&server, "/addonCollectionSet", 1).await;
        core.move_addon(url.clone(), 0).expect("moves");
        syncing.await.expect("joins").expect("syncs");
        core.sync().await.expect("syncs again");
        assert_eq!(bodies(&server, "/addonCollectionSet").await.len(), 2);
        core.sync().await.expect("nothing left to send");
        assert_eq!(bodies(&server, "/addonCollectionSet").await.len(), 2);
    }

    #[tokio::test]
    async fn watching_a_title_saved_elsewhere_keeps_it_saved() {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        // tt1 was added to the library on another device; this one only plays it.
        core.save_progress(crate::playback::WatchProgress {
            id: "tt1".into(),
            kind: "movie".into(),
            name: "Remote".into(),
            poster: None,
            background: None,
            video_id: "tt1".into(),
            season: None,
            episode: None,
            position_ms: 900_000,
            duration_ms: 6_000_000,
            updated_at: 0,
        })
        .expect("plays");

        core.sync().await.expect("syncs");
        let changes = uploads(&server).await;
        let sent = uploaded(&changes[0], "tt1");
        assert_eq!(sent["removed"], false);
        assert_eq!(sent["temp"], false, "still in the account's library");
        assert_eq!(sent["state"]["timeOffset"], 900_000);
        assert!(
            core.is_in_library("tt1".into()).expect("reads"),
            "and in My list here too"
        );
    }

    /// The usual account, signing in as `user`.
    async fn signing_in_as(server: &MockServer, user: &str) {
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "result": {"authKey": "secret-key", "user": {"_id": user, "email": "me@example.com"}}
            })))
            .with_priority(1)
            .mount(server)
            .await;
    }

    #[tokio::test]
    async fn unsent_changes_wait_for_the_same_account_only() {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/api/send"))
            .respond_with(ResponseTemplate::new(503))
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        let pending =
            |core: &StremioCore| pending_removals(&core.store().expect("store")).expect("reads");

        core.sign_in("me@example.com".into(), "pw".into())
            .await
            .expect("signs in");
        core.remove_from_library("tt1".into()).expect("removes");
        core.set_rating(
            "movie".into(),
            "tt1".into(),
            crate::ratings::TitleRating::Liked,
        )
        .await
        .expect("kept here while the likes service is down");
        // The session expires before the next sync; the app signs out and in again.
        core.sign_out().await.expect("signs out");
        core.set_rating(
            "movie".into(),
            "tt2".into(),
            crate::ratings::TitleRating::Loved,
        )
        .await
        .expect("rated signed out");
        core.sign_in("me@example.com".into(), "pw".into())
            .await
            .expect("signs in again");
        assert!(pending(&core).contains_key("tt1"), "the removal waited");
        let ratings = |core: &StremioCore| {
            core.store()
                .expect("store")
                .settings_with_prefix("rating_pending:")
                .expect("reads")
                .len()
        };
        assert_eq!(ratings(&core), 2);
        core.sync().await.expect("syncs");
        let changes = uploads(&server).await;
        assert_eq!(uploaded(&changes[0], "tt1")["removed"], true);

        // Another account signs in: this one's unsent changes are not for it.
        core.remove_from_library("tt1".into()).expect("removes");
        core.sign_out().await.expect("signs out");
        signing_in_as(&server, "u2").await;
        core.sign_in("other@example.com".into(), "pw".into())
            .await
            .expect("another account");
        assert!(pending(&core).is_empty());
        assert_eq!(
            ratings(&core),
            1,
            "only the rating made signed out goes to the new account"
        );
    }

    #[tokio::test]
    async fn changes_during_a_sync_wait_for_the_next_one() {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok().set_delay(Duration::from_millis(200)))
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        // Unreadable removals are forgotten with a warning instead of failing.
        core.store()
            .expect("store")
            .set_setting(PENDING_REMOVALS, "not json")
            .expect("writes");
        core.remove_from_library("tt1".into()).expect("removes");

        let syncing = tokio::spawn({
            let core = std::sync::Arc::clone(&core);
            async move { core.sync().await }
        });
        wait_for_requests(&server, "/datastorePut", 1).await;
        core.remove_from_library("tt2".into()).expect("removes");
        syncing.await.expect("joins").expect("syncs");
        let pending = pending_removals(&core.store().expect("store")).expect("reads");
        assert_eq!(
            pending.keys().collect::<Vec<_>>(),
            ["tt2"],
            "the removal sent is done, the one made meanwhile waits"
        );
    }

    #[tokio::test]
    async fn signing_out_during_a_sync_saves_nothing_it_brought() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/addonCollectionGet"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(serde_json::json!({"result": {"addons": []}})),
            )
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/datastoreGet"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(serde_json::json!({"result": [{
                        "_id": "tt1", "name": "Remote", "type": "movie",
                        "_ctime": "2026-01-01T00:00:00.000Z", "_mtime": "2026-01-02T00:00:00.000Z"
                    }]}))
                    .set_delay(Duration::from_millis(200)),
            )
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .respond_with(ok())
            .with_priority(10)
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        let syncing = tokio::spawn({
            let core = std::sync::Arc::clone(&core);
            async move { core.sync().await }
        });
        wait_for_requests(&server, "/datastoreGet", 1).await;
        core.sign_out().await.expect("signs out");
        let summary = syncing.await.expect("joins").expect("ends quietly");
        assert_eq!(summary, SyncSummary::default());
        assert!(!core.is_in_library("tt1".into()).expect("reads"));
        assert_eq!(
            core.store()
                .expect("store")
                .setting(LAST_SYNC)
                .expect("reads")
                .as_deref(),
            Some("")
        );
    }

    fn movie_progress(id: &str, position_ms: u64) -> crate::playback::WatchProgress {
        crate::playback::WatchProgress {
            id: id.into(),
            kind: "movie".into(),
            name: "Remote".into(),
            poster: None,
            background: None,
            video_id: id.into(),
            season: None,
            episode: None,
            position_ms,
            duration_ms: 6_000_000,
            updated_at: 0,
        }
    }

    /// From now on the account's library holds `items`.
    async fn library_now(server: &MockServer, priority: u8, items: serde_json::Value) {
        Mock::given(method("POST"))
            .and(path("/datastoreGet"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"result": items})),
            )
            .with_priority(priority)
            .mount(server)
            .await;
    }

    #[tokio::test]
    async fn continue_watching_follows_the_account_and_this_device() {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        core.sync().await.expect("syncs");
        assert!(core.progress("tt1".into()).expect("reads").is_some());

        // Finished on another device: it leaves Continue watching here.
        library_now(
            &server,
            4,
            serde_json::json!([{
                "_id": "tt1", "name": "Remote", "type": "movie", "removed": false, "temp": false,
                "_ctime": "2026-01-01T00:00:00.000Z", "_mtime": "2026-01-03T00:00:00.000Z",
                "state": {"timeOffset": 0, "duration": 6000000, "video_id": "tt1", "timesWatched": 1}
            }]),
        )
        .await;
        core.sync().await.expect("syncs");
        assert!(core.progress("tt1".into()).expect("reads").is_none());

        // Dismissed here: the account's copy leaves Continue watching too, and the account's
        // older progress does not bring it back.
        let in_progress = serde_json::json!([{
            "_id": "tt2", "name": "Other", "type": "movie", "removed": false, "temp": false,
            "_ctime": "2026-01-01T00:00:00.000Z", "_mtime": "2026-01-04T00:00:00.000Z",
            "state": {"timeOffset": 600000, "duration": 6000000, "video_id": "tt2"}
        }]);
        library_now(&server, 3, in_progress.clone()).await;
        core.sync().await.expect("syncs");
        assert!(core.progress("tt2".into()).expect("reads").is_some());
        core.remove_progress("tt2".into()).expect("dismisses");
        core.sync().await.expect("syncs");
        let changes = uploads(&server).await;
        let sent = uploaded(changes.last().expect("an upload"), "tt2").clone();
        assert_eq!(sent["state"]["timeOffset"], 0);
        assert!(sent["_mtime"].as_str() > Some("2026-01-04T00:00:00.000Z"));
        assert!(core.progress("tt2".into()).expect("reads").is_none());
        library_now(&server, 2, serde_json::json!([sent])).await;
        core.sync().await.expect("syncs");
        assert!(core.progress("tt2".into()).expect("reads").is_none());
        let later = uploads(&server).await;
        assert!(
            later[changes.len()..]
                .iter()
                .all(|batch| batch.iter().all(|item| item["_id"] != "tt2")),
            "nothing more to send for it"
        );
    }

    #[tokio::test]
    async fn progress_from_the_account_keeps_the_card_and_its_place() {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        core.store()
            .expect("store")
            .save_progress(&ProgressEntry {
                id: "tt5".into(),
                kind: "series".into(),
                name: "Show".into(),
                poster: None,
                background: Some("https://img.example/bg.jpg".into()),
                video_id: "tt5:1:2".into(),
                season: Some(1),
                episode: Some(2),
                position_ms: 60_000,
                duration_ms: 3_000_000,
                updated_at: 1_000,
            })
            .expect("saves");
        let show = |video: &str, offset: u64, mtime: &str| {
            serde_json::json!([{
                "_id": "tt5", "name": "Show", "type": "series", "removed": false, "temp": false,
                "_ctime": "2026-01-01T00:00:00.000Z", "_mtime": mtime,
                "state": {"timeOffset": offset, "duration": 3000000, "video_id": video,
                          "season": 2, "episode": 7}
            }])
        };
        library_now(
            &server,
            4,
            show("tt5:1:2", 900_000, "2026-01-02T00:00:00.000Z"),
        )
        .await;
        core.sync().await.expect("syncs");
        let progress = core.progress("tt5".into()).expect("reads").expect("kept");
        assert_eq!(progress.position_ms, 900_000);
        assert_eq!((progress.season, progress.episode), (Some(1), Some(2)));
        assert_eq!(
            progress.background.as_deref(),
            Some("https://img.example/bg.jpg")
        );

        // The same again: the card keeps its place instead of jumping to the top.
        let before = core.progress("tt5".into()).expect("reads").expect("kept");
        library_now(
            &server,
            3,
            show("tt5:1:2", 900_000, "2026-01-03T00:00:00.000Z"),
        )
        .await;
        core.sync().await.expect("syncs");
        assert_eq!(core.progress("tt5".into()).expect("reads"), Some(before));

        // Another episode there: its numbers come from the item.
        library_now(
            &server,
            2,
            show("tt5:2:7", 30_000, "2026-01-04T00:00:00.000Z"),
        )
        .await;
        core.sync().await.expect("syncs");
        let progress = core
            .progress("tt5".into())
            .expect("reads")
            .expect("moved on");
        assert_eq!((progress.season, progress.episode), (Some(2), Some(7)));
        assert_eq!(
            episode_of(
                &serde_json::from_value(show("tt5:2:7", 1, "2026-01-04T00:00:00.000Z")[0].clone())
                    .expect("item"),
                "tt5:3:4"
            ),
            (Some(3), Some(4)),
            "or from its id"
        );
    }

    #[tokio::test]
    async fn an_account_clock_ahead_does_not_block_changes_here() {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        library_now(
            &server,
            4,
            serde_json::json!([{
                "_id": "tt1", "name": "Remote", "type": "movie", "removed": false, "temp": false,
                "_ctime": "2026-01-01T00:00:00.000Z", "_mtime": "2100-01-01T00:00:00.000Z",
                "state": {"timeOffset": 600000, "duration": 6000000, "video_id": "tt1",
                          "lastWatched": "2100-01-01T00:00:00.000Z"}
            }]),
        )
        .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        core.sync().await.expect("syncs");
        let progress = core.progress("tt1".into()).expect("reads").expect("synced");
        assert!(
            progress.updated_at <= now_millis(),
            "no date in the future here"
        );
        let stats = core
            .store()
            .expect("store")
            .watch_stats("tt1")
            .expect("reads");
        assert!(
            stats.is_some_and(|s| s.updated_at <= now_millis()
                && s.last_watched.is_some_and(|at| at <= now_millis()))
        );
        assert!(uploads(&server).await.is_empty());

        tokio::time::sleep(Duration::from_millis(5)).await;
        core.save_progress(movie_progress("tt1", 1_200_000))
            .expect("plays here");
        core.sync().await.expect("syncs");
        let changes = uploads(&server).await;
        let sent = uploaded(&changes[0], "tt1");
        assert_eq!(
            sent["state"]["timeOffset"], 1_200_000,
            "this device's change wins"
        );
        assert!(sent["_mtime"].as_str() > Some("2100-01-01T00:00:00.000Z"));
        assert_eq!(
            core.progress("tt1".into())
                .expect("reads")
                .expect("kept")
                .position_ms,
            1_200_000
        );
    }

    #[tokio::test]
    async fn an_unreadable_account_item_keeps_its_fields() {
        let server = server().await;
        Mock::given(method("POST"))
            .and(path("/datastorePut"))
            .respond_with(ok())
            .mount(&server)
            .await;
        library_now(
            &server,
            4,
            serde_json::json!([{"_id": "tt5", "name": "Old", "customField": 1, "state": {"x": 2}}]),
        )
        .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        core.add_to_library(crate::records::MetaItem {
            id: "tt5".into(),
            kind: "movie".into(),
            name: "Saved here".into(),
            poster: None,
            background: None,
            logo: None,
            description: None,
            release_info: None,
            imdb_rating: None,
            genres: vec![],
        })
        .expect("adds");
        core.sync().await.expect("syncs");
        let changes = uploads(&server).await;
        let sent = uploaded(&changes[0], "tt5");
        assert_eq!(sent["type"], "movie");
        assert_eq!(sent["name"], "Saved here");
        assert!(sent["_mtime"].is_string());
        assert_eq!(sent["customField"], 1, "the account's fields stay");
        assert_eq!(sent["state"]["x"], 2);
    }

    #[tokio::test]
    async fn an_expired_link_code_stops_waiting() {
        let server = account_server().await;
        Mock::given(method("GET"))
            .and(path("/api/v2/read"))
            .respond_with(ResponseTemplate::new(410))
            .expect(1)
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        let result = core.sign_in_with_link_code("CODE1".into()).await;
        assert!(
            matches!(result, Err(CoreError::SignInNotConfirmed)),
            "{result:?}"
        );
    }

    #[tokio::test]
    async fn a_refused_sign_in_is_a_sign_in_error() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "error": {"code": 77, "message": "Second step needed"}
            })))
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        let result = core.sign_in("me@example.com".into(), "pw".into()).await;
        assert!(
            matches!(&result, Err(CoreError::SignIn { reason }) if reason.contains("77")),
            "{result:?}"
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
