// SPDX-License-Identifier: GPL-3.0-or-later
//! Which interfaces and addresses serving to other devices may use.
//!
//! The server listens on each home-network address of the device, never on `0.0.0.0`, so it
//! cannot end up reachable on a public interface, a mobile network or a VPN by accident;
//! `network` decides which interfaces are on the home network.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use super::network::Candidate;
use crate::TorrentError;

/// The network identity of loopback, for tests and the emulator harness.
#[cfg(any(test, feature = "dev-loopback"))]
pub(crate) const LOOPBACK_NETWORK: &str = "loopback";

/// Whether `ip` is a home-network address the server may listen on and clients may connect
/// to: the private IPv4 ranges of RFC 1918. Loopback, link-local, carrier-grade NAT (also used
/// by Tailscale), public addresses and all of IPv6 are left out.
pub(crate) fn is_lan_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => ip.is_private(),
        IpAddr::V6(_) => false,
    }
}

/// Whether an interface looks like the home network: up, not point-to-point, not a known
/// tunnel, modem or virtual adapter, and with a home-network address.
pub(crate) fn is_lan_interface(name: &str, ip: IpAddr, up: bool, point_to_point: bool) -> bool {
    up && !point_to_point && is_lan_ip(ip) && !super::network::is_excluded_name(name)
}

/// Who the server may listen on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum BindPolicy {
    /// Home-network addresses only: what the apps use.
    Lan,
    /// Loopback only, for tests and the emulator harness (`dev-loopback` feature). The Android
    /// emulator reaches the host's loopback as `10.0.2.2`, a private address its client
    /// accepts, so the client side needs no exception.
    #[cfg(any(test, feature = "dev-loopback"))]
    Loopback,
}

impl BindPolicy {
    /// Interfaces that may be served now, with the network each is on (see `network`).
    pub(crate) fn candidates(self, hint: Option<&str>) -> Vec<Candidate> {
        match self {
            Self::Lan => super::network::candidates(hint),
            #[cfg(any(test, feature = "dev-loopback"))]
            // The hint stands in for the network, so tests can move "between networks".
            Self::Loopback => vec![Candidate {
                address: Ipv4Addr::LOCALHOST,
                network: Some(crate::secret::to_hex(&super::tls::fingerprint(
                    hint.unwrap_or(LOOPBACK_NETWORK).as_bytes(),
                ))),
            }],
        }
    }

    /// Fails unless the policy allows listening on `ip`.
    pub(crate) fn check(self, ip: IpAddr) -> Result<Ipv4Addr, TorrentError> {
        let allowed = match self {
            Self::Lan => is_lan_ip(ip),
            #[cfg(any(test, feature = "dev-loopback"))]
            Self::Loopback => ip.is_loopback(),
        };
        match ip {
            IpAddr::V4(ip) if allowed => Ok(ip),
            _ => Err(TorrentError::NotLocalNetwork),
        }
    }

    /// Whether mDNS announces the server. Never for loopback, which no other device reaches.
    pub(crate) fn announces(self) -> bool {
        self == Self::Lan
    }
}

/// Whether a client may connect from `peer`: a home-network address, as the server's own.
/// A port forward or a misconfigured router could expose the listener to the internet;
/// connections from public, carrier-grade NAT (also Tailscale) and link-local addresses are
/// dropped. A router that forwards the port and also rewrites the source to its own address
/// would hide the internet behind a home address; pairing and tokens still stand then.
pub(crate) fn is_allowed_peer(policy: BindPolicy, peer: IpAddr) -> bool {
    match policy {
        BindPolicy::Lan => is_lan_ip(peer),
        #[cfg(any(test, feature = "dev-loopback"))]
        BindPolicy::Loopback => peer.is_loopback(),
    }
}

/// Parses what a user typed or discovery found as a server address: `192.168.1.20`,
/// `192.168.1.20:47630`, optionally with an `https://` prefix. Only home-network addresses
/// are accepted, plus loopback in tests and the emulator harness.
pub(crate) fn parse_server_address(text: &str) -> Result<SocketAddr, TorrentError> {
    let text = text.trim();
    let text = text.strip_prefix("https://").unwrap_or(text);
    let text = text.trim_end_matches('/');
    let address = match text.parse::<SocketAddr>() {
        Ok(address) => address,
        Err(_) => {
            let ip: IpAddr = text.parse().map_err(|_| TorrentError::NotLocalNetwork)?;
            SocketAddr::new(ip, super::DEFAULT_PORT)
        }
    };
    if is_lan_ip(address.ip())
        || cfg!(any(test, feature = "dev-loopback")) && address.ip().is_loopback()
    {
        Ok(address)
    } else {
        Err(TorrentError::NotLocalNetwork)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(text: &str) -> IpAddr {
        text.parse().expect("ip")
    }

    #[test]
    fn only_private_ipv4_is_lan() {
        for lan in ["10.0.0.5", "172.16.3.4", "172.31.255.1", "192.168.1.20"] {
            assert!(is_lan_ip(ip(lan)), "{lan}");
        }
        for other in [
            "127.0.0.1",
            "0.0.0.0",
            "255.255.255.255",
            "169.254.10.1",
            "100.64.0.1",
            "100.100.1.1",
            "172.32.0.1",
            "8.8.8.8",
            "::1",
            "fd00::1",
            "fe80::1",
            "2001:db8::1",
            "2606:4700::1",
        ] {
            assert!(!is_lan_ip(ip(other)), "{other}");
        }
    }

    #[test]
    fn tunnels_modems_and_virtual_adapters_are_skipped() {
        let lan = ip("192.168.1.20");
        for name in [
            "wlan0",
            "eth0",
            "en0",
            "Wi-Fi",
            "Ethernet",
            "Ethernet 2",
            "Local Area Connection",
        ] {
            assert!(is_lan_interface(name, lan, true, false), "{name}");
        }
        for name in [
            "rmnet_data0",
            "ccmni1",
            "tun0",
            "wg0",
            "utun3",
            "tailscale0",
            "docker0",
            "virbr0",
            "vEthernet (WSL)",
            "VirtualBox Host-Only Network",
            "VMware Network Adapter VMnet8",
            "Loopback Pseudo-Interface 1",
        ] {
            assert!(!is_lan_interface(name, lan, true, false), "{name}");
        }
        assert!(!is_lan_interface("wlan0", lan, false, false), "down");
        assert!(
            !is_lan_interface("wlan0", lan, true, true),
            "point-to-point"
        );
        assert!(
            !is_lan_interface("wlan0", ip("203.0.113.9"), true, false),
            "public"
        );
        assert!(
            !is_lan_interface("wlan0", ip("2001:db8::9"), true, false),
            "IPv6"
        );
    }

    #[test]
    fn public_and_unspecified_binds_are_refused() {
        for address in [
            "0.0.0.0",
            "8.8.8.8",
            "203.0.113.9",
            "::",
            "2001:db8::1",
            "127.0.0.1",
        ] {
            assert!(
                matches!(
                    BindPolicy::Lan.check(ip(address)),
                    Err(TorrentError::NotLocalNetwork)
                ),
                "{address}"
            );
        }
        assert!(BindPolicy::Lan.check(ip("192.168.0.7")).is_ok());
        assert!(BindPolicy::Loopback.check(ip("127.0.0.1")).is_ok());
        assert!(BindPolicy::Loopback.check(ip("192.168.0.7")).is_err());
        assert!(!BindPolicy::Loopback.announces());
    }

    #[test]
    fn candidates_are_all_private() {
        for candidate in BindPolicy::Lan.candidates(None) {
            assert!(candidate.address.is_private(), "{candidate:?}");
        }
        let loopback = BindPolicy::Loopback.candidates(None);
        assert_eq!(loopback.len(), 1);
        assert_eq!(loopback[0].address, Ipv4Addr::LOCALHOST);
    }

    #[test]
    fn only_home_network_peers_may_connect() {
        let lan = BindPolicy::Lan;
        assert!(is_allowed_peer(lan, ip("192.168.1.30")));
        assert!(is_allowed_peer(lan, ip("10.0.0.4")));
        for refused in [
            "127.0.0.1",
            "8.8.8.8",
            "100.64.0.7",
            "100.101.102.103",
            "169.254.3.4",
            "fd00::1",
            "2606:4700::1",
        ] {
            assert!(!is_allowed_peer(lan, ip(refused)), "{refused}");
        }
        assert!(is_allowed_peer(BindPolicy::Loopback, ip("127.0.0.1")));
        assert!(!is_allowed_peer(BindPolicy::Loopback, ip("192.168.1.30")));
    }

    #[test]
    fn server_addresses_parse_with_or_without_port() {
        assert_eq!(
            parse_server_address(" 192.168.1.20 ").expect("address"),
            SocketAddr::new(ip("192.168.1.20"), super::super::DEFAULT_PORT)
        );
        assert_eq!(
            parse_server_address("https://10.0.2.2:5000/").expect("address"),
            "10.0.2.2:5000".parse().expect("address")
        );
        for refused in [
            "8.8.8.8",
            "example.org",
            "",
            "[2001:db8::1]:80",
            "169.254.1.1",
        ] {
            assert!(parse_server_address(refused).is_err(), "{refused}");
        }
    }
}
