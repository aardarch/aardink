# Contributing to Aardink

Thanks for your interest in contributing! Aardink is a Compose Multiplatform code
editor library for Android, desktop and the browser, published as `com.aardarch:aardink` on
Maven Central.

## Getting started

1. Fork and clone the repo.
2. Make sure you have JDK 21 and the Android SDK installed (`ANDROID_HOME` set).
   For the wasmJs browser tests you also need Chrome; if it is not on `PATH`, point
   `CHROME_BIN` at it.
3. Project layout, tech stack, and build commands live in [AGENTS.md](AGENTS.md) —
   read that first.

## Before opening a pull request

Run the full check suite locally:

```pwsh
./scripts/pre-push.ps1 -NoFix
```

That is the local equivalent of CI and covers every library, not just `:editor`:
secret scan, Apache 2.0 headers, Spotless, lint, the public ABI check, JVM tests, wasmJs
browser tests, the npm package and Vite smoke test, the sample build, the Roborazzi
screenshot check, and the consumer smoke test. Add `-SkipWasm` if you have no local Chrome
or pnpm, and drop `-NoFix` to let Spotless auto-format instead of just reporting.

If you intentionally changed the public API surface, also run:

```pwsh
./gradlew updateAbiAll
```

…and commit the updated dumps under `editor/api/`, `languages/api/`, `languages-lsp/api/`
and `editor-web/api/` as part of your PR. Each module has one dump per target, so an API
change normally touches three files per module (one for the wasm-only `editor-web`). Until
0.5.0 ships, the diff must be purely additive — no removed or changed signatures.

If your change alters how the editor renders, `:sample:verifyRoborazziDebug` will fail. Look
at the comparison images under `sample/build/outputs/roborazzi/`, and if the change is
intended, re-record with `./scripts/capture-screenshots.ps1` and explain the visual diff in
your PR.

## Code conventions

- **Compose only** — no XML layouts, no Android resource files in the `editor` module.
- **No cross-module imports** — `editor` must not depend on `sample` or external modules.
- **Apache 2.0 header** on every new source file.
- **Multiplatform first** — new code goes in `commonMain`. `expect`/`actual` declarations
  live only under the `platform/` package, and never reference `Dispatchers.Default` or
  `Dispatchers.IO` directly; use `EditorDispatchers` (`Dispatchers.IO` does not exist on
  wasmJs).
- **Tests** go in `commonTest` using `kotlin.test` and `runTest`, so they run on every
  target. `runBlocking` does not exist on wasm.
- **Don't add runtime deps without strong justification** — `:editor` depends on Compose
  plus `kotlinx-serialization-json` (needed so theme parsing compiles as common Kotlin).

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
