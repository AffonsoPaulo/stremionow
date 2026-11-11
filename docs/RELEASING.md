# Releasing

Releases are built by the [Release workflow](../.github/workflows/release.yml) when a version tag is pushed. One job builds unsigned phone and TV APKs without access to any secret; a second job, which runs no third-party actions, signs them with `apksigner`, writes their SHA-256 checksums and opens a draft GitHub release with generated notes.

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

1. Update `versionCode` and `versionName` in `android/app-mobile/build.gradle.kts` and `android/app-tv/build.gradle.kts`.
2. Commit with `chore(release): vX.Y.Z` and push to `main`.
3. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z`.
4. Review the draft release the workflow creates, then publish it.

## Signing locally

The release build signs itself when these environment variables are set; without them it produces unsigned APKs.

| Variable | Value |
| --- | --- |
| `STREMIONOW_KEYSTORE_FILE` | Path to the keystore |
| `STREMIONOW_KEYSTORE_PASSWORD` | Keystore password |
| `STREMIONOW_KEY_ALIAS` | Key alias |
| `STREMIONOW_KEY_PASSWORD` | Key password |
