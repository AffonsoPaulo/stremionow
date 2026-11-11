// SPDX-License-Identifier: GPL-3.0-or-later
//! Plain data types shared with the app interfaces.

use stremionow_addons::{AddonError, InstalledAddon};
use stremionow_library::StoreError;
use stremionow_protocol::{CatalogDescriptor, MetaDetail, MetaPreview, Video};

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
    /// YouTube ids of trailers.
    pub trailer_youtube_ids: Vec<String>,
}

impl From<MetaDetail> for MetaDetails {
    fn from(detail: MetaDetail) -> Self {
        let mut episodes: Vec<Episode> = detail.videos.into_iter().map(Episode::from).collect();
        episodes.sort_by_key(|e| (e.season.unwrap_or(u32::MAX), e.episode.unwrap_or(u32::MAX)));
        Self {
            item: detail.preview.into(),
            runtime: detail.runtime,
            cast: detail.cast,
            directors: detail.director,
            episodes,
            trailer_youtube_ids: detail
                .trailers
                .into_iter()
                .filter_map(|t| t.source)
                .filter(|id| !id.is_empty())
                .collect(),
        }
    }
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
        }
    }
}

/// A row of the home screen: the first page of a catalog.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CatalogRow {
    pub catalog: CatalogInfo,
    pub items: Vec<MetaItem>,
}

/// An installed addon.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AddonInfo {
    pub manifest_url: String,
    pub id: String,
    pub name: String,
    pub version: String,
    pub description: String,
    pub logo: Option<String>,
    pub enabled: bool,
    pub provides_catalogs: bool,
    pub provides_metadata: bool,
    pub provides_streams: bool,
    pub provides_subtitles: bool,
    pub configurable: bool,
}

impl AddonInfo {
    pub(crate) fn new(addon: &InstalledAddon, enabled: bool) -> Self {
        let manifest = &addon.manifest;
        Self {
            manifest_url: addon.url.manifest_url().to_owned(),
            id: manifest.id.clone(),
            name: manifest.name.clone(),
            version: manifest.version.clone(),
            description: manifest.description.clone(),
            logo: https_only(manifest.logo.clone()),
            enabled,
            provides_catalogs: !manifest.catalogs.is_empty(),
            provides_metadata: manifest.has_resource("meta"),
            provides_streams: manifest.has_resource("stream"),
            provides_subtitles: manifest.has_resource("subtitles"),
            configurable: manifest.behavior_hints.configurable,
        }
    }
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
}
