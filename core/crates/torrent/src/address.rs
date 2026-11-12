// SPDX-License-Identifier: GPL-3.0-or-later
//! Which addresses are not the public internet.
//!
//! One set of rules for the relay, trackers and serving to other devices, so that no path
//! treats as public an address another one would refuse.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// Whether `ip` is on this device or a network that is not the public internet: loopback,
/// private, shared (carrier-grade NAT), link-local, unique-local, unspecified, broadcast,
/// multicast and reserved addresses, and IPv6 forms that embed one of those.
pub(crate) fn is_private_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => is_private_v4(ip),
        IpAddr::V6(ip) => is_private_v6(ip),
    }
}

fn is_private_v4(ip: Ipv4Addr) -> bool {
    let [first, second, ..] = ip.octets();
    ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_unspecified()
        || ip.is_broadcast()
        || ip.is_multicast()
        || first == 0
        || first >= 240
        || (first == 100 && (64..128).contains(&second))
}

fn is_private_v6(ip: Ipv6Addr) -> bool {
    let segments = ip.segments();
    ip.is_loopback()
        || ip.is_unspecified()
        || ip.is_multicast()
        || (segments[0] & 0xfe00) == 0xfc00
        || (segments[0] & 0xffc0) == 0xfe80
        || ip.to_ipv4_mapped().is_some_and(is_private_v4)
        || embedded_v4(ip).is_some_and(is_private_v4)
}

/// The IPv4 address inside a NAT64 (`64:ff9b::/96`) or 6to4 (`2002::/16`) address.
fn embedded_v4(ip: Ipv6Addr) -> Option<Ipv4Addr> {
    let octets = ip.octets();
    let segments = ip.segments();
    if segments[..6] == [0x64, 0xff9b, 0, 0, 0, 0] {
        return Some(Ipv4Addr::new(
            octets[12], octets[13], octets[14], octets[15],
        ));
    }
    if segments[0] == 0x2002 {
        return Some(Ipv4Addr::new(octets[2], octets[3], octets[4], octets[5]));
    }
    None
}

/// Whether `url` points at this device or a private network, judging by its text alone:
/// IP literals and local names. Names that resolve to private addresses are caught when
/// they are resolved (the relay's `PublicResolver`, `magnet::public_trackers`).
///
/// For schemes the URL standard does not know, such as `udp`, the host stays text, so IP
/// literals are parsed here, and numeric forms the system resolver would also read as an
/// address (`2130706433`, `0x7f.1`, `127.1`) are refused outright: no real host name ends
/// in a number.
pub(crate) fn is_private_host(url: &url::Url) -> bool {
    match url.host() {
        Some(url::Host::Ipv4(ip)) => is_private_v4(ip),
        Some(url::Host::Ipv6(ip)) => is_private_v6(ip),
        Some(url::Host::Domain(name)) => {
            let name = name.trim_end_matches('.').to_ascii_lowercase();
            let bare = name.trim_start_matches('[').trim_end_matches(']');
            if let Ok(ip) = bare.parse::<IpAddr>() {
                return is_private_ip(ip);
            }
            let last = name.rsplit('.').next().unwrap_or_default();
            let numeric = last.is_empty()
                || last.bytes().all(|b| b.is_ascii_digit())
                || last.starts_with("0x");
            numeric
                || name == "localhost"
                || [".localhost", ".local", ".lan", ".home.arpa", ".internal"]
                    .iter()
                    .any(|suffix| name.ends_with(suffix))
        }
        None => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recognizes_private_hosts() {
        for private in [
            "http://127.0.0.1/x",
            "http://10.0.0.2/x",
            "http://192.168.1.10/x",
            "http://169.254.1.1/x",
            "http://[::1]/x",
            "http://[fd00::1]/x",
            "http://[fe80::1]/x",
            "http://localhost/x",
            "http://nas.local/x",
            "http://router.lan/x",
            "http://100.64.0.1/x",
            "http://0.1.2.3/x",
            "http://224.0.0.251/x",
            "http://239.255.255.250/x",
            "http://240.0.0.1/x",
            "http://[ff02::fb]/x",
            "http://[::ffff:192.168.1.1]/x",
            "http://[::ffff:169.254.0.1]/x",
            "http://[64:ff9b::c0a8:101]/x",
            "http://[2002:c0a8:101::1]/x",
            // Opaque hosts: the URL standard leaves these as text.
            "udp://127.0.0.1:6969/announce",
            "udp://192.168.1.1:80/announce",
            "udp://[::1]:80/announce",
            "udp://2130706433:80/announce",
            "udp://0x7f.1:80/announce",
            "udp://127.1:80/announce",
            "udp://localhost:80/announce",
            "udp://224.0.0.1:80/announce",
        ] {
            assert!(
                is_private_host(&url::Url::parse(private).expect("url")),
                "{private}"
            );
        }
        for public in [
            "https://cdn.example.org/x",
            "http://8.8.8.8/x",
            "http://[2606:4700::1]/x",
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://8.8.8.8:1337/announce",
            "udp://tracker.example.org.:1337/announce",
        ] {
            assert!(
                !is_private_host(&url::Url::parse(public).expect("url")),
                "{public}"
            );
        }
    }
}
