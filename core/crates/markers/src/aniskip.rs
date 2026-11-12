// SPDX-License-Identifier: GPL-3.0-or-later
//! Client for AniSkip, the crowd-sourced database of anime openings and endings
//! (<https://api.aniskip.com>), and for the id mapping it needs.
//!
//! AniSkip files episodes by MyAnimeList id. Titles from Kitsu or AniList ids are first mapped
//! to MyAnimeList through ARM, the Anime Relations Mapping service (<https://arm.haglund.dev>),
//! whose answer is a few hundred bytes. Both answers are kept in memory for the life of the
//! process, so seeking or reopening an episode does not ask again.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures::StreamExt;
use serde::Deserialize;
use serde::de::DeserializeOwned;

use crate::ids::{AnimeEpisode, AnimeId};

/// AniSkip's API root.
pub const ANISKIP_BASE_URL: &str = "https://api.aniskip.com";
/// ARM's API root.
pub const MAPPING_BASE_URL: &str = "https://arm.haglund.dev";

/// Largest response accepted. Real answers are well under 4 KiB.
const MAX_RESPONSE_BYTES: usize = 64 * 1024;
/// Results read from one answer; an episode rarely has more than a handful.
const MAX_RESULTS: usize = 50;
/// Segments shorter than this are noise, not something worth a button.
const MIN_SEGMENT_SECONDS: f64 = 3.0;
/// Segments longer than this are a bad submission, not an opening or ending.
const MAX_SEGMENT_SECONDS: f64 = 10.0 * 60.0;
/// Videos shorter than this are clips or trailers, which AniSkip does not cover.
const MIN_DURATION_SECONDS: f64 = 60.0;
/// Videos longer than this are not episodes or films.
const MAX_DURATION_SECONDS: f64 = 6.0 * 60.0 * 60.0;
/// Entries kept per cache before it starts over; each is a few dozen bytes.
const MAX_CACHE_ENTRIES: usize = 1024;

/// What a skip segment covers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SkipKind {
    /// The opening.
    Intro,
    /// A summary of earlier episodes.
    Recap,
    /// The ending.
    Credits,
}

/// A stretch of an episode that can be skipped, in seconds from the start of the video.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SkipSegment {
    pub kind: SkipKind,
    pub start_seconds: f64,
    pub end_seconds: f64,
}

/// Why a request failed. None of these are shown to the user; playback goes on without skips.
#[derive(Debug, thiserror::Error)]
pub enum MarkersError {
    #[error("the service did not respond in time")]
    Timeout,
    #[error("could not reach the service: {0}")]
    Network(String),
    #[error("the service answered with HTTP {0}")]
    Status(u16),
    #[error("the response is larger than {MAX_RESPONSE_BYTES} bytes")]
    TooLarge,
    #[error("the response could not be read: {0}")]
    InvalidResponse(String),
}

impl MarkersError {
    fn from_reqwest(error: reqwest::Error) -> Self {
        if error.is_timeout() {
            Self::Timeout
        } else if let Some(status) = error.status() {
            Self::Status(status.as_u16())
        } else {
            Self::Network(error.without_url().to_string())
        }
    }
}

/// Cache key of an episode's segments: the MyAnimeList id, the episode and the video's length
/// in whole seconds, since AniSkip picks the submissions that match the length.
type SegmentsKey = (u32, u32, u32);

/// Asks AniSkip for an episode's segments. Cheap to share; requests run concurrently.
pub struct AniSkip {
    http: reqwest::Client,
    skip_base: String,
    mapping_base: String,
    mal_ids: Mutex<HashMap<AnimeId, Option<u32>>>,
    segments: Mutex<HashMap<SegmentsKey, Vec<SkipSegment>>>,
}

impl AniSkip {
    /// A client for the public services whose requests give up after `timeout`. Only HTTPS is
    /// allowed.
    pub fn new(timeout: Duration) -> Result<Self, MarkersError> {
        Self::build(timeout, ANISKIP_BASE_URL, MAPPING_BASE_URL, true)
    }

    /// A client for other servers, such as mock servers in tests; plain HTTP is allowed.
    pub fn with_endpoints(
        timeout: Duration,
        skip_base: &str,
        mapping_base: &str,
    ) -> Result<Self, MarkersError> {
        Self::build(timeout, skip_base, mapping_base, false)
    }

    fn build(
        timeout: Duration,
        skip_base: &str,
        mapping_base: &str,
        https_only: bool,
    ) -> Result<Self, MarkersError> {
        let roots = rustls::RootCertStore {
            roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
        };
        let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .map_err(|e| MarkersError::Network(e.to_string()))?
        .with_root_certificates(roots)
        .with_no_client_auth();
        let http = reqwest::Client::builder()
            .tls_backend_preconfigured(tls)
            .user_agent(concat!("StremioNow/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(timeout)
            .timeout(timeout)
            // Neither service redirects; never follow one somewhere else.
            .redirect(reqwest::redirect::Policy::none())
            .referer(false)
            .https_only(https_only)
            .build()
            .map_err(|e| MarkersError::Network(e.to_string()))?;
        Ok(Self {
            http,
            skip_base: skip_base.trim_end_matches('/').to_owned(),
            mapping_base: mapping_base.trim_end_matches('/').to_owned(),
            mal_ids: Mutex::new(HashMap::new()),
            segments: Mutex::new(HashMap::new()),
        })
    }

    /// The skippable segments of `episode` in a video `duration_seconds` long, at most one of
    /// each kind, in the order they play. An episode AniSkip does not know, or a title with no
    /// MyAnimeList entry, has none; both answers are cached. Failures are not cached, so the
    /// next playback tries again.
    pub async fn segments(
        &self,
        episode: AnimeEpisode,
        duration_seconds: f64,
    ) -> Result<Vec<SkipSegment>, MarkersError> {
        if !duration_seconds.is_finite()
            || !(MIN_DURATION_SECONDS..=MAX_DURATION_SECONDS).contains(&duration_seconds)
        {
            return Ok(Vec::new());
        }
        let Some(mal_id) = self.mal_id(episode.id).await? else {
            return Ok(Vec::new());
        };
        // Whole seconds: AniSkip matches lengths with a tolerance anyway.
        let length = duration_seconds.round() as u32;
        let key = (mal_id, episode.episode, length);
        if let Some(cached) = lock(&self.segments).get(&key) {
            return Ok(cached.clone());
        }
        // Every part of the URL is a number this crate formatted, so nothing can be injected.
        let url = format!(
            "{}/v2/skip-times/{mal_id}/{}?types[]=op&types[]=ed&types[]=recap\
             &types[]=mixed-op&types[]=mixed-ed&episodeLength={length}",
            self.skip_base, episode.episode
        );
        let segments = match self.get_json::<SkipTimesResponse>(&url).await {
            Ok(response) => select_segments(&response.results, duration_seconds),
            // AniSkip answers 404 for an episode it has no times for.
            Err(MarkersError::Status(404)) => Vec::new(),
            Err(error) => return Err(error),
        };
        insert_bounded(&self.segments, key, segments.clone());
        Ok(segments)
    }

    /// The MyAnimeList id of `id`, mapping Kitsu and AniList ids through ARM.
    async fn mal_id(&self, id: AnimeId) -> Result<Option<u32>, MarkersError> {
        let (source, number) = match id {
            AnimeId::Mal(number) => return Ok(Some(number)),
            AnimeId::Kitsu(number) => ("kitsu", number),
            AnimeId::Anilist(number) => ("anilist", number),
        };
        if let Some(cached) = lock(&self.mal_ids).get(&id) {
            return Ok(*cached);
        }
        let url = format!(
            "{}/api/v2/ids?source={source}&id={number}",
            self.mapping_base
        );
        // ARM answers `null` for an id it does not know, and 400 for one out of its range.
        let mal_id = match self.get_json::<Option<ArmIds>>(&url).await {
            Ok(ids) => ids.and_then(|ids| ids.myanimelist).filter(|mal| *mal > 0),
            Err(MarkersError::Status(400 | 404)) => None,
            Err(error) => return Err(error),
        };
        insert_bounded(&self.mal_ids, id, mal_id);
        Ok(mal_id)
    }

    async fn get_json<T: DeserializeOwned>(&self, url: &str) -> Result<T, MarkersError> {
        let response = self
            .http
            .get(url)
            .header(reqwest::header::ACCEPT, "application/json")
            .send()
            .await
            .map_err(MarkersError::from_reqwest)?;
        let status = response.status();
        if !status.is_success() {
            return Err(MarkersError::Status(status.as_u16()));
        }
        if response
            .content_length()
            .is_some_and(|length| length > MAX_RESPONSE_BYTES as u64)
        {
            return Err(MarkersError::TooLarge);
        }
        let mut body = Vec::new();
        let mut chunks = response.bytes_stream();
        while let Some(chunk) = chunks.next().await {
            let chunk = chunk.map_err(MarkersError::from_reqwest)?;
            if body.len() + chunk.len() > MAX_RESPONSE_BYTES {
                return Err(MarkersError::TooLarge);
            }
            body.extend_from_slice(&chunk);
        }
        serde_json::from_slice(&body).map_err(|e| MarkersError::InvalidResponse(e.to_string()))
    }
}

/// The part of ARM's answer this client reads.
#[derive(Deserialize)]
struct ArmIds {
    #[serde(default)]
    myanimelist: Option<u32>,
}

/// AniSkip's `GET /v2/skip-times` answer. Results are read one by one, so a malformed entry
/// does not discard the others.
#[derive(Deserialize)]
struct SkipTimesResponse {
    #[serde(default)]
    results: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SkipResult {
    interval: Interval,
    skip_type: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Interval {
    start_time: f64,
    end_time: f64,
}

/// Keeps the plausible results, clamped to the video, and picks one per kind: a plain opening
/// or ending over a "mixed" one, which overlaps the story, and otherwise AniSkip's order, which
/// puts the best-voted first.
fn select_segments(results: &[serde_json::Value], duration_seconds: f64) -> Vec<SkipSegment> {
    let mut best: Vec<(SkipSegment, bool)> = Vec::new();
    for value in results.iter().take(MAX_RESULTS) {
        let Ok(result) = SkipResult::deserialize(value) else {
            continue;
        };
        let (kind, mixed) = match result.skip_type.as_str() {
            "op" => (SkipKind::Intro, false),
            "mixed-op" => (SkipKind::Intro, true),
            "recap" => (SkipKind::Recap, false),
            "ed" => (SkipKind::Credits, false),
            "mixed-ed" => (SkipKind::Credits, true),
            _ => continue,
        };
        let Interval {
            start_time,
            end_time,
        } = result.interval;
        if !start_time.is_finite() || !end_time.is_finite() {
            continue;
        }
        let start = start_time.max(0.0);
        let end = end_time.min(duration_seconds);
        let length = end - start;
        if !(MIN_SEGMENT_SECONDS..=MAX_SEGMENT_SECONDS).contains(&length) {
            continue;
        }
        let segment = SkipSegment {
            kind,
            start_seconds: start,
            end_seconds: end,
        };
        match best.iter_mut().find(|(chosen, _)| chosen.kind == kind) {
            None => best.push((segment, mixed)),
            Some(chosen) if chosen.1 && !mixed => *chosen = (segment, mixed),
            Some(_) => {}
        }
    }
    let mut segments: Vec<SkipSegment> = best.into_iter().map(|(segment, _)| segment).collect();
    segments.sort_by(|a, b| a.start_seconds.total_cmp(&b.start_seconds));
    segments
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    // The maps hold plain data, so one left by a panicking thread is still consistent.
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Caches `value`, starting the cache over once it holds [`MAX_CACHE_ENTRIES`].
fn insert_bounded<K: std::hash::Hash + Eq, V>(cache: &Mutex<HashMap<K, V>>, key: K, value: V) {
    let mut cache = lock(cache);
    if cache.len() >= MAX_CACHE_ENTRIES {
        cache.clear();
    }
    cache.insert(key, value);
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    const EPISODE_LENGTH: f64 = 1420.4;

    fn kitsu(id: u32, episode: u32) -> AnimeEpisode {
        AnimeEpisode {
            id: AnimeId::Kitsu(id),
            episode,
        }
    }

    fn mal(id: u32, episode: u32) -> AnimeEpisode {
        AnimeEpisode {
            id: AnimeId::Mal(id),
            episode,
        }
    }

    fn client(server: &MockServer) -> AniSkip {
        AniSkip::with_endpoints(Duration::from_secs(2), &server.uri(), &server.uri())
            .expect("client")
    }

    fn result(kind: &str, start: f64, end: f64) -> serde_json::Value {
        serde_json::json!({
            "interval": {"startTime": start, "endTime": end},
            "skipType": kind,
            "skipId": "id",
            "episodeLength": 1420.0
        })
    }

    fn found(results: Vec<serde_json::Value>) -> ResponseTemplate {
        ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "found": true, "results": results, "message": "ok", "statusCode": 200
        }))
    }

    async fn mount_mapping(server: &MockServer, kitsu_id: u32, body: serde_json::Value) {
        Mock::given(method("GET"))
            .and(path("/api/v2/ids"))
            .and(query_param("source", "kitsu"))
            .and(query_param("id", kitsu_id.to_string()))
            .respond_with(ResponseTemplate::new(200).set_body_json(body))
            .mount(server)
            .await;
    }

    #[tokio::test]
    async fn maps_kitsu_to_mal_and_reads_segments() {
        let server = MockServer::start().await;
        mount_mapping(
            &server,
            41370,
            serde_json::json!({"kitsu": 41370, "myanimelist": 38000, "anilist": 101922}),
        )
        .await;
        Mock::given(method("GET"))
            .and(path("/v2/skip-times/38000/1"))
            .and(query_param("episodeLength", "1420"))
            .respond_with(found(vec![
                result("ed", 1295.1, 1385.1),
                result("mixed-ed", 1300.7, 1415.0),
                result("op", 5.6, 95.6),
                result("mixed-op", 0.0, 100.0),
            ]))
            .expect(1)
            .mount(&server)
            .await;

        let aniskip = client(&server);
        let segments = aniskip
            .segments(kitsu(41370, 1), EPISODE_LENGTH)
            .await
            .expect("segments");
        assert_eq!(
            segments,
            vec![
                SkipSegment {
                    kind: SkipKind::Intro,
                    start_seconds: 5.6,
                    end_seconds: 95.6
                },
                SkipSegment {
                    kind: SkipKind::Credits,
                    start_seconds: 1295.1,
                    end_seconds: 1385.1
                },
            ],
            "plain segments win over mixed ones, in playing order"
        );
        // Seeking or reopening the episode is served from memory (`expect(1)` above).
        let again = aniskip
            .segments(kitsu(41370, 1), EPISODE_LENGTH)
            .await
            .expect("cached");
        assert_eq!(again, segments);
    }

    #[tokio::test]
    async fn uses_mal_ids_directly_and_mixed_segments_when_alone() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v2/skip-times/21/1000"))
            .respond_with(found(vec![
                result("mixed-op", 10.0, 100.0),
                result("recap", 0.0, 10.0),
                result("preview", 1380.0, 1420.0),
            ]))
            .mount(&server)
            .await;
        let segments = client(&server)
            .segments(mal(21, 1000), EPISODE_LENGTH)
            .await
            .expect("segments");
        let kinds: Vec<_> = segments.iter().map(|s| s.kind).collect();
        assert_eq!(kinds, [SkipKind::Recap, SkipKind::Intro]);
        let requests = server.received_requests().await.expect("recorded");
        assert!(
            requests.iter().all(|r| r.url.path() != "/api/v2/ids"),
            "MAL ids need no mapping"
        );
    }

    #[tokio::test]
    async fn caches_titles_without_a_mal_entry() {
        let server = MockServer::start().await;
        // ARM answers `null` for ids it does not know.
        mount_mapping(&server, 7, serde_json::Value::Null).await;
        mount_mapping(
            &server,
            8,
            serde_json::json!({"kitsu": 8, "myanimelist": null}),
        )
        .await;
        let aniskip = client(&server);
        for _ in 0..2 {
            assert!(
                aniskip
                    .segments(kitsu(7, 1), 1400.0)
                    .await
                    .expect("none")
                    .is_empty()
            );
            assert!(
                aniskip
                    .segments(kitsu(8, 1), 1400.0)
                    .await
                    .expect("none")
                    .is_empty()
            );
        }
        let requests = server.received_requests().await.expect("recorded");
        assert_eq!(
            requests.len(),
            2,
            "one mapping request per title, no AniSkip request"
        );
    }

    #[tokio::test]
    async fn caches_episodes_aniskip_does_not_know() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v2/skip-times/1/999"))
            .respond_with(ResponseTemplate::new(404).set_body_json(serde_json::json!({
                "found": false, "results": [], "message": "No skip times found", "statusCode": 404
            })))
            .expect(1)
            .mount(&server)
            .await;
        let aniskip = client(&server);
        for _ in 0..2 {
            assert!(
                aniskip
                    .segments(mal(1, 999), 1400.0)
                    .await
                    .expect("none")
                    .is_empty()
            );
        }
    }

    #[tokio::test]
    async fn reports_failures_without_caching_them() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v2/skip-times/1/1"))
            .respond_with(ResponseTemplate::new(500))
            .up_to_n_times(1)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/v2/skip-times/1/1"))
            .respond_with(found(vec![result("op", 0.0, 90.0)]))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/v2/skip-times/1/2"))
            .respond_with(ResponseTemplate::new(200).set_body_string("<html>busy</html>"))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/v2/ids"))
            .respond_with(ResponseTemplate::new(503))
            .mount(&server)
            .await;

        let aniskip = client(&server);
        assert!(matches!(
            aniskip.segments(mal(1, 1), 1400.0).await,
            Err(MarkersError::Status(500))
        ));
        let retried = aniskip.segments(mal(1, 1), 1400.0).await.expect("retried");
        assert_eq!(retried.len(), 1, "a failure is not cached");
        assert!(matches!(
            aniskip.segments(mal(1, 2), 1400.0).await,
            Err(MarkersError::InvalidResponse(_))
        ));
        assert!(matches!(
            aniskip.segments(kitsu(5, 1), 1400.0).await,
            Err(MarkersError::Status(503))
        ));
    }

    #[tokio::test]
    async fn refuses_oversized_and_slow_answers() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v2/skip-times/1/1"))
            .respond_with(
                ResponseTemplate::new(200).set_body_bytes(vec![b' '; MAX_RESPONSE_BYTES + 1]),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/v2/skip-times/1/2"))
            .respond_with(found(vec![]).set_delay(Duration::from_secs(5)))
            .mount(&server)
            .await;
        let aniskip =
            AniSkip::with_endpoints(Duration::from_millis(300), &server.uri(), &server.uri())
                .expect("client");
        assert!(matches!(
            aniskip.segments(mal(1, 1), 1400.0).await,
            Err(MarkersError::TooLarge)
        ));
        assert!(matches!(
            aniskip.segments(mal(1, 2), 1400.0).await,
            Err(MarkersError::Timeout)
        ));
    }

    #[tokio::test]
    async fn skips_requests_for_implausible_lengths() {
        let server = MockServer::start().await;
        let aniskip = client(&server);
        for duration in [0.0, 30.0, f64::NAN, f64::INFINITY, 7.0 * 3600.0] {
            assert!(
                aniskip
                    .segments(mal(1, 1), duration)
                    .await
                    .expect("none")
                    .is_empty()
            );
        }
        let requests = server.received_requests().await.expect("recorded");
        assert!(requests.is_empty());
    }

    #[tokio::test]
    async fn the_public_client_refuses_plain_http() {
        let aniskip = AniSkip::new(Duration::from_secs(2)).expect("client");
        // `get_json` is what both services go through.
        let result = aniskip
            .get_json::<serde_json::Value>("http://127.0.0.1:9/v2/skip-times/1/1")
            .await;
        assert!(
            matches!(result, Err(MarkersError::Network(_))),
            "{result:?}"
        );
    }

    #[test]
    fn keeps_segments_inside_the_video() {
        let duration = 1400.0;
        let segments = select_segments(
            &[
                // Starts before zero and ends after the video: clamped.
                result("op", -5.0, 80.0),
                result("ed", 1330.0, 1500.0),
                // Entirely past the end of this (shorter) file.
                result("recap", 1450.0, 1500.0),
                // Garbage of every kind.
                serde_json::json!({"skipType": "op"}),
                serde_json::json!({"interval": {"startTime": "x", "endTime": 2}, "skipType": "op"}),
                serde_json::json!(42),
            ],
            duration,
        );
        assert_eq!(
            segments,
            vec![
                SkipSegment {
                    kind: SkipKind::Intro,
                    start_seconds: 0.0,
                    end_seconds: 80.0
                },
                SkipSegment {
                    kind: SkipKind::Credits,
                    start_seconds: 1330.0,
                    end_seconds: 1400.0
                },
            ]
        );
    }

    #[test]
    fn drops_implausible_segments() {
        let segments = select_segments(
            &[
                result("op", 100.0, 101.0),
                result("op", 100.0, 90.0),
                result("ed", 100.0, 1300.0),
                result("recap", f64::MAX, f64::MAX),
            ],
            1400.0,
        );
        assert!(segments.is_empty(), "{segments:?}");
    }
}
