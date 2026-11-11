// SPDX-License-Identifier: GPL-3.0-or-later
//! The addon manifest: identity, resources and catalogs.

use serde::{Deserialize, Deserializer, Serialize};

/// Describes an addon and what it can provide.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    /// Reverse-domain identifier, for example `com.linvo.cinemeta`.
    pub id: String,
    pub version: String,
    pub name: String,
    #[serde(default, deserialize_with = "null_as_default")]
    pub description: String,
    #[serde(default)]
    pub logo: Option<String>,
    #[serde(default, deserialize_with = "null_as_default")]
    pub resources: Vec<ResourceDescriptor>,
    /// Content types the addon handles, such as `movie` and `series`.
    #[serde(default, deserialize_with = "null_as_default")]
    pub types: Vec<String>,
    #[serde(default, deserialize_with = "null_as_default")]
    pub catalogs: Vec<CatalogDescriptor>,
    /// Item id prefixes the addon understands, for example `tt` for IMDb ids.
    #[serde(default)]
    pub id_prefixes: Option<Vec<String>>,
    #[serde(default)]
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
    #[serde(default)]
    pub configurable: bool,
    /// The addon cannot be used before it is configured.
    #[serde(default)]
    pub configuration_required: bool,
    /// The addon serves adult content.
    #[serde(default)]
    pub adult: bool,
}

/// A resource the addon serves. Manifests list either a bare name or a detailed object.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ResourceDescriptor {
    Short(String),
    Full {
        name: String,
        #[serde(default)]
        types: Option<Vec<String>>,
        #[serde(default, rename = "idPrefixes")]
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
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "null_as_default")]
    pub extra: Vec<ExtraProperty>,
    /// Older manifests list supported extras by name only.
    #[serde(default, deserialize_with = "null_as_default")]
    pub extra_supported: Vec<String>,
    #[serde(default, deserialize_with = "null_as_default")]
    pub extra_required: Vec<String>,
}

impl CatalogDescriptor {
    /// Whether the catalog accepts the extra property `name`, such as `search` or `genre`.
    pub fn supports_extra(&self, name: &str) -> bool {
        self.extra.iter().any(|e| e.name == name) || self.extra_supported.iter().any(|e| e == name)
    }

    /// Whether the catalog needs an extra property before it returns anything.
    pub fn requires_extra(&self) -> bool {
        self.extra.iter().any(|e| e.is_required) || !self.extra_required.is_empty()
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
    #[serde(default)]
    pub is_required: bool,
    #[serde(default, deserialize_with = "null_as_default")]
    pub options: Vec<String>,
}

/// Treats an explicit `null` like a missing field.
pub(crate) fn null_as_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: Deserializer<'de>,
    T: Default + Deserialize<'de>,
{
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
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
    fn rejects_manifest_without_identity() {
        let result: Result<Manifest, _> = serde_json::from_value(serde_json::json!({"name": "X"}));
        assert!(result.is_err());
    }
}
