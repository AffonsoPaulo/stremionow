# StremioNow

A fast, native client for the Stremio addon ecosystem, built for Android phones, Android TV, Fire TV and the desktop (Windows, macOS, Linux).

> Status: early development. The Android phone and TV apps browse, play (web, debrid and torrent sources) and sync with a Stremio account. The desktop app shares their interface and plays through mpv; it is tested on Windows only so far.

StremioNow does not host or provide any content. It plays what the addons you install point to. By default it installs the same addons as the official Stremio app: catalogs, metadata, subtitles and links to streaming services, but no source of copyrighted content.

## Why

- **Fast**: native interfaces per device family and a shared core in Rust.
- **Plays everything**: libmpv on every platform, so MKV, HEVC, HDR, DTS and styled ASS subtitles work without transcoding.
- **Picks the right source for you**: your first-run preferences rank the sources; switching is always one click away, even during playback.
- **Built for the remote**: full D-pad navigation with a visible focus everywhere on TV.
- **Your choice of account**: use your Stremio account to sync addons, library and progress, or keep everything on the device.
- **One device torrents for the others**: turn on "Serve to other devices" on a computer, and a weak TV or phone on the same home network plays torrents through it. Pairing takes a six-digit code, and devices only talk to each other over encrypted, pinned connections ([ADR 0008](docs/adr/0008-serve-to-other-devices.md)).

## Architecture

| Layer | Technology |
| --- | --- |
| Android (phone, Android TV, Fire TV) | Kotlin, Jetpack Compose, Compose for TV |
| Desktop (Windows, macOS, Linux) | Compose Multiplatform over a native mpv window ([ADR 0005](docs/adr/0005-desktop-ui.md)) |
| Player | libmpv |
| Core | Rust: addons, Stremio account sync, library and progress, source ranking, torrent streaming (librqbit) behind a local HTTP server, serving torrents to paired devices on the home network |
| Bridge | UniFFI (Kotlin) |

See [`docs/adr`](docs/adr) for the reasoning behind each decision.

## Repository layout

```
core/          Rust workspace (shared logic)
android/       Android apps (phone and TV), and the shared Kotlin Multiplatform modules:
               interface (ui), player and core bridge (see docs/adr/0006-shared-ui.md)
desktop/       Desktop app (Compose Multiplatform); includes the android/ build for the shared modules
design/        Design tokens, the single source of colors, type and spacing
l10n/          UI strings: English source and translations
docs/adr/      Architecture decision records
third_party/   Licenses of bundled third-party assets
```

## Building

Requirements:

- Rust stable via [rustup](https://rustup.rs); the Android targets install automatically from `core/rust-toolchain.toml`
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk): `cargo install cargo-ndk`
- JDK 21 or newer (25 recommended) and the Android SDK with platform 37.2 and NDK 30.0.16248370; exact versions are pinned in [`android/gradle/libs.versions.toml`](android/gradle/libs.versions.toml)

Core tests:

```
cd core
cargo test --workspace
```

To try serving to other devices without the app, run the headless server from `core/` and pair a device with the code it prints (by default it listens on loopback, which the Android emulator reaches as `10.0.2.2`; add `--lan` to serve the network it starts on, and `--repeat-codes` for a new code after each one is used or expires):

```
cargo run -p stremionow-torrent --features dev-loopback --example serve
```

Android apps (the Gradle build compiles the core and generates its Kotlin bindings):

```
cd android
./gradlew assembleDebug
```

The APKs land in `android/app-mobile/build/outputs/apk/debug/` (phone) and `android/app-tv/build/outputs/apk/debug/` (Android TV and Fire TV): one per ABI (`arm64-v8a` for most phones and TVs, `armeabi-v7a` for older or low-end devices, `x86_64` for emulators) and a universal one. Release builds and signing are described in [`docs/RELEASING.md`](docs/RELEASING.md).

Desktop app (needs the Android SDK too, since it includes the `android/` build for the shared modules; cargo-ndk and the NDK are not needed). Gradle builds the core for the machine, and on Windows downloads a pinned libmpv; on macOS and Linux, install libmpv from the system (`brew install mpv`, `libmpv2`):

```
cd desktop
./gradlew run                 # add --args="--verbose" for mpv's detailed logs
```

It keeps its data in `%APPDATA%\StremioNow` (Windows), `~/Library/Application Support/StremioNow` (macOS) or `$XDG_DATA_HOME/stremionow` (Linux).

## Languages

The interface ships in English and Brazilian Portuguese. Translations are welcome; see [`l10n/README.md`](l10n/README.md).

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) and the [code of conduct](CODE_OF_CONDUCT.md) before opening a pull request.

## License

StremioNow is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).

---

**Em português:** StremioNow é um cliente rápido e nativo para o ecossistema de addons do Stremio, para Android, Android TV, Fire TV e desktop. O app não hospeda nem fornece conteúdo. Está em desenvolvimento inicial: os apps de celular e TV já navegam, reproduzem fontes web, de debrid e torrent e sincronizam com a conta do Stremio.
