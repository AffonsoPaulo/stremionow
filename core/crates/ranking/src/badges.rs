// SPDX-License-Identifier: GPL-3.0-or-later
//! Short technical labels for the UI.

use crate::traits::Traits;

/// Short technical labels for a source, in display order: resolution, HDR formats, codec,
/// audio formats, release quality, size. For example
/// `["4K", "DV", "HDR10", "HEVC", "Atmos", "Remux", "14.2 GB"]`. Labels are not localized.
pub fn badges(traits: &Traits) -> Vec<String> {
    let labels = traits
        .resolution
        .map(|r| r.label())
        .into_iter()
        .chain(traits.hdr.iter().map(|h| h.label()))
        .chain(traits.codec.map(|c| c.label()))
        .chain(traits.audio.iter().map(|a| a.label()))
        .chain(traits.quality.map(|q| q.label()));
    labels
        .map(str::to_owned)
        .chain(traits.size_bytes.map(format_size))
        .collect()
}

/// Formats a size with powers of 1024, matching how addons compute it: `"14.2 GB"`,
/// `"700 MB"`.
fn format_size(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    // Precision loss above 2^53 bytes is irrelevant for one decimal.
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    if unit >= 3 {
        format!("{value:.1} {}", UNITS[unit])
    } else {
        format!("{value:.0} {}", UNITS[unit])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{SourceText, parse};

    #[test]
    fn torrentio_badges() {
        let traits = parse(&SourceText {
            name: "Torrentio\n4k DV | HDR",
            title: "Movie.Name.2023.2160p.WEB-DL.DV.HDR.DDP5.1.Atmos.H265-GRP\n👤 154 💾 14.2 GB",
            description: "",
        });
        assert_eq!(
            badges(&traits),
            [
                "4K", "DV", "HDR10", "HEVC", "Atmos", "DD+", "WEB-DL", "14.2 GB"
            ]
        );
    }

    #[test]
    fn empty_traits_have_no_badges() {
        assert!(badges(&Traits::default()).is_empty());
    }

    #[test]
    fn sizes() {
        assert_eq!(format_size(700 << 20), "700 MB");
        assert_eq!(format_size(3 << 30), "3.0 GB");
        assert_eq!(format_size(2 << 40), "2.0 TB");
        assert_eq!(format_size(512), "512 B");
        assert_eq!(format_size(u64::MAX), "16777216.0 TB");
    }
}
