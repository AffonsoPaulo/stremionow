// SPDX-License-Identifier: GPL-3.0-or-later
//! Responses of the catalog, meta, stream and subtitles resources.

use serde::{Deserialize, Serialize};

use crate::manifest::null_as_default;

/// Response of the `catalog` resource.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct CatalogResponse {
    #[serde(default, deserialize_with = "null_as_default")]
    pub metas: Vec<MetaPreview>,
}

/// Response of the `meta` resource.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MetaResponse {
    pub meta: MetaDetail,
}

/// Response of the `stream` resource.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct StreamResponse {
    #[serde(default, deserialize_with = "null_as_default")]
    pub streams: Vec<Stream>,
}

/// Response of the `subtitles` resource.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct SubtitlesResponse {
    #[serde(default, deserialize_with = "null_as_default")]
    pub subtitles: Vec<Subtitle>,
}

/// A title as listed in a catalog.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MetaPreview {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default, deserialize_with = "null_as_default")]
    pub name: String,
    #[serde(default)]
    pub poster: Option<String>,
    #[serde(default)]
    pub background: Option<String>,
    #[serde(default)]
    pub logo: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    /// Year or year range as display text, for example `2019` or `2019-2023`.
    #[serde(default, deserialize_with = "lenient_string")]
    pub release_info: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub imdb_rating: Option<String>,
    #[serde(default, deserialize_with = "null_as_default")]
    pub genres: Vec<String>,
}

/// Full details of a title.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MetaDetail {
    #[serde(flatten)]
    pub preview: MetaPreview,
    #[serde(default, deserialize_with = "lenient_string")]
    pub runtime: Option<String>,
    #[serde(default, deserialize_with = "null_as_default")]
    pub cast: Vec<String>,
    #[serde(default, deserialize_with = "null_as_default")]
    pub director: Vec<String>,
    #[serde(default, deserialize_with = "null_as_default")]
    pub writer: Vec<String>,
    /// Episodes of a series, or the single video of some movies.
    #[serde(default, deserialize_with = "null_as_default")]
    pub videos: Vec<Video>,
    #[serde(default, deserialize_with = "null_as_default")]
    pub trailers: Vec<Trailer>,
    /// Trailers as streams, the newer form (Cinemeta sends both): usually YouTube ids.
    #[serde(default, deserialize_with = "lenient_list")]
    pub trailer_streams: Vec<TrailerStream>,
    /// Links the addon gives with the title: its IMDb page, Stremio's share link, genres,
    /// cast. Entries that cannot be read are skipped.
    #[serde(default, deserialize_with = "lenient_list")]
    pub links: Vec<Link>,
}

/// A trailer, identified by its YouTube id.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Trailer {
    #[serde(default)]
    pub source: Option<String>,
}

/// A trailer in `trailerStreams`: a stream with a YouTube id.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrailerStream {
    #[serde(default, deserialize_with = "lenient_string")]
    pub yt_id: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub title: Option<String>,
}

/// A link of a title (stremio-core `Link`); `category` says what it is, such as `imdb`,
/// `share`, `Genres` or `Cast`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Link {
    #[serde(default, deserialize_with = "lenient_string")]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub category: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub url: Option<String>,
}

/// An episode or other playable video of a title.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", from = "RawVideo")]
pub struct Video {
    pub id: String,
    pub title: String,
    pub season: Option<u32>,
    pub episode: Option<u32>,
    /// Release date as an ISO 8601 string.
    pub released: Option<String>,
    pub overview: Option<String>,
    pub thumbnail: Option<String>,
    /// Length as the addon wrote it, such as `45 min` or `45`, when it gives one.
    pub runtime: Option<String>,
}

/// A video as addons send it. They disagree on field names and Cinemeta sends several
/// spellings at once (`name` and `title`, `episode` and `number`, `overview` and
/// `description`), which serde aliases reject as duplicates; every spelling is read here
/// and [`Video`] keeps the first one that is present.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawVideo {
    #[serde(default, deserialize_with = "null_as_default")]
    id: String,
    #[serde(default, deserialize_with = "lenient_string")]
    title: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    name: Option<String>,
    #[serde(default, deserialize_with = "lenient_number")]
    season: Option<u32>,
    #[serde(default, deserialize_with = "lenient_number")]
    episode: Option<u32>,
    #[serde(default, deserialize_with = "lenient_number")]
    number: Option<u32>,
    #[serde(default, deserialize_with = "lenient_string")]
    released: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    overview: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    description: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    thumbnail: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    runtime: Option<String>,
}

impl From<RawVideo> for Video {
    fn from(raw: RawVideo) -> Self {
        Self {
            id: raw.id,
            title: raw.title.or(raw.name).unwrap_or_default(),
            season: raw.season,
            episode: raw.episode.or(raw.number),
            released: raw.released,
            overview: raw.overview.or(raw.description),
            thumbnail: raw.thumbnail,
            runtime: raw.runtime,
        }
    }
}

/// A playable source for a video.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Stream {
    /// Direct HTTP(S) URL, used by debrid and HTTP sources.
    #[serde(default)]
    pub url: Option<String>,
    /// BitTorrent info hash, for torrent sources.
    #[serde(default)]
    pub info_hash: Option<String>,
    /// Index of the file to play inside the torrent.
    #[serde(default)]
    pub file_idx: Option<u32>,
    /// YouTube video id.
    #[serde(default)]
    pub yt_id: Option<String>,
    /// Link to open outside the app.
    #[serde(default)]
    pub external_url: Option<String>,
    /// Short label, often quality and provider, for example `Torrentio\n4k`.
    #[serde(default)]
    pub name: Option<String>,
    /// Older addons put the description here.
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default, deserialize_with = "null_as_default")]
    pub subtitles: Vec<Subtitle>,
    /// Extra trackers and DHT hints for torrents.
    #[serde(default, deserialize_with = "null_as_default")]
    pub sources: Vec<String>,
    #[serde(default)]
    pub behavior_hints: StreamBehaviorHints,
}

/// Hints about a stream.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamBehaviorHints {
    /// Streams of the same group can replace each other between episodes.
    #[serde(default)]
    pub binge_group: Option<String>,
    #[serde(default)]
    pub filename: Option<String>,
    #[serde(default)]
    pub video_size: Option<u64>,
    #[serde(default)]
    pub not_web_ready: bool,
}

/// A subtitle file.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Subtitle {
    #[serde(default)]
    pub id: Option<String>,
    pub url: String,
    /// Language, usually as an ISO 639-2 code such as `por`.
    #[serde(default, deserialize_with = "null_as_default")]
    pub lang: String,
}

/// Reads a list, skipping entries that do not have the expected shape instead of failing the
/// whole response; `null` or anything that is not a list reads as empty.
fn lenient_list<'de, D, T>(deserializer: D) -> Result<Vec<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: serde::de::DeserializeOwned,
{
    Ok(match serde_json::Value::deserialize(deserializer)? {
        serde_json::Value::Array(values) => values
            .into_iter()
            .filter_map(|value| serde_json::from_value(value).ok())
            .collect(),
        _ => Vec::new(),
    })
}

/// Reads a non-negative whole number sent as a number or a numeric string; anything else
/// (null, negative, fractional, text) becomes `None` instead of failing the whole response.
fn lenient_number<'de, D>(deserializer: D) -> Result<Option<u32>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    Ok(match serde_json::Value::deserialize(deserializer)? {
        serde_json::Value::Number(n) => n.as_u64().and_then(|n| u32::try_from(n).ok()),
        serde_json::Value::String(s) => s.trim().parse().ok(),
        _ => None,
    })
}

/// Accepts a string or a number, since addons send ratings and years as either.
fn lenient_string<'de, D>(deserializer: D) -> Result<Option<String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = Option::<serde_json::Value>::deserialize(deserializer)?;
    Ok(match value {
        Some(serde_json::Value::String(text)) if !text.trim().is_empty() => Some(text),
        Some(serde_json::Value::Number(number)) => Some(number.to_string()),
        _ => None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_catalog_with_loose_fields() {
        let response: CatalogResponse = serde_json::from_value(serde_json::json!({
            "metas": [
                {"id": "tt1", "type": "movie", "name": "A", "imdbRating": 7.5, "releaseInfo": 2019, "genres": null},
                {"id": "tt2", "type": "series", "name": null, "imdbRating": "", "poster": "https://x/p.jpg"}
            ]
        }))
        .expect("valid catalog");
        assert_eq!(response.metas[0].imdb_rating.as_deref(), Some("7.5"));
        assert_eq!(response.metas[0].release_info.as_deref(), Some("2019"));
        assert!(response.metas[0].genres.is_empty());
        assert_eq!(response.metas[1].name, "");
        assert_eq!(
            response.metas[1].imdb_rating, None,
            "blank rating is absent"
        );
    }

    #[test]
    fn missing_metas_is_empty() {
        let response: CatalogResponse = serde_json::from_str("{}").expect("valid");
        assert!(response.metas.is_empty());
        let response: CatalogResponse = serde_json::from_str(r#"{"metas": null}"#).expect("valid");
        assert!(response.metas.is_empty());
    }

    #[test]
    fn parses_episodes_with_name_and_number_aliases() {
        let response: MetaResponse = serde_json::from_value(serde_json::json!({
            "meta": {
                "id": "tt3", "type": "series", "name": "S",
                "videos": [
                    {"id": "tt3:1:1", "title": "Pilot", "season": 1, "episode": 1},
                    {"id": "tt3:1:2", "name": "Second", "season": 1, "number": 2, "description": "D"}
                ]
            }
        }))
        .expect("valid meta");
        let videos = &response.meta.videos;
        assert_eq!(videos[1].title, "Second");
        assert_eq!(videos[1].episode, Some(2));
        assert_eq!(videos[1].overview.as_deref(), Some("D"));
    }

    #[test]
    fn parses_episodes_that_repeat_fields_under_every_name() {
        // Shape of a real Cinemeta episode, which repeats fields under both names.
        let response: MetaResponse = serde_json::from_value(serde_json::json!({
            "meta": {
                "id": "tt4", "type": "series", "name": "S",
                "videos": [
                    {"id": "tt4:1:1", "name": "Pilot", "title": "Pilot (alt)", "season": 1,
                     "episode": 1, "number": 1, "overview": "O", "description": "D"},
                    {"id": "tt4:1:2", "season": "1", "number": "2", "episode": null},
                    {"id": "tt4:1:3", "season": -1, "episode": 2.5}
                ]
            }
        }))
        .expect("valid meta");
        let videos = &response.meta.videos;
        assert_eq!(videos[0].title, "Pilot (alt)");
        assert_eq!(videos[0].episode, Some(1));
        assert_eq!(videos[0].overview.as_deref(), Some("O"));
        assert_eq!((videos[1].season, videos[1].episode), (Some(1), Some(2)));
        assert_eq!((videos[2].season, videos[2].episode), (None, None));
    }

    #[test]
    fn parses_links_trailer_streams_and_runtimes_leniently() {
        let response: MetaResponse = serde_json::from_value(serde_json::json!({
            "meta": {
                "id": "tt5", "type": "series", "name": "S",
                "links": [
                    {"name": "7.9", "category": "imdb", "url": "https://imdb.com/title/tt5"},
                    "not a link",
                    {"name": "S", "category": "share", "url": "https://www.strem.io/s/series/s-5"}
                ],
                "trailerStreams": [{"title": "Trailer", "ytId": "abc"}, 7],
                "videos": [{"id": "tt5:1:1", "season": 1, "episode": 1, "runtime": 45}]
            }
        }))
        .expect("valid meta");
        let meta = &response.meta;
        assert_eq!(meta.links.len(), 2, "the bad entry is skipped");
        assert_eq!(meta.links[1].category.as_deref(), Some("share"));
        assert_eq!(meta.trailer_streams.len(), 1);
        assert_eq!(meta.trailer_streams[0].yt_id.as_deref(), Some("abc"));
        assert_eq!(meta.videos[0].runtime.as_deref(), Some("45"));

        let bare: MetaResponse = serde_json::from_value(serde_json::json!({
            "meta": {"id": "tt6", "type": "movie", "name": "M", "links": null, "trailerStreams": {}}
        }))
        .expect("valid meta");
        assert!(bare.meta.links.is_empty() && bare.meta.trailer_streams.is_empty());
    }

    #[test]
    fn parses_streams() {
        let response: StreamResponse = serde_json::from_value(serde_json::json!({
            "streams": [
                {"name": "Addon\n4k", "infoHash": "abc", "fileIdx": 2, "behaviorHints": {"bingeGroup": "g"}},
                {"url": "https://cdn/x.mkv", "title": "x.mkv"}
            ]
        }))
        .expect("valid streams");
        assert_eq!(response.streams[0].file_idx, Some(2));
        assert_eq!(
            response.streams[0].behavior_hints.binge_group.as_deref(),
            Some("g")
        );
        assert_eq!(
            response.streams[1].url.as_deref(),
            Some("https://cdn/x.mkv")
        );
    }
}
