# Contributing to Aardink

Thanks for your interest in contributing! Aardink is a Jetpack Compose-native code
editor library, published as `com.aardarch:aardink` on Maven Central.

## Getting started

1. Fork and clone the repo.
2. Make sure you have JDK 21 and the Android SDK installed (`ANDROID_HOME` set).
3. Project layout, tech stack, and build commands live in [AGENTS.md](AGENTS.md) —
   read that first.

## Before opening a pull request

Run the full check suite locally:

```pwsh
./gradlew :editor:spotlessApply
./gradlew :editor:spotlessCheck
./gradlew :editor:lint
./gradlew :editor:test
./gradlew :editor:apiCheck
```

If you intentionally changed the public API surface, also run:

```pwsh
./gradlew :editor:apiDump
```

…and commit the updated `editor/api/editor.api` file as part of your PR.

## Code conventions

- **Compose only** — no XML layouts, no Android resource files in the `editor` module.
- **No cross-module imports** — `editor` must not depend on `sample` or external modules.
- **Apache 2.0 header** on every new source file.
- **Don't add runtime deps without strong justification** — the only runtime
  dependency is Jetpack Compose.

## Commits and PRs

- Keep commits focused; conventional-style prefixes (`feat:`, `fix:`, `docs:`,
  `chore:`) are appreciated but not required.
- PRs should describe the *why*, not just the *what*.
- A PR template will guide you through the basics.

## Reporting bugs / requesting features

Use the GitHub issue templates. For security-sensitive issues, see
[SECURITY.md](SECURITY.md) instead — please do not file public issues for
vulnerabilities.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By
participating you agree to abide by its terms.
