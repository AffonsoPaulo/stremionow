// SPDX-License-Identifier: GPL-3.0-or-later
//! Like and Love on the details page.
//!
//! Ratings are kept on the device. Signed in, they also go to Stremio's likes service, as the
//! official apps do, so they follow the account: a change is sent right away and, when that
//! fails (offline), marked pending and sent again the next time the title opens.

use stremionow_sync::Rating;

use crate::core::StremioCore;
use crate::records::CoreError;

/// Settings with each title's rating: `rating:<id>` holds `liked`, `loved` or nothing.
const RATING_PREFIX: &str = "rating:";
/// Settings marking ratings not yet sent to the account: `rating_pending:<id>`.
pub(crate) const PENDING_PREFIX: &str = "rating_pending:";
/// Longest title id or type accepted, like the other per-title settings.
const MAX_ID_LEN: usize = 200;

/// What the user thinks of a title. Like and Love exclude each other, as in the official apps.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TitleRating {
    None,
    Liked,
    Loved,
}

impl TitleRating {
    fn stored(self) -> &'static str {
        match self {
            Self::None => "",
            Self::Liked => "liked",
            Self::Loved => "loved",
        }
    }

    fn from_stored(value: &str) -> Self {
        match value {
            "liked" => Self::Liked,
            "loved" => Self::Loved,
            _ => Self::None,
        }
    }

    fn remote(self) -> Option<Rating> {
        match self {
            Self::None => None,
            Self::Liked => Some(Rating::Liked),
            Self::Loved => Some(Rating::Loved),
        }
    }

    /// The account's status as a choice of the user; "watched" is the apps' own mark.
    fn from_remote(rating: Option<Rating>) -> Self {
        match rating {
            Some(Rating::Liked) => Self::Liked,
            Some(Rating::Loved) => Self::Loved,
            Some(Rating::Watched) | None => Self::None,
        }
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// The rating kept on this device for a title.
    pub fn rating(&self, id: String) -> Result<TitleRating, CoreError> {
        if !valid(&id) {
            return Ok(TitleRating::None);
        }
        self.local_rating(&id)
    }

    /// Rates a title, or clears its rating with [`TitleRating::None`]. Kept on the device, and
    /// sent to the account when signed in; a failed send is retried by
    /// [`StremioCore::refresh_rating`]. Returns the rating now in effect.
    pub async fn set_rating(
        &self,
        kind: String,
        id: String,
        rating: TitleRating,
    ) -> Result<TitleRating, CoreError> {
        if !valid(&id) || !valid(&kind) {
            return Ok(TitleRating::None);
        }
        {
            let store = self.store()?;
            store.set_setting(&format!("{RATING_PREFIX}{id}"), rating.stored())?;
            store.delete_setting(&format!("{PENDING_PREFIX}{id}"))?;
        }
        if let Some(key) = self.account.auth_key()
            && let Err(error) = self
                .account
                .likes
                .send(&key, &id, &kind, rating.remote())
                .await
        {
            log::warn!("could not send a rating to the account: {error}");
            self.store()?
                .set_setting(&format!("{PENDING_PREFIX}{id}"), "1")?;
        }
        Ok(rating)
    }

    /// The rating of a title, brought from the account when signed in: a rating still
    /// pending here is sent instead. Offline, or signed out, the device's rating stands.
    pub async fn refresh_rating(&self, kind: String, id: String) -> Result<TitleRating, CoreError> {
        if !valid(&id) || !valid(&kind) {
            return Ok(TitleRating::None);
        }
        let local = self.local_rating(&id)?;
        let Some(key) = self.account.auth_key() else {
            return Ok(local);
        };
        let pending_key = format!("{PENDING_PREFIX}{id}");
        let pending = self.store()?.setting(&pending_key)?.is_some();
        if pending {
            if self
                .account
                .likes
                .send(&key, &id, &kind, local.remote())
                .await
                .is_ok()
            {
                self.store()?.delete_setting(&pending_key)?;
            }
            return Ok(local);
        }
        match self.account.likes.status(&key, &id, &kind).await {
            Ok(remote) => {
                let rating = TitleRating::from_remote(remote);
                self.store()?
                    .set_setting(&format!("{RATING_PREFIX}{id}"), rating.stored())?;
                Ok(rating)
            }
            Err(error) => {
                log::debug!("could not read a rating from the account: {error}");
                Ok(local)
            }
        }
    }
}

impl StremioCore {
    fn local_rating(&self, id: &str) -> Result<TitleRating, CoreError> {
        Ok(self
            .store()?
            .setting(&format!("{RATING_PREFIX}{id}"))?
            .map_or(TitleRating::None, |value| TitleRating::from_stored(&value)))
    }
}

/// A title id or type that can be stored and sent: not empty, not overlong, no control
/// characters.
fn valid(value: &str) -> bool {
    !value.is_empty() && value.len() <= MAX_ID_LEN && !value.chars().any(char::is_control)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::account::Account;
    use wiremock::matchers::{body_partial_json, method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn core_for(server: &MockServer, dir: &tempfile::TempDir) -> std::sync::Arc<StremioCore> {
        StremioCore::open(
            dir.path().to_string_lossy().into_owned(),
            Account::with_base_url(&server.uri()).expect("account"),
        )
        .expect("core")
    }

    #[tokio::test]
    async fn signed_out_ratings_stay_on_the_device() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(200))
            .expect(0)
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);

        assert_eq!(core.rating("tt1".into()).expect("reads"), TitleRating::None);
        core.set_rating("movie".into(), "tt1".into(), TitleRating::Loved)
            .await
            .expect("rates");
        assert_eq!(
            core.rating("tt1".into()).expect("reads"),
            TitleRating::Loved
        );
        assert_eq!(
            core.refresh_rating("movie".into(), "tt1".into())
                .await
                .expect("refreshes"),
            TitleRating::Loved
        );
        core.set_rating("movie".into(), "tt1".into(), TitleRating::None)
            .await
            .expect("clears");
        assert_eq!(core.rating("tt1".into()).expect("reads"), TitleRating::None);
        assert_eq!(
            core.rating(String::new()).expect("reads"),
            TitleRating::None
        );
    }

    #[tokio::test]
    async fn signed_in_ratings_follow_the_account() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/api/send"))
            .and(body_partial_json(serde_json::json!({
                "authToken": "secret-key", "mediaId": "tt1", "mediaType": "series", "status": "liked"
            })))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({"message": "ok"})))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/get_status"))
            .and(query_param("mediaId", "tt2"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"status": "loved"})),
            )
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());

        core.set_rating("series".into(), "tt1".into(), TitleRating::Liked)
            .await
            .expect("rates");
        assert_eq!(
            core.refresh_rating("series".into(), "tt2".into())
                .await
                .expect("refreshes"),
            TitleRating::Loved,
            "rated on another device"
        );
        assert_eq!(
            core.rating("tt2".into()).expect("reads"),
            TitleRating::Loved
        );
    }

    #[tokio::test]
    async fn a_failed_send_is_retried_before_reading_the_account() {
        let server = MockServer::start().await;
        let failing = Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(503))
            .expect(1)
            .mount_as_scoped(&server)
            .await;
        let dir = tempfile::tempdir().expect("temp dir");
        let core = core_for(&server, &dir);
        core.restore_session("secret-key".into());
        core.set_rating("movie".into(), "tt1".into(), TitleRating::Loved)
            .await
            .expect("kept on the device");
        drop(failing);

        Mock::given(method("POST"))
            .and(path("/api/send"))
            .and(body_partial_json(
                serde_json::json!({"mediaId": "tt1", "status": "loved"}),
            ))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"message": "ok"})),
            )
            .expect(1)
            .mount(&server)
            .await;
        // The account still says nothing; the pending rating must win over it, once.
        Mock::given(method("GET"))
            .and(path("/api/get_status"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"status": null})),
            )
            .mount(&server)
            .await;
        assert_eq!(
            core.refresh_rating("movie".into(), "tt1".into())
                .await
                .expect("refreshes"),
            TitleRating::Loved
        );
        assert_eq!(
            core.refresh_rating("movie".into(), "tt1".into())
                .await
                .expect("refreshes"),
            TitleRating::None,
            "sent, so the account's answer counts from then on"
        );
    }
}
