# 0003. Keep UI strings in Android XML resources under `l10n/`

Date: 2026-11-11
Status: Accepted; desktop mechanism added with [ADR 0006](0006-shared-ui.md)

## Context

The interface must be translatable from day one, with English as the source language and Brazilian Portuguese complete in v1. Strings are consumed by the Android apps first and by the desktop app later. Community translation will run on Weblate.

## Decision

- Strings live in `l10n/` as Android string resources: `l10n/res/values/strings.xml` (English) and `l10n/res/values-<locale>/strings.xml` for each translation (for example `values-pt-rBR`).
- Android modules add `l10n/res/` as a resource directory, so there is no copy step.
- Plurals use `<plurals>`; placeholders use positional arguments (`%1$s`) with a translator comment on every string.
- The shared interface (ADR 0006) reaches strings through generated keys: `stringResource(Res.string.nav_home)` and `pluralStringResource(Res.plurals.sources_count, n, n)`. The `generateStrings` task (`android/build-logic`) reads `l10n/res` and writes:
  - the keys, for shared code;
  - on Android, a mapping from each key to `R.string` and `R.plurals`. Android keeps reading its own resources, so locale matching, plural rules and formatting stay the platform's;
  - for the desktop, one `.properties` table per locale, with Android's escapes and quoting resolved. The desktop picks the exact locale, then the language alone, then another region of the same language, then English. A string missing from a translation falls back along the same chain. Plural categories come from a small table of CLDR rules (`PluralRules`), because the JVM has no public API for them. A desktop test fails if a translated language has no rule.
- Only language and region qualifiers (`values-pt`, `values-pt-rBR`) are supported; the generator rejects others, such as `values-b+sr+Latn`.

## Consequences

- One source of truth, editable in Android Studio and Weblate; the desktop never needs a hand-made copy.
- No ICU select for grammatical gender; phrase strings to avoid it.
- A new translation needs its language's plural rule added to `PluralRules` for the desktop. The desktop test points this out.
- A Qt desktop would have needed a conversion script; with Compose Multiplatform, the generated tables do that job.
