// SPDX-License-Identifier: GPL-3.0-or-later
//! Types of the Stremio addon protocol and account API.
//!
//! The protocol is documented at <https://github.com/Stremio/stremio-addon-sdk/tree/master/docs>.
//! Addons are loosely written, so every optional field tolerates being absent or `null`, and
//! unknown fields are ignored.

mod addon_catalog;
mod lenient;
mod manifest;
mod resources;
mod transport;

pub use addon_catalog::{AddonCatalogResponse, AddonDescriptor};
pub use manifest::{CatalogDescriptor, ExtraProperty, Manifest, ResourceDescriptor};
pub use resources::{
    CatalogResponse, Link, MetaDetail, MetaPreview, MetaResponse, Stream, StreamBehaviorHints,
    StreamResponse, Subtitle, SubtitlesResponse, Trailer, TrailerStream, Video,
};
pub use transport::{AddonUrl, Extra, TransportError};
