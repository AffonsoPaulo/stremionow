// SPDX-License-Identifier: GPL-3.0-or-later
//! Building resource URLs from an addon's manifest URL.

use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};
use url::{Host, Url};

/// Characters that stay readable in path segments; everything else is percent-encoded, which
/// also keeps ids such as `../x` or `a/b` from changing the request path.
const SEGMENT: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'_')
    .remove(b'.')
    .remove(b'~')
    .remove(b':');

/// A path segment at least this long is taken for settings or a key: real addon paths
/// (`stremio/v1`, `addon`) are short words, while keys and encoded options are long.
const SETTINGS_SEGMENT_LEN: usize = 16;

/// Why an addon URL was rejected.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum TransportError {
    #[error("not a valid URL")]
    InvalidUrl,
    #[error("only http and https addons are supported")]
    UnsupportedScheme,
    #[error("addon URLs must end with /manifest.json")]
    NotAManifest,
}

/// The base location of an addon, derived from its manifest URL.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AddonUrl {
    manifest: Url,
    base: String,
}

impl AddonUrl {
    /// Parses a manifest URL such as `https://example.com/config/manifest.json`. The `stremio://`
    /// scheme that install links use is accepted and treated as HTTPS.
    pub fn parse(input: &str) -> Result<Self, TransportError> {
        let trimmed = input.trim();
        let normalized = match trimmed.strip_prefix("stremio://") {
            Some(rest) => format!("https://{rest}"),
            None => trimmed.to_owned(),
        };
        let mut manifest = Url::parse(&normalized).map_err(|_| TransportError::InvalidUrl)?;
        if !matches!(manifest.scheme(), "http" | "https") {
            return Err(TransportError::UnsupportedScheme);
        }
        if manifest.host_str().is_none_or(str::is_empty) {
            return Err(TransportError::InvalidUrl);
        }
        manifest.set_fragment(None);
        let path = manifest.path();
        let Some(base_path) = path.strip_suffix("/manifest.json") else {
            return Err(TransportError::NotAManifest);
        };
        let mut base = manifest.clone();
        base.set_query(None);
        base.set_path(base_path);
        let base = base.as_str().trim_end_matches('/').to_owned();
        Ok(Self { manifest, base })
    }

    /// The manifest URL, normalized.
    pub fn manifest_url(&self) -> &str {
        self.manifest.as_str()
    }

    /// The addon's configuration page, `<base>/configure`, as Stremio opens it. For an addon
    /// that is already configured, the base holds its settings, so the page can start from them.
    pub fn configure_url(&self) -> String {
        format!("{}/configure", self.base)
    }

    /// Whether the URL seems to hold the user's settings rather than only the addon's
    /// location. Configured addons keep their options, and often a debrid or account key, in
    /// the path before `/manifest.json` (or, rarely, in the query), so sharing the link shares
    /// them. Errs on the side of yes: a long or encoded path segment, a `key=value` pair, a
    /// query or credentials all count.
    pub fn carries_settings(&self) -> bool {
        if !self.manifest.username().is_empty()
            || self.manifest.password().is_some()
            || self.manifest.query().is_some_and(|q| !q.is_empty())
        {
            return true;
        }
        let base_path = self
            .manifest
            .path()
            .strip_suffix("/manifest.json")
            .unwrap_or_default();
        base_path
            .split('/')
            .filter(|segment| !segment.is_empty())
            .any(|segment| {
                segment.len() >= SETTINGS_SEGMENT_LEN
                    || !segment
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
            })
    }

    /// Whether the addon is served over HTTPS from a public host: not `localhost`, a
    /// `.local` or `.internal` name, or a loopback, private, link-local or unspecified
    /// address. Addons recommended by a catalog must pass this, so a list from the internet
    /// cannot point the app at devices on the user's network.
    pub fn is_public_https(&self) -> bool {
        if self.manifest.scheme() != "https" {
            return false;
        }
        match self.manifest.host() {
            Some(Host::Domain(domain)) => {
                let domain = domain.trim_end_matches('.').to_ascii_lowercase();
                domain.contains('.')
                    && !["localhost", ".local", ".internal", ".lan", ".home.arpa"]
                        .iter()
                        .any(|suffix| {
                            domain == suffix.trim_start_matches('.') || domain.ends_with(suffix)
                        })
            }
            Some(Host::Ipv4(ip)) => {
                !(ip.is_loopback()
                    || ip.is_private()
                    || ip.is_link_local()
                    || ip.is_unspecified()
                    || ip.is_broadcast()
                    || ip.is_documentation()
                    // Carrier-grade NAT, 100.64.0.0/10.
                    || (ip.octets()[0] == 100 && (ip.octets()[1] & 0xc0) == 64))
            }
            Some(Host::Ipv6(ip)) => {
                let first = ip.segments()[0];
                !(ip.is_loopback()
                    || ip.is_unspecified()
                    // Unique local (fc00::/7) and link-local (fe80::/10) addresses.
                    || (first & 0xfe00) == 0xfc00
                    || (first & 0xffc0) == 0xfe80
                    || ip.to_ipv4_mapped().is_some())
            }
            None => false,
        }
    }

    /// URL of `resource` for `kind` and `id`, for example
    /// `https://example.com/catalog/movie/top/genre=Drama&skip=100.json`.
    pub fn resource(&self, resource: &str, kind: &str, id: &str, extra: &Extra) -> String {
        let mut url = format!(
            "{}/{}/{}/{}",
            self.base,
            encode(resource),
            encode(kind),
            encode(id)
        );
        if !extra.is_empty() {
            url.push('/');
            url.push_str(&extra.encode());
        }
        url.push_str(".json");
        url
    }
}

fn encode(segment: &str) -> String {
    utf8_percent_encode(segment, SEGMENT).to_string()
}

/// Extra properties of a resource request, in the order given.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Extra(Vec<(String, String)>);

impl Extra {
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds `name=value`, ignoring empty values.
    #[must_use]
    pub fn with(mut self, name: &str, value: impl Into<String>) -> Self {
        let value = value.into();
        if !value.trim().is_empty() {
            self.0.push((name.to_owned(), value));
        }
        self
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    fn encode(&self) -> String {
        self.0
            .iter()
            .map(|(name, value)| format!("{}={}", encode(name), encode(value)))
            .collect::<Vec<_>>()
            .join("&")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derives_base_from_manifest() {
        let url = AddonUrl::parse("https://v3-cinemeta.strem.io/manifest.json").expect("valid");
        assert_eq!(
            url.resource("catalog", "movie", "top", &Extra::new()),
            "https://v3-cinemeta.strem.io/catalog/movie/top.json"
        );
    }

    #[test]
    fn keeps_configuration_path_and_drops_query() {
        let url = AddonUrl::parse("https://addon.example/cfg%7Bx%7D/manifest.json?t=1#frag")
            .expect("valid");
        assert_eq!(
            url.resource("stream", "series", "tt1:1:2", &Extra::new()),
            "https://addon.example/cfg%7Bx%7D/stream/series/tt1:1:2.json"
        );
    }

    #[test]
    fn accepts_stremio_scheme() {
        let url = AddonUrl::parse(" stremio://addon.example/manifest.json ").expect("valid");
        assert_eq!(url.manifest_url(), "https://addon.example/manifest.json");
    }

    #[test]
    fn encodes_extra_and_hostile_ids() {
        let url = AddonUrl::parse("https://a.example/manifest.json").expect("valid");
        let extra = Extra::new()
            .with("search", "the office & co/2")
            .with("genre", "")
            .with("skip", "100");
        assert_eq!(
            url.resource("catalog", "series", "top", &extra),
            "https://a.example/catalog/series/top/search=the%20office%20%26%20co%2F2&skip=100.json"
        );
        assert_eq!(
            url.resource("meta", "movie", "../../admin", &Extra::new()),
            "https://a.example/meta/movie/..%2F..%2Fadmin.json"
        );
    }

    #[test]
    fn builds_configure_page_from_base() {
        let url = AddonUrl::parse("https://addon.example/abc/manifest.json?x=1").expect("valid");
        assert_eq!(url.configure_url(), "https://addon.example/abc/configure");
        let root = AddonUrl::parse("https://addon.example/manifest.json").expect("valid");
        assert_eq!(root.configure_url(), "https://addon.example/configure");
    }

    #[test]
    fn spots_links_that_carry_settings() {
        let plain = [
            "https://v3-cinemeta.strem.io/manifest.json",
            "https://opensubtitles.strem.io/stremio/v1/manifest.json",
            "https://addon.example/1.0.0/manifest.json",
        ];
        for link in plain {
            assert!(
                !AddonUrl::parse(link).expect("valid").carries_settings(),
                "{link}"
            );
        }
        let personal = [
            "https://torrentio.strem.fun/providers=yts%7Crealdebrid=ABCDEF/manifest.json",
            "https://addon.example/ABCDEFGHIJKLMNOPQRSTUVWX0123456789/manifest.json",
            "https://addon.example/eyJrZXkiOiJ2YWx1ZSJ9/manifest.json",
            "https://addon.example/0b2f9c1e-8d1a-4f7e-9c2b-1a2b3c4d5e6f/manifest.json",
            "https://addon.example/manifest.json?token=abc",
            "https://user:secret@addon.example/manifest.json",
            "https://addon.example/a,b/manifest.json",
        ];
        for link in personal {
            assert!(
                AddonUrl::parse(link).expect("valid").carries_settings(),
                "{link}"
            );
        }
    }

    #[test]
    fn tells_public_https_hosts_apart() {
        let public = [
            "https://v3-cinemeta.strem.io/manifest.json",
            "https://93.184.216.34/manifest.json",
            "https://[2606:4700::1111]/manifest.json",
        ];
        for link in public {
            assert!(
                AddonUrl::parse(link).expect("valid").is_public_https(),
                "{link}"
            );
        }
        let private = [
            "http://v3-cinemeta.strem.io/manifest.json",
            "https://localhost/manifest.json",
            "https://app.localhost/manifest.json",
            "https://nas.local/manifest.json",
            "https://box.home.arpa/manifest.json",
            "https://intranet/manifest.json",
            "https://127.0.0.1:11470/local-addon/manifest.json",
            "https://10.0.0.2/manifest.json",
            "https://192.168.1.10/manifest.json",
            "https://172.16.0.1/manifest.json",
            "https://169.254.1.1/manifest.json",
            "https://100.64.0.1/manifest.json",
            "https://0.0.0.0/manifest.json",
            "https://[::1]/manifest.json",
            "https://[fd00::1]/manifest.json",
            "https://[fe80::1]/manifest.json",
            "https://[::ffff:127.0.0.1]/manifest.json",
        ];
        for link in private {
            assert!(
                !AddonUrl::parse(link).expect("valid").is_public_https(),
                "{link}"
            );
        }
    }

    #[test]
    fn rejects_bad_urls() {
        assert_eq!(
            AddonUrl::parse("not a url"),
            Err(TransportError::InvalidUrl)
        );
        assert_eq!(
            AddonUrl::parse("file:///etc/manifest.json"),
            Err(TransportError::UnsupportedScheme)
        );
        assert_eq!(
            AddonUrl::parse("javascript:alert(1)//manifest.json"),
            Err(TransportError::UnsupportedScheme)
        );
        assert_eq!(
            AddonUrl::parse("https://a.example/catalog.json"),
            Err(TransportError::NotAManifest)
        );
    }
}
