// SPDX-License-Identifier: GPL-3.0-or-later
//! Plain data types shared with the app interfaces.

use stremionow_addons::{AddonError, InstalledAddon, manifest_fingerprint};
use stremionow_library::StoreError;
use stremionow_protocol::{CatalogDescriptor, Link, MetaDetail, MetaPreview, Video};

/// Why a core operation failed. Messages are for logs; the interface shows its own text.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// An addon or the network could not be reached, or answered with an error.
    #[error("network error: {reason}")]
    Network { reason: String },
    /// A URL or manifest is not a valid addon.
    #[error("invalid addon: {reason}")]
    InvalidAddon { reason: String },
    /// No installed addon can provide what was asked.
    #[error("not found")]
    NotFound,
    /// The local database failed.
    #[error("storage error: {reason}")]
    Storage { reason: String },
    /// The addon no longer matches what the user confirmed; nothing was installed.
    #[error("the addon changed since it was confirmed")]
    AddonChanged,
    /// A source could not be prepared for playback (no peers, no video file, engine error).
    #[error("playback error: {reason}")]
    Playback { reason: String },
    /// Signing in to the Stremio account failed or the session ended.
    #[error("sign-in error: {reason}")]
    SignIn { reason: String },
    /// A sign-in finishing elsewhere (a link code, the browser) was not confirmed in time.
    #[error("the sign-in was not confirmed in time")]
    SignInNotConfirmed,
    /// A sign-in finishing elsewhere was cancelled with `cancel_sign_in`.
    #[error("the sign-in was cancelled")]
    SignInCancelled,
    /// Serving to other devices needs a home network, and the device is not on one.
    #[error("not connected to a local network")]
    NoLocalNetwork,
}

impl From<AddonError> for CoreError {
    fn from(error: AddonError) -> Self {
        match error {
            AddonError::InvalidUrl(e) => Self::InvalidAddon {
                reason: e.to_string(),
            },
            AddonError::InvalidResponse(reason) => Self::InvalidAddon { reason },
            other => Self::Network {
                reason: other.to_string(),
            },
        }
    }
}

impl From<StoreError> for CoreError {
    fn from(error: StoreError) -> Self {
        Self::Storage {
            reason: error.to_string(),
        }
    }
}

/// A title as shown on cards and in lists.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MetaItem {
    pub id: String,
    /// Content type, usually `movie` or `series`.
    pub kind: String,
    pub name: String,
    pub poster: Option<String>,
    pub background: Option<String>,
    pub logo: Option<String>,
    pub description: Option<String>,
    pub release_info: Option<String>,
    pub imdb_rating: Option<String>,
    pub genres: Vec<String>,
}

impl From<MetaPreview> for MetaItem {
    fn from(preview: MetaPreview) -> Self {
        Self {
            id: preview.id,
            kind: preview.kind,
            name: preview.name,
            poster: https_only(preview.poster),
            background: https_only(preview.background),
            logo: https_only(preview.logo),
            description: preview.description,
            release_info: preview.release_info,
            imdb_rating: preview.imdb_rating,
            genres: preview.genres,
        }
    }
}

/// Full details of a title.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MetaDetails {
    pub item: MetaItem,
    pub runtime: Option<String>,
    pub cast: Vec<String>,
    pub directors: Vec<String>,
    /// Episodes for series, sorted by season and number; empty for most movies.
    pub episodes: Vec<Episode>,
    /// YouTube ids of trailers, from `trailerStreams` and the older `trailers`, without repeats.
    pub trailer_youtube_ids: Vec<String>,
    /// A public page to share the title by: Stremio's share link when the addon gives one,
    /// else its IMDb page. Never anything that could carry the user's data.
    pub share_url: Option<String>,
}

impl From<MetaDetail> for MetaDetails {
    fn from(detail: MetaDetail) -> Self {
        let mut episodes: Vec<Episode> = detail.videos.into_iter().map(Episode::from).collect();
        episodes.sort_by_key(|e| (e.season.unwrap_or(u32::MAX), e.episode.unwrap_or(u32::MAX)));
        let mut trailer_youtube_ids: Vec<String> = Vec::new();
        let ids = detail
            .trailer_streams
            .into_iter()
            .filter_map(|t| t.yt_id)
            .chain(detail.trailers.into_iter().filter_map(|t| t.source));
        for id in ids {
            if is_youtube_id(&id) && !trailer_youtube_ids.contains(&id) {
                trailer_youtube_ids.push(id);
            }
        }
        let share_url = share_url(&detail.links, &detail.preview.id);
        Self {
            item: detail.preview.into(),
            runtime: detail.runtime,
            cast: detail.cast,
            directors: detail.director,
            episodes,
            trailer_youtube_ids,
            share_url,
        }
    }
}

/// Hosts of Stremio's public share pages.
const SHARE_HOSTS: &[&str] = &["www.strem.io", "strem.io", "web.stremio.com"];
/// Hosts of IMDb's title pages.
const IMDB_HOSTS: &[&str] = &["www.imdb.com", "imdb.com", "m.imdb.com"];
/// Longest share link kept; a public title page is far shorter.
const MAX_SHARE_URL_LEN: usize = 300;

/// The link to share a title by. Only Stremio's own share pages and IMDb's are taken from the
/// addon's links, and only plain HTTPS addresses without a query or credentials, so an addon
/// cannot make the app hand out a link that tracks the user or carries a configured key.
/// Without one, an IMDb id (`tt…`) gives its IMDb page.
pub(crate) fn share_url(links: &[Link], id: &str) -> Option<String> {
    let link_in = |category: &str, hosts: &[&str]| {
        links
            .iter()
            .filter(|link| {
                link.category
                    .as_deref()
                    .is_some_and(|c| c.eq_ignore_ascii_case(category))
            })
            .filter_map(|link| link.url.as_deref())
            .find(|url| is_public_page(url, hosts))
            .map(str::to_owned)
    };
    link_in("share", SHARE_HOSTS)
        .or_else(|| link_in("imdb", IMDB_HOSTS))
        .or_else(|| {
            let digits = id.strip_prefix("tt")?;
            (!digits.is_empty() && digits.len() <= 12 && digits.bytes().all(|b| b.is_ascii_digit()))
                .then(|| format!("https://www.imdb.com/title/{id}/"))
        })
}

/// An `https://` address on one of `hosts`, with a path only: no port, credentials, query or
/// fragment, and nothing but printable ASCII.
fn is_public_page(url: &str, hosts: &[&str]) -> bool {
    let Some(rest) = url.strip_prefix("https://") else {
        return false;
    };
    let (host, path) = rest.split_once('/').unwrap_or((rest, ""));
    url.len() <= MAX_SHARE_URL_LEN
        && hosts.iter().any(|h| host.eq_ignore_ascii_case(h))
        && path
            .bytes()
            .all(|b| b.is_ascii_graphic() && !matches!(b, b'?' | b'#' | b'@' | b'\\'))
}

/// A YouTube video id: letters, digits, `-` and `_`, as YouTube makes them.
fn is_youtube_id(id: &str) -> bool {
    (1..=32).contains(&id.len())
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// Minutes in a runtime as addons write it: `45`, `45 min`, `45m`, `1h 30min`, `1h30m` or
/// ISO 8601's `PT45M`. Anything else, or a length that is not plausible, is unknown.
pub(crate) fn runtime_minutes(text: &str) -> Option<u32> {
    let lower = text.trim().to_ascii_lowercase();
    let lower = lower.strip_prefix("pt").unwrap_or(&lower);
    if let Ok(minutes) = lower.parse::<u32>() {
        return (1..=24 * 60).contains(&minutes).then_some(minutes);
    }
    let mut total: u32 = 0;
    let mut number: Option<u32> = None;
    let mut saw_unit = false;
    let mut chars = lower.chars().peekable();
    while let Some(c) = chars.next() {
        if let Some(digit) = c.to_digit(10) {
            number = Some(number.unwrap_or(0).checked_mul(10)?.checked_add(digit)?);
            continue;
        }
        if c.is_whitespace() {
            continue;
        }
        let value = number.take()?;
        let factor = match c {
            'h' => 60,
            'm' => 1,
            _ => return None,
        };
        // Skip the rest of the unit's word: "hours", "min", "minutes".
        while chars.peek().is_some_and(char::is_ascii_alphabetic) {
            chars.next();
        }
        total = total.checked_add(value.checked_mul(factor)?)?;
        saw_unit = true;
    }
    (saw_unit && number.is_none() && (1..=24 * 60).contains(&total)).then_some(total)
}

/// An episode of a series.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Episode {
    /// Video id used to ask addons for streams, for example `tt0903747:1:1`.
    pub id: String,
    pub title: String,
    pub season: Option<u32>,
    pub episode: Option<u32>,
    /// Release date as an ISO 8601 string.
    pub released: Option<String>,
    pub overview: Option<String>,
    pub thumbnail: Option<String>,
    /// Length in minutes, when the addon gives it.
    pub runtime_minutes: Option<u32>,
}

impl From<Video> for Episode {
    fn from(video: Video) -> Self {
        Self {
            id: video.id,
            title: video.title,
            season: video.season,
            episode: video.episode,
            released: video.released,
            overview: video.overview,
            thumbnail: https_only(video.thumbnail),
            runtime_minutes: video.runtime.as_deref().and_then(runtime_minutes),
        }
    }
}

/// A catalog that can be browsed page by page.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CatalogInfo {
    pub addon_manifest_url: String,
    pub addon_name: String,
    pub catalog_id: String,
    pub kind: String,
    /// Display name; falls back to the catalog id when the addon gives none.
    pub name: String,
    /// Genres the catalog can filter by, if any.
    pub genres: Vec<String>,
    /// What the catalog holds, when the app knows it, to give it a clearer name.
    pub role: CatalogRole,
}

/// What a well-known catalog holds. Only Cinemeta's are known; every other catalog is
/// [`CatalogRole::Other`] and goes by the name its addon gives it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CatalogRole {
    /// Popular now (Cinemeta's "Popular", `top`).
    Trending,
    /// Released this year (Cinemeta's "New", `year`, filtered by the current year).
    NewReleases,
    /// The best rated (Cinemeta's "Featured", `imdbRating`).
    Featured,
    Other,
}

/// Manifest id of Cinemeta, the default metadata addon.
pub(crate) const CINEMETA_ID: &str = "com.linvo.cinemeta";

impl CatalogRole {
    fn of(addon: &InstalledAddon, catalog: &CatalogDescriptor) -> Self {
        if addon.manifest.id != CINEMETA_ID {
            return Self::Other;
        }
        match catalog.id.as_str() {
            "top" => Self::Trending,
            "year" => Self::NewReleases,
            "imdbRating" => Self::Featured,
            _ => Self::Other,
        }
    }
}

impl CatalogInfo {
    pub(crate) fn new(addon: &InstalledAddon, catalog: &CatalogDescriptor) -> Self {
        Self {
            addon_manifest_url: addon.url.manifest_url().to_owned(),
            addon_name: addon.manifest.name.clone(),
            catalog_id: catalog.id.clone(),
            kind: catalog.kind.clone(),
            name: catalog
                .name
                .clone()
                .filter(|n| !n.trim().is_empty())
                .unwrap_or_else(|| catalog.id.clone()),
            genres: catalog.extra_options("genre"),
            role: CatalogRole::of(addon, catalog),
        }
    }
}

/// A row of the home screen: the first page of a catalog.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CatalogRow {
    pub catalog: CatalogInfo,
    pub items: Vec<MetaItem>,
}

/// An addon, installed or offered for installing.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AddonInfo {
    pub manifest_url: String,
    pub id: String,
    pub name: String,
    pub version: String,
    pub description: String,
    pub logo: Option<String>,
    pub enabled: bool,
    /// Content types it handles, such as `movie`, `series` or `anime`, as the manifest names
    /// them (its catalogs' types when the manifest lists none).
    pub types: Vec<String>,
    pub provides_catalogs: bool,
    pub provides_metadata: bool,
    pub provides_streams: bool,
    pub provides_subtitles: bool,
    /// It gives the information movies and series need to open (by their IMDb ids), as
    /// Cinemeta does; an anime addon with its own ids does not count.
    pub provides_title_metadata: bool,
    pub configurable: bool,
    /// It does nothing until it is set up on its configuration page.
    pub configuration_required: bool,
    /// Its configuration page, for addons that have one.
    pub configure_url: Option<String>,
    /// The manifest URL seems to hold the user's settings, possibly a private key, so it
    /// should not be shared without a warning.
    pub link_has_settings: bool,
    /// What the user agrees to when confirming an install: a hash of what the manifest lets
    /// the addon do (see `manifest_fingerprint`). Pass it back to install exactly that.
    pub fingerprint: String,
}

/// Longest name kept, in characters; addons are loosely written and names show in one line.
const MAX_NAME_CHARS: usize = 100;
/// Longest description kept, in characters; lists show two or three lines of it.
const MAX_DESCRIPTION_CHARS: usize = 500;
const MAX_VERSION_CHARS: usize = 32;
const MAX_TYPES: usize = 12;
const MAX_TYPE_CHARS: usize = 32;

impl AddonInfo {
    pub(crate) fn new(addon: &InstalledAddon, enabled: bool) -> Self {
        let manifest = &addon.manifest;
        let hints = &manifest.behavior_hints;
        let declared: Vec<&str> = if manifest.types.is_empty() {
            manifest.catalogs.iter().map(|c| c.kind.as_str()).collect()
        } else {
            manifest.types.iter().map(String::as_str).collect()
        };
        let mut types: Vec<String> = Vec::new();
        for kind in declared {
            let kind = truncate(kind.trim(), MAX_TYPE_CHARS);
            if !kind.is_empty() && !types.iter().any(|t| t.eq_ignore_ascii_case(&kind)) {
                types.push(kind);
            }
        }
        types.truncate(MAX_TYPES);
        let name = truncate(manifest.name.trim(), MAX_NAME_CHARS);
        let configurable = hints.configurable || hints.configuration_required;
        Self {
            manifest_url: addon.url.manifest_url().to_owned(),
            id: manifest.id.clone(),
            name: if name.is_empty() {
                truncate(&manifest.id, MAX_NAME_CHARS)
            } else {
                name
            },
            version: truncate(manifest.version.trim(), MAX_VERSION_CHARS),
            description: truncate(manifest.description.trim(), MAX_DESCRIPTION_CHARS),
            logo: https_only(manifest.logo.clone()),
            enabled,
            types,
            provides_catalogs: !manifest.catalogs.is_empty(),
            provides_metadata: manifest.has_resource("meta"),
            provides_streams: manifest.has_resource("stream"),
            provides_subtitles: manifest.has_resource("subtitles"),
            // Movies and series are named by IMDb ids (`tt…`) in Stremio's catalogs.
            provides_title_metadata: ["movie", "series"]
                .iter()
                .any(|kind| manifest.supports("meta", kind, "tt0000001")),
            configurable,
            configuration_required: hints.configuration_required,
            configure_url: configurable.then(|| addon.url.configure_url()),
            link_has_settings: addon.url.carries_settings(),
            fingerprint: manifest_fingerprint(manifest),
        }
    }
}

/// An addon from Stremio's lists, shown under Discover.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CatalogAddon {
    /// Its `enabled` flag means nothing here.
    pub addon: AddonInfo,
    /// Made by Stremio, as opposed to listed from the community.
    pub official: bool,
}

/// An addon downloaded from a link but not installed yet, for the user to confirm.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AddonPreview {
    pub addon: AddonInfo,
    /// The installed addon with the same id, which installing replaces in its place; the same
    /// address means installing only refreshes it.
    pub replaces: Option<AddonInfo>,
}

/// `text` cut to `max` characters, with an ellipsis when something was cut.
fn truncate(text: &str, max: usize) -> String {
    if text.chars().count() <= max {
        return text.to_owned();
    }
    let mut cut: String = text.chars().take(max.saturating_sub(1)).collect();
    cut.truncate(cut.trim_end().len());
    cut.push('…');
    cut
}

/// A title saved to "My list".
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LibraryItem {
    pub id: String,
    pub kind: String,
    pub name: String,
    pub poster: Option<String>,
    /// Unix time in milliseconds.
    pub added_at: i64,
}

/// Keeps only HTTPS image URLs: plain HTTP images would leak what the user browses on the
/// network and are blocked by Android's cleartext policy anyway.
fn https_only(url: Option<String>) -> Option<String> {
    url.filter(|u| u.starts_with("https://"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn drops_non_https_images() {
        assert_eq!(https_only(Some("http://x/p.jpg".into())), None);
        assert_eq!(https_only(Some("javascript:alert(1)".into())), None);
        assert_eq!(
            https_only(Some("https://x/p.jpg".into())),
            Some("https://x/p.jpg".into())
        );
    }

    fn addon(url: &str, manifest: serde_json::Value) -> InstalledAddon {
        InstalledAddon {
            url: stremionow_protocol::AddonUrl::parse(url).expect("valid url"),
            manifest: serde_json::from_value(manifest).expect("valid manifest"),
        }
    }

    #[test]
    fn describes_an_addon() {
        let info = AddonInfo::new(
            &addon(
                "https://addon.example/realdebrid=ABCDEFGHIJKLMNOPQRSTUVWXYZ/manifest.json",
                serde_json::json!({
                    "id": "x", "version": "1.2.0", "name": "  X  ", "logo": "http://x/logo.png",
                    "types": ["movie", "Movie", "series", ""],
                    "resources": [{"name": "meta", "types": ["series"]}, "stream"],
                    "behaviorHints": {"configurationRequired": true}
                }),
            ),
            true,
        );
        assert_eq!(info.name, "X");
        assert_eq!(
            info.types,
            ["movie", "series"],
            "trimmed and without repeats"
        );
        assert_eq!(info.logo, None, "plain HTTP logos are dropped");
        assert!(info.provides_metadata && info.provides_title_metadata && info.provides_streams);
        assert!(info.configurable, "required configuration implies a page");
        assert_eq!(
            info.configure_url.as_deref(),
            Some("https://addon.example/realdebrid=ABCDEFGHIJKLMNOPQRSTUVWXYZ/configure")
        );
        assert!(info.link_has_settings);
    }

    #[test]
    fn tames_loose_manifests() {
        let long = "a".repeat(5_000);
        let info = AddonInfo::new(
            &addon(
                "https://addon.example/manifest.json",
                serde_json::json!({
                    "id": "only.id", "version": long, "name": " ", "description": long,
                    "catalogs": [{"type": "channel", "id": "c"}, {"type": "tv", "id": "t"}],
                    "resources": [
                        {"name": "meta", "types": ["channel"]},
                        {"name": "meta", "types": ["series"], "idPrefixes": ["kitsu:"]}
                    ]
                }),
            ),
            false,
        );
        assert_eq!(info.name, "only.id", "falls back to the id");
        assert_eq!(info.description.chars().count(), MAX_DESCRIPTION_CHARS);
        assert!(info.description.ends_with('…'));
        assert_eq!(info.version.chars().count(), MAX_VERSION_CHARS);
        assert_eq!(info.types, ["channel", "tv"], "catalog types stand in");
        assert!(info.provides_metadata && !info.provides_title_metadata);
        assert!(!info.configurable && info.configure_url.is_none());
        assert!(!info.link_has_settings);
    }

    #[test]
    fn knows_what_cinemeta_catalogs_hold() {
        let manifest = |id: &str| {
            serde_json::json!({
                "id": id, "version": "1", "name": "X", "resources": ["catalog"],
                "catalogs": [
                    {"type": "movie", "id": "top", "name": "Popular"},
                    {"type": "series", "id": "year", "name": "New"},
                    {"type": "movie", "id": "imdbRating", "name": "Featured"},
                    {"type": "series", "id": "last-videos"}
                ]
            })
        };
        let roles = |id: &str| {
            let addon = addon("https://addon.example/manifest.json", manifest(id));
            addon
                .manifest
                .catalogs
                .iter()
                .map(|c| CatalogInfo::new(&addon, c).role)
                .collect::<Vec<_>>()
        };
        assert_eq!(
            roles(CINEMETA_ID),
            [
                CatalogRole::Trending,
                CatalogRole::NewReleases,
                CatalogRole::Featured,
                CatalogRole::Other
            ]
        );
        assert!(
            roles("community.lookalike")
                .iter()
                .all(|role| *role == CatalogRole::Other),
            "another addon's catalogs keep their own names"
        );
    }

    #[test]
    fn sorts_episodes_and_puts_unnumbered_last() {
        let detail: MetaDetail = serde_json::from_value(serde_json::json!({
            "id": "tt1", "type": "series", "name": "S",
            "videos": [
                {"id": "c", "title": "Special"},
                {"id": "b", "season": 1, "episode": 2},
                {"id": "a", "season": 1, "episode": 1},
                {"id": "d", "season": 0, "episode": 1}
            ],
            "trailers": [{"source": "abc"}, {"source": ""}, {}]
        }))
        .expect("valid");
        let details = MetaDetails::from(detail);
        let order: Vec<_> = details.episodes.iter().map(|e| e.id.as_str()).collect();
        assert_eq!(order, ["d", "a", "b", "c"]);
        assert_eq!(details.trailer_youtube_ids, ["abc"]);
    }

    #[test]
    fn merges_trailers_from_both_forms_without_repeats() {
        let detail: MetaDetail = serde_json::from_value(serde_json::json!({
            "id": "tt1", "type": "movie", "name": "M",
            "trailerStreams": [{"ytId": "new"}, {"ytId": "old"}, {"ytId": "bad id!"}],
            "trailers": [{"source": "old"}, {"source": "other"}]
        }))
        .expect("valid");
        assert_eq!(
            MetaDetails::from(detail).trailer_youtube_ids,
            ["new", "old", "other"]
        );
    }

    fn link(category: &str, url: &str) -> Link {
        Link {
            name: None,
            category: Some(category.into()),
            url: Some(url.into()),
        }
    }

    #[test]
    fn shares_stremio_links_then_imdb() {
        let share = "https://www.strem.io/s/movie/the-thing-0084787";
        let imdb = "https://imdb.com/title/tt0084787";
        assert_eq!(
            share_url(&[link("imdb", imdb), link("share", share)], "tt0084787").as_deref(),
            Some(share)
        );
        assert_eq!(
            share_url(&[link("imdb", imdb)], "tt0084787").as_deref(),
            Some(imdb)
        );
        assert_eq!(
            share_url(&[], "tt0084787").as_deref(),
            Some("https://www.imdb.com/title/tt0084787/"),
            "built from the IMDb id"
        );
        assert_eq!(
            share_url(&[], "kitsu:1"),
            None,
            "nothing public to point at"
        );
    }

    #[test]
    fn never_shares_links_that_could_carry_data() {
        for hostile in [
            "http://www.strem.io/s/movie/x-1",
            "https://tracker.example/s/movie/x-1",
            "https://www.strem.io/s/movie/x-1?user=me@example.com",
            "https://www.strem.io/s/movie/x-1#key",
            "https://user:pass@www.strem.io/s/movie/x-1",
            "https://www.strem.io.evil.example/s/movie/x-1",
            "https://www.strem.io:8443/s/movie/x-1",
            "https://www.strem.io/s/movie/x 1",
        ] {
            assert_eq!(
                share_url(&[link("share", hostile)], "kitsu:1"),
                None,
                "{hostile}"
            );
        }
        let long = format!("https://www.strem.io/{}", "a".repeat(400));
        assert_eq!(share_url(&[link("share", &long)], "kitsu:1"), None);
    }

    #[test]
    fn reads_runtimes_in_the_forms_addons_use() {
        for (text, minutes) in [
            ("45", Some(45)),
            ("45 min", Some(45)),
            ("45m", Some(45)),
            ("1h 30min", Some(90)),
            ("1h30m", Some(90)),
            ("2 hours", Some(120)),
            ("PT45M", Some(45)),
            ("0", None),
            ("", None),
            ("about an hour", None),
            ("45 seconds", None),
            ("99999999999 min", None),
            ("10000", None),
        ] {
            assert_eq!(runtime_minutes(text), minutes, "{text}");
        }
    }
}
