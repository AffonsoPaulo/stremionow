# Phase 0 proof-of-concept results

Date: 2026-11-11

## Player: libmpv on Android

**Result: passed on the emulator; pending on real TV hardware.**

Setup: `dev.jdtech.mpv:libmpv` 1.0.0 (libmpv and FFmpeg builds for arm64, armv7, x86 and x86_64, 16 KB aligned, MIT-licensed wrapper), wrapped by the `player` module. Tested with the debug-only `PlayerProbeActivity` on the Pixel 10a emulator (Android 17, x86_64, 16 KB pages).

| Check | Result |
| --- | --- |
| 4K HEVC HDR10 at 40 Mbit/s over HTTPS (Jellyfin test set) | Plays; position, duration, buffer and tracks reach the UI |
| HTTPS certificate verification | On, using the bundled Mozilla CA store |
| Styled ASS subtitles (colors, italics, positioning, fades) | Rendered correctly |
| Hardware decoding | Works: 1080p HEVC 8-bit decoded with `mediacodec-copy`; 4K HEVC 10-bit fell back to software because the emulator's decoder lacks that profile |
| Dropped frames, audio/subtitle switch time | Not measurable on the emulator |

Configuration chosen: `vo=gpu`, `gpu-context=android`, `hwdec=mediacodec-copy`, TLS verification with the bundled CA file, 64 MiB forward buffer, `keep-open` so the last frame stays for the next-episode card.

To finish on hardware (Fire TV Stick 4K and a Google TV box): run the probe with the same sample, read `PlayerProbe` logs for `hardwareDecoder` and watch for dropped frames. If `mediacodec-copy` drops frames at 4K, try `vo=mediacodec_embed` with `hwdec=mediacodec`, which renders directly to the surface; Media3 remains the fallback.

```
adb shell am start -n io.github.affonsopaulo.stremionow/.mobile.debug.PlayerProbeActivity \
  -e url "https://repo.jellyfin.org/test-videos/HDR/HDR10/HEVC/Test%20Jellyfin%204K%20HEVC%20HDR10%2040M.mp4" \
  --ez subtitle true
```

## Row scrolling on Fire TV Stick

**Result: pending.** It needs the physical device; there is no TV emulator image in this environment. The check moves to phase 1, where the real home screen exists: six rows of 200 posters must scroll at 60 fps and stay under 300 MB.

## mpv inside Compose Desktop

**Result: decided by analysis, validated at the start of phase 5.** See [ADR 0005](../adr/0005-desktop-ui.md): Compose Multiplatform with the video in a native mpv window under a transparent Compose window; Qt/QML is the fallback.
