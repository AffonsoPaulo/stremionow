// SPDX-License-Identifier: GPL-3.0-or-later
//! Unguessable values that authorize requests to the loopback server.
//!
//! Any app on the device can connect to a loopback port, so every URL carries a random
//! per-process secret, and each proxied source its own random token. The same helpers make
//! the tokens and codes of serving to other devices.

use std::fmt::Write;
use std::io;

/// Random bytes per value: 128 bits, written as 32 hex digits.
const BYTES: usize = 16;

/// A fresh random value from the operating system's generator, as lowercase hex.
pub(crate) fn random_hex() -> io::Result<String> {
    Ok(to_hex(&random_bytes::<BYTES>()?))
}

/// `N` fresh random bytes from the operating system's generator.
pub(crate) fn random_bytes<const N: usize>() -> io::Result<[u8; N]> {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).map_err(io::Error::other)?;
    Ok(bytes)
}

/// `bytes` as lowercase hex.
pub(crate) fn to_hex(bytes: &[u8]) -> String {
    let mut text = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        // Writing to a String cannot fail.
        let _ = write!(text, "{byte:02x}");
    }
    text
}

/// The bytes written as hex in `text`, in either case; `None` for anything else.
pub(crate) fn from_hex(text: &str) -> Option<Vec<u8>> {
    let (pairs, rest) = text.as_bytes().as_chunks::<2>();
    if !rest.is_empty() {
        return None;
    }
    pairs
        .iter()
        .map(|&[high, low]| Some((hex_digit(high)? << 4) | hex_digit(low)?))
        .collect()
}

fn hex_digit(digit: u8) -> Option<u8> {
    match digit {
        b'0'..=b'9' => Some(digit - b'0'),
        b'a'..=b'f' => Some(digit - b'a' + 10),
        b'A'..=b'F' => Some(digit - b'A' + 10),
        _ => None,
    }
}

/// Compares a value taken from a request with a known one without stopping at the first
/// difference, so response timing does not reveal how much of a guess was right.
pub(crate) fn constant_time_eq(candidate: &str, known: &str) -> bool {
    constant_time_eq_bytes(candidate.as_bytes(), known.as_bytes())
}

/// [`constant_time_eq`] for raw bytes.
pub(crate) fn constant_time_eq_bytes(candidate: &[u8], known: &[u8]) -> bool {
    if candidate.len() != known.len() {
        return false;
    }
    candidate
        .iter()
        .zip(known)
        .fold(0u8, |diff, (a, b)| diff | (a ^ b))
        == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn values_are_long_random_hex() {
        let first = random_hex().expect("random bytes");
        let second = random_hex().expect("random bytes");
        assert_eq!(first.len(), 32);
        assert!(
            first
                .bytes()
                .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
        );
        assert_ne!(first, second);
    }

    #[test]
    fn matching_needs_the_exact_value() {
        assert!(constant_time_eq("abc123", "abc123"));
        assert!(!constant_time_eq("abc124", "abc123"));
        assert!(!constant_time_eq("abc12", "abc123"));
        assert!(!constant_time_eq("", "abc123"));
    }

    #[test]
    fn hex_round_trips() {
        let bytes = [0x00, 0x7f, 0xab, 0xff];
        assert_eq!(to_hex(&bytes), "007fabff");
        assert_eq!(from_hex("007FABff").as_deref(), Some(&bytes[..]));
        assert_eq!(from_hex("abc"), None);
        assert_eq!(from_hex("zz"), None);
        assert_eq!(from_hex("").as_deref(), Some(&[][..]));
    }
}
