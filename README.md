# StremioNow

A fast, native client for the Stremio addon ecosystem, built for Android phones, Android TV, Fire TV and the desktop (Windows, macOS, Linux).

> Status: early development (phase 0). Nothing is installable yet.

StremioNow does not host or provide any content. It plays what the addons you install point to. Only catalog and subtitle addons are installed by default.

## Why

- **Fast**: native interfaces per device family and a shared core in Rust.
- **Plays everything**: libmpv on every platform, so MKV, HEVC, HDR, DTS and styled ASS subtitles work without transcoding.
- **Picks the right source for you**: your first-run preferences rank the sources; switching is always one click away, even during playback.
- **Built for the remote**: full D-pad navigation with a visible focus everywhere on TV.
- **Your choice of account**: use your Stremio account to sync addons, library and progress, or keep everything on the device.

## Architecture

| Layer | Technology |
| --- | --- |
| Android (phone, Android TV, Fire TV) | Kotlin, Jetpack Compose, Compose for TV |
| Desktop (Windows, macOS, Linux) | Compose Multiplatform or Qt/QML (decided in phase 0) |
| Player | libmpv |
| Core | Rust: addons, Stremio account sync, library, source ranking, torrent (librqbit), skip-intro markers |
| Bridge | UniFFI (Kotlin) |

See [`docs/adr`](docs/adr) for the reasoning behind each decision.

## Repository layout

```
core/          Rust workspace (shared logic)
android/       Android apps (phone and TV), shared UI and the core bridge
desktop/       Desktop app                                  (phase 5)
design/        Design tokens, the single source of colors, type and spacing
l10n/          UI strings: English source and translations
docs/adr/      Architecture decision records
third_party/   Licenses of bundled third-party assets
```

## Building

Requirements:

- Rust stable via [rustup](https://rustup.rs); the Android targets install automatically from `core/rust-toolchain.toml`
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk): `cargo install cargo-ndk`
- JDK 17 or newer and the Android SDK with NDK 27.1.12297006 (Android Studio covers both)

Core tests:

```
cd core
cargo test --workspace
```

Android apps (the Gradle build compiles the core and generates its Kotlin bindings):

```
cd android
./gradlew assembleDebug
```

The APKs land in `android/app-mobile/build/outputs/apk/debug/` (phone) and `android/app-tv/build/outputs/apk/debug/` (Android TV and Fire TV).

## Languages

The interface ships in English and Brazilian Portuguese. Translations are welcome; see [`l10n/README.md`](l10n/README.md).

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) and the [code of conduct](CODE_OF_CONDUCT.md) before opening a pull request.

## License

StremioNow is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).

---

**Em português:** StremioNow é um cliente rápido e nativo para o ecossistema de addons do Stremio, para Android, Android TV, Fire TV e desktop. O app não hospeda nem fornece conteúdo. Está em desenvolvimento inicial.
