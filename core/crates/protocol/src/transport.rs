// SPDX-License-Identifier: GPL-3.0-or-later
//! Building resource URLs from an addon's manifest URL.

use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};
use url::Url;

/// Characters that stay readable in path segments; everything else is percent-encoded, which
/// also keeps ids such as `../x` or `a/b` from changing the request path.
const SEGMENT: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'_')
    .remove(b'.')
    .remove(b'~')
    .remove(b':');

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
