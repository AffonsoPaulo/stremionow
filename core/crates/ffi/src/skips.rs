// SPDX-License-Identifier: GPL-3.0-or-later
//! Skippable segments of anime episodes from AniSkip (see docs/adr/0009-aniskip.md).

use std::time::Duration;

use stremionow_markers::{AniSkip, anime_episode};

use crate::core::StremioCore;
use crate::records::CoreError;

/// AniSkip and the id mapping give up after this long. The answer only adds buttons, so a
/// slow service must not keep a request open for the length of an episode.
pub(crate) const ANISKIP_TIMEOUT: Duration = Duration::from_secs(10);

/// What a skippable segment covers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SkipKind {
    Intro,
    Recap,
    Credits,
}

/// A stretch of the video that can be skipped, in seconds from its start.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SkipSegment {
    pub kind: SkipKind,
    pub start_seconds: f64,
    pub end_seconds: f64,
}

impl From<stremionow_markers::SkipSegment> for SkipSegment {
    fn from(segment: stremionow_markers::SkipSegment) -> Self {
        Self {
            kind: match segment.kind {
                stremionow_markers::SkipKind::Intro => SkipKind::Intro,
                stremionow_markers::SkipKind::Recap => SkipKind::Recap,
                stremionow_markers::SkipKind::Credits => SkipKind::Credits,
            },
            start_seconds: segment.start_seconds,
            end_seconds: segment.end_seconds,
        }
    }
}

/// The AniSkip client for the public services.
pub(crate) fn new_client() -> Result<AniSkip, CoreError> {
    AniSkip::new(ANISKIP_TIMEOUT).map_err(|e| CoreError::Network {
        reason: e.to_string(),
    })
}

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// Openings, recaps and endings of an anime episode from AniSkip, at most one of each, in
    /// playing order. `meta_id` is the title's id, `video_id` the episode's (the title's for a
    /// movie), and `duration_seconds` the length of the file being played.
    ///
    /// Returns nothing, without any request, when the setting is off or the ids are not anime
    /// ids (`kitsu:`, `mal:`, `anilist:`). Failures are logged and also return nothing:
    /// skipping is a convenience, never a reason to interrupt playback.
    pub async fn skip_segments(
        &self,
        meta_id: String,
        video_id: String,
        season: Option<u32>,
        episode: Option<u32>,
        duration_seconds: f64,
    ) -> Vec<SkipSegment> {
        let enabled = self.settings().map(|s| s.aniskip).unwrap_or(false);
        if !enabled {
            return Vec::new();
        }
        let Some(episode) = anime_episode(&meta_id, &video_id, season, episode) else {
            return Vec::new();
        };
        match self.aniskip.segments(episode, duration_seconds).await {
            Ok(segments) => segments.into_iter().map(SkipSegment::from).collect(),
            Err(error) => {
                log::debug!("AniSkip lookup failed: {error}");
                Vec::new()
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;
    use crate::account::Account;

    async fn server() -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/api/v2/ids"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(serde_json::json!({"kitsu": 12, "myanimelist": 21})),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/v2/skip-times/21/5"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "found": true,
                "results": [{"interval": {"startTime": 10.0, "endTime": 100.0}, "skipType": "op"}]
            })))
            .mount(&server)
            .await;
        server
    }

    fn core(server: &MockServer) -> (Arc<StremioCore>, tempfile::TempDir) {
        let directory = tempfile::tempdir().expect("temp dir");
        let aniskip =
            AniSkip::with_endpoints(ANISKIP_TIMEOUT, &server.uri(), &server.uri()).expect("client");
        let core = StremioCore::open_with(
            directory.path().to_string_lossy().into_owned(),
            Account::new().expect("account"),
            aniskip,
        )
        .expect("core");
        (core, directory)
    }

    async fn episode(core: &StremioCore, meta_id: &str, video_id: &str) -> Vec<SkipSegment> {
        core.skip_segments(meta_id.into(), video_id.into(), Some(1), Some(5), 1420.0)
            .await
    }

    #[tokio::test]
    async fn returns_segments_for_anime_episodes() {
        let server = server().await;
        let (core, _dir) = core(&server);
        assert_eq!(
            episode(&core, "kitsu:12", "kitsu:12:5").await,
            vec![SkipSegment {
                kind: SkipKind::Intro,
                start_seconds: 10.0,
                end_seconds: 100.0
            }]
        );
    }

    #[tokio::test]
    async fn makes_no_request_for_other_titles_or_when_turned_off() {
        let server = server().await;
        let (core, _dir) = core(&server);
        assert!(episode(&core, "tt1", "tt1:1:5").await.is_empty());

        let mut settings = core.settings().expect("reads");
        assert!(settings.aniskip, "on by default");
        settings.aniskip = false;
        core.update_settings(settings).expect("saves");
        assert!(episode(&core, "kitsu:12", "kitsu:12:5").await.is_empty());

        let requests = server.received_requests().await.expect("recorded");
        assert!(requests.is_empty(), "{requests:?}");
    }

    #[tokio::test]
    async fn failures_give_no_segments() {
        let server = server().await;
        let (core, _dir) = core(&server);
        // Episode 6 is not mounted, so the server answers 404.
        assert!(episode(&core, "kitsu:12", "kitsu:12:6").await.is_empty());
        drop(server);
        // Offline: the mapping is cached, AniSkip itself cannot be reached.
        assert!(episode(&core, "kitsu:12", "kitsu:12:7").await.is_empty());
    }
}
