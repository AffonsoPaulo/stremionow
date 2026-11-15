// SPDX-License-Identifier: GPL-3.0-or-later
//! Icons of the streaming services that sources open on (Netflix, Prime Video, Max...).

use std::time::Duration;

use stremionow_addons::{IconLookup, MAX_ICON_BYTES, icon_host, is_icon_image};

use crate::core::{StremioCore, millis, now_millis};
use crate::records::CoreError;

/// How long the icons of services stay before they are asked again; they rarely change.
const ICON_FRESHNESS: Duration = Duration::from_secs(7 * 24 * 60 * 60);
/// How long a site without a usable icon is left alone before it is asked again.
const MISSING_ICON_FRESHNESS: Duration = Duration::from_secs(24 * 60 * 60);
/// How long a site that could not be asked is left alone; the absence noted for it is dated
/// back so that it goes stale after this long instead of [`MISSING_ICON_FRESHNESS`].
const UNREACHABLE_RETRY: Duration = Duration::from_secs(10 * 60);
/// Requests for an icon give up after this long; the initial shows meanwhile.
pub(crate) const ICON_TIMEOUT: Duration = Duration::from_secs(6);

#[uniffi::export(async_runtime = "tokio")]
impl StremioCore {
    /// The icon of the service a link opens on, from the service's own site, as image bytes
    /// (PNG, ICO, JPEG, GIF or WebP); None when there is none, and the interface shows the
    /// service's initial instead. Only public HTTPS sites are asked, and answers (including
    /// "none") are cached, so each service is asked about once a week.
    pub async fn service_icon(&self, page_url: String) -> Result<Option<Vec<u8>>, CoreError> {
        let Some(host) = icon_host(&page_url) else {
            return Ok(None);
        };
        let key = format!("service-icon:{host}");
        let cached = self.store()?.cached(&key)?;
        if let Some((body, fetched_at)) = &cached {
            let freshness = if body.is_empty() {
                MISSING_ICON_FRESHNESS
            } else {
                ICON_FRESHNESS
            };
            if now_millis() - fetched_at < millis(freshness) {
                return Ok(usable(body.clone()));
            }
        }
        let lookup = self.icons.fetch(&host).await;
        let (icon, note) = settle(lookup, cached.map(|(body, _)| body), now_millis());
        if let Some((body, fetched_at)) = note {
            self.store()?.put_cached(&key, &body, fetched_at)?;
        }
        Ok(icon)
    }
}

/// What to note in the icon cache: the bytes (empty for none) and when they count as
/// fetched.
type CacheNote = (Vec<u8>, i64);

/// The icon to show after asking a site, given the stale cached answer if any, and what to
/// note in the cache, if anything.
fn settle(
    lookup: IconLookup,
    cached: Option<Vec<u8>>,
    now: i64,
) -> (Option<Vec<u8>>, Option<CacheNote>) {
    match lookup {
        IconLookup::Found(icon) => (Some(icon.clone()), Some((icon, now))),
        // The site said it has none: it is left alone for a day.
        IconLookup::Missing => (None, Some((Vec::new(), now))),
        // Whether it has one is not known. An old icon beats none meanwhile, and stays;
        // with none at all, the absence is dated back so that the site is asked again
        // after a short while rather than a day, which a moment offline would otherwise
        // cost.
        IconLookup::Unreachable => match cached {
            Some(body) if !body.is_empty() => (usable(body), None),
            _ => {
                let retry_at = now - millis(MISSING_ICON_FRESHNESS) + millis(UNREACHABLE_RETRY);
                (None, Some((Vec::new(), retry_at)))
            }
        },
    }
}

/// Cached bytes as an icon, checked again since the cache is only a file on disk.
fn usable(body: Vec<u8>) -> Option<Vec<u8>> {
    (body.len() <= MAX_ICON_BYTES && is_icon_image(&body)).then_some(body)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn caches_icons_and_their_absence() {
        let dir = tempfile::tempdir().expect("temp dir");
        let core = StremioCore::new(dir.path().to_string_lossy().into_owned()).expect("core");
        let png = b"\x89PNG\r\n\x1a\n...".to_vec();
        core.store()
            .expect("store")
            .put_cached("service-icon:www.netflix.com", &png, now_millis())
            .expect("caches");
        core.store()
            .expect("store")
            .put_cached("service-icon:www.nothing.example", &[], now_millis())
            .expect("caches");

        // Both answer from the cache, without the network.
        assert_eq!(
            core.service_icon("https://www.netflix.com/title/1".into())
                .await
                .expect("reads"),
            Some(png)
        );
        assert_eq!(
            core.service_icon("https://www.nothing.example/x".into())
                .await
                .expect("reads"),
            None
        );
        assert_eq!(
            core.service_icon("http://192.168.0.1/".into())
                .await
                .expect("reads"),
            None,
            "never asked"
        );
    }

    #[test]
    fn notes_absence_for_a_day_only_when_the_site_said_so() {
        let png = b"\x89PNG\r\n\x1a\n...".to_vec();
        let now = 1_000_000_000;
        assert_eq!(
            settle(IconLookup::Found(png.clone()), None, now),
            (Some(png.clone()), Some((png.clone(), now)))
        );
        assert_eq!(
            settle(IconLookup::Missing, Some(png.clone()), now),
            (None, Some((Vec::new(), now)))
        );
        assert_eq!(
            settle(IconLookup::Unreachable, Some(png.clone()), now),
            (Some(png), None),
            "the old icon stays"
        );
        let (icon, note) = settle(IconLookup::Unreachable, Some(Vec::new()), now);
        assert_eq!(icon, None);
        let (body, fetched_at) = note.expect("noted");
        assert!(body.is_empty());
        let stale_at = fetched_at + millis(MISSING_ICON_FRESHNESS);
        assert_eq!(
            stale_at - now,
            millis(UNREACHABLE_RETRY),
            "asked again soon, not in a day"
        );
    }
}
