// SPDX-License-Identifier: GPL-3.0-or-later
//! Client for Stremio addons: manifests, catalogs, metadata, streams and subtitles, and the
//! lists of addons to discover.

mod catalog;
mod client;

pub use catalog::{AddonList, DISCOVER_SOURCE, DiscoverableAddon, discoverable};
pub use client::{AddonClient, AddonError, InstalledAddon};
