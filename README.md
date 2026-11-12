# StremioNow

A fast, native client for the Stremio addon ecosystem, built for Android phones, Android TV, Fire TV and the desktop (Windows, macOS, Linux).

> Status: early development. The Android phone and TV apps browse, play (web, debrid and torrent sources) and sync with a Stremio account. The desktop app shares their interface and plays through mpv; it is tested by hand on Windows, and builds, passes its tests and plays a test pattern in CI on macOS and Linux.

StremioNow does not host or provide any content. It plays what the addons you install point to. By default it installs the same addons as the official Stremio app: catalogs, metadata, subtitles and links to streaming services, but no source of copyrighted content.

## Why

- **Fast**: native interfaces per device family and a shared core in Rust.
- **Plays everything**: libmpv on every platform, so MKV, HEVC, HDR, DTS and styled ASS subtitles work without transcoding.
- **Picks the right source for you**: your first-run preferences rank the sources; switching is always one click away, even during playback.
- **Built for the remote**: full D-pad navigation with a visible focus everywhere on TV.
- **Skips what you've seen before**: intros, recaps and credits, found in the file's chapters and, for anime, on [AniSkip](https://aniskip.com), which you can turn off in Settings ([ADR 0009](docs/adr/0009-aniskip.md)).
- **Your choice of account**: use your Stremio account to sync addons, library and progress, or keep everything on the device.
- **One device torrents for the others**: turn on "Serve to other devices" on a computer, and a weak TV or phone on the same home network plays torrents through it. Pairing takes a six-digit code, and devices only talk to each other over encrypted, pinned connections ([ADR 0008](docs/adr/0008-serve-to-other-devices.md)).

## Architecture

| Layer | Technology |
| --- | --- |
| Android (phone, Android TV, Fire TV) | Kotlin, Jetpack Compose, Compose for TV |
| Desktop (Windows, macOS, Linux) | Compose Multiplatform over a native mpv window ([ADR 0005](docs/adr/0005-desktop-ui.md)) |
| Player | libmpv |
| Core | Rust: addons, Stremio account sync, library and progress, source ranking, torrent streaming (librqbit) behind a local HTTP server, serving torrents to paired devices on the home network, AniSkip markers |
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

Desktop app (needs the Android SDK too, since it includes the `android/` build for the shared modules; cargo-ndk and the NDK are not needed). Gradle builds the core for the machine, and on Windows downloads a pinned, checksum-verified libmpv. The app only ever loads the libmpv it bundles; until macOS and Linux packages bundle one, development runs there use the system's: `brew install mpv` on macOS, `sudo apt install libmpv2` (Debian, Ubuntu) on Linux. On a Wayland session the app runs through XWayland.

```
cd desktop
./gradlew run                       # add --args="--verbose" for mpv's detailed logs
./gradlew run --args="--smoke-test" # start, play a test pattern through libmpv, exit 0 when it worked
```

It keeps its data in `%APPDATA%\StremioNow` (Windows), `~/Library/Application Support/StremioNow` (macOS) or `$XDG_DATA_HOME/stremionow` (Linux), and the Stremio session key in the system's secret store (DPAPI, the login keychain, or the Secret Service through libsecret; without one, the session lasts until the app closes).

## Privacy

StremioNow has no server of its own. It talks only to the addons you install and to what they point to (artwork, debrid services, torrent peers), and, if you sign in, to your Stremio account.

**Crash reports** are opt-in and never sent on their own. When the app closes unexpectedly, it saves a report on the device: the app version, the operating system and its version, the device type (phone, tablet, TV, desktop), the processor architecture, the interface language, the error and where it happened, and the app's last 50 warnings. Before the report is saved, addresses lose everything but their host (debrid and addon keys live in the rest), and keys, tokens, email addresses, IP addresses and file paths in your home folder are removed. On the next launch the app asks whether to send it: **View report** shows exactly what it holds, and **Send report** opens a pre-filled issue on GitHub, which you review and submit from your own account (on a TV, a QR code opens it on your phone). **Don't send** deletes it. To stop saving reports altogether, choose **Never** in Settings › Privacy › Crash reports. Details are in [ADR 0007](docs/adr/0007-crash-reports.md).

## Languages

The interface ships in English and Brazilian Portuguese. Translations are welcome; see [`l10n/README.md`](l10n/README.md).

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) and the [code of conduct](CODE_OF_CONDUCT.md) before opening a pull request.

## License

StremioNow is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).

---

**Em português:** StremioNow é um cliente rápido e nativo para o ecossistema de addons do Stremio, para Android, Android TV, Fire TV e desktop. O app não hospeda nem fornece conteúdo. Está em desenvolvimento inicial: os apps de celular e TV já navegam, reproduzem fontes web, de debrid e torrent e sincronizam com a conta do Stremio.
