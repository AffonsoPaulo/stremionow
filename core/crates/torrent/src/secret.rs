// SPDX-License-Identifier: GPL-3.0-or-later
//! Unguessable values that authorize requests to the loopback server.
//!
//! Any app on the device can connect to a loopback port, so every URL carries a random
//! per-process secret, and each proxied source its own random token.

use std::fmt::Write;
use std::io;

/// Random bytes per value: 128 bits, written as 32 hex digits.
const BYTES: usize = 16;

/// A fresh random value from the operating system's generator, as lowercase hex.
pub(crate) fn random_hex() -> io::Result<String> {
    let mut bytes = [0u8; BYTES];
    getrandom::fill(&mut bytes).map_err(io::Error::other)?;
    let mut text = String::with_capacity(BYTES * 2);
    for byte in bytes {
        // Writing to a String cannot fail.
        let _ = write!(text, "{byte:02x}");
    }
    Ok(text)
}

/// Compares a value taken from a request with a known one without stopping at the first
/// difference, so response timing does not reveal how much of a guess was right.
pub(crate) fn constant_time_eq(candidate: &str, known: &str) -> bool {
    let (candidate, known) = (candidate.as_bytes(), known.as_bytes());
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
}
