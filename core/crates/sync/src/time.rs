// SPDX-License-Identifier: GPL-3.0-or-later
//! RFC 3339 timestamps, as used by the library datastore (`_ctime`, `_mtime`, `lastWatched`).
//!
//! The official clients write them with chrono (`2020-01-01T00:00:00Z`) or JavaScript's
//! `toISOString` (`2020-01-01T00:00:00.000Z`). Only what is needed to compare and create
//! them is implemented, to avoid a date library dependency.

const MS_PER_SECOND: i64 = 1_000;
const MS_PER_DAY: i64 = 86_400 * MS_PER_SECOND;

/// Parses an RFC 3339 timestamp into milliseconds since the Unix epoch.
///
/// Accepts any number of fraction digits (extra precision is truncated) and `Z` or `±HH:MM`
/// offsets. Returns `None` for anything else, including empty strings.
pub fn parse_timestamp(text: &str) -> Option<i64> {
    let bytes = text.as_bytes();
    // ASCII only, so the fixed byte offsets below are always char boundaries.
    if !text.is_ascii() || bytes.len() < 20 || !matches!(bytes[10], b'T' | b't' | b' ') {
        return None;
    }
    let date = parse_date(text.get(..10)?)?;
    let (seconds_of_day, rest) = parse_time(text.get(11..)?)?;
    let (millis, rest) = parse_fraction(rest)?;
    let offset_seconds = parse_offset(rest)?;
    let seconds = date * 86_400 + seconds_of_day - offset_seconds;
    Some(seconds * MS_PER_SECOND + millis)
}

/// Formats milliseconds since the Unix epoch like JavaScript's `toISOString`
/// (`2020-01-01T00:00:00.000Z`).
pub fn format_timestamp(ms: i64) -> String {
    let days = ms.div_euclid(MS_PER_DAY);
    let ms_of_day = ms.rem_euclid(MS_PER_DAY);
    let (year, month, day) = civil_from_days(days);
    let seconds = ms_of_day / MS_PER_SECOND;
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        seconds / 3600,
        seconds / 60 % 60,
        seconds % 60,
        ms_of_day % MS_PER_SECOND
    )
}

/// `YYYY-MM-DD` to days since the epoch.
fn parse_date(text: &str) -> Option<i64> {
    let b = text.as_bytes();
    if b[4] != b'-' || b[7] != b'-' {
        return None;
    }
    let year = digits(&text[0..4])?;
    let month = digits(&text[5..7])?;
    let day = digits(&text[8..10])?;
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return None;
    }
    Some(days_from_civil(year, month, day))
}

/// `HH:MM:SS` to seconds of the day, plus the unparsed remainder.
fn parse_time(text: &str) -> Option<(i64, &str)> {
    let b = text.as_bytes();
    if b.len() < 8 || b[2] != b':' || b[5] != b':' {
        return None;
    }
    let hours = digits(&text[0..2])?;
    let minutes = digits(&text[3..5])?;
    // 60 allows a leap second.
    let seconds = digits(&text[6..8])?;
    if hours > 23 || minutes > 59 || seconds > 60 {
        return None;
    }
    Some((hours * 3600 + minutes * 60 + seconds, &text[8..]))
}

/// Optional `.fff…` to milliseconds, plus the unparsed remainder.
fn parse_fraction(text: &str) -> Option<(i64, &str)> {
    let Some(fraction) = text.strip_prefix('.') else {
        return Some((0, text));
    };
    let len = fraction.bytes().take_while(u8::is_ascii_digit).count();
    if len == 0 {
        return None;
    }
    let millis = fraction[..len.min(3)]
        .bytes()
        .chain(std::iter::repeat(b'0'))
        .take(3)
        .fold(0, |acc, digit| acc * 10 + i64::from(digit - b'0'));
    Some((millis, &fraction[len..]))
}

/// `Z` or `±HH:MM` to an offset in seconds east of UTC.
fn parse_offset(text: &str) -> Option<i64> {
    if text.eq_ignore_ascii_case("z") {
        return Some(0);
    }
    let sign = match text.as_bytes().first()? {
        b'+' => 1,
        b'-' => -1,
        _ => return None,
    };
    let b = text.as_bytes();
    if b.len() != 6 || b[3] != b':' {
        return None;
    }
    let hours = digits(&text[1..3])?;
    let minutes = digits(&text[4..6])?;
    Some(sign * (hours * 3600 + minutes * 60))
}

fn digits(text: &str) -> Option<i64> {
    if text.bytes().all(|b| b.is_ascii_digit()) {
        text.parse().ok()
    } else {
        None
    }
}

/// Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm).
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = year.div_euclid(400);
    let year_of_era = year.rem_euclid(400);
    let month_index = (month + 9) % 12;
    let day_of_year = (153 * month_index + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

/// Inverse of [`days_from_civil`].
fn civil_from_days(days: i64) -> (i64, i64, i64) {
    let days = days + 719_468;
    let era = days.div_euclid(146_097);
    let day_of_era = days.rem_euclid(146_097);
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_index = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_index + 2) / 5 + 1;
    let month = if month_index < 10 {
        month_index + 3
    } else {
        month_index - 9
    };
    let year = year_of_era + era * 400 + i64::from(month <= 2);
    (year, month, day)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_chrono_and_javascript_formats() {
        assert_eq!(parse_timestamp("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(
            parse_timestamp("2020-01-01T00:00:00Z"),
            Some(1_577_836_800_000)
        );
        assert_eq!(
            parse_timestamp("2020-01-01T00:00:00.123Z"),
            Some(1_577_836_800_123)
        );
        assert_eq!(
            parse_timestamp("2020-01-01T00:00:00.123456789Z"),
            Some(1_577_836_800_123)
        );
        assert_eq!(
            parse_timestamp("2020-01-01T00:00:00.5Z"),
            Some(1_577_836_800_500)
        );
    }

    #[test]
    fn applies_offsets() {
        assert_eq!(
            parse_timestamp("2020-01-01T02:00:00+02:00"),
            parse_timestamp("2020-01-01T00:00:00Z")
        );
        assert_eq!(
            parse_timestamp("2019-12-31T21:00:00-03:00"),
            parse_timestamp("2020-01-01T00:00:00Z")
        );
    }

    #[test]
    fn rejects_malformed_input() {
        for text in [
            "",
            "2020-01-01",
            "2020-13-01T00:00:00Z",
            "2020-01-01T25:00:00Z",
            "2020-01-01T00:00:00",
            "2020-01-01T00:00:00.Z",
            "2020-01-01T00:00:00+0200",
            "not a timestamp at all",
        ] {
            assert_eq!(parse_timestamp(text), None, "{text}");
        }
    }

    #[test]
    fn formats_like_to_iso_string() {
        assert_eq!(format_timestamp(0), "1970-01-01T00:00:00.000Z");
        assert_eq!(
            format_timestamp(1_709_210_096_789),
            "2024-02-29T12:34:56.789Z"
        );
        assert_eq!(format_timestamp(-1), "1969-12-31T23:59:59.999Z");
    }

    #[test]
    fn format_and_parse_round_trip() {
        for ms in [0, 951_782_400_000, 1_709_210_096_789, 4_102_444_799_999] {
            assert_eq!(parse_timestamp(&format_timestamp(ms)), Some(ms));
        }
    }
}
