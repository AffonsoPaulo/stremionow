// SPDX-License-Identifier: GPL-3.0-or-later
//! Generates foreign-language bindings for the core, for example:
//!
//! ```text
//! cargo run -p uniffi-bindgen -- generate --library <libstremionow_ffi.so> --language kotlin --out-dir <dir>
//! ```

fn main() {
    uniffi::uniffi_bindgen_main()
}
