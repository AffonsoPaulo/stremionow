# 0004. Android baseline: SDK levels, ABIs and native build

Date: 2026-11-11
Status: Accepted

## Context

The Android apps must run on phones, Android TV and Fire TV, including inexpensive and older TV sticks, and must be accepted by Google Play's current requirements. The Rust core ships as a native library.

## Decision

- **minSdk 26** (Android 8.0): covers Fire OS 7 and later, Android TV 8 and later, and lets Compose use variable fonts natively.
- **compileSdk 37**, **targetSdk 36**: compileSdk follows what current AndroidX libraries require; targetSdk moves up after each behavior-change review.
- **ABIs**: `arm64-v8a`, `armeabi-v7a` (32-bit TV sticks) and `x86_64` (emulators). APKs are filtered to these, so a dependency cannot add an ABI without the core.
- **16 KB page alignment** for every native library, required by Google Play and by devices with 16 KB memory pages. NDK 27 does not do it by default, so `core/.cargo/config.toml` passes `-z max-page-size=16384` to the linker.
- **Build integration**: the `stremionow.rust-core` Gradle plugin builds the core with cargo-ndk and generates the Kotlin bindings with UniFFI from a host debug build, because release builds strip the metadata UniFFI reads.
- **Separate apps** for phone (`io.github.affonsopaulo.stremionow`) and TV (`io.github.affonsopaulo.stremionow.tv`), sharing the `ui` and `core-bridge` modules.

## Consequences

- Devices older than Android 8.0 (some first-generation Fire TV Sticks) are not supported.
- Building needs Rust, cargo-ndk and the pinned NDK in addition to the Android SDK.
- Two application IDs mean two installs on a device that is both phone and TV, which is rare.
