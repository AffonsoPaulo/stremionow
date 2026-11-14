# 0006. One Kotlin Multiplatform interface for phone, TV and desktop

Date: 2026-11-11
Status: Accepted

## Context

ADR 0005 chose Compose Multiplatform for the desktop so that the phone, TV and desktop apps share one interface. Until now, the interface (`android/ui`) was an Android library. The desktop proof of concept lived in a separate Gradle build (`desktop/`), with its own player controls, its own copy of the theme and its own UniFFI bindings.

To share the interface, it needs:

- a structure both builds can use without making either build, its CI or the release workflow harder;
- replacements for the Android-only APIs the screens use (`Context`, the Keystore, `Intent`, `ConnectivityManager`, `Log`, activity back handling, Android resources);
- the same strings on the desktop without a second copy of the translations (ADR 0003);
- no change in Android behavior.

Options considered for the structure:

- **One root-level Gradle build** for every app: one wrapper and one command, but every path in the Android build, its CI jobs, the release workflow and the docs would move.
- **A third build for the shared modules**, included by both apps: three builds to keep in step, each with its own settings and version catalog.
- **The Android build owns the shared modules, and the desktop build includes it**: Android, CI and releases stay as they are. The cost is that desktop builds need the Android SDK installed, because Gradle configures the included Android projects.

## Decision

**Structure.** The shared modules stay in `android/` and become Kotlin Multiplatform libraries. Each has an Android target (AGP's `com.android.kotlin.multiplatform.library`) and a desktop JVM target (`jvm("desktop")`), configured by the `stremionow.multiplatform.library` convention plugin:

| Module | `commonMain` | `androidMain` | `desktopMain` |
| --- | --- | --- | --- |
| `:core-bridge` | UniFFI bindings of the Rust core | JNA AAR, the core for each ABI (cargo-ndk) | JNA; the desktop app bundles the core it builds |
| `:player` | `VideoPlayer`, `PlaybackState`, tracks, chapters, `PlayerOptions` | `MpvPlayer` on a `Surface` (libmpv AAR) | `MpvPlayer` in a native window (libmpv over JNA, from the proof of concept) |
| `:ui` | Theme and tokens, components, every screen and view model, navigation, `PlayerViewModel` and the player's panels | Android actuals, the Android player screen, media session | Desktop actuals, the two-window player (`WindowPair`, `VideoWindow`, overlay controls) |

`desktop/` includes the Android build (`includeBuild("../android")`) and depends on `io.github.affonsopaulo.stremionow:ui`; Gradle substitutes the included project. The desktop app itself only holds the entry point, the native-library setup and packaging. The phone and TV apps depend on `:ui` as before.

**JVM-only shared code.** Both targets run on the JVM, so Kotlin compiles `commonMain` once per target instead of as metadata. That lets shared code use the JDK, JNA and the UniFFI bindings directly. Adding a non-JVM target (iOS, the web) would mean putting those behind `expect`/`actual` first.

**Platform seams.** These are small `expect`/`actual` declarations in `ui/.../platform`, `data`, `theme`, `l10n` and `screens`:

| Seam | Android | Desktop |
| --- | --- | --- |
| `PlatformContext`, `LocalPlatformContext` | `Context`, `LocalContext` | One process-wide instance |
| Core data folder (`coreDataDirectory`) | `noBackupFilesDir/core` | `%APPDATA%\StremioNow`, `~/Library/Application Support/StremioNow` or `$XDG_DATA_HOME/stremionow` (default `~/.local/share/stremionow`), then `core` |
| `SecureStore` (`PlatformSecretStore`) | Android Keystore | Windows DPAPI (current user, app entropy); macOS login keychain (generic passwords, service `StremioNow`, through Security.framework); Linux Secret Service through libsecret. Where the store is missing or refuses (no libsecret or Secret Service, a locked or denied keychain) nothing is kept and the session lasts until the app closes; the first failure is logged once. Never plain text |
| `Connectivity.onMobileData` | `ConnectivityManager` | Always false: no portable way to tell, so never block |
| `rememberLocalNetworkAccess` | Android 17 permission request | No permission needed |
| `openExternalLink` | `ACTION_VIEW` intent | `Desktop.browse`, http(s) only |
| `logWarning` | `Log.w` | `System.Logger` |
| `appVersion` | Package version name | `-Dstremionow.version`, set by the launcher |
| `SnBackHandler` | `androidx.activity` `BackHandler` | `NavigationBackHandler` (Esc) |
| `deviceLanguage`, `isLargeScreen`, `screenHeightDp` | `LocalConfiguration` | Default locale, always large, window height |
| `formatShortFileSize` | `Formatter.formatShortFileSize` | Decimal units |
| `SnFontFamilies` | Font resources | The same font files, copied to the classpath at build time |
| `stringResource`, `pluralStringResource` | Android resources | Generated tables (ADR 0003) |
| `PlayerScreen` | Full-screen player in the activity | Two-window player over the app window's position |
| `displaySwitchingSupported` | False: the system handles HDR, and the surface's frame rate feeds "Match content frame rate" | True on Windows: Settings shows HDR and refresh rate switching (ADR 0005) |

Media sessions and Watch Next stay in Android code. `LifecycleEventEffect`, `collectAsStateWithLifecycle`, `viewModel` and navigation are already multiplatform.

**Libraries.** Shared code uses Compose Multiplatform 1.12.2 and JetBrains' multiplatform builds of Lifecycle (2.11.0), Navigation (2.10.0) and NavigationEvent (1.1.0), plus Coil 3.6.3. On Android these resolve to the androidx artifacts. Compose Multiplatform 1.12.2 maps to androidx Compose 1.12.1, the same version as the Compose BOM. `androidMain` keeps the BOM and the androidx versions from the catalog, so the Android dependency set does not change.

**Player.** `PlayerViewModel` is shared and is given its player through a factory: `MpvPlayer.create(context, …)` on Android, and a video window plus `MpvPlayer.create(windowHandle, …)` on the desktop. Opening a source, picking subtitles and saving progress run the same code on both. On the desktop, the player's window pair opens where the app's window is. The app window hides until the player closes, then returns, so the two read as one window.

**Feedback.** `snClickable` reads `LocalSnFeedback`. Android keeps the defaults: focus rings always show, a press darkens what it touches, and a mouse, if one is plugged in, highlights what it points at (touch never hovers). The desktop shows focus rings only after keyboard input (clicks focus what they hit), and adds the rows' mouse paging (`mouseFirst`). A TV shows only the focus ring, whatever the feedback says.

**Builds and CI.** AGP's multiplatform library has a single variant. The convention plugin therefore registers `lintDebug`, `testDebugUnitTest` and `assembleDebug` as aliases of the modules' Android lint, host-test and assemble tasks. The existing command (`./gradlew lintDebug testDebugUnitTest assembleDebug`) still covers every module. CI's desktop job runs on Windows, macOS and Linux: it compiles the app, runs the shared modules' desktop tests, round-trips a secret through each system's store (on Linux inside a private D-Bus session with GNOME Keyring) and starts the app with `--smoke-test`, which composes the window and plays a generated test pattern through libmpv in the player's window (on Linux under `xvfb-run`). Formatting is checked once, on Linux.

## Consequences

- One interface codebase: screens, view models, theme and strings are written once. Platform code is limited to the seams above and the two player screens.
- Building the desktop app needs the Android SDK installed (`ANDROID_HOME` or `android/local.properties`), but not the NDK or cargo-ndk.
- Shared code may use JVM APIs freely; any non-JVM target would need that undone first.
- Lint now analyzes the generated UniFFI bindings, because they are an ordinary source folder. `core-bridge/lint.xml` silences `NewApi` there, since the bindings check for `java.lang.ref.Cleaner` at run time.
- Changes to shared code are checked by both the Android and the desktop CI jobs.
- Secrets are kept by the system's store on every desktop; a desktop without one (no Secret Service on a bare Linux window manager) signs in for the session only.
- `PlaybackState.video` (`VideoFormat`: HDR or not, frame rate) is shared; both players fill it from mpv, after checking the values.
