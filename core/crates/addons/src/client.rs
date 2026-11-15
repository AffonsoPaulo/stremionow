// SPDX-License-Identifier: GPL-3.0-or-later
//! HTTP access to addons, with timeouts and response size limits.

use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use serde::de::DeserializeOwned;
use stremionow_protocol::{
    AddonUrl, CatalogDescriptor, CatalogResponse, Extra, Manifest, MetaDetail, MetaPreview,
    MetaResponse, Stream, StreamResponse, Subtitle, SubtitlesResponse, TransportError,
    has_public_host,
};

/// Largest response accepted from an addon. Real catalogs are well under 1 MiB; the limit
/// protects devices with little memory from broken or hostile addons.
const MAX_RESPONSE_BYTES: usize = 8 * 1024 * 1024;
/// Redirects followed per request.
const MAX_REDIRECTS: usize = 5;
/// Longest parser message kept in an error. The message can quote what the addon sent, which
/// is untrusted and has no length limit, and it ends up in the app's errors and logs.
const MAX_REASON_CHARS: usize = 200;

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
        } else if error.is_redirect() {
            // The reason is one of the redirect policy's fixed texts, without the address.
            let reason = std::error::Error::source(&error)
                .map(ToString::to_string)
                .unwrap_or_default();
            Self::Network(format!("redirect not followed: {reason}"))
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
            .redirect(reqwest::redirect::Policy::custom(|attempt| {
                let previous = attempt.previous();
                let refusal = if previous.len() > MAX_REDIRECTS {
                    Some("too many redirects")
                } else {
                    match (previous.first(), previous.last()) {
                        (Some(first), Some(last)) => {
                            redirect_refusal(first.as_str(), last.as_str(), attempt.url().as_str())
                        }
                        _ => None,
                    }
                };
                match refusal {
                    Some(reason) => attempt.error(reason),
                    None => attempt.follow(),
                }
            }))
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
        // The id tells installed addons apart; one without it cannot be told from another
        // (Discover leaves such addons out too).
        if manifest.id.trim().is_empty() {
            return Err(AddonError::InvalidResponse("the manifest has no id".into()));
        }
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

    /// The size of the file `url` leads to, following redirects and asking for its first
    /// byte only, so nothing is downloaded. None when the server does not tell.
    pub async fn file_size(&self, url: &str) -> Result<Option<u64>, AddonError> {
        let response = self
            .http
            .get(url)
            .header(reqwest::header::RANGE, "bytes=0-0")
            .send()
            .await
            .map_err(AddonError::from_reqwest)?;
        let status = response.status();
        if !status.is_success() {
            return Err(AddonError::Status(status.as_u16()));
        }
        // "bytes 0-0/734003200": the whole size after the slash. A server that ignores ranges
        // answers with the whole file, and its length; the body is dropped unread.
        let total = response
            .headers()
            .get(reqwest::header::CONTENT_RANGE)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.rsplit('/').next())
            .and_then(|total| total.trim().parse::<u64>().ok());
        let whole = (status != reqwest::StatusCode::PARTIAL_CONTENT)
            .then(|| response.content_length())
            .flatten();
        Ok(total.or(whole))
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
        parse_json(&self.get_bytes(url).await?)
    }
}

/// Parses an addon's JSON response, ignoring a leading byte order mark (some addons served
/// from Windows hosts send one). A parse error keeps only the start of the parser's message.
pub fn parse_json<T: DeserializeOwned>(body: &[u8]) -> Result<T, AddonError> {
    serde_json::from_slice(without_bom(body)).map_err(|e| {
        let mut reason = e.to_string();
        if let Some((cut, _)) = reason.char_indices().nth(MAX_REASON_CHARS) {
            reason.truncate(cut);
            reason.push('…');
        }
        AddonError::InvalidResponse(reason)
    })
}

/// `body` without a leading UTF-8 byte order mark.
pub fn without_bom(body: &[u8]) -> &[u8] {
    body.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(body)
}

/// Why a redirect from `previous` to `next`, in a chain that started at `first`, is not
/// followed, if it is not. HTTPS never leads to plain HTTP, which would send the addon's
/// URL, and the key it often holds, in the clear; and an address on the internet never
/// leads into the user's network (a router's page, a NAS), though an addon the user runs
/// on their own network may redirect within it.
fn redirect_refusal(first: &str, previous: &str, next: &str) -> Option<&'static str> {
    if previous.starts_with("https:") && !next.starts_with("https:") {
        Some("an HTTPS address redirected to plain HTTP")
    } else if has_public_host(first) && !has_public_host(next) {
        Some("a public address redirected into a private network")
    } else {
        None
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
    async fn reads_manifests_with_a_byte_order_mark_and_refuses_ones_without_id() {
        let server = MockServer::start().await;
        let mut body = b"\xEF\xBB\xBF".to_vec();
        body.extend_from_slice(manifest_json().to_string().as_bytes());
        Mock::given(method("GET"))
            .and(path("/bom/manifest.json"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(body))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/anonymous/manifest.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "id": " ", "version": "1.0.0", "name": "Nobody"
            })))
            .mount(&server)
            .await;
        let client = client();
        let addon = client
            .install(&format!("{}/bom/manifest.json", server.uri()))
            .await
            .expect("installs");
        assert_eq!(addon.manifest.id, "test.addon");
        let result = client
            .install(&format!("{}/anonymous/manifest.json", server.uri()))
            .await;
        assert!(
            matches!(result, Err(AddonError::InvalidResponse(_))),
            "{result:?}"
        );
    }

    #[test]
    fn keeps_parse_errors_short() {
        let hostile = format!(r#"{{"meta": "{}"}}"#, "x".repeat(10_000));
        let Err(AddonError::InvalidResponse(reason)) =
            parse_json::<MetaResponse>(hostile.as_bytes())
        else {
            panic!("not a meta response");
        };
        assert!(reason.chars().count() <= MAX_REASON_CHARS + 1, "{reason}");
        assert!(reason.ends_with('…'));
    }

    #[test]
    fn refuses_downgrades_and_redirects_into_private_networks() {
        let refused = |first: &str, previous: &str, next: &str| {
            redirect_refusal(first, previous, next).is_some()
        };
        let public = "https://addon.example/manifest.json";
        assert!(!refused(
            public,
            public,
            "https://cdn.example/manifest.json"
        ));
        assert!(refused(public, public, "http://cdn.example/manifest.json"));
        assert!(refused(public, public, "https://192.168.1.1/manifest.json"));
        assert!(refused(public, public, "https://localhost/manifest.json"));
        assert!(
            refused(public, "https://cdn.example/x", "https://10.0.0.1/x"),
            "later hops are held to where the chain started"
        );
        // Addons the user runs on their own network keep working.
        let lan = "http://192.168.1.20:7000/manifest.json";
        assert!(!refused(
            lan,
            lan,
            "http://192.168.1.20:7000/v2/manifest.json"
        ));
        assert!(!refused(lan, lan, "https://addon.example/manifest.json"));
        let lan_https = "https://nas.local/manifest.json";
        assert!(refused(
            lan_https,
            lan_https,
            "http://nas.local/manifest.json"
        ));
        // Plain HTTP may move to HTTPS.
        let plain = "http://addon.example/manifest.json";
        assert!(!refused(
            plain,
            plain,
            "https://addon.example/manifest.json"
        ));
    }

    #[tokio::test]
    async fn follows_redirects_of_addons_on_the_local_network() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/manifest.json"))
            .respond_with(ResponseTemplate::new(302).insert_header("location", "/v2/manifest.json"))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/v2/manifest.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(manifest_json()))
            .mount(&server)
            .await;
        let addon = client()
            .install(&format!("{}/manifest.json", server.uri()))
            .await
            .expect("follows the redirect");
        assert_eq!(addon.manifest.name, "Test");
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
