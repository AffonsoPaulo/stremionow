# 0001. Native UI per device family over a shared Rust core

Date: 2026-11-11
Status: Accepted

## Context

StremioNow targets Android phones, Android TV, Fire TV, Windows, macOS and Linux. The main complaint about the official app is speed, and the player must handle MKV, HEVC, HDR, DTS and ASS subtitles without transcoding. TV needs first-class remote navigation.

Options considered:

- **Flutter**: one codebase for all targets; weaker TV focus support and system integration need manual bridges.
- **Web UI with Tauri or Electron**: the browser cannot play many formats without transcoding, which is a main cause of the official app's slowness.
- **React Native**: no Linux.
- **Kotlin + Compose Multiplatform**: excellent on Android and Android TV; desktop video embedding is less proven.
- **Qt/QML**: excellent desktop video with mpv; awkward on Android TV.
- **One native app per OS**: best quality, too much to maintain.

## Decision

Group platforms into native families that share one Rust core:

- **Android family** (phone, Android TV, Fire TV): Kotlin, Jetpack Compose and Compose for TV.
- **Desktop family** (Windows, macOS, Linux): Compose Multiplatform if a phase 0 proof of concept shows libmpv embeds well; otherwise Qt/QML. That choice gets its own ADR.
- **Core** in Rust: addon client, Stremio account sync, local library, source ranking, torrent engine (librqbit) and skip-intro markers, exposed to Kotlin through UniFFI.
- **Player**: libmpv in both families.

Samsung and LG TVs are out of scope for v1; if added later, they will be a web app.

## Consequences

- Logic is written and tested once, in Rust.
- UI work is roughly 1.8 to 2 times that of a single cross-platform toolkit, since each screen exists in up to two interfaces.
- Contributors need Kotlin and Rust (plus QML if the desktop goes Qt).
- Design tokens must generate a theme per interface so the look stays consistent.
