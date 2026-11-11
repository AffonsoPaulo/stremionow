// SPDX-License-Identifier: GPL-3.0-or-later
//! Client for Stremio addons: manifests, catalogs, metadata, streams and subtitles.

mod client;

pub use client::{AddonClient, AddonError, InstalledAddon};
