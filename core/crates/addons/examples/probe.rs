// SPDX-License-Identifier: GPL-3.0-or-later
//! Fetches a real addon manifest (or any other addon URL) and reports how long it took; used to diagnose network
//! problems on devices. The client is created outside any Tokio runtime, as in the app.
//!
//! ```text
//! cargo run -p stremionow-addons --example probe -- https://v3-cinemeta.strem.io/manifest.json
//! ```
//!
//! On Android, build it with `cargo ndk -t x86_64 build --release --example probe`, push the
//! binary to `/data/local/tmp` and run it with `adb shell`.

use std::time::{Duration, Instant};

fn main() {
    let url = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "https://v3-cinemeta.strem.io/manifest.json".into());
    let client = stremionow_addons::AddonClient::new(Duration::from_secs(15)).expect("client");
    let runtime = tokio::runtime::Runtime::new().expect("runtime");
    let started = Instant::now();
    if url.ends_with("/manifest.json") {
        match runtime.block_on(client.install(&url)) {
            Ok(addon) => println!("ok in {:?}: {}", started.elapsed(), addon.manifest.name),
            Err(error) => println!("error after {:?}: {error}", started.elapsed()),
        }
    } else {
        match runtime.block_on(client.get_bytes(&url)) {
            Ok(body) => println!("ok in {:?}: {} bytes", started.elapsed(), body.len()),
            Err(error) => println!("error after {:?}: {error}", started.elapsed()),
        }
    }
}
