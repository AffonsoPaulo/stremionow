# Architecture decision records

One page per decision that is hard to reverse. Copy the template below into `NNNN-short-title.md`.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-native-per-family.md) | Native UI per device family over a shared Rust core | Accepted |
| [0002](0002-license-gpl-3.md) | License the project under GPL-3.0-or-later | Accepted |
| [0003](0003-strings-format.md) | Keep UI strings in Android XML resources under `l10n/` | Accepted; desktop tables added by 0006 |
| [0004](0004-android-baseline.md) | Android baseline: SDK levels, ABIs and native build | Accepted |
| [0005](0005-desktop-ui.md) | Desktop UI: Compose Multiplatform over a native mpv window | Accepted; Windows validated in phase 5, macOS and Linux checked in CI |
| [0006](0006-shared-ui.md) | One Kotlin Multiplatform interface for phone, TV and desktop | Accepted |
| [0007](0007-crash-reports.md) | Opt-in crash reports without a server | Accepted |
| [0008](0008-serve-to-other-devices.md) | Serve torrents to other devices on the home network | Accepted |
| [0009](0009-aniskip.md) | Skip anime openings and endings with AniSkip | Accepted |
| [0010](0010-discover-addons.md) | Discover addons from Stremio's lists | Accepted |

## Template

```
# NNNN. Title

Date: YYYY-MM-DD
Status: Proposed | Accepted | Superseded by NNNN

## Context
What forces the decision.

## Decision
What we chose.

## Consequences
What gets easier, what gets harder, what we must watch.
```
