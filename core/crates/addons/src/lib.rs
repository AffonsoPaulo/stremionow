// SPDX-License-Identifier: GPL-3.0-or-later
//! Client for Stremio addons: manifests, catalogs, metadata, streams and subtitles, and the
//! lists of addons to discover.

mod catalog;
mod client;
mod fingerprint;
mod icons;

pub use catalog::{AddonList, DISCOVER_SOURCE, DiscoverableAddon, discoverable};
pub use client::{AddonClient, AddonError, InstalledAddon, parse_json, without_bom};
pub use fingerprint::manifest_fingerprint;
pub use icons::{IconClient, MAX_ICON_BYTES, icon_host, is_icon_image};
