// SPDX-License-Identifier: GPL-3.0-or-later
//! Finding servers with mDNS and DNS-SD (`_stremionow._tcp`).
//!
//! Announcements carry only the server's random id and name; nothing in them is trusted.
//! Pairing checks who is really answering, and a paired client checks the pinned
//! certificate on every connection. Both announcing and browsing stay on home-network
//! interfaces, so queries never go out over a VPN or a mobile network.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};

use mdns_sd::{IfKind, IfPredicate, ServiceDaemon, ServiceEvent, ServiceInfo};

use super::net;

/// The DNS-SD service type.
pub(crate) const SERVICE_TYPE: &str = "_stremionow._tcp.local.";

/// A server found on the network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredServer {
    /// The name the server's user sees for it, such as "Living room PC".
    pub name: String,
    /// The server's random id, the same after it moves to another address.
    pub server_id: String,
    pub address: SocketAddr,
}

/// A daemon that only uses home-network interfaces.
fn daemon() -> Option<ServiceDaemon> {
    let daemon = ServiceDaemon::new()
        .inspect_err(|error| log::warn!("mdns: cannot start: {error}"))
        .ok()?;
    let lan = IfPredicate::new(|interface| {
        net::is_lan_interface(
            &interface.name,
            interface.ip(),
            interface.is_oper_up(),
            interface.is_p2p,
        )
    });
    let configured = daemon
        .disable_interface(IfKind::All)
        .and_then(|()| daemon.enable_interface(IfKind::Predicate(lan)));
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
    /// Announces `name` and `server_id` on `port`. The addresses follow the interfaces, so a
    /// new address after a DHCP renewal is announced by itself. Failing to announce is not
    /// fatal: clients can still enter the address by hand.
    pub(crate) fn start(name: &str, server_id: &str, port: u16) -> Option<Self> {
        let daemon = daemon()?;
        let properties = [("id", server_id), ("name", name), ("v", "1")];
        let info = ServiceInfo::new(
            SERVICE_TYPE,
            server_id,
            &format!("{server_id}.local."),
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
    let Some(daemon) = daemon() else {
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
        let (Some(id), Some(name)) = (
            properties.get_property_val_str("id"),
            properties.get_property_val_str("name"),
        ) else {
            continue;
        };
        if exclude == Some(id) || crate::secret::from_hex(id).is_none_or(|id| id.len() != 16) {
            continue;
        }
        let mut addresses: Vec<IpAddr> = service
            .addresses
            .iter()
            .map(mdns_sd::ScopedIp::to_ip_addr)
            .filter(|ip| net::is_lan_ip(*ip))
            .collect();
        addresses.sort_unstable();
        let Some(ip) = addresses.first() else {
            continue;
        };
        found.insert(
            id.to_owned(),
            DiscoveredServer {
                name: super::clients::clean_name(name),
                server_id: id.to_owned(),
                address: SocketAddr::new(*ip, service.port),
            },
        );
    }
    let _ = daemon.stop_browse(SERVICE_TYPE);
    let _ = daemon.shutdown();
    let mut servers: Vec<DiscoveredServer> = found.into_values().collect();
    servers.sort_by(|a, b| a.name.cmp(&b.name).then(a.server_id.cmp(&b.server_id)));
    servers
}
