# 0004. Android baseline: SDK levels, ABIs and native build

Date: 2026-11-11
Status: Accepted

## Context

The Android apps must run on phones, Android TV and Fire TV, including inexpensive and older TV sticks, and must be accepted by Google Play's current requirements. The Rust core ships as a native library. The project tracks the latest stable toolchains.

## Decision

- **minSdk 26** (Android 8.0): covers Fire OS 7 and later, Android TV 8 and later, and lets Compose use variable fonts natively.
- **compileSdk 37.2** and **targetSdk 37**: the latest stable platform.
- **Java 21** bytecode; builds run on JDK 25, the current LTS.
- **NDK 30**, the latest stable.
- **ABIs**: `arm64-v8a`, `armeabi-v7a` (32-bit TV sticks) and `x86_64` (emulators). APKs are filtered to these, so a dependency cannot add an ABI without the core.
- **16 KB page alignment** for every native library, required by Google Play and by devices with 16 KB memory pages. NDK 28 and later do it by default; `core/.cargo/config.toml` also passes `-z max-page-size=16384` so the guarantee does not depend on the NDK.
- **Convention plugins** in `android/build-logic` hold the shared configuration (`stremionow.android.application`, `stremionow.android.library`), and every version lives in `android/gradle/libs.versions.toml`, so an upgrade is a one-line change.
- **Build integration**: the `stremionow.rust-core` plugin builds the core with cargo-ndk and generates the Kotlin bindings with UniFFI from a host debug build, because release builds strip the metadata UniFFI reads.
- **Separate apps** for phone (`io.github.affonsopaulo.stremionow`) and TV (`io.github.affonsopaulo.stremionow.tv`), sharing the `ui` and `core-bridge` modules.

## Consequences

- Devices older than Android 8.0 (some first-generation Fire TV Sticks) are not supported.
- Building needs Rust, cargo-ndk and the pinned NDK in addition to the Android SDK.
- Two application IDs mean two installs on a device that is both phone and TV, which is rare.
- Dependabot opens weekly update pull requests; each must pass CI before it is merged.
