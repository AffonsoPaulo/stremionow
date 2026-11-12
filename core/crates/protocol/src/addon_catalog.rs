// SPDX-License-Identifier: GPL-3.0-or-later
//! Response of the `addon_catalog` resource: addons an addon recommends installing.
//!
//! Cinemeta serves Stremio's official and community lists this way, at
//! `/addon_catalog/<type>/<official|community>.json`.

use serde::{Deserialize, Deserializer};

use crate::manifest::Manifest;

/// Response of the `addon_catalog` resource.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
pub struct AddonCatalogResponse {
    /// Entries in the order the catalog lists them. Entries that do not parse are left out,
    /// so one broken manifest does not hide the rest of the list.
    #[serde(default, deserialize_with = "valid_entries")]
    pub addons: Vec<AddonDescriptor>,
}

/// An addon in a catalog: where it lives and its manifest as the catalog last saw it.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AddonDescriptor {
    /// Manifest URL, such as `https://example.com/manifest.json`.
    pub transport_url: String,
    pub manifest: Manifest,
}

fn valid_entries<'de, D>(deserializer: D) -> Result<Vec<AddonDescriptor>, D::Error>
where
    D: Deserializer<'de>,
{
    let raw = Option::<Vec<serde_json::Value>>::deserialize(deserializer)?.unwrap_or_default();
    Ok(raw
        .into_iter()
        .filter_map(|entry| serde_json::from_value(entry).ok())
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn skips_broken_entries_and_keeps_order() {
        let response: AddonCatalogResponse = serde_json::from_value(serde_json::json!({
            "addons": [
                {"transportUrl": "https://a.example/manifest.json", "transportName": "http",
                 "manifest": {"id": "a", "version": "1.0.0", "name": "A", "types": ["movie"]}},
                {"transportUrl": "https://b.example/manifest.json", "manifest": {"name": "no id"}},
                "not an object",
                {"manifest": {"id": "c", "version": "1", "name": "C"}},
                {"transportUrl": "https://d.example/manifest.json",
                 "manifest": {"id": "d", "version": "2", "name": "D",
                              "behaviorHints": {"configurable": true, "configurationRequired": true}}}
            ]
        }))
        .expect("valid");
        let ids: Vec<_> = response
            .addons
            .iter()
            .map(|a| a.manifest.id.as_str())
            .collect();
        assert_eq!(ids, ["a", "d"]);
        assert!(
            response.addons[1]
                .manifest
                .behavior_hints
                .configuration_required
        );
    }

    #[test]
    fn missing_or_null_list_is_empty() {
        let empty: AddonCatalogResponse = serde_json::from_str("{}").expect("valid");
        assert!(empty.addons.is_empty());
        let null: AddonCatalogResponse =
            serde_json::from_str(r#"{"addons": null}"#).expect("valid");
        assert!(null.addons.is_empty());
    }

    #[test]
    fn rejects_a_body_that_is_not_a_catalog() {
        assert!(serde_json::from_str::<AddonCatalogResponse>("[1, 2]").is_err());
        assert!(serde_json::from_str::<AddonCatalogResponse>("<html>").is_err());
    }
}
