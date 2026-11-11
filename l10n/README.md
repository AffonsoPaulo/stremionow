# Translations

All interface text lives here as Android string resources (see [ADR 0003](../docs/adr/0003-strings-format.md)).

| Folder | Language |
| --- | --- |
| `values/` | English, the source language |
| `values-pt-rBR/` | Portuguese (Brazil) |

## Rules for strings

- Write whole sentences with placeholders; never glue fragments together in code.
- Add a `<!-- comment -->` above each string saying where it appears and what the placeholders are.
- Use `<plurals>` for anything with a count.
- Never format numbers, sizes, dates or durations by hand; pass raw values and let the app format them for the locale.
- The season and episode abbreviation is itself a string (`T1 E5` in Portuguese, `S1 E5` in English).

## Adding a language

Translations will be managed on Weblate once the project is public. Until then, copy `values/strings.xml` into a new `values-<locale>/` folder and open a pull request. A language ships once it is at least 90% translated.
