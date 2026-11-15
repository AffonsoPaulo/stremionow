// SPDX-License-Identifier: GPL-3.0-or-later
//! `StremioApi` against a mock server. Request bodies are asserted against the shapes the
//! official stremio-core client sends.

use std::time::Duration;

use serde_json::{Value, json};
use stremionow_sync::{RemoteAddon, RemoteLibraryItem, StremioApi, SyncError};
use wiremock::matchers::{body_json, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const KEY: &str = "auth-key-123";

async fn setup() -> (MockServer, StremioApi) {
    let server = MockServer::start().await;
    let api = StremioApi::new(Duration::from_secs(5))
        .expect("client")
        .with_base_url(&format!("{}/api/", server.uri()));
    (server, api)
}

async fn mock(server: &MockServer, api_method: &str, request: Value, response: Value) {
    Mock::given(method("POST"))
        .and(path(format!("/api/{api_method}")))
        .and(body_json(request))
        .respond_with(ResponseTemplate::new(200).set_body_json(response))
        .expect(1)
        .mount(server)
        .await;
}

fn user_json() -> Value {
    json!({
        "_id": "user-1",
        "email": "user@example.com",
        "fbId": null,
        "avatar": null,
        "lastModified": "2024-01-01T00:00:00.000Z",
        "dateRegistered": "2020-01-01T00:00:00.000Z",
        "premium_expire": null,
        "gdpr_consent": { "tos": true, "privacy": true, "marketing": false, "from": "web" }
    })
}

fn login_request(password: &str) -> Value {
    json!({
        "type": "Login",
        "email": "user@example.com",
        "password": password,
        "facebook": false
    })
}

fn success() -> Value {
    json!({ "result": { "success": true } })
}

fn library_item_json() -> Value {
    json!({
        "_id": "tt0111161",
        "name": "The Shawshank Redemption",
        "type": "movie",
        "poster": "https://images.example/poster.jpg",
        "posterShape": "poster",
        "removed": false,
        "temp": false,
        "_ctime": "2020-01-01T00:00:00.000Z",
        "_mtime": "2024-01-01T00:00:00.000Z",
        "state": {
            "lastWatched": "2024-01-01T00:00:00.000Z",
            "timeWatched": 1000,
            "timeOffset": 2000,
            "overallTimeWatched": 3000,
            "timesWatched": 0,
            "flaggedWatched": 0,
            "duration": 8520000,
            "video_id": "tt0111161",
            "watched": null,
            "noNotif": false,
            "futureStateField": [1, 2]
        },
        "behaviorHints": {
            "defaultVideoId": "tt0111161",
            "featuredVideoId": null,
            "hasScheduledVideos": false,
            "futureHint": true
        },
        "futureTopLevelField": { "nested": "value" }
    })
}

#[tokio::test]
async fn login_returns_the_session() {
    let (server, api) = setup().await;
    mock(
        &server,
        "login",
        login_request("hunter2"),
        json!({ "result": { "authKey": KEY, "user": user_json() } }),
    )
    .await;

    let session = api
        .login("user@example.com", "hunter2")
        .await
        .expect("login");
    assert_eq!(session.auth_key, KEY);
    assert_eq!(session.user.id, "user-1");
    assert_eq!(session.user.email, "user@example.com");
    assert_eq!(session.user.extra["gdpr_consent"]["from"], "web");
}

#[tokio::test]
async fn login_with_wrong_password_is_invalid_credentials() {
    let (server, api) = setup().await;
    mock(
        &server,
        "login",
        login_request("S3cret-Wrong"),
        json!({ "error": { "code": 3, "message": "Wrong passphrase", "wrongPass": true } }),
    )
    .await;

    let error = api
        .login("user@example.com", "S3cret-Wrong")
        .await
        .unwrap_err();
    assert_eq!(error, SyncError::InvalidCredentials);
    assert!(!format!("{error} {error:?}").contains("S3cret-Wrong"));
}

#[tokio::test]
async fn login_with_unknown_email_is_invalid_credentials() {
    let (server, api) = setup().await;
    // Exact response of the live API for an unregistered email.
    mock(
        &server,
        "login",
        login_request("pw"),
        json!({ "error": { "code": 2, "message": "User not found", "wrongEmail": true } }),
    )
    .await;

    let error = api.login("user@example.com", "pw").await.unwrap_err();
    assert_eq!(error, SyncError::InvalidCredentials);
}

#[tokio::test]
async fn logout_sends_the_auth_key() {
    let (server, api) = setup().await;
    mock(
        &server,
        "logout",
        json!({ "type": "Logout", "authKey": KEY }),
        success(),
    )
    .await;

    api.logout(KEY).await.expect("logout");
}

#[tokio::test]
async fn logout_of_an_expired_session_succeeds() {
    let (server, api) = setup().await;
    mock(
        &server,
        "logout",
        json!({ "type": "Logout", "authKey": KEY }),
        json!({ "error": { "code": 1, "message": "Session does not exist" } }),
    )
    .await;

    api.logout(KEY).await.expect("logout");
}

#[tokio::test]
async fn user_is_fetched() {
    let (server, api) = setup().await;
    mock(
        &server,
        "getUser",
        json!({ "type": "GetUser", "authKey": KEY }),
        json!({ "result": user_json() }),
    )
    .await;

    let user = api.user(KEY).await.expect("user");
    assert_eq!(user.id, "user-1");
    assert_eq!(
        user.date_registered.as_deref(),
        Some("2020-01-01T00:00:00.000Z")
    );
}

#[tokio::test]
async fn expired_session_is_reported() {
    let (server, api) = setup().await;
    // Exact response of the live API for an unknown auth key.
    mock(
        &server,
        "getUser",
        json!({ "type": "GetUser", "authKey": KEY }),
        json!({ "error": { "code": 1, "message": "Session does not exist" } }),
    )
    .await;

    let error = api.user(KEY).await.unwrap_err();
    assert_eq!(error, SyncError::SessionExpired);
    assert!(!format!("{error} {error:?}").contains(KEY));
}

#[tokio::test]
async fn other_api_errors_keep_code_and_message() {
    let (server, api) = setup().await;
    mock(
        &server,
        "getUser",
        json!({ "type": "GetUser", "authKey": KEY }),
        json!({ "error": { "code": 42, "message": "Something broke" } }),
    )
    .await;

    let error = api.user(KEY).await.unwrap_err();
    assert_eq!(
        error,
        SyncError::Server {
            code: 42,
            message: "Something broke".to_owned()
        }
    );
}

#[tokio::test]
async fn other_login_errors_are_a_refused_sign_in_without_the_message() {
    let (server, api) = setup().await;
    mock(
        &server,
        "login",
        login_request("pw"),
        json!({ "error": { "code": 77, "message": "Second step for user@example.com" } }),
    )
    .await;

    let error = api.login("user@example.com", "pw").await.unwrap_err();
    assert_eq!(error, SyncError::SignInRefused { code: 77 });
    assert!(!format!("{error} {error:?}").contains("user@example.com"));
}

#[tokio::test]
async fn http_errors_without_envelope_are_reported() {
    let (server, api) = setup().await;
    Mock::given(path("/api/getUser"))
        .respond_with(ResponseTemplate::new(502).set_body_string("Bad gateway"))
        .mount(&server)
        .await;

    assert_eq!(api.user(KEY).await.unwrap_err(), SyncError::Status(502));
}

#[tokio::test]
async fn malformed_responses_do_not_leak_the_body() {
    let (server, api) = setup().await;
    // A malformed login result: the key must not appear in the error.
    Mock::given(path("/api/login"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "result": { "authKey": KEY, "user": KEY } })),
        )
        .mount(&server)
        .await;

    let error = api.login("user@example.com", "pw").await.unwrap_err();
    assert!(matches!(error, SyncError::InvalidResponse(_)), "{error:?}");
    assert!(!format!("{error} {error:?}").contains(KEY));
}

#[tokio::test]
async fn addon_collection_is_fetched_with_raw_manifests() {
    let (server, api) = setup().await;
    let manifest = json!({
        "id": "org.example",
        "version": "1.0.0",
        "name": "Example",
        "resources": ["stream"],
        "types": ["movie"],
        "catalogs": [],
        "futureManifestField": { "kept": true }
    });
    mock(
        &server,
        "addonCollectionGet",
        json!({ "type": "AddonCollectionGet", "authKey": KEY, "update": true }),
        json!({ "result": {
            "addons": [{
                "manifest": manifest,
                "transportUrl": "https://example.com/manifest.json",
                "flags": { "official": true, "protected": false }
            }],
            "lastModified": "2024-01-01T00:00:00.000Z"
        } }),
    )
    .await;

    let addons = api.addon_collection(KEY).await.expect("addons");
    assert_eq!(addons.len(), 1);
    assert_eq!(addons[0].transport_url, "https://example.com/manifest.json");
    assert!(addons[0].flags.official);
    assert!(!addons[0].flags.protected);
    assert_eq!(addons[0].manifest, manifest);
    assert_eq!(
        addons[0].parsed_manifest().expect("manifest").id,
        "org.example"
    );
}

#[tokio::test]
async fn addon_collection_is_set() {
    let (server, api) = setup().await;
    let manifest = json!({ "id": "org.example", "version": "1.0.0", "name": "Example" });
    let mut addon = RemoteAddon::new("https://example.com/manifest.json", manifest.clone());
    addon.flags.protected = true;
    mock(
        &server,
        "addonCollectionSet",
        json!({
            "type": "AddonCollectionSet",
            "authKey": KEY,
            "addons": [{
                "manifest": manifest,
                "transportUrl": "https://example.com/manifest.json",
                "flags": { "official": false, "protected": true }
            }]
        }),
        success(),
    )
    .await;

    api.set_addon_collection(KEY, &[addon]).await.expect("set");
}

#[tokio::test]
async fn library_is_fetched_and_round_trips_unknown_fields() {
    let (server, api) = setup().await;
    mock(
        &server,
        "datastoreGet",
        json!({ "authKey": KEY, "collection": "libraryItem", "ids": [], "all": true }),
        json!({ "result": [
            library_item_json(),
            { "_id": "broken-without-mtime", "type": "movie" }
        ] }),
    )
    .await;

    let library = api.library(KEY).await.expect("library");
    let items = &library.items;
    assert_eq!(items.len(), 1, "unreadable items are set apart");
    assert_eq!(
        library.unreadable["broken-without-mtime"],
        json!({ "_id": "broken-without-mtime", "type": "movie" }),
        "and kept as the server sent them"
    );
    let item = &items[0];
    assert!(item.in_library());
    assert_eq!(item.progress(), Some(("tt0111161", 2000, 8_520_000)));
    assert_eq!(
        serde_json::to_value(item).expect("serializable"),
        library_item_json()
    );
}

#[tokio::test]
async fn library_changes_are_put() {
    let (server, api) = setup().await;
    let item: RemoteLibraryItem = serde_json::from_value(library_item_json()).expect("valid item");
    mock(
        &server,
        "datastorePut",
        json!({
            "authKey": KEY,
            "collection": "libraryItem",
            "changes": [library_item_json()]
        }),
        success(),
    )
    .await;

    api.put_library(KEY, &[item]).await.expect("put");
}

#[tokio::test]
async fn put_with_expired_session_is_reported() {
    let (server, api) = setup().await;
    Mock::given(path("/api/datastorePut"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(
                json!({ "error": { "code": 1, "message": "Session does not exist" } }),
            ),
        )
        .mount(&server)
        .await;

    let error = api.put_library(KEY, &[]).await.unwrap_err();
    assert_eq!(error, SyncError::SessionExpired);
}

#[tokio::test]
async fn unreachable_server_is_a_network_error() {
    let api = StremioApi::new(Duration::from_secs(2))
        .expect("client")
        .with_base_url("http://127.0.0.1:9/api");
    let error = api.user(KEY).await.unwrap_err();
    assert!(matches!(error, SyncError::Network(_)), "{error:?}");
}
