// SPDX-License-Identifier: GPL-3.0-or-later
//! The `Range` request header (RFC 9110, section 14.2), reduced to what a video player sends:
//! one byte range per request.

/// Inclusive byte positions inside a resource, already clamped to its length.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct ByteRange {
    pub(crate) start: u64,
    pub(crate) end: u64,
}

impl ByteRange {
    /// Number of bytes covered. Never zero: `end` is inclusive and `start <= end`.
    pub(crate) fn len(&self) -> u64 {
        self.end - self.start + 1
    }

    /// Value of the `Content-Range` header describing this part of a `total`-byte resource.
    pub(crate) fn content_range(&self, total: u64) -> String {
        format!("bytes {}-{}/{total}", self.start, self.end)
    }
}

/// What to send for a request, given its `Range` header and the length of the resource.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RangeRequest {
    /// No range, or one the server must ignore: send everything with 200.
    Full,
    /// Send this part with 206.
    Partial(ByteRange),
    /// A well-formed range that lies outside the resource: answer 416.
    Unsatisfiable,
}

/// Resolves a `Range` header value against a resource of `total` bytes.
///
/// Malformed values and units other than `bytes` are ignored, as RFC 9110 requires. When
/// several ranges are listed only the first one is served: players never ask for more, and a
/// `multipart/byteranges` body would only complicate them.
pub(crate) fn resolve(header: Option<&str>, total: u64) -> RangeRequest {
    let Some(spec) = header.and_then(first_range_spec) else {
        return RangeRequest::Full;
    };
    match parse_spec(spec) {
        Some(spec) => spec.resolve(total),
        None => RangeRequest::Full,
    }
}

/// Text of the first range in a `bytes=` header, without surrounding whitespace.
fn first_range_spec(header: &str) -> Option<&str> {
    let (unit, ranges) = header.trim().split_once('=')?;
    if !unit.trim().eq_ignore_ascii_case("bytes") {
        return None;
    }
    let first = ranges.split(',').next()?.trim();
    (!first.is_empty()).then_some(first)
}

/// A syntactically valid range, before it is checked against the resource length.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Spec {
    /// `first-` or `first-last`.
    From { first: u64, last: Option<u64> },
    /// `-length`: the final `length` bytes.
    Suffix { length: u64 },
}

fn parse_spec(spec: &str) -> Option<Spec> {
    let (first, last) = spec.split_once('-')?;
    let (first, last) = (first.trim(), last.trim());
    if first.is_empty() {
        return Some(Spec::Suffix {
            length: parse_digits(last)?,
        });
    }
    let first = parse_digits(first)?;
    let last = if last.is_empty() {
        None
    } else {
        Some(parse_digits(last)?)
    };
    if last.is_some_and(|last| last < first) {
        return None;
    }
    Some(Spec::From { first, last })
}

/// Parses a non-empty run of ASCII digits. Unlike `str::parse`, rejects a leading `+`.
fn parse_digits(text: &str) -> Option<u64> {
    if text.is_empty() || !text.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    text.parse().ok()
}

impl Spec {
    fn resolve(self, total: u64) -> RangeRequest {
        if total == 0 {
            return RangeRequest::Unsatisfiable;
        }
        let last_byte = total - 1;
        let range = match self {
            Spec::From { first, .. } if first > last_byte => return RangeRequest::Unsatisfiable,
            Spec::From { first, last } => ByteRange {
                start: first,
                end: last.map_or(last_byte, |last| last.min(last_byte)),
            },
            Spec::Suffix { length: 0 } => return RangeRequest::Unsatisfiable,
            Spec::Suffix { length } => ByteRange {
                start: total.saturating_sub(length),
                end: last_byte,
            },
        };
        RangeRequest::Partial(range)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn partial(start: u64, end: u64) -> RangeRequest {
        RangeRequest::Partial(ByteRange { start, end })
    }

    #[test]
    fn no_header_means_full_body() {
        assert_eq!(resolve(None, 1000), RangeRequest::Full);
    }

    #[test]
    fn open_ended_range_runs_to_the_end() {
        assert_eq!(resolve(Some("bytes=0-"), 1000), partial(0, 999));
        assert_eq!(resolve(Some("bytes=900-"), 1000), partial(900, 999));
    }

    #[test]
    fn closed_range_is_inclusive() {
        let request = resolve(Some("bytes=100-199"), 1000);
        assert_eq!(request, partial(100, 199));
        let RangeRequest::Partial(range) = request else {
            panic!("expected a partial response");
        };
        assert_eq!(range.len(), 100);
        assert_eq!(range.content_range(1000), "bytes 100-199/1000");
    }

    #[test]
    fn range_past_the_end_is_clamped() {
        assert_eq!(resolve(Some("bytes=500-5000"), 1000), partial(500, 999));
    }

    #[test]
    fn suffix_range_counts_from_the_end() {
        assert_eq!(resolve(Some("bytes=-500"), 1000), partial(500, 999));
        assert_eq!(resolve(Some("bytes=-5000"), 1000), partial(0, 999));
    }

    #[test]
    fn multiple_ranges_serve_the_first() {
        assert_eq!(resolve(Some("bytes=0-99, 200-299"), 1000), partial(0, 99));
        assert_eq!(
            resolve(Some("bytes=2000-2100,0-99"), 1000),
            RangeRequest::Unsatisfiable
        );
    }

    #[test]
    fn ranges_outside_the_resource_are_unsatisfiable() {
        assert_eq!(
            resolve(Some("bytes=1000-"), 1000),
            RangeRequest::Unsatisfiable
        );
        assert_eq!(resolve(Some("bytes=-0"), 1000), RangeRequest::Unsatisfiable);
        assert_eq!(resolve(Some("bytes=0-"), 0), RangeRequest::Unsatisfiable);
    }

    #[test]
    fn malformed_headers_are_ignored() {
        for header in [
            "",
            "bytes",
            "bytes=",
            "bytes=-",
            "bytes=abc-def",
            "bytes=+5-10",
            "bytes=10-+20",
            "bytes=200-100",
            "bytes=1-2-3",
            "bytes=99999999999999999999999-",
            "items=0-10",
            "bytes=\u{663}-",
        ] {
            assert_eq!(
                resolve(Some(header), 1000),
                RangeRequest::Full,
                "{header:?}"
            );
        }
    }

    #[test]
    fn unit_and_whitespace_are_lenient() {
        assert_eq!(resolve(Some(" Bytes = 1 - 2 "), 1000), partial(1, 2));
    }
}
