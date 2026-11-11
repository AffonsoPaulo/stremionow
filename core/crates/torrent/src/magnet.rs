// SPDX-License-Identifier: GPL-3.0-or-later
//! Info hashes and magnet links built from what Stremio addons send.

use std::fmt;

use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};

/// Characters escaped inside a magnet query value: everything but RFC 3986 unreserved ones.
const QUERY_VALUE: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

/// Trackers kept per torrent. Real addon answers list a few dozen at most.
const MAX_TRACKERS: usize = 64;

/// Whether `https://` trackers are announced to. On Android librqbit's HTTP client verifies
/// certificates through the platform verifier, which needs JNI setup this crate cannot do, and
/// fails by panicking inside the announce task; UDP and plain HTTP trackers plus DHT remain.
const HTTPS_TRACKERS: bool = !cfg!(target_os = "android");

/// A BitTorrent v1 info hash (SHA-1 of the torrent's info dictionary).
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) struct InfoHash([u8; 20]);

impl InfoHash {
    /// Parses the 40 hexadecimal digits Stremio uses, in either case.
    pub(crate) fn parse(text: &str) -> Option<Self> {
        let (pairs, rest) = text.as_bytes().as_chunks::<2>();
        if pairs.len() != 20 || !rest.is_empty() {
            return None;
        }
        let mut bytes = [0u8; 20];
        for (byte, &[high, low]) in bytes.iter_mut().zip(pairs) {
            *byte = (hex_value(high)? << 4) | hex_value(low)?;
        }
        Some(Self(bytes))
    }

    /// The hash as librqbit's identifier type.
    pub(crate) fn id20(self) -> librqbit_core::Id20 {
        librqbit_core::Id20::new(self.0)
    }
}

fn hex_value(digit: u8) -> Option<u8> {
    match digit {
        b'0'..=b'9' => Some(digit - b'0'),
        b'a'..=b'f' => Some(digit - b'a' + 10),
        b'A'..=b'F' => Some(digit - b'A' + 10),
        _ => None,
    }
}

impl fmt::Display for InfoHash {
    /// Lowercase hex, the form used in URLs and cache folder names.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.iter().try_for_each(|byte| write!(f, "{byte:02x}"))
    }
}

impl fmt::Debug for InfoHash {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "InfoHash({self})")
    }
}

/// Tracker URLs from a stream's `sources`, in order and without duplicates.
///
/// Addons send entries such as `tracker:udp://host:port/announce` and `dht:<info hash>`. The
/// `tracker:` prefix is optional here; `dht:` entries are dropped because DHT is always on,
/// and so is anything librqbit cannot announce to.
pub(crate) fn tracker_urls(sources: &[String]) -> Vec<String> {
    let mut trackers: Vec<String> = Vec::new();
    for source in sources {
        if trackers.len() == MAX_TRACKERS {
            break;
        }
        let Some(tracker) = parse_tracker(source) else {
            continue;
        };
        if !trackers.contains(&tracker) {
            trackers.push(tracker);
        }
    }
    trackers
}

fn parse_tracker(source: &str) -> Option<String> {
    let source = source.trim();
    let source = source.strip_prefix("tracker:").unwrap_or(source);
    let url = url::Url::parse(source).ok()?;
    let supported = match url.scheme() {
        "udp" | "http" => true,
        "https" => HTTPS_TRACKERS,
        _ => false,
    };
    // Public torrents never need trackers on this device or the local network, and an addon
    // must not be able to make the app announce into it.
    (supported && url.has_host() && !crate::proxy::is_private_host(&url)).then(|| url.into())
}

/// A magnet link for `info_hash` announcing to `trackers`, which are URL-encoded.
pub(crate) fn magnet_link(info_hash: InfoHash, trackers: &[String]) -> String {
    let mut link = format!("magnet:?xt=urn:btih:{info_hash}");
    for tracker in trackers {
        link.push_str("&tr=");
        link.extend(utf8_percent_encode(tracker, QUERY_VALUE));
    }
    link
}

#[cfg(test)]
mod tests {
    use super::*;

    const HASH: &str = "08ada5a7a6183aae1e09d831df6748d566095a10";

    fn strings(items: &[&str]) -> Vec<String> {
        items.iter().map(|item| (*item).to_owned()).collect()
    }

    #[test]
    fn info_hash_accepts_hex_in_any_case() {
        let lower = InfoHash::parse(HASH).expect("valid hash");
        let upper = InfoHash::parse(&HASH.to_uppercase()).expect("valid hash");
        assert_eq!(lower, upper);
        assert_eq!(upper.to_string(), HASH);
    }

    #[test]
    fn info_hash_rejects_other_input() {
        for text in [
            "",
            "08ada5a7",
            "08ada5a7a6183aae1e09d831df6748d566095a1",
            "08ada5a7a6183aae1e09d831df6748d566095a100",
            "08ada5a7a6183aae1e09d831df6748d566095a1g",
            "BCWWFF5HVQQ5VQ5M4HHSZ3XURJ5ZJ3DS",
            "08ada5a7a6183aae1e09d831df6748d566095aé",
        ] {
            assert!(InfoHash::parse(text).is_none(), "{text:?}");
        }
    }

    #[test]
    fn trackers_come_from_tracker_sources_only() {
        let sources = strings(&[
            "tracker:udp://tracker.opentrackr.org:1337/announce",
            "dht:08ada5a7a6183aae1e09d831df6748d566095a10",
            "tracker:http://tracker.example.org/announce",
            "udp://open.stealth.si:80/announce",
            "tracker:udp://tracker.opentrackr.org:1337/announce",
            "tracker:wss://tracker.webtorrent.dev",
            "tracker:not a url",
            "tracker:",
        ]);
        assert_eq!(
            tracker_urls(&sources),
            strings(&[
                "udp://tracker.opentrackr.org:1337/announce",
                "http://tracker.example.org/announce",
                "udp://open.stealth.si:80/announce",
            ])
        );
    }

    #[test]
    fn https_trackers_follow_the_platform_policy() {
        let sources = strings(&["tracker:https://tracker.example.org/announce"]);
        assert_eq!(tracker_urls(&sources).len(), usize::from(HTTPS_TRACKERS));
    }

    #[test]
    fn tracker_list_is_bounded() {
        let sources: Vec<String> = (0..1000)
            .map(|i| format!("tracker:udp://t{i}.example.org:80/announce"))
            .collect();
        assert_eq!(tracker_urls(&sources).len(), MAX_TRACKERS);
    }

    #[test]
    fn magnet_encodes_trackers() {
        let hash = InfoHash::parse(HASH).expect("valid hash");
        let trackers = strings(&[
            "udp://tracker.opentrackr.org:1337/announce",
            "http://t.example.org/announce?passkey=a&b=c d",
        ]);
        assert_eq!(
            magnet_link(hash, &trackers),
            "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10\
             &tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce\
             &tr=http%3A%2F%2Ft.example.org%2Fannounce%3Fpasskey%3Da%26b%3Dc%20d"
        );
    }

    #[test]
    fn magnet_round_trips_through_librqbit() {
        let hash = InfoHash::parse(HASH).expect("valid hash");
        let trackers = strings(&["http://t.example.org/announce?a=1&b=2"]);
        let magnet = librqbit::Magnet::parse(&magnet_link(hash, &trackers)).expect("parses");
        assert_eq!(magnet.as_id20(), Some(hash.id20()));
        assert_eq!(magnet.trackers, trackers);
    }

    #[test]
    fn magnet_without_trackers_is_just_the_hash() {
        let hash = InfoHash::parse(HASH).expect("valid hash");
        assert_eq!(
            magnet_link(hash, &[]),
            "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"
        );
    }
}
