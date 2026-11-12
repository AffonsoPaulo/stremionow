// SPDX-License-Identifier: GPL-3.0-or-later
//! Serving to other devices: one device downloads torrents for the others on the home
//! network (ADR 0008).
//!
//! - `net`: which interfaces and addresses may be used.
//! - `tls`, `pairing`, `clients`: the server's certificate, pairing codes and paired devices.
//! - `server`: the API paired devices use.
//! - `discovery`: mDNS announcements and browsing.
//! - `client`, `relay`: pairing from the other side, and streaming through the paired server.

mod client;
mod clients;
mod discovery;
mod net;
mod pairing;
mod relay;
mod server;
mod tls;
mod wire;

pub use client::{PairError, RemoteError, RemoteHealth, RemoteServer, pair};
pub use discovery::{DiscoveredServer, discover};
pub(crate) use relay::RemoteTorrent;
pub use relay::TorrentRoute;
pub(crate) use server::DEFAULT_PORT;
pub use server::{LanConfig, LanServer, LanStatus, PairedDevice, PairingCode};
