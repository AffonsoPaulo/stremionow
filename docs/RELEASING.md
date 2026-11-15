# Releasing

A release carries the Android APKs and the Windows app. The APKs are built by the [Release workflow](../.github/workflows/release.yml) when a version tag is pushed: one job builds unsigned phone and TV APKs without access to any secret; a second job, which runs no third-party actions, signs them with `apksigner`, writes their SHA-256 checksums and opens a draft GitHub release with generated notes, or adds them to the release that already exists. The Windows files are built on a Windows machine and attached to the same release (see [Windows app](#windows-app)).

Each app ships as one APK per ABI plus a universal APK, named `StremioNow-<tag>-<phone|tv>-<abi>.apk`. Most phones and TVs need `arm64-v8a`; `armeabi-v7a` covers older and low-end devices (many Fire TV sticks); the universal APK works everywhere at about three times the size.

## One-time setup: signing key

1. Create a keystore and keep it, with its passwords, somewhere safe outside the repository. Losing it means existing installs cannot be updated.

   ```
   keytool -genkeypair -v -keystore stremionow-release.jks -alias stremionow -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add these repository secrets under **Settings › Secrets and variables › Actions**:

   | Secret | Value |
   | --- | --- |
   | `STREMIONOW_KEYSTORE_BASE64` | The keystore file, base64-encoded (`base64 -w0 stremionow-release.jks`) |
   | `STREMIONOW_KEYSTORE_PASSWORD` | Keystore password |
   | `STREMIONOW_KEY_ALIAS` | Key alias, for example `stremionow` |
   | `STREMIONOW_KEY_PASSWORD` | Key password |

## Cutting a release

1. Update `versionCode` and `versionName` in `android/app-mobile/build.gradle.kts` and `android/app-tv/build.gradle.kts`, and `appVersion` in `desktop/app/build.gradle.kts`, to the same version.
2. Commit with `chore(release): vX.Y.Z` and push to `main`.
3. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z`.
4. Attach the Windows files (below).
5. Review the draft release, then publish it.

## Windows app

The installed Windows apps update from the **latest published release**: Velopack's `releases.win.json` and the full package it lists (see `AppUpdates.kt`). So every published release must carry the Windows files, and each needs a higher `appVersion` than the last, since an app never installs a version that is not newer than its own.

On Windows, with the [.NET SDK](https://dotnet.microsoft.com/download) 8 or newer, a full JDK 25 and the [GitHub CLI](https://cli.github.com) signed in (`gh auth login`):

```
cd desktop
dotnet tool restore
./gradlew :app:packageVelopack :app:publishVelopack -Pstremionow.packaging.javaHome="C:/path/to/jdk-25"
```

`publishVelopack` attaches `StremioNow-win.msi` (the installer), `StremioNow-win-Portable.zip`, `StremioNow-X.Y.Z-full.nupkg` and `releases.win.json` to the release `vX.Y.Z`, replacing earlier copies. If there is no release yet, it creates a draft at the commit checked out (push it first); publishing the draft creates the tag, and the workflow then adds the APKs. The installer keeps the name `StremioNow-win.msi` in every release, so `releases/latest/download/StremioNow-win.msi` always gives the newest one.

The installer is not code-signed yet, so Windows SmartScreen warns people who download it until it builds a reputation.

## Signing locally

The release build signs itself when these environment variables are set; without them it produces unsigned APKs.

| Variable | Value |
| --- | --- |
| `STREMIONOW_KEYSTORE_FILE` | Path to the keystore |
| `STREMIONOW_KEYSTORE_PASSWORD` | Keystore password |
| `STREMIONOW_KEY_ALIAS` | Key alias |
| `STREMIONOW_KEY_PASSWORD` | Key password |
