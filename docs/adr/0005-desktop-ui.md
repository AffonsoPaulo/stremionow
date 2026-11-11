# 0005. Desktop UI: Compose Multiplatform over a native mpv window

Date: 2026-11-11
Status: Accepted, to be validated at the start of phase 5

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
