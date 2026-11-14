// SPDX-License-Identifier: GPL-3.0-or-later
//! Icons of streaming services, for sources that open on a service's own site.
//!
//! The icon comes from the service itself, the host of the link the addon gave, never from a
//! third-party favicon service that would learn what the user watches. Everything about it is
//! untrusted: only HTTPS hosts with a public name are asked, redirects stay on HTTPS, the body
//! is capped and must be a bitmap image the app can decode (no SVG).

use std::time::Duration;

use futures::StreamExt;
use reqwest::Url;
use reqwest::header::{ACCEPT, HeaderValue};

use crate::AddonError;

/// Largest icon accepted. A 180 × 180 touch icon is a few dozen KiB.
pub const MAX_ICON_BYTES: usize = 256 * 1024;
/// Longest host name kept (the DNS limit).
const MAX_HOST_LEN: usize = 253;
/// Redirects followed per icon, all on HTTPS.
const MAX_REDIRECTS: usize = 3;
/// Where sites keep their icons, best first: the touch icon is larger and sharper than most
/// favicons, and both are found at the root without reading the page.
const ICON_PATHS: &[&str] = &["/apple-touch-icon.png", "/favicon.ico"];

/// Endings of names that only exist on local networks.
const LOCAL_SUFFIXES: &[&str] = &[".local", ".lan", ".internal", ".home.arpa", ".localhost"];

/// The host whose icon stands for a link: the lowercased name of an `https://` address on the
/// default port, without credentials. IP addresses and local names give none, so a link from
/// an addon can never make the app reach into the user's network.
pub fn icon_host(page_url: &str) -> Option<String> {
    let url = Url::parse(page_url).ok()?;
    if url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.port().is_some()
    {
        return None;
    }
    // A name, not an IP address.
    let host = url.domain()?;
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    let public = host.contains('.')
        && host.len() <= MAX_HOST_LEN
        && host != "localhost"
        && !LOCAL_SUFFIXES.iter().any(|suffix| host.ends_with(suffix))
        && host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'.');
    public.then_some(host)
}

/// Whether `bytes` start like a PNG, ICO, JPEG, GIF or WebP image.
pub fn is_icon_image(bytes: &[u8]) -> bool {
    bytes.starts_with(b"\x89PNG\r\n\x1a\n")
        || bytes.starts_with(&[0, 0, 1, 0])
        || bytes.starts_with(&[0xFF, 0xD8, 0xFF])
        || bytes.starts_with(b"GIF87a")
        || bytes.starts_with(b"GIF89a")
        || (bytes.len() >= 12 && bytes.starts_with(b"RIFF") && &bytes[8..12] == b"WEBP")
}

/// Fetches service icons. Cheap to clone; clones share the connection pool.
#[derive(Clone)]
pub struct IconClient {
    http: reqwest::Client,
}

impl IconClient {
    /// Creates a client whose requests give up after `timeout`, over HTTPS only.
    pub fn new(timeout: Duration) -> Result<Self, AddonError> {
        Self::build(timeout, true)
    }

    fn build(timeout: Duration, https_only: bool) -> Result<Self, AddonError> {
        let roots = rustls::RootCertStore {
            roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
        };
        let tls = rustls::ClientConfig::builder_with_provider(std::sync::Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .map_err(|e| AddonError::Network(e.to_string()))?
        .with_root_certificates(roots)
        .with_no_client_auth();
        let redirects = reqwest::redirect::Policy::custom(move |attempt| {
            let allowed = attempt.previous().len() < MAX_REDIRECTS
                && (!https_only || icon_host(attempt.url().as_str()).is_some());
            if allowed {
                attempt.follow()
            } else {
                attempt.stop()
            }
        });
        let http = reqwest::Client::builder()
            .tls_backend_preconfigured(tls)
            .user_agent(concat!("StremioNow/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(timeout)
            .timeout(timeout)
            .redirect(redirects)
            .referer(false)
            .https_only(https_only)
            .build()
            .map_err(|e| AddonError::Network(e.to_string()))?;
        Ok(Self { http })
    }

    /// The icon of `host` (from [`icon_host`]), or None when the site has none the app can
    /// show. Failures are not errors: the interface then shows the service's initial.
    pub async fn fetch(&self, host: &str) -> Option<Vec<u8>> {
        if icon_host(&format!("https://{host}/")).as_deref() != Some(host) {
            return None;
        }
        self.fetch_from(&format!("https://{host}")).await
    }

    /// Tries each icon path under `origin` (scheme and host) in turn.
    async fn fetch_from(&self, origin: &str) -> Option<Vec<u8>> {
        for path in ICON_PATHS {
            match self.image(&format!("{origin}{path}")).await {
                Ok(bytes) if is_icon_image(&bytes) => return Some(bytes),
                Ok(_) => log::debug!("a service icon was not an image the app can show"),
                Err(error) => log::debug!("no service icon at a candidate path: {error}"),
            }
        }
        None
    }

    async fn image(&self, url: &str) -> Result<Vec<u8>, AddonError> {
        let response = self
            .http
            .get(url)
            .header(ACCEPT, HeaderValue::from_static("image/png,image/*;q=0.8"))
            .send()
            .await
            .map_err(AddonError::from_reqwest)?;
        let status = response.status();
        if !status.is_success() {
            return Err(AddonError::Status(status.as_u16()));
        }
        if response
            .content_length()
            .is_some_and(|length| length > MAX_ICON_BYTES as u64)
        {
            return Err(AddonError::TooLarge);
        }
        let mut body = Vec::new();
        let mut chunks = response.bytes_stream();
        while let Some(chunk) = chunks.next().await {
            let chunk = chunk.map_err(AddonError::from_reqwest)?;
            if body.len() + chunk.len() > MAX_ICON_BYTES {
                return Err(AddonError::TooLarge);
            }
            body.extend_from_slice(&chunk);
        }
        Ok(body)
    }
}

#[cfg(test)]
mod tests {
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;

    const PNG: &[u8] = b"\x89PNG\r\n\x1a\n rest of the image";

    #[test]
    fn takes_public_https_hosts_only() {
        assert_eq!(
            icon_host("https://www.NetFlix.com/title/80057281?trkid=1").as_deref(),
            Some("www.netflix.com")
        );
        assert_eq!(
            icon_host("https://tv.apple.com./show/x").as_deref(),
            Some("tv.apple.com")
        );
        for hostile in [
            "http://www.netflix.com/title/1",
            "https://192.168.1.1/admin",
            "https://[::1]/",
            "https://localhost/",
            "https://router.local/",
            "https://nas.lan/",
            "https://printer.home.arpa/",
            "https://user:pass@www.netflix.com/",
            "https://www.netflix.com:8443/",
            "https://intranet/",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "not a url",
        ] {
            assert_eq!(icon_host(hostile), None, "{hostile}");
        }
    }

    #[test]
    fn recognizes_bitmap_images_only() {
        assert!(is_icon_image(PNG));
        assert!(is_icon_image(&[0, 0, 1, 0, 1, 0]));
        assert!(is_icon_image(&[0xFF, 0xD8, 0xFF, 0xE0]));
        assert!(is_icon_image(b"GIF89a..."));
        assert!(is_icon_image(b"RIFF\x10\0\0\0WEBPVP8 "));
        assert!(!is_icon_image(b"<svg xmlns='http://www.w3.org/2000/svg'/>"));
        assert!(!is_icon_image(b"<!doctype html><html>"));
        assert!(!is_icon_image(b""));
    }

    fn client() -> IconClient {
        IconClient::build(Duration::from_secs(5), false).expect("client")
    }

    #[tokio::test]
    async fn prefers_the_touch_icon_then_the_favicon() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/apple-touch-icon.png"))
            .respond_with(ResponseTemplate::new(404))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/favicon.ico"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(PNG))
            .mount(&server)
            .await;
        assert_eq!(
            client().fetch_from(&server.uri()).await.as_deref(),
            Some(PNG)
        );
    }

    #[tokio::test]
    async fn refuses_pages_oversized_bodies_and_svg() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/apple-touch-icon.png"))
            .respond_with(ResponseTemplate::new(200).set_body_string("<html>not found</html>"))
            .mount(&server)
            .await;
        let mut huge = PNG.to_vec();
        huge.resize(MAX_ICON_BYTES + 1, 0);
        Mock::given(method("GET"))
            .and(path("/favicon.ico"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(huge))
            .mount(&server)
            .await;
        assert_eq!(client().fetch_from(&server.uri()).await, None);
    }

    #[tokio::test]
    async fn never_asks_hosts_that_are_not_public() {
        let client = IconClient::new(Duration::from_secs(1)).expect("client");
        assert_eq!(client.fetch("127.0.0.1").await, None);
        assert_eq!(client.fetch("nas.local").await, None);
        assert_eq!(client.fetch("www.netflix.com/../x").await, None);
    }
}
