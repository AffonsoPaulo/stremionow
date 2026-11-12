# Phase 5 proof-of-concept results

Date: 2026-11-11

## Desktop UI: Compose over a native mpv window

**Result: passed on Windows 11. The known issue found then (window size when dragging between monitors with different scales) has a fix since, see known issue 1. macOS, Linux X11 and Wayland are not tested on this machine; their players were built afterwards and are checked in CI (see "Other platforms").**

This validates [ADR 0005](../adr/0005-desktop-ui.md) on Windows: libmpv renders into its own borderless native window, and the Compose interface runs in a transparent, undecorated window kept exactly on top of it. The proof of concept was a standalone Gradle build in [`desktop/`](../../desktop). Its player, window code and controls have since moved into the shared Kotlin Multiplatform modules ([ADR 0006](../adr/0006-shared-ui.md)): `MpvPlayer` (desktop) in `android/player`, and `WindowPair`, `VideoWindow` and the overlay in `android/ui` under `desktopMain`.

### Setup

| Part | Choice |
| --- | --- |
| Build | Gradle 9.9.1 (same wrapper as `android/`), Kotlin 2.4.21, Compose Multiplatform 1.12.2 (Skiko 0.150.2), JNA 5.19.1, kotlinx-coroutines 1.11.0, Spotless 8.11.0 with ktlint 1.8.0, Java 21 bytecode on JDK 25 |
| libmpv | [shinchiro](https://github.com/shinchiro/mpv-winbuild-cmake/releases) build `20261111-git-bcde6b93cc` (`mpv-dev-x86_64-*.7z`, `libmpv-2.dll`, client API 2.6), downloaded by Gradle into `desktop/app/build/` and checked against a pinned SHA-256 |
| Rust core | `stremionow-ffi` built for the host in release; UniFFI 0.32.2 Kotlin bindings generated from the debug build (release strips the metadata) |
| Machine | Windows 11 Pro (10.0.26300), NVIDIA GeForce RTX 5070 Ti, two 1920 × 1080 monitors at 100 % (primary at 240 Hz, second one to its left) |

How it fits together:

- **Video window**: an undecorated `JFrame` holding a heavyweight AWT `Canvas`. Its HWND (`Native.getComponentID`) goes to mpv's `wid` option, so mpv creates its own child window inside the canvas and resizes it with the canvas. It is the app's main window for the system: taskbar entry, title, icon.
- **Overlay**: a `ComposeDialog` (`undecorated`, `transparent`, `resizable = false`) owned by the video window, shown with Compose's `SwingDialog`. Ownership is what keeps it directly above the video without being topmost, hides it while the video window is minimized and gives the pair a single taskbar and Alt+Tab entry.
- **`WindowPair`** keeps them aligned. The overlay is the reference: moves and resizes made by the app go to both windows in one `DeferWindowPos` batch, in physical pixels; changes made by the system (DPI, displays, other programs) are copied from the overlay to the video window from AWT's component and `graphicsConfiguration` events, with a re-check 300 ms after a DPI or monitor change. Activating the video window passes focus to the overlay.
- **Controls** (HBO Max style, English only for now): back and title with the core version and the decoder in use, minimize; play/pause and ±10 s in the center; seek bar, time and full screen at the bottom. Click toggles play, double-click toggles full screen, dragging the video moves the window, 6 dp invisible strips along the edges resize it (minimum 480 × 270 dp). Keys: Space, Left, Right, F, Esc; Tab moves focus with a visible ring that clicks do not trigger. Controls and cursor hide after 4 s without pointer movement while playing; they stay while paused, as on Android.
- **mpv options**, from the Android player where they apply: `config=no`, no scripts, OSC or default bindings, `msg-level=all=warn` (`--verbose` turns on `all=v`), `idle`, `keep-open`, `vo=gpu-next`, `hwdec=auto-safe`, `tls-verify=yes` with the same Mozilla CA bundle as Android (`cacert-2026-11-05.pem`, pinned SHA-256), `cache=yes` with 64 MiB ahead and 16 MiB behind, subtitles off. mpv input on its own window is off (`input-vo-keyboard`, `input-cursor`): the overlay covers it and owns all input.

**Why `wid` rather than letting mpv open its own window** (`force-window`, `border=no`): with `wid` both top-level windows belong to the JVM, so ownership, stacking, focus, minimize and taskbar identity are set in one place with plain AWT, and moving both is one `DeferWindowPos` call on windows of the same thread. mpv's own window runs on mpv's thread with its own behaviour to switch off (autofit, resizing to the video size, its taskbar entry, cursor autohide, borderless hit testing, its own DPI handling), and making it the overlay's owner would take raw Win32 calls on a window AWT knows nothing about. The `wid` path is also the one mpv supports on X11.

### How it was verified

Every check was driven from PowerShell through Win32 calls in a small harness kept outside the repository: `SetWindowPos` and `ShowWindow` to act as other programs or the taskbar, `SendInput` for real mouse drags, clicks and keys, `GetWindowRect` on the overlay, the video window and mpv's child window (class `mpv`) to compare their rectangles in physical pixels, `EnumWindows` for the z-order, `GetForegroundWindow` and `GetGUIThreadInfo` for focus, `WindowFromPoint` for what covers the taskbar area, `GetCursorInfo` for the cursor, `BitBlt` with `CAPTUREBLT` for screenshots (layered windows are missing from plain screen copies), and `DisplayConfigSetDeviceInfo` (what the Settings app uses) to change a monitor's scale live. During drags the three rectangles were sampled every 30 ms. Keys were only sent after checking that the app owned the foreground.

"Aligned" below means the overlay, the video window and mpv's child window have identical rectangles. The full run (23 checks) passed on `./gradlew run`; the installed MSI passed the same run except one check that hit the end of the 29 s sample (see the table).

### Results

| Behaviour | Result | How it was checked |
| --- | --- | --- |
| Video visible under transparent areas, controls on top | Pass | Screenshots: video through every area without controls, Compose controls and scrims drawn over it; overlay directly above the video window in the z-order |
| Move by dragging the video | Pass | 20-step `SendInput` drag: aligned at all 20 samples |
| Move or resize by another program | Pass | `SetWindowPos` on the overlay alone: video window followed. `SetWindowPos` on the video window alone: put back under the overlay |
| Resize by dragging an overlay edge or corner | Pass | Right edge, bottom-right and top-left corners: aligned at every sample; minimum 480 × 270 dp held when dragging past it |
| Minimize and restore | Pass | Minimize button, `SC_MINIMIZE` sent to the video window (taskbar) and `SW_MINIMIZE` on the overlay (Win+Down): both hidden, one taskbar entry, focus handed to the next window. Restore as the taskbar does it (activate, `SW_RESTORE`): both back, aligned, overlay focused |
| Focus and Alt+Tab, not always-on-top | Pass | Another app activated: it covers both windows and the overlay stays right above the video. Overlay has no `WS_EX_TOPMOST`. Alt+Tab back: overlay focused and above the other app; Space, Left and Right work after restore, Alt+Tab and minimize |
| Full screen | Pass | F, double-click and the button, on both monitors: both windows equal the monitor rectangle and cover the taskbar (`WindowFromPoint` there returns the app); Esc, double-click or the button restore the previous rectangle |
| Move to another monitor | Pass | Drag across the monitor boundary: aligned at all 30 samples; full screen then covers the monitor holding most of the window |
| DPI scaling 125 % and 150 % | Pass | Second monitor switched live to 150 %, 125 % and back to 100 %, twice, with the app on it: aligned every time, window keeps its logical size (1280 × 720 → 1920 × 1080 → 1600 × 900 → 1280 × 720 px), controls scale |
| Drag between monitors with different scales | Partial | 150 % to 100 % and back: the drag goes through and the windows realign, with 3 of 40 samples misaligned around the switch; but the window ends smaller than it should (1279 × 720 px became 569 × 320 instead of 853 × 480). See known issues |
| Controls and cursor auto-hide | Pass | Cursor is the arrow while the controls show, a blank cursor 4 s after the last movement, the arrow again on movement; screenshots before and after |
| Keyboard | Pass | Screenshots of the time label and play icon after Left (−10 s), Space, Right (+10 s); F and Esc above; Tab shows the focus ring on Back, then Minimize |
| Exit and native resources | Pass | Back button, or `WM_CLOSE` on the overlay (what Alt+F4 sends): mpv logs its statistics and is destroyed (`mpv_terminate_destroy` after the event thread stops) before the video window is disposed; the process exits with code 0 |
| Rust core on the desktop JVM | Pass | `coreVersion()` returns `0.0.1` and is shown in the title area |
| HTTPS verification | Pass | `https://self-signed.badssl.com/` is refused (`[mpv/curl] TLS certificate verification failed`) and the error screen shows; the Jellyfin samples play |
| Installer | Pass | `packageDistributionForCurrentOS` built `StremioNow-1.0.0.msi` (115 MB). Silent per-user install to `%LOCALAPPDATA%\StremioNow`, with `libmpv-2.dll`, `stremionow_ffi.dll` and `cacert.pem` in `app\resources`. The installed app starts, shows the core version and `d3d11va`, and passed the full run except the cursor check, which ran after the sample had ended (paused, so the controls stay by design); repeated while playing, it passed. Uninstall removed it |

### Hardware decoding

| Sample | `hwdec-current` | Format reported by mpv | Dropped frames |
| --- | --- | --- | --- |
| 1080p H.264, 10 Mbit/s, 60 fps | `d3d11va` | `d3d11` frames, BT.709 | 0 during the full check run (about 2 minutes of moves, resizes, full screen and minimize); 3 in a 6 s run that included start-up and a full screen switch |
| 4K HEVC HDR10, 40 Mbit/s, 60 fps | `d3d11va` | `d3d11` frames, PQ, BT.2020; `gpu-next` tone-maps for the SDR monitor | 2 in 30 s full screen (around the switch), 8 in a first 30 s windowed run including start-up; 0 decoder drops |

Frames stay on the GPU (`d3d11` pixel format, no copy-back). Process CPU while playing the 1080p sample in a 1280 × 720 window: 3.1 % of one core with the controls hidden, 12.7 % with the controls showing and the pointer moving (Compose redraws the transparent overlay).

### Known issues

1. **Drag between monitors with different scales shrank the window (fixed).** Each window receives `WM_DPICHANGED` separately and AWT applies Windows' suggested size from whatever size the window has at that moment; when the shrink moved the window's centre back across the boundary, the scale flipped again and the size was applied twice. A later check also showed a live scale change on the window's own monitor that grew neither window, then shrank both on the way back. `WindowPair` now keeps the windowed size in dp (`WindowedSize`): moves use it at the scale of the monitor under the window, and 300 ms after any scale change both windows get that size at the new scale. Covered by unit tests; it has not been re-run against live scale changes, which are no longer changed on the test machine.
2. **Transparent rendering path.** With Skiko's default Direct3D renderer the overlay is a layered window (`WS_EX_LAYERED`) whose content is a Skia child window; input reaches it everywhere, including fully transparent pixels. The OpenGL renderer produced an opaque black overlay and was dropped; the software renderer works but costs more CPU. Keep Direct3D, and measure the overlay's cost at 4K during animations before release.
3. **Pitfalls found and handled in `WindowPair`**, worth keeping in mind when this moves to the real app: Compose's built-in resizer for undecorated windows resizes the overlay alone and has no minimum size (disabled with `isResizable = false`); calling `requestFocus()` on the overlay window itself starves Compose of key events (focus goes back to the most recent focus owner instead); a minimized video window left the hidden overlay holding the keyboard (minimizing now activates the video window first, so the system hands focus to the next app); Compose restarts pointer handlers when the density changes, which dropped drags across monitors (gestures now follow the mouse button through `GetAsyncKeyState` until it is released); hover moves on desktop report `previousPosition == position`.
4. **Launches from a background process start behind the foreground window** (Windows foreground lock). Normal launches from Explorer or the Start menu get the foreground; the harness activated the app with an Alt tap.
5. **libmpv pin expires.** shinchiro keeps about 30 daily releases, so the pinned URL stops working after a month. Mirror the archive (for example as a release asset of this repository) before the MVP; `stremionow.libmpv.url` and `.sha256` in `desktop/gradle.properties` can point anywhere.
6. **Packaging needs a JDK with `jpackage`.** The Android Studio JBR has none. The MSI above was built with `-Pstremionow.packaging.javaHome` pointing at an Oracle JDK 25.0.3; release builds should use an OpenJDK distribution (Temurin, or the JBR SDK) so the bundled runtime is GPL-compatible. `packageVersion` is `1.0.0` because the DMG format rejects a major version of 0.
7. Two Gradle deprecation warnings come from the Compose plugin (hot reload attribute type, `Configuration.setVisible`), not from this build.
8. `7z` extraction relies on Windows' own `%SystemRoot%\System32\tar.exe` (libarchive `bsdtar`, Windows 10 1803 and later): the archive uses the BCJ2 filter, which Apache Commons Compress cannot read, and Git Bash's GNU tar has no 7z support.

### Since the proof of concept

- **Controls**: hover tooltips with the keyboard shortcut (Space, ←, →, F, N, Esc) on the player's buttons; N plays the next episode.
- **Mouse back button** (`XButton1`): one step back, like Esc, in the app window and in the player.
- **Media keys**: play/pause, play, pause, stop, next and previous while the player is focused (Windows `WM_APPCOMMAND`, read by subclassing the overlay's window procedure). The system media overlay (SMTC on Windows, MPRIS on Linux, Now Playing on macOS) is still to do: SMTC needs WinRT activation and an app identity, which wait for the installer.
- **Missing libmpv**: the player window says so and offers the way back, instead of failing.
- **`--smoke-test`**: composes the app window, plays a generated test pattern through libmpv in the player's window and exits 0 when it worked; CI runs it on Windows, macOS and Linux.
- **HDR and refresh rate switching** with the user's consent (ADR 0005).

### Other platforms

Since this was written, macOS and Linux have players (ADR 0005, "Update: macOS, Linux and libmpv per system"): `--wid` on X11 (and XWayland), the render API into a `CAOpenGLLayer` on macOS. They compile, pass the shared tests and the start-up smoke test in CI; nobody has used them by hand yet. The notes below are the analysis that led there.

**macOS: not tested on this machine.** The pieces exist but the embedding is the risk:
- AWT on macOS has no native view per component: a heavyweight `Canvas` is drawn into the window's layer tree, and JAWT hands out a `CALayer` attachment, not an `NSView`. mpv's `wid` expects an `NSView`, so the Windows path does not carry over as is. Options: a native `NSWindow` (or `NSView`) created through JNA and the Objective-C runtime for mpv, or mpv's render API drawing into a `CAMetalLayer`/OpenGL layer attached through JAWT (still no frame copy through the CPU).
- The overlay as an owned window maps to `-[NSWindow addChildWindow:ordered:]`, which moves child windows with the parent natively. Risks: native full screen moves the parent to its own Space, where child windows are known to misbehave, so covering the screen with both windows (as on Windows) is the safer route; Mission Control and Stage Manager treat child windows separately.
- Compose transparent windows work on macOS (Metal). Retina scaling is uniform per screen, but moving between screens with different scales triggers the same resize race.

**Linux X11: not tested on this machine.** Closest to Windows:
- mpv supports `--wid` with an X11 window id, which `Native.getComponentID` returns for a heavyweight `Canvas`.
- The owned dialog becomes `WM_TRANSIENT_FOR` the video window; the window manager decides whether it stays above, follows minimize (iconify) and joins full screen. Behaviour varies between window managers (Mutter, KWin, Xfwm, i3 and others).
- Transparency needs a compositing window manager and an ARGB visual; without one the overlay is opaque.
- There is no atomic move of two windows: two `XConfigureWindow` requests, so a one-frame lag during drags is possible. Per-monitor scaling does not exist; AWT uses one global scale.

**Wayland: not tested on this machine.** The main risk named in the ADR:
- Native Wayland clients cannot read or set the global position of their windows, so two top-level windows cannot be kept aligned, and mpv's `--wid` is not available on Wayland.
- Standard OpenJDK AWT runs through XWayland on a Wayland session, so the X11 path applies there, with XWayland's limits: blurry output under fractional scaling, focus and stacking decided by the compositor, no per-monitor scale.
- JetBrains Runtime's native Wayland toolkit (`-Dawt.toolkit.name=WLToolkit`) would need a single-surface design: mpv drawing into a `wl_subsurface` under the Compose surface, or mpv's render API into a texture shared with Skia. Neither is exposed by Compose today.

### Recommendation

**Keep ADR 0005.** On Windows the two-window composition meets every requirement it was tested against: hardware decoding with frames kept on the GPU (4K HDR10 at 60 fps on `d3d11va`), controls drawn by the same Compose code as Android, and the two windows moving, resizing, minimizing, keeping focus order and going full screen as one, on two monitors and at 100, 125 and 150 % scale. The open problem on Windows (window size when dragging between monitors with different scales) is a polish item with a known fix, not a blocker.

The decision is not final for all platforms. Before committing the desktop UI to Compose for good, run this proof of concept on macOS (with a native `NSView` or the render API for mpv) and on Linux X11 under GNOME and KDE. For Wayland, plan on XWayland at first, and treat a single-window design (mpv's render API into a Skia-shared texture, which would also remove the window synchronization everywhere) as the long-term direction. Fall back to Qt/QML only if macOS embedding fails or the XWayland experience is unacceptable.

### Build and run

The proof of concept played one address given on the command line. The desktop app that replaced it browses addons and plays sources through the core, so it no longer takes an address. From `desktop/` (set `JAVA_HOME` to a JDK 25, for example Android Studio's JBR; the Android SDK must be installed, see ADR 0006):

```
./gradlew run                                    # the desktop app
./gradlew run --args="--verbose"                 # with mpv's detailed logs
./gradlew packageDistributionForCurrentOS -Pstremionow.packaging.javaHome="C:/path/to/jdk-25"
./gradlew spotlessCheck
```

`run` and the installers depend on `prepareNativeResources`, which builds the Rust core (`cargo build -p stremionow-ffi --release`), downloads and verifies libmpv and the CA bundle, and lays them out as Compose's app resources. The core's Kotlin bindings come with the shared `core-bridge` module. On macOS and Linux the core is built the same way, and libmpv comes from the system (`brew install mpv`, `libmpv2`); those paths have not been exercised.

### Moving this into a Kotlin Multiplatform `ui` module

Done; see [ADR 0006](../adr/0006-shared-ui.md). The notes below are what this proof of concept recommended.

- The overlay (`PlayerOverlay`, `SeekBar`, the icons and colors) is plain Compose with no AWT types except the hidden cursor and resize cursors; it can move to `commonMain`/`desktopMain` and replace `Theme.kt` with the generated design tokens. Window actions reach it through the `PlayerActions` interface.
- `mpv/` (`Mpv`, `Player`) and `window/` (`VideoWindow`, `WindowPair`, `WindowSystem`) are desktop-only (`jvm("desktop")` source set). `Player` mirrors the Android `MpvPlayer` API (`state`, `load`, `togglePause`, `seekBy`, `seekTo`); a shared interface in `commonMain` would let one player screen drive both.
- The whole app can live in the overlay: when nothing plays, the Compose window draws an opaque background and the video window stays black underneath. Keep the video window as the owner (taskbar identity) and the Compose window as the owned one.
- The UniFFI bindings are the same file Android generates; on desktop JNA finds `stremionow_ffi` through `NativeLibraries.install()` (`compose.application.resources.dir`). `kotlinx-coroutines-swing` provides `Dispatchers.Main`.
- JDK 25 needs `--enable-native-access=ALL-UNNAMED` for JNA (set in `jvmArgs` and in the installed launcher).
