// SPDX-License-Identifier: GPL-3.0-or-later
//! Serves torrents to other devices without the app, for testing pairing and streaming.
//!
//! ```text
//! cargo run -p stremionow-torrent --features dev-loopback --example serve -- [--lan] [NAME]
//! ```
//!
//! By default it listens on 127.0.0.1 only, which the Android emulator reaches as
//! `10.0.2.2`: pair the emulator with the address `10.0.2.2` and the code printed here. With
//! `--lan` it listens on the home-network addresses and announces itself, as the apps do.
//!
//! It keeps a pairing code on screen (a new one when the last expires or is used) and prints
//! the paired devices. Its state, TLS private key included, is kept in plain text in the
//! system temp folder under `stremionow-serve-example`: this is a test tool. Stop it with
//! Ctrl-C.

use std::error::Error;
use std::time::Duration;

use stremionow_torrent::{LanConfig, ServerConfig, StreamServer};

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let mut lan = false;
    let mut name = "StremioNow test server".to_owned();
    for arg in std::env::args().skip(1) {
        if arg == "--lan" {
            lan = true;
        } else {
            name = arg;
        }
    }
    let folder = std::env::temp_dir().join("stremionow-serve-example");
    std::fs::create_dir_all(&folder)?;
    let state_file = folder.join("state.json");
    let saved_state = std::fs::read_to_string(&state_file).ok();

    let stream = StreamServer::start(ServerConfig {
        cache_dir: folder.join("cache"),
        max_cache_bytes: 2 << 30,
    })
    .await?;
    let config = LanConfig {
        device_name: name,
        saved_state,
        on_state_change: Box::new(move |state| {
            if let Err(error) = std::fs::write(&state_file, state) {
                eprintln!("cannot save the state: {error}");
            }
        }),
    };
    let server = if lan {
        stream.serve_lan(config).await?
    } else {
        stream.serve_loopback(config).await?
    };

    let mut shown = String::new();
    loop {
        let mut status = server.status();
        if status.pairing.is_none() {
            server.new_pairing_code()?;
            status = server.status();
        }
        let addresses: Vec<String> = status
            .addresses
            .iter()
            .map(|address| format!("{address}:{}", status.port))
            .collect();
        let devices: Vec<String> = status
            .devices
            .iter()
            .map(|device| {
                let streaming = if device.streaming { " (streaming)" } else { "" };
                format!("{}{streaming}", device.name)
            })
            .collect();
        let line = format!(
            "{} on {} | code {} | paired: {}",
            status.device_name,
            addresses.join(", "),
            status
                .pairing
                .map(|pairing| pairing.code)
                .unwrap_or_default(),
            if devices.is_empty() {
                "none".to_owned()
            } else {
                devices.join(", ")
            }
        );
        if line != shown {
            println!("{line}");
            shown = line;
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}
