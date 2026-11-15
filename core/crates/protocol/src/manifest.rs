// SPDX-License-Identifier: GPL-3.0-or-later
//! The addon manifest: identity, resources and catalogs.

use std::collections::HashSet;

use serde::{Deserialize, Deserializer, Serialize};

use crate::lenient::{
    lenient_bool, lenient_list, lenient_optional_strings, lenient_string, lenient_strings,
    lenient_text, or_default,
};

/// Describes an addon and what it can provide.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    /// Reverse-domain identifier, for example `com.linvo.cinemeta`.
    pub id: String,
    pub version: String,
    pub name: String,
    #[serde(default, deserialize_with = "lenient_text")]
    pub description: String,
    #[serde(default, deserialize_with = "lenient_string")]
    pub logo: Option<String>,
    #[serde(default, deserialize_with = "lenient_list")]
    pub resources: Vec<ResourceDescriptor>,
    /// Content types the addon handles, such as `movie` and `series`.
    #[serde(default, deserialize_with = "lenient_strings")]
    pub types: Vec<String>,
    /// Catalogs in the addon's order, each type and id once.
    #[serde(default, deserialize_with = "distinct_catalogs")]
    pub catalogs: Vec<CatalogDescriptor>,
    /// Item id prefixes the addon understands, for example `tt` for IMDb ids.
    #[serde(default, deserialize_with = "lenient_optional_strings")]
    pub id_prefixes: Option<Vec<String>>,
    #[serde(default, deserialize_with = "or_default")]
    pub behavior_hints: ManifestBehaviorHints,
}

impl Manifest {
    /// Whether the addon serves `resource` (for example `meta` or `stream`) for `kind` and `id`.
    pub fn supports(&self, resource: &str, kind: &str, id: &str) -> bool {
        self.resources.iter().any(|descriptor| {
            descriptor.name() == resource
                && descriptor
                    .types()
                    .unwrap_or(&self.types)
                    .iter()
                    .any(|t| t == kind)
                && descriptor
                    .id_prefixes()
                    .or(self.id_prefixes.as_deref())
                    .is_none_or(|prefixes| prefixes.iter().any(|p| id.starts_with(p.as_str())))
        })
    }

    /// Whether the addon declares `resource` at all, for any type.
    pub fn has_resource(&self, resource: &str) -> bool {
        self.resources.iter().any(|d| d.name() == resource)
    }
}

/// Hints about how the addon behaves.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestBehaviorHints {
    /// The addon offers a configuration page.
    #[serde(default, deserialize_with = "lenient_bool")]
    pub configurable: bool,
    /// The addon cannot be used before it is configured.
    #[serde(default, deserialize_with = "lenient_bool")]
    pub configuration_required: bool,
    /// The addon serves adult content.
    #[serde(default, deserialize_with = "lenient_bool")]
    pub adult: bool,
}

/// A resource the addon serves. Manifests list either a bare name or a detailed object.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ResourceDescriptor {
    Short(String),
    Full {
        name: String,
        #[serde(default, deserialize_with = "lenient_optional_strings")]
        types: Option<Vec<String>>,
        #[serde(
            default,
            rename = "idPrefixes",
            deserialize_with = "lenient_optional_strings"
        )]
        id_prefixes: Option<Vec<String>>,
    },
}

impl ResourceDescriptor {
    pub fn name(&self) -> &str {
        match self {
            Self::Short(name) | Self::Full { name, .. } => name,
        }
    }

    fn types(&self) -> Option<&Vec<String>> {
        match self {
            Self::Short(_) => None,
            Self::Full { types, .. } => types.as_ref(),
        }
    }

    fn id_prefixes(&self) -> Option<&[String]> {
        match self {
            Self::Short(_) => None,
            Self::Full { id_prefixes, .. } => id_prefixes.as_deref(),
        }
    }
}

/// A catalog the addon exposes, such as "Popular movies".
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogDescriptor {
    #[serde(rename = "type")]
    pub kind: String,
    pub id: String,
    #[serde(default, deserialize_with = "lenient_string")]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "lenient_list")]
    pub extra: Vec<ExtraProperty>,
    /// Older manifests list supported extras by name only.
    #[serde(default, deserialize_with = "lenient_strings")]
    pub extra_supported: Vec<String>,
    #[serde(default, deserialize_with = "lenient_strings")]
    pub extra_required: Vec<String>,
}

impl CatalogDescriptor {
    /// Whether the catalog accepts the extra property `name`, such as `search` or `genre`.
    pub fn supports_extra(&self, name: &str) -> bool {
        self.extra.iter().any(|e| e.name == name) || self.extra_supported.iter().any(|e| e == name)
    }

    /// Whether the catalog needs an extra property before it returns anything.
    pub fn requires_extra(&self) -> bool {
        self.required_extras().next().is_some()
    }

    /// Whether the catalog needs an extra property other than `name` before it returns
    /// anything, such as a search catalog that also needs a genre.
    pub fn requires_extra_other_than(&self, name: &str) -> bool {
        self.required_extras().any(|required| required != name)
    }

    /// Names of the extra properties the catalog needs.
    fn required_extras(&self) -> impl Iterator<Item = &str> {
        self.extra
            .iter()
            .filter(|e| e.is_required)
            .map(|e| e.name.as_str())
            .chain(self.extra_required.iter().map(String::as_str))
    }

    /// Allowed values of the extra property `name`, for example the genres.
    pub fn extra_options(&self, name: &str) -> Vec<String> {
        self.extra
            .iter()
            .find(|e| e.name == name)
            .map(|e| e.options.clone())
            .unwrap_or_default()
    }
}

/// An extra property a catalog accepts, such as `genre`, `search` or `skip`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExtraProperty {
    pub name: String,
    #[serde(default, deserialize_with = "lenient_bool")]
    pub is_required: bool,
    /// Allowed values; numbers (years, for example) are kept as text.
    #[serde(default, deserialize_with = "lenient_strings")]
    pub options: Vec<String>,
}

/// Reads the catalogs, skipping the ones that cannot be read and keeping only the first of
/// catalogs that repeat a type and id: the interface tells catalogs apart by addon, type and
/// id, and two at the same address would serve the same items anyway.
fn distinct_catalogs<'de, D>(deserializer: D) -> Result<Vec<CatalogDescriptor>, D::Error>
where
    D: Deserializer<'de>,
{
    let mut seen = HashSet::new();
    let mut catalogs: Vec<CatalogDescriptor> = lenient_list(deserializer)?;
    catalogs.retain(|catalog| seen.insert((catalog.kind.clone(), catalog.id.clone())));
    Ok(catalogs)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cinemeta_like() -> Manifest {
        serde_json::from_value(serde_json::json!({
            "id": "com.linvo.cinemeta",
            "version": "3.0.0",
            "name": "Cinemeta",
            "description": null,
            "resources": ["catalog", {"name": "meta", "types": ["movie", "series"], "idPrefixes": ["tt"]}],
            "types": ["movie", "series"],
            "catalogs": [
                {"type": "movie", "id": "top", "name": "Popular",
                 "extra": [{"name": "genre", "options": ["Action", "Drama"]}, {"name": "skip"}]},
                {"type": "movie", "id": "search", "extra": [{"name": "search", "isRequired": true}]},
                {"type": "series", "id": "legacy", "extraSupported": ["search"]}
            ],
            "unknownField": 1
        }))
        .expect("valid manifest")
    }

    #[test]
    fn parses_short_and_full_resources() {
        let manifest = cinemeta_like();
        assert!(manifest.has_resource("catalog"));
        assert!(manifest.supports("meta", "movie", "tt0111161"));
        assert!(
            !manifest.supports("meta", "movie", "kitsu:1"),
            "id prefix must match"
        );
        assert!(
            !manifest.supports("meta", "channel", "tt0111161"),
            "type must match"
        );
        assert!(!manifest.supports("stream", "movie", "tt0111161"));
        assert_eq!(manifest.description, "", "null becomes empty");
    }

    #[test]
    fn reads_catalog_extras() {
        let manifest = cinemeta_like();
        let popular = &manifest.catalogs[0];
        assert!(!popular.requires_extra());
        assert_eq!(popular.extra_options("genre"), ["Action", "Drama"]);
        assert!(manifest.catalogs[1].requires_extra());
        assert!(manifest.catalogs[1].supports_extra("search"));
        assert!(
            manifest.catalogs[2].supports_extra("search"),
            "legacy extraSupported"
        );
    }

    #[test]
    fn short_resources_inherit_manifest_types_and_prefixes() {
        let manifest: Manifest = serde_json::from_value(serde_json::json!({
            "id": "x", "version": "1", "name": "X",
            "resources": ["stream"], "types": ["movie"], "idPrefixes": ["tt"]
        }))
        .expect("valid manifest");
        assert!(manifest.supports("stream", "movie", "tt1"));
        assert!(!manifest.supports("stream", "series", "tt1"));
        assert!(!manifest.supports("stream", "movie", "kitsu:1"));
    }

    #[test]
    fn reads_loosely_written_manifests() {
        let manifest: Manifest = serde_json::from_value(serde_json::json!({
            "id": "x", "version": "1", "name": "X", "logo": null,
            "behaviorHints": null,
            "resources": ["stream", 7, {"name": "meta", "types": ["movie", null], "idPrefixes": null}],
            "types": ["movie", null],
            "idPrefixes": ["tt", null],
            "catalogs": [
                {"type": "movie", "id": "year", "name": null,
                 "extra": [{"name": "genre", "isRequired": null, "options": [2024, "2023", null]}, {"isRequired": true}]},
                {"type": "movie"},
                {"type": "movie", "id": "year", "name": "Repeated"},
                {"type": "series", "id": "year", "extraRequired": null}
            ]
        }))
        .expect("loose manifest");
        assert_eq!(manifest.behavior_hints, ManifestBehaviorHints::default());
        assert_eq!(manifest.types, ["movie"]);
        assert_eq!(
            manifest.id_prefixes.as_deref(),
            Some(&["tt".to_owned()][..])
        );
        assert_eq!(manifest.resources.len(), 2, "the number is skipped");
        assert!(
            manifest.supports("meta", "movie", "tt1") && !manifest.supports("meta", "movie", "x1"),
            "null prefixes fall back to the manifest's"
        );
        let catalogs: Vec<_> = manifest
            .catalogs
            .iter()
            .map(|c| (c.kind.as_str(), c.id.as_str()))
            .collect();
        assert_eq!(
            catalogs,
            [("movie", "year"), ("series", "year")],
            "the catalog without an id and the repeated one are dropped"
        );
        let year = &manifest.catalogs[0];
        assert_eq!(year.name, None);
        assert_eq!(year.extra.len(), 1, "the extra without a name is dropped");
        assert!(!year.requires_extra());
        assert_eq!(year.extra_options("genre"), ["2024", "2023"]);

        let hints: Manifest = serde_json::from_value(serde_json::json!({
            "id": "y", "version": "1", "name": "Y",
            "behaviorHints": {"configurable": null, "configurationRequired": "true", "adult": 1}
        }))
        .expect("loose hints");
        assert!(!hints.behavior_hints.configurable);
        assert!(hints.behavior_hints.configuration_required);
        assert!(!hints.behavior_hints.adult);
    }

    #[test]
    fn tells_which_extras_a_catalog_needs() {
        let catalog: CatalogDescriptor = serde_json::from_value(serde_json::json!({
            "type": "movie", "id": "search",
            "extra": [{"name": "search", "isRequired": true}, {"name": "genre"}]
        }))
        .expect("valid");
        assert!(catalog.requires_extra());
        assert!(!catalog.requires_extra_other_than("search"));
        let narrow: CatalogDescriptor = serde_json::from_value(serde_json::json!({
            "type": "movie", "id": "search", "extraSupported": ["search", "genre"],
            "extraRequired": ["search", "genre"]
        }))
        .expect("valid");
        assert!(narrow.requires_extra_other_than("search"));
    }

    #[test]
    fn rejects_manifest_without_identity() {
        let result: Result<Manifest, _> = serde_json::from_value(serde_json::json!({"name": "X"}));
        assert!(result.is_err());
    }
}
