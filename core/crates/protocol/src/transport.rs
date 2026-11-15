// SPDX-License-Identifier: GPL-3.0-or-later
//! Building resource URLs from an addon's manifest URL.

use std::net::{Ipv4Addr, Ipv6Addr};

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

/// A path segment at least this long is taken for a key or encoded settings when it has a
/// digit or a capital in it (keys, UUIDs and base64 mix them) or is one unbroken run of
/// letters; real addon paths (`stremio/v1`, `publicdomainmovies.now.sh`,
/// `a-long-addon-name`) are short, or words split by '-', '_' or '.'.
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
        const STREMIO: &str = "stremio://";
        let trimmed = input.trim();
        // Schemes are case-insensitive, and links typed or pasted by hand are not always
        // lowercase.
        let normalized = match trimmed.get(..STREMIO.len()) {
            Some(scheme) if scheme.eq_ignore_ascii_case(STREMIO) => {
                format!("https://{}", &trimmed[STREMIO.len()..])
            }
            _ => trimmed.to_owned(),
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
    /// them. Errs on the side of yes: a long key-like or encoded path segment, a `key=value`
    /// pair, a query or credentials all count.
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
                (segment.len() >= SETTINGS_SEGMENT_LEN
                    && (segment
                        .bytes()
                        .any(|b| b.is_ascii_digit() || b.is_ascii_uppercase())
                        // A long run of lowercase letters with no word or host separators
                        // reads as a key too; names and hosts are split by '-', '_' or '.'.
                        || !segment.bytes().any(|b| matches!(b, b'.' | b'-' | b'_'))))
                    || !segment
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
            })
    }

    /// Whether the addon is served over HTTPS from a public host (see [`has_public_host`]).
    /// Addons recommended by a catalog must pass this, so a list from the internet cannot
    /// point the app at devices on the user's network.
    pub fn is_public_https(&self) -> bool {
        self.manifest.scheme() == "https" && is_public(&self.manifest)
    }

    /// URL of `resource` for `kind` and `id`, for example
    /// `https://example.com/catalog/movie/top/genre=Drama&skip=100.json`. A query on the
    /// manifest URL (`manifest.json?token=abc`) is kept on every resource URL, as Stremio
    /// does: some addons take their settings or key there.
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
        if let Some(query) = self.manifest.query().filter(|query| !query.is_empty()) {
            url.push('?');
            url.push_str(query);
        }
        url
    }
}

/// Whether `url` is an HTTP(S) address on a public host: not `localhost`, a `.local`,
/// `.internal`, `.lan` or `.home.arpa` name, a name that spells out a private IPv4 address
/// (`192.168.1.1.nip.io`), or a loopback, private, link-local, unspecified or otherwise
/// special address, IPv6 forms that embed an IPv4 address included. It cannot tell where a
/// public name resolves to.
pub fn has_public_host(url: &str) -> bool {
    Url::parse(url).is_ok_and(|url| matches!(url.scheme(), "http" | "https") && is_public(&url))
}

fn is_public(url: &Url) -> bool {
    match url.host() {
        Some(Host::Domain(domain)) => is_public_name(domain),
        Some(Host::Ipv4(ip)) => is_public_ipv4(ip),
        Some(Host::Ipv6(ip)) => is_public_ipv6(ip),
        None => false,
    }
}

fn is_public_name(domain: &str) -> bool {
    let domain = domain.trim_end_matches('.').to_ascii_lowercase();
    domain.contains('.')
        && !["localhost", ".local", ".internal", ".lan", ".home.arpa"]
            .iter()
            .any(|suffix| domain == suffix.trim_start_matches('.') || domain.ends_with(suffix))
        && !names_private_ipv4(&domain)
}

/// Whether the name holds a non-public IPv4 address as four labels (`10.0.0.1.nip.io`) or
/// at the end of a label split by dashes (`app-10-0-0-1.sslip.io`), the forms wildcard DNS
/// services resolve to the address they spell.
fn names_private_ipv4(domain: &str) -> bool {
    let octets = |parts: &[&str]| -> Option<Ipv4Addr> {
        let mut octets = [0u8; 4];
        for (octet, part) in octets.iter_mut().zip(parts) {
            *octet = part.parse().ok()?;
        }
        Some(Ipv4Addr::from(octets))
    };
    let labels: Vec<&str> = domain.split('.').collect();
    let dotted = labels
        .windows(4)
        .filter_map(octets)
        .any(|ip| !is_public_ipv4(ip));
    let dashed = labels.iter().any(|label| {
        let parts: Vec<&str> = label.split('-').collect();
        parts.len() >= 4 && octets(&parts[parts.len() - 4..]).is_some_and(|ip| !is_public_ipv4(ip))
    });
    dotted || dashed
}

fn is_public_ipv4(ip: Ipv4Addr) -> bool {
    let [a, b, ..] = ip.octets();
    !(ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_unspecified()
        || ip.is_broadcast()
        || ip.is_documentation()
        || ip.is_multicast()
        // "This network" (0.0.0.0/8), carrier-grade NAT (100.64.0.0/10), protocol
        // assignments (192.0.0.0/24), benchmarking (198.18.0.0/15) and reserved (240.0.0.0/4).
        || a == 0
        || (a == 100 && (b & 0xc0) == 64)
        || (a == 192 && b == 0 && ip.octets()[2] == 0)
        || (a == 198 && (b & 0xfe) == 18)
        || a >= 240)
}

fn is_public_ipv6(ip: Ipv6Addr) -> bool {
    let segments = ip.segments();
    let first = segments[0];
    !(ip.is_loopback()
        || ip.is_unspecified()
        || ip.is_multicast()
        // IPv4-compatible (::a.b.c.d) and IPv4-mapped (::ffff:a.b.c.d) addresses.
        || segments[..5].iter().all(|&s| s == 0)
        // NAT64 (64:ff9b::/96 and the local-use 64:ff9b:1::/48), which reach IPv4 hosts.
        || (first == 0x64 && segments[1] == 0xff9b)
        // 6to4 (2002::/16) and Teredo (2001::/32), which embed IPv4 addresses too.
        || first == 0x2002
        || (first == 0x2001 && segments[1] == 0)
        // Unique local (fc00::/7), link-local (fe80::/10) and the old site-local (fec0::/10).
        || (first & 0xfe00) == 0xfc00
        || (first & 0xffc0) == 0xfe80
        || (first & 0xffc0) == 0xfec0)
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
    fn keeps_configuration_path_and_query() {
        let url = AddonUrl::parse("https://addon.example/cfg%7Bx%7D/manifest.json?t=1#frag")
            .expect("valid");
        assert_eq!(
            url.resource("stream", "series", "tt1:1:2", &Extra::new()),
            "https://addon.example/cfg%7Bx%7D/stream/series/tt1:1:2.json?t=1"
        );
        let url = AddonUrl::parse("https://addon.example/manifest.json?token=abc").expect("valid");
        assert_eq!(
            url.resource("catalog", "movie", "top", &Extra::new().with("skip", "100")),
            "https://addon.example/catalog/movie/top/skip=100.json?token=abc"
        );
        let url = AddonUrl::parse("https://addon.example/manifest.json?").expect("valid");
        assert_eq!(
            url.resource("meta", "movie", "tt1", &Extra::new()),
            "https://addon.example/meta/movie/tt1.json",
            "an empty query adds nothing"
        );
    }

    #[test]
    fn accepts_stremio_scheme() {
        let url = AddonUrl::parse(" stremio://addon.example/manifest.json ").expect("valid");
        assert_eq!(url.manifest_url(), "https://addon.example/manifest.json");
        let url = AddonUrl::parse("Stremio://addon.example/manifest.json").expect("valid");
        assert_eq!(url.manifest_url(), "https://addon.example/manifest.json");
        assert_eq!(
            AddonUrl::parse("STREMIO:/"),
            Err(TransportError::UnsupportedScheme),
            "shorter than the scheme is just another URL"
        );
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
            "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json",
            "https://addon.example/a-long-lowercase-addon-name/manifest.json",
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
            "https://addon.example/3f2a9c0d1e4b5a6f7c8d/manifest.json",
            "https://addon.example/qwhzkvmrtplxsndfgjc/manifest.json",
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
            "https://[::127.0.0.1]/manifest.json",
            "https://[64:ff9b::c0a8:101]/manifest.json",
            "https://[2002:c0a8:101::1]/manifest.json",
            "https://[2001:0:4136:e378::1]/manifest.json",
            "https://[fec0::1]/manifest.json",
            "https://[ff02::1]/manifest.json",
            "https://224.0.0.1/manifest.json",
            "https://240.0.0.1/manifest.json",
            "https://198.18.0.1/manifest.json",
            "https://127.0.0.1.nip.io/manifest.json",
            "https://app.192.168.1.1.nip.io/manifest.json",
            "https://app-10-0-0-1.sslip.io/manifest.json",
        ];
        for link in private {
            assert!(
                !AddonUrl::parse(link).expect("valid").is_public_https(),
                "{link}"
            );
        }
    }

    #[test]
    fn tells_public_hosts_of_any_url_apart() {
        assert!(has_public_host("https://cdn.example/x.mkv"));
        assert!(
            has_public_host("http://cdn.example/x.mkv"),
            "any HTTP scheme"
        );
        assert!(
            has_public_host("https://8.8.8.8.nip.io/"),
            "spells a public address"
        );
        assert!(has_public_host("https://v1.2.3.example/"));
        assert!(!has_public_host("http://192.168.1.1/admin"));
        assert!(!has_public_host("http://localhost:11470/"));
        assert!(!has_public_host("ftp://cdn.example/"));
        assert!(!has_public_host("not a url"));
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
