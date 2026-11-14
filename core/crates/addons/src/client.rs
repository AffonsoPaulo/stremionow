// SPDX-License-Identifier: GPL-3.0-or-later
//! HTTP access to addons, with timeouts and response size limits.

use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use serde::de::DeserializeOwned;
use stremionow_protocol::{
    AddonUrl, CatalogDescriptor, CatalogResponse, Extra, Manifest, MetaDetail, MetaPreview,
    MetaResponse, Stream, StreamResponse, Subtitle, SubtitlesResponse, TransportError,
};

/// Largest response accepted from an addon. Real catalogs are well under 1 MiB; the limit
/// protects devices with little memory from broken or hostile addons.
const MAX_RESPONSE_BYTES: usize = 8 * 1024 * 1024;

/// An addon the user installed, with its parsed manifest.
#[derive(Debug, Clone, PartialEq)]
pub struct InstalledAddon {
    pub url: AddonUrl,
    pub manifest: Manifest,
}

/// Why an addon request failed.
#[derive(Debug, thiserror::Error)]
pub enum AddonError {
    #[error("invalid addon URL: {0}")]
    InvalidUrl(#[from] TransportError),
    #[error("the addon did not respond in time")]
    Timeout,
    #[error("could not reach the addon: {0}")]
    Network(String),
    #[error("the addon answered with HTTP {0}")]
    Status(u16),
    #[error("the addon response is larger than {MAX_RESPONSE_BYTES} bytes")]
    TooLarge,
    #[error("the addon sent a response that could not be read: {0}")]
    InvalidResponse(String),
}

impl AddonError {
    pub(crate) fn from_reqwest(error: reqwest::Error) -> Self {
        if error.is_timeout() {
            Self::Timeout
        } else if let Some(status) = error.status() {
            Self::Status(status.as_u16())
        } else {
            // Drop the URL: configured addons (debrid ones especially) keep the user's API key
            // in the path, and this message ends up in errors shown to the app and its logs.
            Self::Network(error.without_url().to_string())
        }
    }
}

/// Talks to addons over HTTP. Cheap to clone; clones share the connection pool.
#[derive(Clone)]
pub struct AddonClient {
    http: reqwest::Client,
    timeout: Duration,
}

impl AddonClient {
    /// Creates a client whose requests give up after `timeout`, response included.
    ///
    /// There is no separate, shorter connect limit: it would include the DNS lookup, which
    /// can take several seconds on a cold resolver. Broken IPv6 is handled by the connector's
    /// Happy Eyeballs fallback instead.
    pub fn new(timeout: Duration) -> Result<Self, AddonError> {
        let roots = rustls::RootCertStore {
            roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
        };
        let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .map_err(|e| AddonError::Network(e.to_string()))?
        .with_root_certificates(roots)
        .with_no_client_auth();

        let http = reqwest::Client::builder()
            .tls_backend_preconfigured(tls)
            .user_agent(concat!("StremioNow/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(timeout)
            .timeout(timeout)
            .redirect(reqwest::redirect::Policy::limited(5))
            // Configured addons keep keys in their URLs; don't pass them on in Referer.
            .referer(false)
            .https_only(false)
            .build()
            .map_err(|e| AddonError::Network(e.to_string()))?;
        Ok(Self { http, timeout })
    }

    /// Time after which a request is abandoned.
    pub fn timeout(&self) -> Duration {
        self.timeout
    }

    /// Downloads and parses the manifest at `url`.
    pub async fn install(&self, url: &str) -> Result<InstalledAddon, AddonError> {
        let url = AddonUrl::parse(url)?;
        let manifest: Manifest = self.get_json(url.manifest_url()).await?;
        Ok(InstalledAddon { url, manifest })
    }

    /// Items of a catalog page. `skip` is the number of items already shown.
    pub async fn catalog(
        &self,
        addon: &InstalledAddon,
        catalog: &CatalogDescriptor,
        genre: Option<&str>,
        search: Option<&str>,
        skip: u32,
    ) -> Result<Vec<MetaPreview>, AddonError> {
        let mut extra = Extra::new();
        if let Some(search) = search {
            extra = extra.with("search", search);
        }
        if let Some(genre) = genre {
            extra = extra.with("genre", genre);
        }
        if skip > 0 {
            extra = extra.with("skip", skip.to_string());
        }
        let url = addon
            .url
            .resource("catalog", &catalog.kind, &catalog.id, &extra);
        let response: CatalogResponse = self.get_json(&url).await?;
        Ok(response.metas)
    }

    /// Full details of a title.
    pub async fn meta(
        &self,
        addon: &InstalledAddon,
        kind: &str,
        id: &str,
    ) -> Result<MetaDetail, AddonError> {
        let url = addon.url.resource("meta", kind, id, &Extra::new());
        let response: MetaResponse = self.get_json(&url).await?;
        Ok(response.meta)
    }

    /// Playable sources of a video.
    pub async fn streams(
        &self,
        addon: &InstalledAddon,
        kind: &str,
        video_id: &str,
    ) -> Result<Vec<Stream>, AddonError> {
        let url = addon.url.resource("stream", kind, video_id, &Extra::new());
        let response: StreamResponse = self.get_json(&url).await?;
        Ok(response.streams)
    }

    /// Subtitles of a video.
    pub async fn subtitles(
        &self,
        addon: &InstalledAddon,
        kind: &str,
        video_id: &str,
    ) -> Result<Vec<Subtitle>, AddonError> {
        let url = addon
            .url
            .resource("subtitles", kind, video_id, &Extra::new());
        let response: SubtitlesResponse = self.get_json(&url).await?;
        Ok(response.subtitles)
    }

    /// Fetches `url` and returns the raw body, refusing bodies above the size limit.
    pub async fn get_bytes(&self, url: &str) -> Result<Vec<u8>, AddonError> {
        let response = self
            .http
            .get(url)
            .header(reqwest::header::ACCEPT, "application/json")
            .send()
            .await
            .map_err(AddonError::from_reqwest)?;
        let status = response.status();
        if !status.is_success() {
            return Err(AddonError::Status(status.as_u16()));
        }
        if response
            .content_length()
            .is_some_and(|length| length > MAX_RESPONSE_BYTES as u64)
        {
            return Err(AddonError::TooLarge);
        }
        let mut body = Vec::new();
        let mut chunks = response.bytes_stream();
        while let Some(chunk) = chunks.next().await {
            let chunk = chunk.map_err(AddonError::from_reqwest)?;
            if body.len() + chunk.len() > MAX_RESPONSE_BYTES {
                return Err(AddonError::TooLarge);
            }
            body.extend_from_slice(&chunk);
        }
        Ok(body)
    }

    async fn get_json<T: DeserializeOwned>(&self, url: &str) -> Result<T, AddonError> {
        let body = self.get_bytes(url).await?;
        serde_json::from_slice(&body).map_err(|e| AddonError::InvalidResponse(e.to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn manifest_json() -> serde_json::Value {
        serde_json::json!({
            "id": "test.addon", "version": "1.0.0", "name": "Test",
            "resources": ["catalog", "meta", "stream"], "types": ["movie"],
            "catalogs": [{"type": "movie", "id": "top", "name": "Top"}]
        })
    }

    async fn server_with_manifest() -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/manifest.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(manifest_json()))
            .mount(&server)
            .await;
        server
    }

    fn client() -> AddonClient {
        AddonClient::new(Duration::from_secs(2)).expect("client")
    }

    #[tokio::test]
    async fn installs_and_reads_catalog() {
        let server = server_with_manifest().await;
        Mock::given(method("GET"))
            .and(path("/catalog/movie/top/skip=100.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "metas": [{"id": "tt1", "type": "movie", "name": "One"}]
            })))
            .mount(&server)
            .await;

        let client = client();
        let addon = client
            .install(&format!("{}/manifest.json", server.uri()))
            .await
            .expect("installs");
        assert_eq!(addon.manifest.name, "Test");
        let catalog = &addon.manifest.catalogs[0];
        let items = client
            .catalog(&addon, catalog, None, None, 100)
            .await
            .expect("catalog");
        assert_eq!(items[0].name, "One");
    }

    #[tokio::test]
    async fn reports_http_errors_and_bad_json() {
        let server = server_with_manifest().await;
        Mock::given(method("GET"))
            .and(path("/meta/movie/missing.json"))
            .respond_with(ResponseTemplate::new(404))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/meta/movie/broken.json"))
            .respond_with(ResponseTemplate::new(200).set_body_string("<html>"))
            .mount(&server)
            .await;

        let client = client();
        let addon = client
            .install(&format!("{}/manifest.json", server.uri()))
            .await
            .expect("installs");
        assert!(matches!(
            client.meta(&addon, "movie", "missing").await,
            Err(AddonError::Status(404))
        ));
        assert!(matches!(
            client.meta(&addon, "movie", "broken").await,
            Err(AddonError::InvalidResponse(_))
        ));
    }

    #[tokio::test]
    async fn times_out_slow_addons() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(manifest_json())
                    .set_delay(Duration::from_secs(5)),
            )
            .mount(&server)
            .await;
        let client = AddonClient::new(Duration::from_millis(300)).expect("client");
        let result = client
            .install(&format!("{}/manifest.json", server.uri()))
            .await;
        assert!(matches!(result, Err(AddonError::Timeout)), "{result:?}");
    }

    #[tokio::test]
    async fn refuses_oversized_responses() {
        let server = MockServer::start().await;
        let huge = vec![b' '; MAX_RESPONSE_BYTES + 1];
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(huge))
            .mount(&server)
            .await;
        let result = client()
            .install(&format!("{}/manifest.json", server.uri()))
            .await;
        assert!(matches!(result, Err(AddonError::TooLarge)), "{result:?}");
    }

    #[tokio::test]
    async fn rejects_invalid_urls_without_network() {
        let result = client().install("ftp://example.com/manifest.json").await;
        assert!(matches!(result, Err(AddonError::InvalidUrl(_))));
    }

    #[tokio::test]
    async fn network_errors_do_not_leak_the_url() {
        let client = AddonClient::new(Duration::from_secs(5)).expect("client");
        // Nothing listens on port 9 (discard) locally, so the connection is refused.
        let error = client
            .get_bytes("http://127.0.0.1:9/secret-api-key/catalog/movie/top.json")
            .await
            .expect_err("no server");
        assert!(!error.to_string().contains("secret-api-key"), "{error}");
    }
}
