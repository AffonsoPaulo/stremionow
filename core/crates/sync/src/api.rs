// SPDX-License-Identifier: GPL-3.0-or-later
//! HTTP client for the Stremio account API.
//!
//! Every method is a JSON `POST` to `<base>/<method>`. Requests are internally tagged with
//! `type` like stremio-core's `APIRequest` (`src/types/api/request.rs`), except the datastore
//! ones, which are untagged (`DatastoreRequest`). The server answers HTTP 200 with either
//! `{"result": ...}` or `{"error": {"code", "message"}}` (`APIResult`,
//! `src/types/api/response.rs`).
//!
//! Signing in elsewhere (a link code, Facebook or Apple in the browser) uses two more services,
//! with plain `GET`s: see [`crate::signin`].

use std::sync::Arc;
use std::time::Duration;

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::signin::{LinkAuthKey, WebAccount};
use crate::{
    AppleCredentials, FacebookCredentials, LinkCode, RemoteAddon, RemoteLibraryItem, Session,
    SyncError, User,
};

/// Base URL of the official API.
pub const DEFAULT_BASE_URL: &str = "https://api.strem.io/api";
/// Stremio's website, where Facebook and Apple sign-in run (stremio-web `STREMIO_URL`).
pub const DEFAULT_WEB_URL: &str = "https://www.strem.io";
/// The link-code service (stremio-core `LINK_API_URL`).
pub const DEFAULT_LINK_URL: &str = "https://link.stremio.com";

/// Redirects followed by the sign-in `GET`s, which carry no secret in a body.
const MAX_REDIRECTS: usize = 3;

/// Datastore collection holding the library (stremio-core `LIBRARY_COLLECTION_NAME`).
const LIBRARY_COLLECTION: &str = "libraryItem";

/// Largest response accepted. A big library with watched bitfields is a few MiB.
const MAX_RESPONSE_BYTES: usize = 32 * 1024 * 1024;

/// API error code for an unknown or expired auth key ("Session does not exist").
const CODE_SESSION_INVALID: u64 = 1;
/// `login` error code for an unregistered email ("User not found").
const CODE_USER_NOT_FOUND: u64 = 2;
/// `login` error code for a wrong password.
const CODE_WRONG_PASSWORD: u64 = 3;

/// Client for the Stremio account API. Cheap to clone; clones share the connection pool.
#[derive(Debug, Clone)]
pub struct StremioApi {
    http: reqwest::Client,
    /// For the sign-in `GET`s: follows a few redirects, to `https` only.
    http_get: reqwest::Client,
    base_url: String,
    web_url: String,
    link_url: String,
}

impl StremioApi {
    /// Creates a client for the official API whose requests give up after `timeout`.
    pub fn new(timeout: Duration) -> Result<Self, SyncError> {
        // A redirected POST could resend the password elsewhere; the API never redirects.
        let http = client(timeout, reqwest::redirect::Policy::none())?;
        let http_get = client(
            timeout,
            reqwest::redirect::Policy::custom(|attempt| {
                if attempt.previous().len() >= MAX_REDIRECTS || attempt.url().scheme() != "https" {
                    attempt.stop()
                } else {
                    attempt.follow()
                }
            }),
        )?;
        Ok(Self {
            http,
            http_get,
            base_url: DEFAULT_BASE_URL.to_owned(),
            web_url: DEFAULT_WEB_URL.to_owned(),
            link_url: DEFAULT_LINK_URL.to_owned(),
        })
    }

    /// Uses another API root, such as a mock server in tests. `url` is the part before the
    /// method name, `https://api.strem.io/api` for the official API.
    pub fn with_base_url(mut self, url: &str) -> Self {
        url.trim_end_matches('/').clone_into(&mut self.base_url);
        self
    }

    /// Uses another website root for Facebook and Apple sign-in, such as a mock server.
    pub fn with_web_url(mut self, url: &str) -> Self {
        url.trim_end_matches('/').clone_into(&mut self.web_url);
        self
    }

    /// Uses another link-code service root, such as a mock server.
    pub fn with_link_url(mut self, url: &str) -> Self {
        url.trim_end_matches('/').clone_into(&mut self.link_url);
        self
    }

    /// Logs in with email and password.
    ///
    /// Fails with [`SyncError::InvalidCredentials`] for an unknown email or a wrong password.
    pub async fn login(&self, email: &str, password: &str) -> Result<Session, SyncError> {
        let body = json!({
            "type": "Login",
            "email": email,
            "password": password,
            "facebook": false,
        });
        self.call("login", &body).await
    }

    /// Logs in with what the website handed over after a Facebook sign-in: the usual `login`,
    /// with `facebook: true` and the token as the password (stremio-web `useFacebookLogin`).
    pub async fn login_with_facebook(
        &self,
        credentials: &FacebookCredentials,
    ) -> Result<Session, SyncError> {
        let body = json!({
            "type": "Login",
            "email": credentials.email,
            "password": credentials.fb_login_token,
            "facebook": true,
        });
        self.call("login", &body).await
    }

    /// Logs in with what the website handed over after an Apple sign-in (stremio-core
    /// `AuthRequest::Apple`).
    pub async fn login_with_apple(
        &self,
        credentials: &AppleCredentials,
    ) -> Result<Session, SyncError> {
        let body = json!({
            "type": "Apple",
            "token": credentials.token,
            "sub": credentials.sub,
            "email": credentials.email,
            "name": credentials.name,
        });
        self.call("authWithApple", &body).await
    }

    /// The page to open in the browser to sign in with Facebook. `state` must pass
    /// [`crate::is_valid_state`].
    pub fn facebook_login_url(&self, state: &str) -> String {
        format!("{}/login-fb/{state}", self.web_url)
    }

    /// The page to open in the browser to sign in with Apple. `state` must pass
    /// [`crate::is_valid_state`].
    pub fn apple_login_url(&self, state: &str) -> String {
        format!("{}/login-apple/{state}", self.web_url)
    }

    /// What the website hands over for `state` once the Facebook sign-in there is done, or
    /// `None` while it is not.
    pub async fn facebook_credentials(
        &self,
        state: &str,
    ) -> Result<Option<FacebookCredentials>, SyncError> {
        self.web_account("login-fb-get-acc", state).await
    }

    /// What the website hands over for `state` once the Apple sign-in there is done, or `None`
    /// while it is not.
    pub async fn apple_credentials(
        &self,
        state: &str,
    ) -> Result<Option<AppleCredentials>, SyncError> {
        self.web_account("login-apple-get-acc", state).await
    }

    /// A new code to confirm on another device.
    pub async fn create_link_code(&self) -> Result<LinkCode, SyncError> {
        let url = self.link_endpoint("create", &[("type", "Create")])?;
        let (status, bytes) = self.get("link create", url).await?;
        parse_envelope("linkCreate", &bytes, status)
    }

    /// The account key once `code` has been confirmed, or `None` while it has not.
    ///
    /// The service answers with an error until then; any error counts as "not yet", and
    /// network failures are returned for the caller to retry.
    pub async fn read_link_code(&self, code: &str) -> Result<Option<String>, SyncError> {
        let url = self.link_endpoint("read", &[("type", "Read"), ("code", code)])?;
        let (status, bytes) = self.get("link read", url).await?;
        match parse_envelope::<LinkAuthKey>("linkRead", &bytes, status) {
            Ok(key) if !key.auth_key.is_empty() => Ok(Some(key.auth_key)),
            Ok(_) | Err(SyncError::Server { .. } | SyncError::SessionExpired) => Ok(None),
            Err(error) => Err(error),
        }
    }

    /// `<link root>/api/v2/<path>?<query>`, as stremio-core's `fetch_api` builds it.
    fn link_endpoint(&self, path: &str, query: &[(&str, &str)]) -> Result<reqwest::Url, SyncError> {
        let mut url = reqwest::Url::parse(&format!("{}/api/v2/{path}", self.link_url))
            .map_err(|e| SyncError::Network(e.to_string()))?;
        url.query_pairs_mut().extend_pairs(query);
        Ok(url)
    }

    /// Polls one of the website's `<endpoint>/<state>` pages. A failed request or one without
    /// a `user` means the sign-in is not done yet, as for stremio-web.
    async fn web_account<T: DeserializeOwned>(
        &self,
        endpoint: &str,
        state: &str,
    ) -> Result<Option<T>, SyncError> {
        if !crate::is_valid_state(state) {
            return Err(SyncError::InvalidResponse(
                "invalid sign-in state".to_owned(),
            ));
        }
        let url = reqwest::Url::parse(&format!("{}/{endpoint}/{state}", self.web_url))
            .map_err(|e| SyncError::Network(e.to_string()))?;
        let (status, bytes) = self.get(endpoint, url).await?;
        if !status.is_success() {
            return Ok(None);
        }
        Ok(serde_json::from_slice::<WebAccount<T>>(&bytes)
            .ok()
            .and_then(|account| account.user))
    }

    /// A `GET` whose URL is never logged: it carries the sign-in state or the link code.
    async fn get(
        &self,
        label: &str,
        url: reqwest::Url,
    ) -> Result<(reqwest::StatusCode, Vec<u8>), SyncError> {
        log::debug!("Stremio sign-in request: {label}");
        let response = self
            .http_get
            .get(url)
            .send()
            .await
            .map_err(SyncError::from_reqwest)?;
        let status = response.status();
        Ok((status, read_body(response).await?))
    }

    /// Ends the session on the server. An already expired session counts as success.
    pub async fn logout(&self, auth_key: &str) -> Result<(), SyncError> {
        let body = json!({ "type": "Logout", "authKey": auth_key });
        match self.call_success("logout", &body).await {
            Err(SyncError::SessionExpired) => Ok(()),
            result => result,
        }
    }

    /// The account the session belongs to, with its latest data.
    pub async fn user(&self, auth_key: &str) -> Result<User, SyncError> {
        let body = json!({ "type": "GetUser", "authKey": auth_key });
        self.call("getUser", &body).await
    }

    /// The addons installed on the account, in the user's order.
    pub async fn addon_collection(&self, auth_key: &str) -> Result<Vec<RemoteAddon>, SyncError> {
        #[derive(Deserialize)]
        struct CollectionResponse {
            addons: Vec<RemoteAddon>,
        }
        // `update: true` is what the official apps send (stremio-core `authenticate`).
        let body = json!({ "type": "AddonCollectionGet", "authKey": auth_key, "update": true });
        let response: CollectionResponse = self.call("addonCollectionGet", &body).await?;
        Ok(response.addons)
    }

    /// Replaces the account's addon collection with `addons`, in that order.
    pub async fn set_addon_collection(
        &self,
        auth_key: &str,
        addons: &[RemoteAddon],
    ) -> Result<(), SyncError> {
        let body = json!({ "type": "AddonCollectionSet", "authKey": auth_key, "addons": addons });
        self.call_success("addonCollectionSet", &body).await
    }

    /// Every library item of the account, removed and temporary ones included.
    ///
    /// Items that cannot be read (missing `_id`, `type` or `_mtime`) are skipped with a
    /// warning, as stremio-core does, instead of failing the whole library.
    pub async fn library(&self, auth_key: &str) -> Result<Vec<RemoteLibraryItem>, SyncError> {
        let body = json!({
            "authKey": auth_key,
            "collection": LIBRARY_COLLECTION,
            "ids": [],
            "all": true,
        });
        let items: Vec<Value> = self.call("datastoreGet", &body).await?;
        Ok(items.into_iter().filter_map(parse_library_item).collect())
    }

    /// Creates or replaces `items` in the account library. To remove an item, upload it with
    /// `removed: true` and a newer `_mtime`.
    pub async fn put_library(
        &self,
        auth_key: &str,
        items: &[RemoteLibraryItem],
    ) -> Result<(), SyncError> {
        let body = json!({
            "authKey": auth_key,
            "collection": LIBRARY_COLLECTION,
            "changes": items,
        });
        self.call_success("datastorePut", &body).await
    }

    /// Calls a method whose result is `{"success": true}` (stremio-core `SuccessResponse`).
    async fn call_success(&self, method: &str, body: &impl Serialize) -> Result<(), SyncError> {
        #[derive(Deserialize)]
        struct SuccessResponse {
            success: bool,
        }
        let response: SuccessResponse = self.call(method, body).await?;
        if response.success {
            Ok(())
        } else {
            Err(SyncError::InvalidResponse(format!(
                "{method} did not report success"
            )))
        }
    }

    /// Posts `body` to `method` and unwraps the result envelope.
    async fn call<T: DeserializeOwned>(
        &self,
        method: &str,
        body: &impl Serialize,
    ) -> Result<T, SyncError> {
        log::debug!("Stremio API call: {method}");
        let response = self
            .http
            .post(format!("{}/{method}", self.base_url))
            .json(body)
            .send()
            .await
            .map_err(SyncError::from_reqwest)?;
        let status = response.status();
        let bytes = read_body(response).await?;
        let result = parse_envelope(method, &bytes, status);
        if let Err(error) = &result {
            log::warn!("Stremio API call {method} failed: {error}");
        }
        result
    }
}

/// An HTTP client with the app's TLS roots and user agent.
fn client(
    timeout: Duration,
    redirect: reqwest::redirect::Policy,
) -> Result<reqwest::Client, SyncError> {
    let roots = rustls::RootCertStore {
        roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
    };
    let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .map_err(|e| SyncError::Network(e.to_string()))?
    .with_root_certificates(roots)
    .with_no_client_auth();
    reqwest::Client::builder()
        .tls_backend_preconfigured(tls)
        .user_agent(concat!("StremioNow/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(timeout)
        .timeout(timeout)
        .redirect(redirect)
        .build()
        .map_err(|e| SyncError::Network(e.to_string()))
}

/// Reads the body, refusing anything larger than [`MAX_RESPONSE_BYTES`].
async fn read_body(mut response: reqwest::Response) -> Result<Vec<u8>, SyncError> {
    if response
        .content_length()
        .is_some_and(|len| len > MAX_RESPONSE_BYTES as u64)
    {
        return Err(too_large());
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(SyncError::from_reqwest)? {
        if bytes.len() + chunk.len() > MAX_RESPONSE_BYTES {
            return Err(too_large());
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}

fn too_large() -> SyncError {
    SyncError::InvalidResponse(format!(
        "the response is larger than {MAX_RESPONSE_BYTES} bytes"
    ))
}

/// The `{"result"}` / `{"error"}` envelope of every response.
#[derive(Deserialize)]
struct Envelope<T> {
    result: Option<T>,
    error: Option<ApiError>,
}

/// Body of `{"error": ...}`. `wrongEmail` / `wrongPass` accompany some `login` errors.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiError {
    #[serde(default)]
    code: u64,
    #[serde(default)]
    message: String,
    #[serde(default)]
    wrong_email: bool,
    #[serde(default)]
    wrong_pass: bool,
}

/// Unwraps the envelope. Without one, a non-success HTTP status is reported as such.
fn parse_envelope<T: DeserializeOwned>(
    method: &str,
    bytes: &[u8],
    status: reqwest::StatusCode,
) -> Result<T, SyncError> {
    match serde_json::from_slice::<Envelope<T>>(bytes) {
        Ok(Envelope {
            error: Some(error), ..
        }) => Err(map_api_error(method, error)),
        Ok(Envelope {
            result: Some(result),
            ..
        }) => Ok(result),
        _ if !status.is_success() => Err(SyncError::Status(status.as_u16())),
        Ok(_) => Err(SyncError::InvalidResponse(
            "neither result nor error in the response".to_owned(),
        )),
        Err(error) => Err(SyncError::from_json(&error)),
    }
}

fn map_api_error(method: &str, error: ApiError) -> SyncError {
    let bad_login = method == "login"
        && (matches!(error.code, CODE_USER_NOT_FOUND | CODE_WRONG_PASSWORD)
            || error.wrong_email
            || error.wrong_pass);
    if bad_login {
        SyncError::InvalidCredentials
    } else if error.code == CODE_SESSION_INVALID {
        SyncError::SessionExpired
    } else {
        SyncError::Server {
            code: error.code,
            message: error.message,
        }
    }
}

fn parse_library_item(value: Value) -> Option<RemoteLibraryItem> {
    match RemoteLibraryItem::deserialize(&value) {
        Ok(item) => Some(item),
        Err(error) => {
            let id = value.get("_id").and_then(Value::as_str).unwrap_or("?");
            log::warn!(
                "skipping unreadable library item {id}: {}",
                SyncError::from_json(&error)
            );
            None
        }
    }
}
