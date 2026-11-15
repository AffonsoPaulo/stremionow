# 0005. Desktop UI: Compose Multiplatform over a native mpv window

Date: 2026-11-11
Status: Accepted; validated by hand on Windows 11; macOS and Linux implemented and checked in CI (start-up smoke test), not yet tried by hand

## Context

ADR 0001 left the desktop interface open between Compose Multiplatform and Qt/QML. The requirement is smooth 4K HDR playback with the app's own controls drawn over the video, without copying frames through the CPU.

What each option can do:

- **Compose Desktop** draws with Skia on its own surface and exposes no OpenGL context, so mpv's render API cannot draw into it without a CPU copy (about 2 GB/s for 4K at 60 fps). Embedding mpv as a native child window (`wid`) inside the Compose window works, but native child windows always cover lightweight content, so controls cannot sit on top of the video.
- **Qt/QML** has an official mpv integration through the render API and draws controls over video natively, at the cost of a second UI codebase in QML and C++.

## Decision

Use Compose Multiplatform with **two-window composition**:

- mpv renders into its own borderless native window, using hardware decoding and its GPU video output.
- The Compose interface runs in a transparent, undecorated window kept exactly on top of the video window. Transparent areas show the video; controls are ordinary Compose content.
- The two windows move, resize, minimize, change focus and enter full screen together.

This keeps a single interface codebase shared with Android (Kotlin Multiplatform) and avoids any frame copy.

Phase 5 starts with a proof of concept on Windows, macOS and Linux (X11 and Wayland) that checks window synchronization, full screen, multi-monitor moves and input focus. If it fails on any of them, the desktop falls back to Qt/QML as described in ADR 0001, and this record is superseded.

## Consequences

- One UI codebase for phone, TV and desktop.
- Window synchronization is platform-specific code that needs care and testing.
- On Wayland, transparent windows and window positioning depend on the compositor; this is the main risk of the approach.

## Update: macOS, Linux and libmpv per system (2026-11-12)

**Embedding per system.**

| System | How mpv draws | Why |
| --- | --- | --- |
| Windows | `--wid` with the canvas HWND; mpv creates a child window (`vo=gpu-next`, D3D11) | As validated in phase 5 |
| Linux (X11, and Wayland through XWayland) | `--wid` with the canvas's X11 window id (`Native.getComponentID`) | mpv supports `--wid` on X11. The GPU context is pinned to X11 (`gpu-context=x11egl,x11vk`, or `x11egl` before mpv 0.38) so that on a Wayland session mpv does not pick its Wayland backend and open a window of its own. AWT runs under XWayland by default; JetBrains Runtime's native Wayland toolkit is not supported |
| macOS | Render API: `vo=libmpv` with OpenGL into a `CAOpenGLLayer` subclass, hosted by an `NSView` added over the video window's content view | mpv's macOS backends ignore `--wid` (it is documented for Windows, X11 and Android only). The render API is what mpv's own `cocoa-cb` output uses: VideoToolbox frames reach OpenGL as IOSurfaces, so nothing is copied through the CPU |

Trade-offs on macOS:

- The `NSWindow` behind the AWT frame comes from AWT's peer through reflection, which needs `--add-opens` for `java.awt`, `sun.lwawt` and `sun.lwawt.macosx` (added to the launcher on macOS only). A JDK that changes those internals breaks the player, not the rest of the app; the CI smoke test catches it.
- The layer draws on the AppKit main thread when mpv reports a frame, with `MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME` off so the main thread never waits. Frame pacing is therefore less exact than mpv's own output, which renders from a dedicated queue; moving rendering to a display-link thread is the follow-up if judder shows.
- OpenGL is deprecated on macOS but still shipped, including on Apple silicon; a Metal path (mpv's `libplacebo`-based render API) can replace it later without touching the window code.
- Full screen covers the screen with both windows, as on Windows; the menu bar and Dock are not hidden yet (`NSApplicationPresentationOptions` would do it).

**libmpv comes from the app only.** The app ships its own libmpv on every system and loads only that one, by absolute path from its bundled native folder: never the working directory, the `PATH`, the system libraries or Homebrew. There is no "use the system mpv" option and no version comparison: one build, tested with the app, updated with it. mpv and FFmpeg parse untrusted streams, so the bundled build must follow their security releases; the pin in `desktop/gradle.properties` is bumped like any dependency.

Until macOS and Linux packaging exists, development runs (`./gradlew run`, which CI's smoke test uses) may fall back to the system's libmpv: Homebrew's on macOS, the `libmpv2` package on Linux. Only the `run` task sets the `stremionow.libmpv.allowSystem` property that allows it; installers never do. Packaging will need:

- macOS: `libmpv.2.dylib` and its dependencies (FFmpeg, libplacebo, MoltenVK and the rest) inside the `.app`, with install names rewritten to `@loader_path`, signed with the app and notarized (hardened runtime, library validation).
- Linux: an AppImage or Flatpak that carries libmpv and its dependencies, built on an old enough base for the target distributions.
- Every system: an update process for the mpv and FFmpeg pins, since they handle untrusted input.

## Update: HDR and refresh rate switching (2026-11-12)

The player may change two display settings for a video, only on Windows and only with the user's consent; macOS and Linux do not offer it (the setting is hidden).

- **HDR.** When an HDR video (transfer PQ or HLG, as mpv reports it) plays on a monitor that supports HDR but has it off, the player asks in a small corner prompt ("Turn on HDR?", Turn on / Always). It never pauses playback and fades away after 10 seconds, which counts as "not now". Settings › Display has Ask (default), Always and Never, and forgets the titles answered with "Turn on". The switch uses `DisplayConfigSetDeviceInfo` (`SET_HDR_STATE` on Windows 11 24H2, `SET_ADVANCED_COLOR_STATE` before) on the monitor showing the video window only. While the app has HDR on, mpv's `target-colorspace-hint` is on; otherwise it stays off, and `d3d11-exclusive-fs` is always off, so mpv never changes the display by itself.
- **Refresh rate.** Off by default (Settings › Display). When on, the monitor switches to the highest refresh rate that is a multiple of the video's frame rate, at the same resolution, unless the current rate already is one (240 Hz for 24 fps). It uses `ChangeDisplaySettingsEx` with `CDS_FULLSCREEN`, a temporary mode that Windows drops when the process ends.
- **Putting it back.** Leaving the player, the next title, closing the window or quitting the app restores what the app changed (HDR only if it is still on). HDR is written to a journal before it is turned on, so the next launch turns it off after a crash or a kill; a temporary refresh rate needs no journal. A failed or unverified switch leaves nothing to undo: the app trusts the monitor's state over the call's result.
- **Safety.** A stream cannot cause a switch by itself: HDR needs "Always", "Turn on" for that title or the prompt's answer; mpv's values are checked (`VideoFormat`) before use; settings that cannot be read count as Never. The decision and restore logic is behind a `DisplayControl` interface and tested with a fake display; the system calls themselves are not exercised in CI and must be tried by hand.
- **Android** keeps its own behavior: HDR is handled by the system, and the player tells the video surface its frame rate (`Surface.setFrameRate`, seamless switches only) so the system setting "Match content frame rate" decides; the request is cleared when the player goes away.
