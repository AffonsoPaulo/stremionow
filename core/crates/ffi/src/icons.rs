// SPDX-License-Identifier: GPL-3.0-or-later
//! Icons of the streaming services that sources open on (Netflix, Prime Video, Max...).

use std::time::Duration;

use stremionow_addons::{MAX_ICON_BYTES, icon_host, is_icon_image};

use crate::core::{StremioCore, millis, now_millis};
use crate::records::CoreError;

/// How long the icons of services stay before they are asked again; they rarely change.
const ICON_FRESHNESS: Duration = Duration::from_secs(7 * 24 * 60 * 60);
/// How long a site without a usable icon is left alone before it is asked again.
const MISSING_ICON_FRESHNESS: Duration = Duration::from_secs(24 * 60 * 60);
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
        match self.icons.fetch(&host).await {
            Some(icon) => {
                self.store()?.put_cached(&key, &icon, now_millis())?;
                Ok(Some(icon))
            }
            // An old icon beats none while the site is unreachable; with none at all, the
            // site is left alone for a day.
            None => match cached {
                Some((body, _)) if !body.is_empty() => Ok(usable(body)),
                _ => {
                    self.store()?.put_cached(&key, &[], now_millis())?;
                    Ok(None)
                }
            },
        }
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
}
