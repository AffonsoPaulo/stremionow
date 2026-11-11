# 0003. Keep UI strings in Android XML resources under `l10n/`

Date: 2026-11-11
Status: Accepted

## Context

The interface must be translatable from day one, with English as the source language and Brazilian Portuguese complete in v1. Strings are consumed by the Android apps first and by the desktop app later. Community translation will run on Weblate.

## Decision

- Strings live in `l10n/` as Android string resources: `l10n/res/values/strings.xml` (English) and `l10n/res/values-<locale>/strings.xml` for each translation (for example `values-pt-rBR`).
- Android modules add `l10n/res/` as a resource directory, so there is no copy step.
- Compose Multiplatform reads the same XML format; if the desktop goes Qt, a script converts it.
- Plurals use `<plurals>`; placeholders use positional arguments (`%1$s`) with a translator comment on every string.

## Consequences

- One source of truth, editable in Android Studio and Weblate.
- No ICU select for grammatical gender; phrase strings to avoid it.
- A Qt desktop would need a small conversion script.
