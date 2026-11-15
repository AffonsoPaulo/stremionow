// SPDX-License-Identifier: GPL-3.0-or-later
//! Streams a torrent through the local server and prints its progress.
//!
//! ```text
//! cargo run -p stremionow-torrent --example stream -- [INFO_HASH] [FILE_INDEX]
//! curl -r 0-1023 -o first-kib.bin <printed URL>
//! ```
//!
//! Without arguments it streams Sintel (CC BY 3.0, Blender Foundation), the WebTorrent demo
//! torrent. Stop it with Ctrl-C; downloaded data stays in the system temp folder under
//! `stremionow-stream-example`.

use std::error::Error;
use std::time::Duration;

use stremionow_torrent::{ServerConfig, StreamServer};

const SINTEL: &str = "08ada5a7a6183aae1e09d831df6748d566095a10";

/// Public trackers in the `sources` format Stremio addons use.
const TRACKERS: &[&str] = &[
    "tracker:udp://tracker.opentrackr.org:1337/announce",
    "tracker:udp://open.stealth.si:80/announce",
    "tracker:udp://tracker.torrent.eu.org:451/announce",
    "tracker:udp://exodus.desync.com:6969/announce",
    "tracker:udp://explodie.org:6969/announce",
    "tracker:udp://tracker.leechers-paradise.org:6969/announce",
    "dht:08ada5a7a6183aae1e09d831df6748d566095a10",
];

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let mut args = std::env::args().skip(1);
    let info_hash = args.next().unwrap_or_else(|| SINTEL.to_owned());
    let file_idx = args.next().map(|index| index.parse()).transpose()?;

    let server = StreamServer::start(ServerConfig {
        cache_dir: std::env::temp_dir().join("stremionow-stream-example"),
        max_cache_bytes: 2 << 30,
    })
    .await?;
    println!("fetching metadata for {info_hash}...");
    let trackers: Vec<String> = TRACKERS.iter().map(|&tracker| tracker.to_owned()).collect();
    let url = server
        .torrent_url(&info_hash, file_idx, None, &trackers)
        .await?;
    println!("stream URL: {url}");

    loop {
        if let Some(status) = server.status(&info_hash) {
            println!(
                "{:?}: {} / {} bytes of {}, {} peers, {} KiB/s",
                status.state,
                status.downloaded_bytes,
                status.file_size,
                status.file_name.as_deref().unwrap_or("?"),
                status.peers,
                status.download_speed / 1024,
            );
        }
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
}
