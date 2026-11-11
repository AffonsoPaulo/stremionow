// SPDX-License-Identifier: GPL-3.0-or-later
//! Bindings that expose the core to the app interfaces.
//!
//! Everything exported here is turned into Kotlin by UniFFI. Keep this crate a thin layer:
//! protocol and storage logic live in the domain crates, which this one wires together.

// The UniFFI scaffolding generates `extern "C"` entry points, which require `unsafe`.
#![allow(unsafe_code)]

mod core;
mod records;

pub use crate::core::StremioCore;
pub use crate::records::*;

uniffi::setup_scaffolding!();

/// Version of the core library.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_matches_package() {
        assert_eq!(core_version(), env!("CARGO_PKG_VERSION"));
    }
}
