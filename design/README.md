# Design tokens

`tokens.json` is the single source of colors, typography, spacing, radii, shadows and control sizes. It is exported from the StremioNow design system, which also holds the brand guide and component specs.

A generator (added in phase 0) turns it into the theme of each interface:

| Target | Output |
| --- | --- |
| Android and Compose Desktop | Kotlin theme object in the shared `ui` module |
| Qt (only if chosen for desktop) | QML singleton |

Rules:

- Change a value here, then regenerate. Never edit generated theme files by hand.
- Token names are kebab-case (`surface-raised`, `space-4`); generated code converts them to the target's naming style.
- The app is dark-only; the file has a single `dark` theme.
