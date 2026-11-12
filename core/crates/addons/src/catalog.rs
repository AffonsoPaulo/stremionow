// SPDX-License-Identifier: GPL-3.0-or-later
//! Addons to discover: Stremio's official and community lists, which Cinemeta serves through
//! the protocol's `addon_catalog` resource.

use std::collections::HashSet;

use stremionow_protocol::{AddonCatalogResponse, AddonUrl, Extra};

use crate::InstalledAddon;

/// The addon that serves the lists. Stremio asks Cinemeta too; the address is fixed here
/// rather than taken from whatever addons are installed, so an addon the user added cannot
/// put its own recommendations in front of them.
pub const DISCOVER_SOURCE: &str = "https://v3-cinemeta.strem.io/manifest.json";

/// Most entries kept from one list. Real lists hold about a hundred; the cap keeps a broken
/// or hostile list from flooding the screen.
const MAX_ENTRIES_PER_LIST: usize = 500;

/// One of the lists the source serves.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AddonList {
    /// Addons made by Stremio.
    Official,
    /// Addons made by others and listed by Stremio.
    Community,
}

impl AddonList {
    /// URL of the list's every-type page, which holds all its addons.
    pub fn url(self, source: &AddonUrl) -> String {
        let id = match self {
            Self::Official => "official",
            Self::Community => "community",
        };
        source.resource("addon_catalog", "all", id, &Extra::new())
    }
}

/// An addon from a list, ready to show and install.
#[derive(Debug, Clone, PartialEq)]
pub struct DiscoverableAddon {
    pub addon: InstalledAddon,
    pub official: bool,
}

/// Merges the lists, official addons first, in list order. Each addon appears once (by id and
/// by URL), and only addons served over HTTPS from a public host are kept: lists also name
/// addons that need Stremio's local server or use a legacy protocol, and nothing from the
/// internet should point the app at the user's own network.
pub fn discoverable(
    official: AddonCatalogResponse,
    community: AddonCatalogResponse,
) -> Vec<DiscoverableAddon> {
    let mut ids = HashSet::new();
    let mut urls = HashSet::new();
    let lists = [(official, true), (community, false)];
    lists
        .into_iter()
        .flat_map(|(list, official)| {
            list.addons
                .into_iter()
                .take(MAX_ENTRIES_PER_LIST)
                .map(move |entry| (entry, official))
        })
        .filter_map(|(entry, official)| {
            let url = AddonUrl::parse(&entry.transport_url).ok()?;
            if !url.is_public_https() || entry.manifest.id.trim().is_empty() {
                return None;
            }
            if !ids.insert(entry.manifest.id.clone()) || !urls.insert(url.manifest_url().to_owned())
            {
                return None;
            }
            Some(DiscoverableAddon {
                addon: InstalledAddon {
                    url,
                    manifest: entry.manifest,
                },
                official,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn list(entries: &[(&str, &str)]) -> AddonCatalogResponse {
        let addons: Vec<_> = entries
            .iter()
            .map(|(url, id)| {
                serde_json::json!({
                    "transportUrl": url,
                    "manifest": {"id": id, "version": "1.0.0", "name": id}
                })
            })
            .collect();
        serde_json::from_value(serde_json::json!({ "addons": addons })).expect("valid")
    }

    #[test]
    fn puts_official_first_and_drops_repeats() {
        let official = list(&[("https://a.example/manifest.json", "a")]);
        let community = list(&[
            ("https://b.example/manifest.json", "b"),
            ("https://a.example/manifest.json", "a"),
            ("https://other.example/manifest.json", "b"),
            ("https://a.example/manifest.json", "renamed"),
        ]);
        let merged = discoverable(official, community);
        let ids: Vec<_> = merged
            .iter()
            .map(|d| (d.addon.manifest.id.as_str(), d.official))
            .collect();
        assert_eq!(ids, [("a", true), ("b", false)]);
    }

    #[test]
    fn keeps_only_public_https_manifests() {
        let official = list(&[
            ("https://opensubtitles.strem.io/stremio/v1", "legacy"),
            ("http://127.0.0.1:11470/local-addon/manifest.json", "local"),
            ("http://plain.example/manifest.json", "plain"),
            ("https://192.168.0.2/manifest.json", "lan"),
            ("stremio://ok.example/manifest.json", "ok"),
            ("https://blank.example/manifest.json", " "),
        ]);
        let merged = discoverable(official, AddonCatalogResponse::default());
        let ids: Vec<_> = merged
            .iter()
            .map(|d| d.addon.manifest.id.as_str())
            .collect();
        assert_eq!(ids, ["ok"]);
        assert_eq!(
            merged[0].addon.url.manifest_url(),
            "https://ok.example/manifest.json"
        );
    }

    #[test]
    fn caps_each_list() {
        let many: Vec<(String, String)> = (0..MAX_ENTRIES_PER_LIST + 50)
            .map(|i| {
                (
                    format!("https://a{i}.example/manifest.json"),
                    format!("id{i}"),
                )
            })
            .collect();
        let refs: Vec<(&str, &str)> = many.iter().map(|(u, i)| (u.as_str(), i.as_str())).collect();
        let merged = discoverable(AddonCatalogResponse::default(), list(&refs));
        assert_eq!(merged.len(), MAX_ENTRIES_PER_LIST);
    }

    #[test]
    fn builds_list_urls() {
        let source = AddonUrl::parse(DISCOVER_SOURCE).expect("valid");
        assert_eq!(
            AddonList::Community.url(&source),
            "https://v3-cinemeta.strem.io/addon_catalog/all/community.json"
        );
        assert_eq!(
            AddonList::Official.url(&source),
            "https://v3-cinemeta.strem.io/addon_catalog/all/official.json"
        );
    }
}
