// SPDX-License-Identifier: GPL-3.0-or-later
//! Bindings that expose the core to the app interfaces.

/// Version of the core library, shown on the phase 0 placeholder screen.
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
