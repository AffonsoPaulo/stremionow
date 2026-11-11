# Contributing to StremioNow

Thanks for helping. This guide covers how work gets into the project.

## Before you start

- Check the open issues. For anything bigger than a small fix, open an issue first so we can agree on the approach.
- Follow the [code of conduct](CODE_OF_CONDUCT.md).
- By contributing, you agree that your work is licensed under the [GPL-3.0-or-later](LICENSE).

## Workflow

1. Fork the repository and create a short-lived branch from `main`.
2. Keep each pull request focused on one change.
3. Make sure CI passes: formatting, lint and tests.
4. Describe what changed and why, with screenshots for any UI change.

`main` must always build and install.

## Commit messages

We use [Conventional Commits](https://www.conventionalcommits.org):

```
feat(player): add subtitle delay control
fix(addons): time out slow addons after 5 s
docs(adr): record desktop UI decision
```

## Project rules

CI checks Rust formatting, lint and tests, Kotlin formatting (ktlint through Spotless), Android Lint, the Android build, the desktop build with its tests, and license headers. Reviewers enforce the rules CI cannot check.

Format Kotlin locally with `./gradlew spotlessApply` from `android/` and from `desktop/`.

- **No hard-coded UI text.** Every string lives in [`l10n/`](l10n) and is translatable.
- **No colors, sizes or fonts outside the design tokens.** They come from [`design/tokens.json`](design/tokens.json); generated theme files are never edited by hand.
- **Every source file starts with** `SPDX-License-Identifier: GPL-3.0-or-later`.
- Rust: `cargo fmt` and `cargo clippy -- -D warnings`.
- Kotlin: `ktlint`.

## UI changes

Every screen must:

- use only design system components and tokens;
- work entirely with a keyboard and a TV remote, with a visible focus ring;
- keep touch and click targets at 44 px or larger;
- have loading, empty and error states;
- survive pseudolocalization (text 40% longer) and a right-to-left language.

## Decisions

Any decision that is hard to reverse (a new dependency, a new module, a change of approach) gets a one-page record in [`docs/adr`](docs/adr).

## Translations

See [`l10n/README.md`](l10n/README.md).
