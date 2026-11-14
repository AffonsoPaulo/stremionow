// SPDX-License-Identifier: GPL-3.0-or-later
//! Client for Stremio's likes service, which keeps the "Like" and "Love" of each title.
//!
//! Requests and responses follow the official `stremio-core` crate (`src/types/rating`):
//!
//! - `GET <base>/api/get_status?authToken=<key>&mediaId=<id>&mediaType=<type>` answers
//!   `{"status": "liked" | "loved" | "watched" | null}`;
//! - `POST <base>/api/send` with `{"authToken", "mediaId", "mediaType", "status"}`, where a
//!   `null` status clears the rating, answers `{"message", "rating"?, "action"?}`.
//!
//! The service takes the auth key in the query of `get_status`, as the official apps send it.
//! Requests go over HTTPS only and URLs never reach logs or error messages.

use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::SyncError;
use crate::api::{http_client, read_limited};

/// Base URL of the official likes service.
pub const DEFAULT_LIKES_URL: &str = "https://likes.stremio.com";

/// Largest response accepted; the answers are a few dozen bytes.
const MAX_RESPONSE_BYTES: usize = 64 * 1024;

/// A title's rating on the likes service (stremio-core `Rating`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Rating {
    /// Set by the official apps once a title was watched to the end; not a choice of the user.
    Watched,
    Liked,
    Loved,
}

/// Client for the likes service. Cheap to clone; clones share the connection pool.
#[derive(Debug, Clone)]
pub struct LikesApi {
    http: reqwest::Client,
    base_url: String,
}

impl LikesApi {
    /// Creates a client for the official service whose requests give up after `timeout`.
    pub fn new(timeout: Duration) -> Result<Self, SyncError> {
        Ok(Self {
            http: http_client(timeout)?,
            base_url: DEFAULT_LIKES_URL.to_owned(),
        })
    }

    /// Uses another root, such as a mock server in tests: the part before `/api/...`.
    pub fn with_base_url(mut self, url: &str) -> Self {
        url.trim_end_matches('/').clone_into(&mut self.base_url);
        self
    }

    /// The account's rating of a title. A status the app does not know reads as none.
    pub async fn status(
        &self,
        auth_key: &str,
        media_id: &str,
        media_type: &str,
    ) -> Result<Option<Rating>, SyncError> {
        #[derive(Deserialize)]
        struct StatusResponse {
            #[serde(default)]
            status: Option<Value>,
        }
        let mut url = reqwest::Url::parse(&format!("{}/api/get_status", self.base_url))
            .map_err(|_| SyncError::Network("invalid likes service address".to_owned()))?;
        url.query_pairs_mut()
            .append_pair("authToken", auth_key)
            .append_pair("mediaId", media_id)
            .append_pair("mediaType", media_type);
        let response = self
            .http
            .get(url)
            .send()
            .await
            .map_err(SyncError::from_reqwest)?;
        let bytes = Self::body(response).await?;
        let parsed: StatusResponse =
            serde_json::from_slice(&bytes).map_err(|e| SyncError::from_json(&e))?;
        Ok(parsed
            .status
            .and_then(|status| serde_json::from_value(status).ok()))
    }

    /// Sets the account's rating of a title; `None` clears it.
    pub async fn send(
        &self,
        auth_key: &str,
        media_id: &str,
        media_type: &str,
        rating: Option<Rating>,
    ) -> Result<(), SyncError> {
        #[derive(Serialize)]
        #[serde(rename_all = "camelCase")]
        struct SendBody<'a> {
            auth_token: &'a str,
            media_id: &'a str,
            media_type: &'a str,
            status: Option<Rating>,
        }
        let body = SendBody {
            auth_token: auth_key,
            media_id,
            media_type,
            status: rating,
        };
        let response = self
            .http
            .post(format!("{}/api/send", self.base_url))
            .json(&body)
            .send()
            .await
            .map_err(SyncError::from_reqwest)?;
        Self::body(response).await.map(drop)
    }

    /// The body of a successful answer. The service's error bodies are not documented, so an
    /// unauthorized answer means the session is gone and any other failure is its status.
    async fn body(response: reqwest::Response) -> Result<Vec<u8>, SyncError> {
        let status = response.status();
        if status == reqwest::StatusCode::UNAUTHORIZED || status == reqwest::StatusCode::FORBIDDEN {
            return Err(SyncError::SessionExpired);
        }
        if !status.is_success() {
            return Err(SyncError::Status(status.as_u16()));
        }
        read_limited(response, MAX_RESPONSE_BYTES).await
    }
}

#[cfg(test)]
mod tests {
    use wiremock::matchers::{body_json, method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;

    fn api(server: &MockServer) -> LikesApi {
        LikesApi::new(Duration::from_secs(5))
            .expect("client")
            .with_base_url(&server.uri())
    }

    #[tokio::test]
    async fn reads_the_status_of_a_title() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/api/get_status"))
            .and(query_param("authToken", "key&more"))
            .and(query_param("mediaId", "tt1"))
            .and(query_param("mediaType", "movie"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"status": "loved"})),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/get_status"))
            .and(query_param("mediaId", "tt2"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"status": null})),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/get_status"))
            .and(query_param("mediaId", "tt3"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"status": "adored"})),
            )
            .mount(&server)
            .await;
        let api = api(&server);
        assert_eq!(
            api.status("key&more", "tt1", "movie").await,
            Ok(Some(Rating::Loved)),
            "the key is encoded, not split into parameters"
        );
        assert_eq!(api.status("key", "tt2", "movie").await, Ok(None));
        assert_eq!(
            api.status("key", "tt3", "movie").await,
            Ok(None),
            "an unknown status is none"
        );
    }

    #[tokio::test]
    async fn sends_and_clears_a_rating() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/api/send"))
            .and(body_json(serde_json::json!({
                "authToken": "key", "mediaId": "tt1", "mediaType": "series", "status": "liked"
            })))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "message": "ok", "rating": {"status": "liked"}, "action": "add"
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/api/send"))
            .and(body_json(serde_json::json!({
                "authToken": "key", "mediaId": "tt1", "mediaType": "series", "status": null
            })))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"message": "ok"})),
            )
            .expect(1)
            .mount(&server)
            .await;
        let api = api(&server);
        api.send("key", "tt1", "series", Some(Rating::Liked))
            .await
            .expect("sends");
        api.send("key", "tt1", "series", None)
            .await
            .expect("clears");
    }

    #[tokio::test]
    async fn reports_failures_without_the_key() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(401))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(500))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(query_param("mediaId", "big"))
            .respond_with(
                ResponseTemplate::new(200).set_body_string("x".repeat(MAX_RESPONSE_BYTES + 1)),
            )
            .with_priority(1)
            .mount(&server)
            .await;
        let api = api(&server);
        assert_eq!(
            api.status("secret", "tt1", "movie").await,
            Err(SyncError::SessionExpired)
        );
        let error = api
            .send("secret", "tt1", "movie", Some(Rating::Loved))
            .await
            .expect_err("fails");
        assert_eq!(error, SyncError::Status(500));
        assert!(!error.to_string().contains("secret"));
        assert!(matches!(
            api.status("secret", "big", "movie").await,
            Err(SyncError::InvalidResponse(_))
        ));
    }
}
