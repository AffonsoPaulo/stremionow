// SPDX-License-Identifier: GPL-3.0-or-later
//! Input text and the technical traits extracted from it.

/// The free-text fields an addon attaches to a stream. Any of them may be empty.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct SourceText<'a> {
    /// Stream `name`, usually the addon name plus a short quality line (`"Torrentio\n4k DV"`).
    pub name: &'a str,
    /// Stream `title`, usually the release name, size, seeders and languages.
    pub title: &'a str,
    /// Stream `description`, used by newer addons instead of `title`.
    pub description: &'a str,
}

/// Vertical resolution class, ordered from lowest to highest.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Resolution {
    /// Anything below 720p (480p, 576p, ...).
    Sd,
    /// 720p.
    P720,
    /// 1080p, "FHD".
    P1080,
    /// 1440p, "QHD".
    P1440,
    /// 2160p, "4K", "UHD".
    P2160,
    /// 4320p, "8K".
    P4320,
}

impl Resolution {
    /// Short label for the UI, such as `"4K"` or `"1080p"`.
    pub fn label(self) -> &'static str {
        match self {
            Self::Sd => "SD",
            Self::P720 => "720p",
            Self::P1080 => "1080p",
            Self::P1440 => "1440p",
            Self::P2160 => "4K",
            Self::P4320 => "8K",
        }
    }
}

/// High dynamic range format.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Hdr {
    /// Dolby Vision ("DV", "DoVi").
    DolbyVision,
    /// HDR10+.
    Hdr10Plus,
    /// HDR10, also used for a generic "HDR" mention.
    Hdr10,
    /// Hybrid log-gamma.
    Hlg,
}

impl Hdr {
    /// Short label for the UI.
    pub fn label(self) -> &'static str {
        match self {
            Self::DolbyVision => "DV",
            Self::Hdr10Plus => "HDR10+",
            Self::Hdr10 => "HDR10",
            Self::Hlg => "HLG",
        }
    }
}

/// Video codec.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Codec {
    /// H.264 / AVC.
    Avc,
    /// H.265 / HEVC.
    Hevc,
    /// AV1.
    Av1,
}

impl Codec {
    /// Short label for the UI.
    pub fn label(self) -> &'static str {
        match self {
            Self::Avc => "AVC",
            Self::Hevc => "HEVC",
            Self::Av1 => "AV1",
        }
    }
}

/// Audio format, ordered roughly from most to least premium.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Audio {
    /// Dolby Atmos (carried by TrueHD or DD+).
    Atmos,
    /// Dolby TrueHD.
    TrueHd,
    /// DTS:X.
    DtsX,
    /// DTS-HD Master Audio.
    DtsHdMa,
    /// DTS core or DTS-HD without MA.
    Dts,
    /// Dolby Digital Plus (E-AC-3).
    Ddp,
    /// Dolby Digital (AC-3).
    Dd,
    /// AAC.
    Aac,
    /// Opus.
    Opus,
    /// FLAC.
    Flac,
}

impl Audio {
    /// Short label for the UI.
    pub fn label(self) -> &'static str {
        match self {
            Self::Atmos => "Atmos",
            Self::TrueHd => "TrueHD",
            Self::DtsX => "DTS:X",
            Self::DtsHdMa => "DTS-HD MA",
            Self::Dts => "DTS",
            Self::Ddp => "DD+",
            Self::Dd => "DD",
            Self::Aac => "AAC",
            Self::Opus => "Opus",
            Self::Flac => "FLAC",
        }
    }
}

/// Release source quality, ordered from worst to best.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum ReleaseQuality {
    /// Camera recording in a theater (CAM, HDCAM).
    Cam,
    /// Telesync or telecine (TS, HDTS, TC).
    Telesync,
    /// Screener (SCR, DVDSCR).
    Screener,
    /// DVD source.
    Dvd,
    /// Broadcast capture.
    Hdtv,
    /// Re-encoded web capture.
    WebRip,
    /// Untouched web download (also bare "WEB").
    WebDl,
    /// Blu-ray encode.
    BluRay,
    /// Untouched Blu-ray remux.
    Remux,
}

impl ReleaseQuality {
    /// Whether this is a theater capture or pre-release copy (CAM, TS, screener).
    pub fn is_low(self) -> bool {
        matches!(self, Self::Cam | Self::Telesync | Self::Screener)
    }

    /// Short label for the UI.
    pub fn label(self) -> &'static str {
        match self {
            Self::Cam => "CAM",
            Self::Telesync => "TS",
            Self::Screener => "SCR",
            Self::Dvd => "DVD",
            Self::Hdtv => "HDTV",
            Self::WebRip => "WEBRip",
            Self::WebDl => "WEB-DL",
            Self::BluRay => "BluRay",
            Self::Remux => "Remux",
        }
    }
}

/// Technical traits extracted from a source's text. Every field is best effort; `None` or an
/// empty list means "not mentioned", never "absent".
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Traits {
    /// Highest resolution mentioned, explicit `NNNNp` values taking precedence over words.
    pub resolution: Option<Resolution>,
    /// HDR formats, deduplicated, in [`Hdr`] declaration order.
    pub hdr: Vec<Hdr>,
    /// First video codec mentioned.
    pub codec: Option<Codec>,
    /// Audio formats, deduplicated, in [`Audio`] declaration order.
    pub audio: Vec<Audio>,
    /// Release quality; theater captures win over anything else mentioned.
    pub quality: Option<ReleaseQuality>,
    /// File size in bytes (GB and GiB are both read as powers of 1024).
    pub size_bytes: Option<u64>,
    /// Seeder count, for torrents.
    pub seeders: Option<u32>,
    /// Debrid cache state: `Some(true)` when cached, `Some(false)` when it must be downloaded
    /// first, `None` when the text does not say (for example, plain P2P or HTTP sources).
    pub cached: Option<bool>,
    /// ISO 639-1 audio language codes, deduplicated, in order of discovery. Flags count as
    /// audio; languages only said to be subtitles (`Sub.Ita`, `VOSTFR`) are not here.
    pub languages: Vec<String>,
    /// ISO 639-1 codes of the subtitle languages the text names (`Sub.Ita`, `VOSTFR`,
    /// `Legendado`), deduplicated, in order of discovery. They do not count for ranking.
    pub subtitle_languages: Vec<String>,
    /// Whether the source advertises several audio tracks ("Multi", "Dual Audio").
    pub multi_audio: bool,
    /// Indexer or tracker the source came from, such as `"ThePirateBay"`.
    pub provider: Option<String>,
    /// Group that made the release, from a scene-style name such as
    /// `Movie.2023.1080p.WEB-DL.H264-GRP`.
    pub release_group: Option<String>,
}
