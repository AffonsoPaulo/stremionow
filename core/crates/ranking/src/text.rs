// SPDX-License-Identifier: GPL-3.0-or-later
//! Low-level text helpers: safe truncation, tokenizing and scanning for numbers after markers.
//!
//! Byte-level scanning only ever stops on ASCII bytes, which are always whole characters in
//! UTF-8, so every slice taken here lies on a char boundary.

/// Returns the longest prefix of `s` that is at most `max` bytes and ends on a char boundary.
pub(crate) fn truncate(s: &str, max: usize) -> &str {
    &s[..s.floor_char_boundary(max)]
}

/// A word of the input: a run of alphanumeric characters, `+` included so that `HDR10+`,
/// `DD+` and `[RD+]` keep their meaning.
#[derive(Debug)]
pub(crate) struct Token<'a> {
    /// The token as written, for case-sensitive checks.
    pub raw: &'a str,
    /// The token lowercased.
    pub lower: String,
}

/// Splits `text` into [`Token`]s. Emoji, punctuation and whitespace are separators.
pub(crate) fn tokenize(text: &str) -> Vec<Token<'_>> {
    text.split(|c: char| !(c.is_alphanumeric() || c == '+'))
        .filter(|raw| !raw.is_empty())
        .map(|raw| Token {
            raw,
            lower: raw.to_lowercase(),
        })
        .collect()
}

/// Lowercase text of the first three tokens of `tokens`, padded with `""`.
pub(crate) fn lookahead<'t>(tokens: &'t [Token<'_>]) -> [&'t str; 3] {
    let at = |i: usize| tokens.get(i).map_or("", |t| t.lower.as_str());
    [at(0), at(1), at(2)]
}

/// Runs `matcher` over `tokens` left to right. A match consumes the number of tokens it
/// reports (at least one), so multi-word phrases such as "Dolby Vision" are not matched twice.
pub(crate) fn scan<V>(
    tokens: &[Token<'_>],
    matcher: impl Fn(&[Token<'_>]) -> Option<(V, usize)>,
) -> Vec<V> {
    let mut found = Vec::new();
    let mut i = 0;
    while i < tokens.len() {
        match matcher(&tokens[i..]) {
            Some((value, consumed)) => {
                found.push(value);
                i += consumed.max(1);
            }
            None => i += 1,
        }
    }
    found
}

/// `token` without its trailing ASCII digits, so `ddp5` and `aac2` match `ddp` and `aac`.
pub(crate) fn stem(token: &str) -> &str {
    token.trim_end_matches(|c: char| c.is_ascii_digit())
}

/// Characters skipped between a marker and its value: whitespace, emoji variation selectors
/// and label punctuation.
fn is_filler(c: char) -> bool {
    c.is_whitespace() || matches!(c, '\u{fe0f}' | '\u{200d}' | ':' | '=')
}

/// The unsigned integer right after the first occurrence of `marker` in `text` that has one.
/// Thousands separators (`1,234`) are accepted; overflowing values are rejected.
pub(crate) fn number_after(text: &str, marker: &str) -> Option<u32> {
    text.match_indices(marker).find_map(|(at, _)| {
        let rest = text[at + marker.len()..].trim_start_matches(is_filler);
        leading_integer(rest)
    })
}

fn leading_integer(s: &str) -> Option<u32> {
    let mut value: u32 = 0;
    let mut digits = 0;
    let mut chars = s.chars().peekable();
    while let Some(&c) = chars.peek() {
        if let Some(d) = c.to_digit(10) {
            value = value.checked_mul(10)?.checked_add(d)?;
            digits += 1;
            chars.next();
        } else if c == ',' && digits > 0 {
            // Only a thousands separator when followed by a digit.
            chars.next();
            if !chars.peek().is_some_and(char::is_ascii_digit) {
                break;
            }
        } else {
            break;
        }
    }
    (digits > 0).then_some(value)
}

const KIB: f64 = 1024.0;

/// Bytes per unit; decimal and binary prefixes are both read as powers of 1024, which is what
/// addons actually compute.
fn unit_multiplier(unit: &str) -> Option<f64> {
    Some(match unit {
        "kb" | "kib" => KIB,
        "mb" | "mib" => KIB * KIB,
        "gb" | "gib" => KIB * KIB * KIB,
        "tb" | "tib" => KIB * KIB * KIB * KIB,
        _ => return None,
    })
}

/// The first size such as `14.2 GB`, `700MB`, `1,5 GB` or `3.1 GiB` in `text`, in bytes.
pub(crate) fn first_size(text: &str) -> Option<u64> {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let starts_number =
            bytes[i].is_ascii_digit() && (i == 0 || !bytes[i - 1].is_ascii_alphanumeric());
        if starts_number {
            if let Some(size) = size_at(text, i) {
                return Some(size);
            }
            i += count_while(&bytes[i..], |b| b.is_ascii_digit());
        } else {
            i += 1;
        }
    }
    None
}

/// Parses `<number>[.,<digits>] *<unit>` starting at byte `start` (an ASCII digit) into bytes.
fn size_at(text: &str, start: usize) -> Option<u64> {
    let bytes = text.as_bytes();
    let int_end = start + count_while(&bytes[start..], |b| b.is_ascii_digit());
    let mut integer = text[start..int_end].to_owned();
    let mut fraction = "";
    let mut end = int_end;
    if let Some(&sep) = bytes.get(int_end).filter(|b| matches!(b, b'.' | b',')) {
        let frac_end = int_end + 1 + count_while(&bytes[int_end + 1..], |b| b.is_ascii_digit());
        let digits = &text[int_end + 1..frac_end];
        if !digits.is_empty() {
            // "1,500 MB" is a thousands separator, "1,5 GB" a decimal comma.
            if sep == b',' && digits.len() == 3 {
                integer.push_str(digits);
            } else {
                fraction = digits;
            }
            end = frac_end;
        }
    }
    let unit_start = end + count_while(&bytes[end..], |b| b == b' ');
    let unit_end = unit_start + count_while(&bytes[unit_start..], |b| b.is_ascii_alphabetic());
    let multiplier = unit_multiplier(&text[unit_start..unit_end].to_ascii_lowercase())?;
    let value: f64 = format!("{integer}.{fraction}0").parse().ok()?;
    let size = value * multiplier;
    // Float to integer casts saturate, so this cannot overflow.
    (size.is_finite() && size >= 1.0).then_some(size as u64)
}

fn count_while(bytes: &[u8], pred: impl Fn(u8) -> bool) -> usize {
    bytes.iter().take_while(|&&b| pred(b)).count()
}

/// The short label right after `marker` on the same line, such as the tracker name after
/// `⚙️` in Torrentio titles.
pub(crate) fn label_after(text: &str, marker: &str) -> Option<String> {
    const MAX_CHARS: usize = 48;
    text.match_indices(marker).find_map(|(at, _)| {
        let label: String = text[at + marker.len()..]
            .trim_start_matches(is_filler)
            .chars()
            .take_while(|&c| c.is_alphanumeric() || matches!(c, ' ' | '.' | '-' | '_' | '\''))
            .take(MAX_CHARS)
            .collect();
        let label = label.trim();
        (!label.is_empty()).then(|| label.to_owned())
    })
}

/// Two-letter lowercase country codes of the flag emoji in `text`, in order.
pub(crate) fn flag_countries(text: &str) -> Vec<[char; 2]> {
    const BASE: u32 = 0x1F1E6;
    let letter = |c: char| {
        let offset = u32::from(c).checked_sub(BASE).filter(|&o| o < 26)?;
        char::from_u32(u32::from('a') + offset)
    };
    let mut countries = Vec::new();
    let mut chars = text.chars();
    while let Some(c) = chars.next() {
        if let Some(first) = letter(c)
            && let Some(second) = chars.clone().next().and_then(letter)
        {
            chars.next();
            countries.push([first, second]);
        }
    }
    countries
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truncate_never_splits_a_char() {
        let s = "aé🇬🇧";
        for max in 0..=s.len() + 2 {
            let t = truncate(s, max);
            assert!(s.starts_with(t));
            assert!(t.len() <= max);
        }
    }

    #[test]
    fn tokenize_keeps_plus_and_drops_emoji() {
        let lowers: Vec<_> = tokenize("HDR10+ 👤 [RD+] DD+5.1 Português")
            .into_iter()
            .map(|t| t.lower)
            .collect();
        assert_eq!(lowers, ["hdr10+", "rd+", "dd+5", "1", "português"]);
    }

    #[test]
    fn sizes() {
        let gib = 1024.0_f64.powi(3);
        assert_eq!(first_size("💾 14.2 GB"), Some((14.2 * gib) as u64));
        assert_eq!(first_size("700 MB"), Some(700 * 1024 * 1024));
        assert_eq!(first_size("1,5 GB"), Some((1.5 * gib) as u64));
        assert_eq!(first_size("1,500 MB"), Some(1500 * 1024 * 1024));
        assert_eq!(first_size("💾3.1 GiB"), Some((3.1 * gib) as u64));
        assert_eq!(first_size("x264 GB"), None);
        assert_eq!(first_size("1080p 5.1 DTS"), None);
        assert_eq!(
            first_size("Size: 2 TB"),
            Some(2 * 1024 * 1024 * 1024 * 1024)
        );
        assert_eq!(
            first_size("99999999999999999999999999999 TB"),
            Some(u64::MAX)
        );
    }

    #[test]
    fn numbers_after_markers() {
        assert_eq!(number_after("👤 154 💾", "👤"), Some(154));
        assert_eq!(number_after("seeders: 1,234", "seeders"), Some(1234));
        assert_eq!(number_after("👤 x 👤 7", "👤"), Some(7));
        assert_eq!(number_after("👤 99999999999", "👤"), None);
        assert_eq!(number_after("👤", "👤"), None);
    }

    #[test]
    fn flags() {
        assert_eq!(flag_countries("🇬🇧 / 🇮🇹 x 🇧"), [['g', 'b'], ['i', 't']]);
    }
}
