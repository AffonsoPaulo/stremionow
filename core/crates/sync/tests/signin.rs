// SPDX-License-Identifier: GPL-3.0-or-later
//! Signing in elsewhere (link code, Facebook, Apple) against a mock server. Requests are
//! asserted against the shapes the official clients send: stremio-core for the link code
//! (`src/unit_tests/link.rs` asserts the exact URLs), stremio-web for Facebook and Apple.

use std::time::Duration;

use serde_json::json;
use stremionow_sync::{
    AppleCredentials, FacebookCredentials, StremioApi, SyncError, is_valid_state, state_from_bytes,
};
use wiremock::matchers::{body_json, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

const STATE: &str = "0123456789abcdef0123456789abcdef";

async fn setup() -> (MockServer, StremioApi) {
    let server = MockServer::start().await;
    let api = StremioApi::new(Duration::from_secs(5))
        .expect("client")
        .with_base_url(&format!("{}/api/", server.uri()))
        .with_web_url(&server.uri())
        .with_link_url(&format!("{}/", server.uri()));
    (server, api)
}

fn auth_response() -> serde_json::Value {
    json!({ "result": { "authKey": "key-1", "user": { "_id": "u1", "email": "me@example.com" } } })
}

#[test]
fn official_urls_are_https() {
    for url in [
        stremionow_sync::DEFAULT_BASE_URL,
        stremionow_sync::DEFAULT_WEB_URL,
        stremionow_sync::DEFAULT_LINK_URL,
    ] {
        assert!(url.starts_with("https://"), "{url}");
    }
}

#[test]
fn browser_pages_carry_the_state() {
    let api = StremioApi::new(Duration::from_secs(5)).expect("client");
    assert_eq!(
        api.facebook_login_url(STATE),
        format!("https://www.strem.io/login-fb/{STATE}")
    );
    assert_eq!(
        api.apple_login_url(STATE),
        format!("https://www.strem.io/login-apple/{STATE}")
    );
    assert!(is_valid_state(&state_from_bytes([7; 16])));
}

#[tokio::test]
async fn link_code_is_created_as_stremio_core_does() {
    let (server, api) = setup().await;
    Mock::given(method("GET"))
        .and(path("/api/v2/create"))
        .and(query_param("type", "Create"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "result": { "code": "ABCD", "link": "https://www.stremio.com/link?code=ABCD", "qrcode": "iVBOR..." }
        })))
        .expect(1)
        .mount(&server)
        .await;

    let code = api.create_link_code().await.expect("creates");
    assert_eq!(code.code, "ABCD");
    assert_eq!(code.link, "https://www.stremio.com/link?code=ABCD");
}

#[tokio::test]
async fn link_code_reads_nothing_until_confirmed() {
    let (server, api) = setup().await;
    let pending = Mock::given(method("GET"))
        .and(path("/api/v2/read"))
        .and(query_param("type", "Read"))
        .and(query_param("code", "AB CD"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "error": { "code": 101, "message": "Not confirmed" }
        })))
        .expect(1)
        .mount_as_scoped(&server)
        .await;
    assert_eq!(api.read_link_code("AB CD").await.expect("reads"), None);
    drop(pending);

    Mock::given(method("GET"))
        .and(path("/api/v2/read"))
        .and(query_param("code", "AB CD"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(json!({ "result": { "authKey": "key-1" } })),
        )
        .expect(1)
        .mount(&server)
        .await;
    assert_eq!(
        api.read_link_code("AB CD").await.expect("reads").as_deref(),
        Some("key-1")
    );
}

#[tokio::test]
async fn an_expired_link_code_is_reported() {
    let (server, api) = setup().await;
    let expired = Mock::given(method("GET"))
        .and(path("/api/v2/read"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "error": { "code": 102, "message": "Code expired" }
        })))
        .mount_as_scoped(&server)
        .await;
    assert_eq!(
        api.read_link_code("ABCD").await.unwrap_err(),
        SyncError::LinkCodeExpired
    );
    drop(expired);
    Mock::given(method("GET"))
        .and(path("/api/v2/read"))
        .respond_with(ResponseTemplate::new(410))
        .mount(&server)
        .await;
    assert_eq!(
        api.read_link_code("ABCD").await.unwrap_err(),
        SyncError::LinkCodeExpired
    );
}

#[tokio::test]
async fn link_code_network_errors_are_returned() {
    let (server, api) = setup().await;
    Mock::given(method("GET"))
        .and(path("/api/v2/read"))
        .respond_with(ResponseTemplate::new(502))
        .mount(&server)
        .await;
    assert_eq!(
        api.read_link_code("ABCD").await.unwrap_err(),
        SyncError::Status(502)
    );
}

#[tokio::test]
async fn facebook_credentials_arrive_once_the_browser_is_done() {
    let (server, api) = setup().await;
    let pending = Mock::given(method("GET"))
        .and(path(format!("/login-fb-get-acc/{STATE}")))
        .respond_with(ResponseTemplate::new(404))
        .expect(1)
        .mount_as_scoped(&server)
        .await;
    assert_eq!(api.facebook_credentials(STATE).await.expect("polls"), None);
    drop(pending);

    let empty = Mock::given(method("GET"))
        .and(path(format!("/login-fb-get-acc/{STATE}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
        .expect(1)
        .mount_as_scoped(&server)
        .await;
    assert_eq!(api.facebook_credentials(STATE).await.expect("polls"), None);
    drop(empty);

    Mock::given(method("GET"))
        .and(path(format!("/login-fb-get-acc/{STATE}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "user": { "email": "me@example.com", "fbLoginToken": "fb-token" }
        })))
        .mount(&server)
        .await;
    let credentials = api
        .facebook_credentials(STATE)
        .await
        .expect("polls")
        .expect("ready");
    assert_eq!(
        credentials,
        FacebookCredentials {
            email: "me@example.com".into(),
            fb_login_token: "fb-token".into(),
        }
    );
}

#[tokio::test]
async fn facebook_login_sends_the_token_as_the_password() {
    let (server, api) = setup().await;
    Mock::given(method("POST"))
        .and(path("/api/login"))
        .and(body_json(json!({
            "type": "Login",
            "email": "me@example.com",
            "password": "fb-token",
            "facebook": true
        })))
        .respond_with(ResponseTemplate::new(200).set_body_json(auth_response()))
        .expect(1)
        .mount(&server)
        .await;
    let session = api
        .login_with_facebook(&FacebookCredentials {
            email: "me@example.com".into(),
            fb_login_token: "fb-token".into(),
        })
        .await
        .expect("logs in");
    assert_eq!(session.auth_key, "key-1");
}

#[tokio::test]
async fn apple_credentials_and_login() {
    let (server, api) = setup().await;
    Mock::given(method("GET"))
        .and(path(format!("/login-apple-get-acc/{STATE}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "user": { "token": "apple-token", "sub": "sub-1", "email": "me@example.com", "name": null }
        })))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/api/authWithApple"))
        .and(body_json(json!({
            "type": "Apple",
            "token": "apple-token",
            "sub": "sub-1",
            "email": "me@example.com",
            "name": ""
        })))
        .respond_with(ResponseTemplate::new(200).set_body_json(auth_response()))
        .expect(1)
        .mount(&server)
        .await;

    let credentials: AppleCredentials = api
        .apple_credentials(STATE)
        .await
        .expect("polls")
        .expect("ready");
    let session = api.login_with_apple(&credentials).await.expect("logs in");
    assert_eq!(session.user.email, "me@example.com");
}

#[tokio::test]
async fn states_that_are_not_ours_are_refused() {
    let (_server, api) = setup().await;
    let error = api.facebook_credentials("../api/login").await.unwrap_err();
    assert!(matches!(error, SyncError::InvalidResponse(_)), "{error:?}");
}
