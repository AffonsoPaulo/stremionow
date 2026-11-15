// SPDX-License-Identifier: GPL-3.0-or-later
//! Fingerprints of what a manifest lets an addon do, so an install can be held to what the
//! user confirmed.

use serde::Serialize;
use stremionow_protocol::{CatalogDescriptor, Manifest, ResourceDescriptor};

/// The parts of a manifest that decide what an addon serves and sees.
#[derive(Serialize)]
struct Shape<'a> {
    id: &'a str,
    resources: &'a [ResourceDescriptor],
    types: &'a [String],
    catalogs: Vec<CatalogShape<'a>>,
    id_prefixes: Option<&'a [String]>,
    configurable: bool,
    configuration_required: bool,
    adult: bool,
}

/// What a catalog is asked with: its address and the extras it takes.
#[derive(Serialize)]
struct CatalogShape<'a> {
    kind: &'a str,
    id: &'a str,
    extra: Vec<(&'a str, bool)>,
    extra_supported: &'a [String],
    extra_required: &'a [String],
}

impl<'a> From<&'a CatalogDescriptor> for CatalogShape<'a> {
    fn from(catalog: &'a CatalogDescriptor) -> Self {
        Self {
            kind: &catalog.kind,
            id: &catalog.id,
            extra: catalog
                .extra
                .iter()
                .map(|extra| (extra.name.as_str(), extra.is_required))
                .collect(),
            extra_supported: &catalog.extra_supported,
            extra_required: &catalog.extra_required,
        }
    }
}

/// SHA-256 (hex) of the manifest's id, resources, types, catalogs, id prefixes and behavior
/// hints. Name, version, description and artwork are left out: they change between releases,
/// and in the snapshots addon lists keep, without changing what the addon does. So are the
/// catalogs' names and the options of their extras: addons that list this year's titles or
/// the genres they currently have rewrite them all the time.
pub fn manifest_fingerprint(manifest: &Manifest) -> String {
    let hints = &manifest.behavior_hints;
    let shape = Shape {
        id: &manifest.id,
        resources: &manifest.resources,
        types: &manifest.types,
        catalogs: manifest.catalogs.iter().map(CatalogShape::from).collect(),
        id_prefixes: manifest.id_prefixes.as_deref(),
        configurable: hints.configurable,
        configuration_required: hints.configuration_required,
        adult: hints.adult,
    };
    // Serializing borrowed, already parsed data cannot fail.
    let bytes = serde_json::to_vec(&shape).unwrap_or_default();
    let digest = ring::digest::digest(&ring::digest::SHA256, &bytes);
    digest
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest(extra: serde_json::Value) -> Manifest {
        let mut base = serde_json::json!({
            "id": "a", "version": "1.0.0", "name": "A", "description": "One",
            "resources": ["stream"], "types": ["movie"],
            "catalogs": [{"type": "movie", "id": "top"}]
        });
        if let (Some(target), Some(fields)) = (base.as_object_mut(), extra.as_object()) {
            target.extend(fields.clone());
        }
        serde_json::from_value(base).expect("valid")
    }

    #[test]
    fn ignores_presentation_and_catches_behavior() {
        let original = manifest_fingerprint(&manifest(serde_json::json!({})));
        assert_eq!(original.len(), 64);
        let cosmetic = manifest(serde_json::json!({
            "version": "1.1.0", "name": "A+", "description": "Two", "logo": "https://x/l.png"
        }));
        assert_eq!(manifest_fingerprint(&cosmetic), original);

        let genres = |name: &str, options: serde_json::Value| {
            manifest_fingerprint(&manifest(serde_json::json!({"catalogs": [
                {"type": "movie", "id": "top", "name": name,
                 "extra": [{"name": "genre", "options": options}]}
            ]})))
        };
        assert_eq!(
            genres("Top", serde_json::json!(["Drama"])),
            genres("Top 2026", serde_json::json!(["Drama", "Comedy", 2026])),
            "catalog names and extra options are cosmetic"
        );
        assert_ne!(
            genres("Top", serde_json::json!([])),
            original,
            "a new extra is not"
        );

        for change in [
            serde_json::json!({"catalogs": [{"type": "series", "id": "top"}]}),
            serde_json::json!({"catalogs": [{"type": "movie", "id": "top", "extraRequired": ["genre"]}]}),
            serde_json::json!({"catalogs": [{"type": "movie", "id": "top", "extra": [{"name": "search", "isRequired": true}]}]}),
            serde_json::json!({"id": "b"}),
            serde_json::json!({"resources": ["stream", "subtitles"]}),
            serde_json::json!({"types": ["movie", "series"]}),
            serde_json::json!({"catalogs": []}),
            serde_json::json!({"idPrefixes": ["tt"]}),
            serde_json::json!({"behaviorHints": {"adult": true}}),
            serde_json::json!({"behaviorHints": {"configurationRequired": true}}),
        ] {
            assert_ne!(
                manifest_fingerprint(&manifest(change.clone())),
                original,
                "{change}"
            );
        }
    }
}
