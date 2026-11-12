// SPDX-License-Identifier: GPL-3.0-or-later
//! Which network the device is on, interface by interface.
//!
//! Serving is tied to the network where the user turned it on: an RFC 1918 address alone
//! does not make a network the user's (a café, a hotel, an office all hand them out). Each
//! candidate interface is identified by its default gateway: by the gateway's MAC address
//! where the system exposes it (Windows, Linux, macOS), otherwise by what the app reports
//! about the network (Android hides both the ARP table and the Wi-Fi name from apps), and
//! only interfaces whose identity matches the remembered home network are served.
//!
//! Candidates are physical Ethernet and Wi-Fi interfaces with a home-network address and a
//! default gateway: on Windows by adapter type (6 and 71) and description, on Linux by sysfs
//! (a device behind it, not a bridge), and everywhere by name, leaving out modems, tunnels,
//! VPNs, bridges, hotspots and Wi-Fi Direct groups.

use std::net::{IpAddr, Ipv4Addr};

use super::net;

/// An interface that may be served, if it is on the home network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Candidate {
    pub(crate) address: Ipv4Addr,
    /// Identifies the network: hashed gateway MAC, app hint or gateway address. `None` when
    /// nothing identifies it, and then it is never served.
    pub(crate) network: Option<String>,
}

/// The candidate interfaces right now. `hint` is what the app knows about its current
/// network (Android), used for interfaces whose gateway MAC is unknown.
pub(crate) fn candidates(hint: Option<&str>) -> Vec<Candidate> {
    let mut found: Vec<Candidate> = platform::interfaces()
        .into_iter()
        .filter(|interface| net::is_lan_ip(IpAddr::V4(interface.address)))
        .map(|interface| Candidate {
            address: interface.address,
            network: identity(&interface, hint),
        })
        .collect();
    found.sort_by_key(|candidate| candidate.address);
    found.dedup_by_key(|candidate| candidate.address);
    found
}

/// What the platform layer reports about an interface.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Interface {
    address: Ipv4Addr,
    gateway: Option<Ipv4Addr>,
    gateway_mac: Option<[u8; 6]>,
}

fn identity(interface: &Interface, hint: Option<&str>) -> Option<String> {
    let component = match (interface.gateway_mac, hint, interface.gateway) {
        (Some(mac), _, _) if mac != [0; 6] => format!("mac:{}", crate::secret::to_hex(&mac)),
        (_, Some(hint), _) if !hint.is_empty() => format!("hint:{hint}"),
        (_, _, Some(gateway)) => format!("gateway:{gateway}"),
        _ => return None,
    };
    // Hashed: the saved state need not reveal the router's vendor or the network's setup.
    Some(crate::secret::to_hex(&super::tls::fingerprint(
        component.as_bytes(),
    )))
}

/// Interface names that are never the home network, in lowercase: modems, tunnels, VPNs,
/// container and virtual-machine bridges, hotspots, Wi-Fi Direct and USB tethering.
const EXCLUDED_PREFIXES: &[&str] = &[
    "rmnet",
    "ccmni",
    "pdp",
    "wwan",
    "clat",
    "v4-",
    "tun",
    "tap",
    "ppp",
    "wg",
    "utun",
    "ipsec",
    "tailscale",
    "nordlynx",
    "proton",
    "mullvad",
    "zt",
    "docker",
    "veth",
    "br",
    "virbr",
    "lxdbr",
    "lxcbr",
    "cni",
    "podman",
    "flannel",
    "cali",
    "vxlan",
    "kube",
    "weave",
    "vmnet",
    "vboxnet",
    "vethernet",
    "llw",
    "awdl",
    "p2p",
    "ap",
    "swlan",
    "softap",
    "wigig",
    "usb",
    "rndis",
    "bnep",
    "dummy",
    "ifb",
    "sit",
    "gre",
    "vti",
];

/// Words in adapter names and descriptions that mark virtual, VPN or tethering adapters.
const EXCLUDED_WORDS: &[&str] = &[
    "virtual",
    "vpn",
    "tap-",
    "tap ",
    "wintun",
    "wireguard",
    "anyconnect",
    "globalprotect",
    "pangp",
    "fortinet",
    "fortissl",
    "juniper",
    "pulse secure",
    "openvpn",
    "nordlynx",
    "tailscale",
    "zerotier",
    "hyper-v",
    "vmware",
    "virtualbox",
    "npcap",
    "loopback",
    "bluetooth",
    "remote ndis",
    "wi-fi direct",
    "hosted network",
    "docker",
    "wsl",
    "vethernet",
];

/// Whether an interface name or adapter description rules it out.
pub(crate) fn is_excluded_name(name: &str) -> bool {
    let name = name.to_ascii_lowercase();
    EXCLUDED_PREFIXES
        .iter()
        .any(|prefix| name.starts_with(prefix))
        || EXCLUDED_WORDS.iter().any(|word| name.contains(word))
}

/// Windows adapter types that can be the home network: Ethernet and Wi-Fi.
#[cfg(any(windows, test))]
fn is_home_adapter(if_type: u32, description: &str, friendly_name: &str) -> bool {
    const ETHERNET: u32 = 6;
    const WIFI: u32 = 71;
    matches!(if_type, ETHERNET | WIFI)
        && !EXCLUDED_WORDS.iter().any(|word| {
            description.to_ascii_lowercase().contains(word)
                || friendly_name.to_ascii_lowercase().contains(word)
        })
}

/// Default routes in `/proc/net/route`: interface name and gateway.
#[cfg(any(target_os = "linux", target_os = "android", test))]
fn parse_proc_routes(text: &str) -> Vec<(String, Ipv4Addr)> {
    text.lines()
        .skip(1)
        .filter_map(|line| {
            let fields: Vec<&str> = line.split_whitespace().collect();
            let (name, destination, gateway) = (*fields.first()?, *fields.get(1)?, *fields.get(2)?);
            if destination != "00000000" {
                return None;
            }
            // Little-endian hex, as the kernel prints it.
            let gateway = u32::from_str_radix(gateway, 16).ok()?;
            let gateway = Ipv4Addr::from(gateway.to_le_bytes());
            (!gateway.is_unspecified()).then(|| (name.to_owned(), gateway))
        })
        .collect()
}

/// The MAC address of `ip` on `device` in `/proc/net/arp`.
#[cfg(any(target_os = "linux", target_os = "android", test))]
fn parse_proc_arp(text: &str, ip: Ipv4Addr, device: &str) -> Option<[u8; 6]> {
    text.lines().skip(1).find_map(|line| {
        let fields: Vec<&str> = line.split_whitespace().collect();
        if fields.first()?.parse::<Ipv4Addr>().ok()? != ip || *fields.get(5)? != device {
            return None;
        }
        parse_mac(fields.get(3)?)
    })
}

/// A MAC address written with `:` or `-`, with or without leading zeros (`0:1a:...`).
#[cfg(any(unix, test))]
fn parse_mac(text: &str) -> Option<[u8; 6]> {
    let parts: Vec<u8> = text
        .split([':', '-'])
        .map(|part| u8::from_str_radix(part, 16).ok())
        .collect::<Option<_>>()?;
    let mac: [u8; 6] = parts.try_into().ok()?;
    (mac != [0; 6]).then_some(mac)
}

/// Gateway and interface in the output of macOS `route -n get default`.
#[cfg(any(target_os = "macos", test))]
fn parse_route_get(text: &str) -> Option<(Ipv4Addr, String)> {
    let value = |key: &str| {
        text.lines()
            .find_map(|line| line.trim().strip_prefix(key)?.trim().to_owned().into())
    };
    let gateway: Ipv4Addr = value("gateway:")?.parse().ok()?;
    Some((gateway, value("interface:")?))
}

/// The MAC address in the output of macOS `arp -n <ip>`: `? (192.168.1.1) at a:b:c:d:e:f on en0`.
#[cfg(any(target_os = "macos", test))]
fn parse_arp_line(text: &str) -> Option<[u8; 6]> {
    let after = text.split(" at ").nth(1)?;
    parse_mac(after.split_whitespace().next()?)
}

/// Home-network IPv4 addresses on interfaces named like the home network, from if-addrs.
/// The fallback where the system tells no gateways (Android).
#[cfg_attr(windows, allow(dead_code))]
fn named_interfaces() -> Vec<(String, Ipv4Addr)> {
    let Ok(interfaces) = if_addrs::get_if_addrs() else {
        return Vec::new();
    };
    interfaces
        .into_iter()
        .filter(|interface| {
            interface.is_oper_up() && !interface.is_p2p && !is_excluded_name(&interface.name)
        })
        .filter_map(|interface| match interface.ip() {
            IpAddr::V4(ip) => Some((interface.name, ip)),
            IpAddr::V6(_) => None,
        })
        .collect()
}

#[cfg(windows)]
mod platform {
    //! Adapters from `GetAdaptersAddresses`, gateway MACs from the neighbor cache.
    #![allow(unsafe_code)]

    use std::net::Ipv4Addr;

    use windows_sys::Win32::Foundation::{ERROR_BUFFER_OVERFLOW, ERROR_SUCCESS};
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        GAA_FLAG_INCLUDE_GATEWAYS, GAA_FLAG_SKIP_ANYCAST, GAA_FLAG_SKIP_DNS_SERVER,
        GAA_FLAG_SKIP_MULTICAST, GetAdaptersAddresses, IP_ADAPTER_ADDRESSES_LH, MIB_IPNET_ROW2,
        ResolveIpNetEntry2,
    };
    use windows_sys::Win32::NetworkManagement::Ndis::IfOperStatusUp;
    use windows_sys::Win32::Networking::WinSock::{AF_INET, SOCKADDR_IN, SOCKET_ADDRESS};

    use super::Interface;

    pub(super) fn interfaces() -> Vec<Interface> {
        let Some(buffer) = adapters() else {
            return Vec::new();
        };
        let mut found = Vec::new();
        // SAFETY: `buffer` holds the linked list GetAdaptersAddresses wrote, 8-byte aligned
        // because it is a Vec<u64>; every pointer followed comes from that list and stays
        // valid while `buffer` lives.
        unsafe {
            let mut adapter = buffer.as_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
            while let Some(current) = adapter.as_ref() {
                adapter = current.Next;
                if current.OperStatus != IfOperStatusUp
                    || !super::is_home_adapter(
                        current.IfType,
                        &wide(current.Description),
                        &wide(current.FriendlyName),
                    )
                {
                    continue;
                }
                let Some(gateway) =
                    first_v4(current.FirstGatewayAddress.as_ref().map(|g| g.Address))
                else {
                    continue;
                };
                let index = current.Anonymous1.Anonymous.IfIndex;
                let gateway_mac = gateway_mac(index, gateway);
                let mut unicast = current.FirstUnicastAddress;
                while let Some(address) = unicast.as_ref() {
                    unicast = address.Next;
                    if let Some(ip) = first_v4(Some(address.Address)) {
                        found.push(Interface {
                            address: ip,
                            gateway: Some(gateway),
                            gateway_mac,
                        });
                    }
                }
            }
        }
        found
    }

    /// The adapter list, in a buffer that grows until it fits.
    fn adapters() -> Option<Vec<u64>> {
        let flags = GAA_FLAG_INCLUDE_GATEWAYS
            | GAA_FLAG_SKIP_ANYCAST
            | GAA_FLAG_SKIP_MULTICAST
            | GAA_FLAG_SKIP_DNS_SERVER;
        let mut size: u32 = 16 * 1024;
        for _ in 0..4 {
            let mut buffer = vec![0u64; (size as usize).div_ceil(8)];
            // SAFETY: the buffer is `size` bytes long and suitably aligned; the call writes
            // at most `size` bytes and reports the size it needs otherwise.
            let result = unsafe {
                GetAdaptersAddresses(
                    u32::from(AF_INET),
                    flags,
                    std::ptr::null(),
                    buffer.as_mut_ptr().cast(),
                    &mut size,
                )
            };
            match result {
                ERROR_SUCCESS => return Some(buffer),
                ERROR_BUFFER_OVERFLOW => continue,
                _ => return None,
            }
        }
        None
    }

    /// The gateway's MAC address from the neighbor cache, resolving it if needed.
    fn gateway_mac(index: u32, gateway: Ipv4Addr) -> Option<[u8; 6]> {
        let mut row = MIB_IPNET_ROW2 {
            InterfaceIndex: index,
            ..MIB_IPNET_ROW2::default()
        };
        row.Address.Ipv4 = SOCKADDR_IN {
            sin_family: AF_INET,
            ..SOCKADDR_IN::default()
        };
        row.Address.Ipv4.sin_addr.S_un.S_addr = u32::from_ne_bytes(gateway.octets());
        // SAFETY: `row` is a valid, initialized MIB_IPNET_ROW2 with an IPv4 address and an
        // interface index, as ResolveIpNetEntry2 requires.
        let result = unsafe { ResolveIpNetEntry2(&mut row, std::ptr::null()) };
        if result != ERROR_SUCCESS || row.PhysicalAddressLength != 6 {
            return None;
        }
        let mut mac = [0u8; 6];
        mac.copy_from_slice(&row.PhysicalAddress[..6]);
        (mac != [0; 6]).then_some(mac)
    }

    /// The IPv4 address in a SOCKET_ADDRESS, if it holds one.
    ///
    /// SAFETY: `address` must come from GetAdaptersAddresses and its buffer must be alive.
    unsafe fn first_v4(address: Option<SOCKET_ADDRESS>) -> Option<Ipv4Addr> {
        let address = address?;
        if address.lpSockaddr.is_null()
            || usize::try_from(address.iSockaddrLength).ok()? < size_of::<SOCKADDR_IN>()
        {
            return None;
        }
        // SAFETY: non-null and long enough for a SOCKADDR_IN, per the checks above.
        let sockaddr = unsafe { &*address.lpSockaddr.cast::<SOCKADDR_IN>() };
        if sockaddr.sin_family != AF_INET {
            return None;
        }
        // SAFETY: every variant of the IN_ADDR union is plain bytes.
        let raw = unsafe { sockaddr.sin_addr.S_un.S_addr };
        Some(Ipv4Addr::from(raw.to_ne_bytes()))
    }

    /// A NUL-terminated UTF-16 string from the adapter list.
    ///
    /// SAFETY: `text` must be null or point to a NUL-terminated string that stays alive.
    unsafe fn wide(text: *const u16) -> String {
        if text.is_null() {
            return String::new();
        }
        let mut len = 0;
        // SAFETY: the string is NUL-terminated, so every offset up to the NUL is readable.
        while unsafe { *text.add(len) } != 0 && len < 1024 {
            len += 1;
        }
        // SAFETY: `len` code units were just read.
        String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(text, len) })
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
mod platform {
    //! Default routes and the ARP cache from `/proc`, physical devices from sysfs. Android
    //! keeps apps out of all three, so there the names decide and the app's hint identifies.

    use std::path::Path;

    use super::Interface;

    pub(super) fn interfaces() -> Vec<Interface> {
        let named = super::named_interfaces();
        let Ok(routes) = std::fs::read_to_string("/proc/net/route") else {
            return named
                .into_iter()
                .map(|(_, address)| Interface {
                    address,
                    gateway: None,
                    gateway_mac: None,
                })
                .collect();
        };
        let routes = super::parse_proc_routes(&routes);
        let arp = std::fs::read_to_string("/proc/net/arp").unwrap_or_default();
        named
            .into_iter()
            .filter(|(name, _)| is_physical(name))
            .filter_map(|(name, address)| {
                let gateway = routes
                    .iter()
                    .find(|(device, _)| *device == name)
                    .map(|(_, gateway)| *gateway)?;
                Some(Interface {
                    address,
                    gateway: Some(gateway),
                    gateway_mac: super::parse_proc_arp(&arp, gateway, &name),
                })
            })
            .collect()
    }

    /// A real network device rather than a bridge or a virtual one, when sysfs says so.
    fn is_physical(name: &str) -> bool {
        let base = Path::new("/sys/class/net").join(name);
        if !base.exists() {
            // No sysfs to ask: the name rules already applied.
            return true;
        }
        base.join("device").exists() && !base.join("bridge").exists()
    }
}

#[cfg(target_os = "macos")]
mod platform {
    //! The default route's interface and gateway from `route`, its MAC from `arp`.

    use std::process::Command;

    use super::Interface;

    pub(super) fn interfaces() -> Vec<Interface> {
        let output = |program: &str, args: &[&str]| {
            Command::new(program)
                .args(args)
                .output()
                .ok()
                .filter(|output| output.status.success())
                .map(|output| String::from_utf8_lossy(&output.stdout).into_owned())
        };
        let Some((gateway, device)) = output("/sbin/route", &["-n", "get", "default"])
            .and_then(|text| super::parse_route_get(&text))
        else {
            return Vec::new();
        };
        let gateway_mac = output("/usr/sbin/arp", &["-n", &gateway.to_string()])
            .and_then(|text| super::parse_arp_line(&text));
        super::named_interfaces()
            .into_iter()
            .filter(|(name, _)| *name == device)
            .map(|(_, address)| Interface {
                address,
                gateway: Some(gateway),
                gateway_mac,
            })
            .collect()
    }
}

#[cfg(not(any(
    windows,
    target_os = "linux",
    target_os = "android",
    target_os = "macos"
)))]
mod platform {
    use super::Interface;

    pub(super) fn interfaces() -> Vec<Interface> {
        super::named_interfaces()
            .into_iter()
            .map(|(_, address)| Interface {
                address,
                gateway: None,
                gateway_mac: None,
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn interface(mac: Option<[u8; 6]>, gateway: Option<&str>) -> Interface {
        Interface {
            address: "192.168.1.20".parse().expect("ip"),
            gateway: gateway.map(|gateway| gateway.parse().expect("ip")),
            gateway_mac: mac,
        }
    }

    #[test]
    fn networks_are_told_apart_by_their_gateway() {
        let home = identity(
            &interface(Some([1, 2, 3, 4, 5, 6]), Some("192.168.1.1")),
            None,
        );
        let cafe = identity(
            &interface(Some([9, 9, 9, 9, 9, 9]), Some("192.168.1.1")),
            None,
        );
        assert!(home.is_some());
        assert_ne!(home, cafe, "same addresses, another router");
        assert_eq!(
            home,
            identity(
                &interface(Some([1, 2, 3, 4, 5, 6]), Some("192.168.1.254")),
                Some("x")
            ),
            "the MAC decides when known"
        );
        // Without a MAC, the app's hint, then the gateway address.
        let hinted = identity(
            &interface(None, Some("192.168.1.1")),
            Some("dhcp=192.168.1.1"),
        );
        assert_ne!(
            hinted,
            identity(&interface(None, Some("192.168.1.1")), Some("other"))
        );
        assert!(identity(&interface(None, Some("192.168.1.1")), None).is_some());
        assert_eq!(
            identity(&interface(None, None), None),
            None,
            "unidentifiable"
        );
        let id = home.expect("id");
        assert!(!id.contains("01:02") && id.len() == 64, "stored hashed");
    }

    #[test]
    fn only_physical_ethernet_and_wifi_adapters_count_on_windows() {
        assert!(is_home_adapter(
            6,
            "Intel(R) Ethernet Connection I219-V",
            "Ethernet"
        ));
        assert!(is_home_adapter(
            71,
            "Intel(R) Wi-Fi 6 AX201 160MHz",
            "Wi-Fi"
        ));
        // Wintun and WireGuard report type 53; TAP drivers and some VPNs report Ethernet.
        assert!(!is_home_adapter(53, "WireGuard Tunnel", "home"));
        assert!(!is_home_adapter(53, "Wintun Userspace Tunnel", "Tailscale"));
        for (description, name) in [
            ("TAP-Windows Adapter V9", "Ethernet 2"),
            (
                "Cisco AnyConnect Secure Mobility Client Virtual Miniport Adapter",
                "Ethernet 3",
            ),
            ("PANGP Virtual Ethernet Adapter Secure", "Ethernet 4"),
            ("Hyper-V Virtual Ethernet Adapter", "vEthernet (WSL)"),
            ("VirtualBox Host-Only Ethernet Adapter", "Ethernet 5"),
            ("Remote NDIS based Internet Sharing Device", "Ethernet 6"),
            (
                "Bluetooth Device (Personal Area Network)",
                "Bluetooth Network Connection",
            ),
            (
                "Microsoft Wi-Fi Direct Virtual Adapter",
                "Local Area Connection* 2",
            ),
            ("Fortinet SSL VPN Virtual Ethernet Adapter", "Ethernet 7"),
        ] {
            assert!(!is_home_adapter(6, description, name), "{description}");
            assert!(!is_home_adapter(71, description, name), "{description}");
        }
    }

    #[test]
    fn linux_and_android_names_leave_out_bridges_and_groups() {
        for name in [
            "wlan0",
            "eth0",
            "enp3s0",
            "wlp2s0",
            "enx00e04c680001",
            "en0",
        ] {
            assert!(!is_excluded_name(name), "{name}");
        }
        for name in [
            "p2p-wlan0-0",
            "p2p0",
            "ap0",
            "swlan0",
            "br0",
            "br-1a2b3c",
            "lxdbr0",
            "podman0",
            "cni0",
            "virbr0",
            "docker0",
            "usb0",
            "rndis0",
            "rmnet_data0",
            "v4-rmnet_data0",
            "tun0",
            "wg0",
            "tailscale0",
        ] {
            assert!(is_excluded_name(name), "{name}");
        }
    }

    #[test]
    fn proc_tables_give_gateways_and_their_macs() {
        let routes = "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\n\
                      wlan0\t00000000\t0101A8C0\t0003\t0\t0\t600\t00000000\n\
                      wlan0\t0001A8C0\t00000000\t0001\t0\t0\t600\t00FFFFFF\n\
                      docker0\t000011AC\t00000000\t0001\t0\t0\t0\t0000FFFF\n";
        assert_eq!(
            parse_proc_routes(routes),
            vec![("wlan0".to_owned(), "192.168.1.1".parse().expect("ip"))]
        );
        let arp = "IP address       HW type     Flags       HW address            Mask     Device\n\
                   192.168.1.1      0x1         0x2         a4:91:b1:0c:22:7e     *        wlan0\n\
                   192.168.1.30     0x1         0x2         00:00:00:00:00:00     *        wlan0\n";
        let gateway = "192.168.1.1".parse().expect("ip");
        assert_eq!(
            parse_proc_arp(arp, gateway, "wlan0"),
            Some([0xa4, 0x91, 0xb1, 0x0c, 0x22, 0x7e])
        );
        assert_eq!(parse_proc_arp(arp, gateway, "eth0"), None);
        assert_eq!(
            parse_proc_arp(arp, "192.168.1.30".parse().expect("ip"), "wlan0"),
            None
        );
    }

    #[test]
    fn macos_route_and_arp_output_is_read() {
        let route = "   route to: default\ndestination: default\n       mask: default\n    gateway: 192.168.1.1\n  interface: en0\n      flags: <UP,GATEWAY,DONE,STATIC,PRCLONING>\n";
        assert_eq!(
            parse_route_get(route),
            Some(("192.168.1.1".parse().expect("ip"), "en0".to_owned()))
        );
        assert_eq!(
            parse_arp_line("? (192.168.1.1) at a4:91:b1:c:22:7e on en0 ifscope [ethernet]\n"),
            Some([0xa4, 0x91, 0xb1, 0x0c, 0x22, 0x7e])
        );
        assert_eq!(
            parse_arp_line("? (192.168.1.1) at (incomplete) on en0\n"),
            None
        );
    }

    #[test]
    fn candidates_are_home_network_addresses() {
        for candidate in candidates(None) {
            assert!(candidate.address.is_private(), "{candidate:?}");
        }
    }
}
