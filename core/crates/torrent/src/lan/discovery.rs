// SPDX-License-Identifier: GPL-3.0-or-later
//! Finding servers with mDNS and DNS-SD (`_stremionow._tcp`).
//!
//! An announcement carries the name the user chose for the server (neutral by default) and
//! nothing that stays the same between runs: the instance and host names are random each
//! time serving starts, and instead of the server's id it carries a random nonce with a tag,
//! an HMAC of the nonce keyed by the id. A paired client, which learned the id when pairing,
//! recognizes its server by the tag; anyone else sees a different, unlinkable announcement
//! every time.
//!
//! Nothing in an announcement is trusted: pairing and the pinned certificate decide who is
//! answering. Announcing happens only on the interfaces being served, and browsing only on
//! home-network interfaces, so queries never leave through a VPN or a mobile network.

use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use mdns_sd::{IfKind, IfPredicate, ServiceDaemon, ServiceEvent, ServiceInfo};
use ring::hmac;

use super::net;
use crate::secret;

/// The DNS-SD service type.
pub(crate) const SERVICE_TYPE: &str = "_stremionow._tcp.local.";

/// Hex digits of the tag: 64 bits, plenty to tell a handful of servers apart.
const TAG_HEX: usize = 16;

/// A server found on the network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredServer {
    /// The name its user chose, such as "Living room PC".
    pub name: String,
    /// Every home-network address it announced, in a stable order. Try each in turn.
    pub addresses: Vec<SocketAddr>,
    nonce: String,
    tag: String,
}

impl DiscoveredServer {
    /// Whether this is the server with `server_id`, known from pairing.
    pub fn is_server(&self, server_id: &str) -> bool {
        secret::constant_time_eq(&tag(server_id, &self.nonce), &self.tag)
    }
}

/// The tag announced with `nonce` by the server with `server_id`.
fn tag(server_id: &str, nonce: &str) -> String {
    let key = hmac::Key::new(hmac::HMAC_SHA256, server_id.as_bytes());
    let mac = hmac::sign(&key, nonce.as_bytes());
    let mut text = secret::to_hex(mac.as_ref());
    text.truncate(TAG_HEX);
    text
}

/// A daemon limited to the interfaces `allowed` accepts.
fn daemon(
    allowed: impl Fn(&if_addrs::Interface) -> bool + Send + Sync + 'static,
) -> Option<ServiceDaemon> {
    let daemon = ServiceDaemon::new()
        .inspect_err(|error| log::warn!("mdns: cannot start: {error}"))
        .ok()?;
    let configured = daemon
        .disable_interface(IfKind::All)
        .and_then(|()| daemon.enable_interface(IfKind::Predicate(IfPredicate::new(allowed))));
    if let Err(error) = configured {
        log::warn!("mdns: cannot limit interfaces: {error}");
        let _ = daemon.shutdown();
        return None;
    }
    Some(daemon)
}

/// The server's announcement; withdrawn when dropped.
pub(crate) struct Announcement {
    daemon: ServiceDaemon,
    fullname: String,
}

impl Announcement {
    /// Announces `name` on `port`, on the interfaces whose addresses are in `served`. The
    /// announced addresses follow those interfaces. Failing to announce is not fatal:
    /// clients can still enter the address by hand.
    pub(crate) fn start(
        name: &str,
        server_id: &str,
        port: u16,
        served: Arc<Mutex<HashSet<IpAddr>>>,
    ) -> Option<Self> {
        let daemon = daemon(move |interface| {
            served
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .contains(&interface.ip())
        })?;
        let nonce = secret::random_hex().ok()?;
        let tag = tag(server_id, &nonce);
        let properties = [
            ("name", name),
            ("n", nonce.as_str()),
            ("t", tag.as_str()),
            ("v", "2"),
        ];
        let info = ServiceInfo::new(
            SERVICE_TYPE,
            &nonce,
            &format!("{nonce}.local."),
            "",
            port,
            &properties[..],
        )
        .map(ServiceInfo::enable_addr_auto)
        .and_then(|info| {
            let fullname = info.get_fullname().to_owned();
            daemon.register(info).map(|()| fullname)
        });
        match info {
            Ok(fullname) => Some(Self { daemon, fullname }),
            Err(error) => {
                log::warn!("mdns: cannot announce: {error}");
                let _ = daemon.shutdown();
                None
            }
        }
    }
}

impl Drop for Announcement {
    fn drop(&mut self) {
        // Sends a goodbye, so browsers drop the server at once instead of at TTL expiry.
        let _ = self.daemon.unregister(&self.fullname);
        let _ = self.daemon.shutdown();
    }
}

/// Servers that answer within `timeout`, without the one whose id is `exclude` (this
/// device's own). On Android the app must hold a multicast lock meanwhile.
pub async fn discover(timeout: Duration, exclude: Option<String>) -> Vec<DiscoveredServer> {
    tokio::task::spawn_blocking(move || browse(timeout, exclude.as_deref()))
        .await
        .unwrap_or_default()
}

fn browse(timeout: Duration, exclude: Option<&str>) -> Vec<DiscoveredServer> {
    let Some(daemon) = daemon(|interface| {
        net::is_lan_interface(
            &interface.name,
            interface.ip(),
            interface.is_oper_up(),
            interface.is_p2p,
        )
    }) else {
        return Vec::new();
    };
    let events = match daemon.browse(SERVICE_TYPE) {
        Ok(events) => events,
        Err(error) => {
            log::warn!("mdns: cannot browse: {error}");
            let _ = daemon.shutdown();
            return Vec::new();
        }
    };
    let deadline = Instant::now() + timeout;
    let mut found: HashMap<String, DiscoveredServer> = HashMap::new();
    while let Some(left) = deadline.checked_duration_since(Instant::now()) {
        let Ok(event) = events.recv_timeout(left) else {
            break;
        };
        let ServiceEvent::ServiceResolved(service) = event else {
            continue;
        };
        let properties = &service.txt_properties;
        let (Some(name), Some(nonce), Some(tag)) = (
            properties.get_property_val_str("name"),
            properties.get_property_val_str("n"),
            properties.get_property_val_str("t"),
        ) else {
            continue;
        };
        if nonce.len() != 32 || tag.len() != TAG_HEX {
            continue;
        }
        let addresses = service
            .addresses
            .iter()
            .map(mdns_sd::ScopedIp::to_ip_addr)
            .filter(|ip| net::is_lan_ip(*ip))
            .map(|ip| SocketAddr::new(ip, service.port));
        // One server answers once per interface; keep every address it gave.
        let server = found
            .entry(nonce.to_owned())
            .or_insert_with(|| DiscoveredServer {
                name: super::clients::clean_name(name),
                addresses: Vec::new(),
                nonce: nonce.to_owned(),
                tag: tag.to_owned(),
            });
        server.addresses.extend(addresses);
        server.addresses.sort_unstable();
        server.addresses.dedup();
    }
    let _ = daemon.stop_browse(SERVICE_TYPE);
    let _ = daemon.shutdown();
    let mut servers: Vec<DiscoveredServer> = found
        .into_values()
        .filter(|server| !server.addresses.is_empty())
        .filter(|server| exclude.is_none_or(|id| !server.is_server(id)))
        .collect();
    servers.sort_by(|a, b| a.name.cmp(&b.name).then(a.addresses.cmp(&b.addresses)));
    servers
}

#[cfg(test)]
mod tests {
    use super::*;

    fn announced(server_id: &str, nonce: &str) -> DiscoveredServer {
        DiscoveredServer {
            name: "PC".to_owned(),
            addresses: vec!["192.168.1.20:47630".parse().expect("address")],
            nonce: nonce.to_owned(),
            tag: tag(server_id, nonce),
        }
    }

    #[test]
    fn paired_clients_recognize_their_server_and_others_cannot_link_it() {
        let id = "1".repeat(32);
        let first = announced(&id, &"a".repeat(32));
        let second = announced(&id, &"b".repeat(32));
        assert!(first.is_server(&id));
        assert!(second.is_server(&id));
        assert!(!first.is_server(&"2".repeat(32)));
        assert_ne!(first.tag, second.tag, "nothing stable across runs");
        assert_eq!(first.tag.len(), TAG_HEX);
    }
}
