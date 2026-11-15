// SPDX-License-Identifier: GPL-3.0-or-later
//! Relaying remote HTTP sources to the player, ranges included.

use std::collections::{HashMap, VecDeque};
use std::io;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use futures::TryStreamExt;
use hyper::header::{self, HeaderName, HeaderValue};
use hyper::{Method, Response, StatusCode};
use url::Url;

use crate::TorrentError;
use crate::address::{is_private_host, is_private_ip};
use crate::http::{self, Body};

/// Registered sources kept at once; the one used least recently is forgotten first. A session
/// plays a handful.
const MAX_SOURCES: usize = 64;

/// Redirects followed before giving up. CDNs and debrid services chain a few.
const MAX_REDIRECTS: usize = 10;

/// Time allowed to open a connection to the source.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

/// Longest wait for the next chunk of a response. There is no limit on the whole response:
/// the player keeps reading a film for hours.
const READ_TIMEOUT: Duration = Duration::from_secs(30);

/// Response headers passed from the source to the player. The relay asks for the bytes as
/// stored, but a server may compress them anyway; the body is passed on untouched, so the
/// player must learn how it is encoded.
const FORWARDED_HEADERS: [HeaderName; 6] = [
    header::CONTENT_TYPE,
    header::CONTENT_LENGTH,
    header::CONTENT_RANGE,
    header::CONTENT_ENCODING,
    header::ACCEPT_RANGES,
    header::LAST_MODIFIED,
];

/// Remote sources the player may fetch through the server, each behind a random token.
pub(crate) struct Proxy {
    /// For sources at public addresses: it only ever connects to public IPs.
    public: reqwest::Client,
    /// For sources the user pointed at their own network on purpose, such as a home server.
    local: reqwest::Client,
    sources: Mutex<Sources>,
}

#[derive(Default)]
struct Sources {
    by_token: HashMap<String, Source>,
    /// Tokens from least to most recently registered or used, for eviction.
    order: VecDeque<String>,
}

#[derive(Clone)]
struct Source {
    url: Url,
    /// Whether the source may lead into the home network (see [`Proxy::register`]).
    private_allowed: bool,
}

impl Proxy {
    /// Creates the proxy and its HTTP client, which verifies TLS with the bundled web roots.
    pub(crate) fn new() -> Result<Self, TorrentError> {
        Ok(Self {
            public: http_client(true)?,
            local: http_client(false)?,
            sources: Mutex::default(),
        })
    }

    /// Registers `url` and returns the token that addresses it. Only http(s) is accepted, and
    /// an address on this device or the home network only with `private_allowed`: addons on
    /// the internet must not make the device reach into its own network.
    pub(crate) fn register(
        &self,
        url: &str,
        private_allowed: bool,
    ) -> Result<String, TorrentError> {
        let url = Url::parse(url).map_err(|_| TorrentError::UnsupportedUrl)?;
        if !matches!(url.scheme(), "http" | "https") || !url.has_host() {
            return Err(TorrentError::UnsupportedUrl);
        }
        if !private_allowed && is_private_host(&url) {
            return Err(TorrentError::PrivateAddress);
        }
        let token = crate::secret::random_hex()?;
        let mut sources = self.sources();
        if sources.order.len() == MAX_SOURCES
            && let Some(oldest) = sources.order.pop_front()
        {
            sources.by_token.remove(&oldest);
        }
        sources.order.push_back(token.clone());
        let source = Source {
            url,
            private_allowed,
        };
        sources.by_token.insert(token.clone(), source);
        Ok(token)
    }

    /// The source behind `token`, which becomes the most recently used: a film playing for
    /// hours must not be forgotten because many subtitles were registered meanwhile.
    fn take(&self, token: &str) -> Option<Source> {
        let mut sources = self.sources();
        let source = sources.by_token.get(token).cloned()?;
        if let Some(position) = sources.order.iter().position(|known| known == token)
            && let Some(used) = sources.order.remove(position)
        {
            sources.order.push_back(used);
        }
        Some(source)
    }

    /// Answers a player request for the source behind `token`: 404 if there is none.
    pub(crate) async fn respond(
        &self,
        token: &str,
        method: &Method,
        range: Option<&HeaderValue>,
    ) -> Response<Body> {
        let Some(Source {
            url,
            private_allowed,
        }) = self.take(token)
        else {
            return http::not_found();
        };
        // Only a source allowed into the home network gets the client that may connect there;
        // a public one resolves names to public addresses only.
        let client = if private_allowed && is_private_host(&url) {
            &self.local
        } else {
            &self.public
        };
        let mut request = client
            .request(method.clone(), url)
            // The player needs the bytes as stored; a compressed body would break ranges.
            .header(header::ACCEPT_ENCODING, "identity");
        if let Some(range) = range {
            request = request.header(header::RANGE, range.clone());
        }
        match request.send().await {
            Ok(upstream) => relay(upstream, method == Method::HEAD),
            Err(error) => {
                // The source URL often embeds credentials (debrid links), so keep it out of logs.
                log::warn!("proxy: source unreachable: {}", error.without_url());
                http::empty(StatusCode::BAD_GATEWAY)
            }
        }
    }

    fn sources(&self) -> std::sync::MutexGuard<'_, Sources> {
        // The map stays consistent even if a holder panicked, so poisoning is ignored.
        self.sources.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

/// The source's status, selected headers and, unless `head_only`, its streamed body.
pub(crate) fn relay(upstream: reqwest::Response, head_only: bool) -> Response<Body> {
    let status = upstream.status();
    let headers: Vec<_> = FORWARDED_HEADERS
        .iter()
        .filter_map(|name| Some((name.clone(), upstream.headers().get(name)?.clone())))
        .collect();
    let mut response = if head_only {
        http::empty(status)
    } else {
        let chunks = upstream.bytes_stream().map_err(io::Error::other);
        let mut response = Response::new(http::stream_body(chunks));
        *response.status_mut() = status;
        response
    };
    response.headers_mut().extend(headers);
    response
}

/// The relay's HTTP client. With `public_only`, names resolve to public addresses only, so a
/// public source cannot reach the user's network through DNS (a name pointing at a private
/// address, or one that changes answers between checks): the vetted address is the one used.
fn http_client(public_only: bool) -> Result<reqwest::Client, TorrentError> {
    let roots = rustls::RootCertStore {
        roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
    };
    let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .map_err(io::Error::other)?
    .with_root_certificates(roots)
    .with_no_client_auth();
    let builder = reqwest::Client::builder();
    let builder = if public_only {
        builder.dns_resolver(Arc::new(PublicResolver))
    } else {
        builder
    };
    builder
        .tls_backend_preconfigured(tls)
        .user_agent(concat!("StremioNow/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(CONNECT_TIMEOUT)
        .read_timeout(READ_TIMEOUT)
        .redirect(redirect_policy())
        // Debrid resolve links carry the user's key in their path and always redirect; a
        // Referer would hand that key to the download host.
        .referer(false)
        .build()
        .map_err(|error| TorrentError::Io(io::Error::other(error)))
}

/// Follows up to [`MAX_REDIRECTS`] redirects, refusing to downgrade from https to http or to
/// move from a public host to a private one, so a source cannot bounce the relay into the
/// user's network. Sources the user points at a private address directly still work.
fn redirect_policy() -> reqwest::redirect::Policy {
    reqwest::redirect::Policy::custom(|attempt| {
        if attempt.previous().len() > MAX_REDIRECTS {
            return attempt.error("too many redirects");
        }
        let Some(previous) = attempt.previous().last() else {
            return attempt.follow();
        };
        let next = attempt.url();
        if previous.scheme() == "https" && next.scheme() != "https" {
            return attempt.error("refusing a redirect from https to http");
        }
        if !is_private_host(previous) && is_private_host(next) {
            return attempt.error("refusing a redirect into a private network");
        }
        attempt.follow()
    })
}

/// Resolves names to their public addresses only, failing when there are none.
struct PublicResolver;

impl reqwest::dns::Resolve for PublicResolver {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        Box::pin(async move {
            let host = name.as_str().to_owned();
            let public: Vec<std::net::SocketAddr> = tokio::net::lookup_host((host.as_str(), 0))
                .await?
                .filter(|address| !is_private_ip(address.ip()))
                .collect();
            if public.is_empty() {
                return Err("the source resolves only to private addresses".into());
            }
            Ok(Box::new(public.into_iter()) as reqwest::dns::Addrs)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn public_sources_never_resolve_to_private_addresses() {
        use reqwest::dns::Resolve;
        let name: reqwest::dns::Name = "localhost".parse().expect("name");
        let result = PublicResolver.resolve(name).await;
        assert!(
            result.is_err(),
            "localhost must not resolve for a public source"
        );
    }

    #[test]
    fn only_http_sources_are_accepted() {
        let proxy = Proxy::new().expect("proxy");
        for url in [
            "file:///etc/passwd",
            "ftp://example.org/movie.mkv",
            "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10",
            "javascript:alert(1)",
            "/relative/path",
            "",
        ] {
            assert!(
                matches!(proxy.register(url, true), Err(TorrentError::UnsupportedUrl)),
                "{url:?}"
            );
        }
        assert!(
            proxy
                .register("https://cdn.example.org/movie.mkv", false)
                .is_ok()
        );
        assert!(
            proxy
                .register("http://192.168.1.2:8080/movie.mkv", true)
                .is_ok()
        );
    }

    #[test]
    fn private_sources_need_permission() {
        let proxy = Proxy::new().expect("proxy");
        for url in [
            "http://192.168.1.1/admin",
            "http://127.0.0.1:8080/x",
            "http://[::1]/x",
            "http://router.lan/x",
            "http://[2001:0:4136:e378:8000:63bf:3fff:fdd2]/x",
        ] {
            assert!(
                matches!(
                    proxy.register(url, false),
                    Err(TorrentError::PrivateAddress)
                ),
                "{url}"
            );
            assert!(proxy.register(url, true).is_ok(), "{url}");
        }
    }

    #[test]
    fn least_recently_used_sources_are_forgotten() {
        let proxy = Proxy::new().expect("proxy");
        let first = proxy
            .register("https://example.org/0", false)
            .expect("token");
        let second = proxy
            .register("https://example.org/1", false)
            .expect("token");
        // Playing the first keeps it, even after many more registrations.
        for i in 2..=MAX_SOURCES {
            if i % 8 == 0 {
                assert!(proxy.take(&first).is_some());
            }
            proxy
                .register(&format!("https://example.org/{i}"), false)
                .expect("token");
        }
        let sources = proxy.sources();
        assert_eq!(sources.by_token.len(), MAX_SOURCES);
        assert_eq!(sources.order.len(), MAX_SOURCES);
        assert!(sources.by_token.contains_key(&first));
        assert!(!sources.by_token.contains_key(&second));
    }
}

#[cfg(test)]
mod live_check {
    #[tokio::test]
    #[ignore = "needs the network"]
    async fn follows_public_https_redirects() {
        let client = super::http_client(true).expect("client");
        let response = client
            .get("https://archive.org/download/night-of-the-living-dead-mp-4-burned/NightOfTheLivingDead%20%28mp4%29-BURNED.ia.mp4")
            .header("Range", "bytes=0-1")
            .send()
            .await;
        println!("{response:?}");
        assert!(response.is_ok());
    }
}
