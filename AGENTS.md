# Aardink — Agent Instructions

Aardink is a standalone Compose Multiplatform code editor library published as
`com.aardarch:aardink` on Maven Central. It targets Android, JVM (desktop) and wasmJs
(browser) from one common source set.

## Project Layout

Every library is Kotlin Multiplatform. Source lives in `commonMain` unless it genuinely
cannot -- `src/main/java` no longer exists anywhere.

```text
editor/          # The library module -- the published artifact (com.aardarch:aardink)
  src/commonMain/kotlin/com/aardarch/aardink/
    core/        # Document model, tokenization, undo, find, folding, LSP models (TextEdit, CodeAction, SignatureHelp)
    ui/          # Composables: CodeEditorLayout, EditorGutter, SignatureHelpPopup, CodeActionMenu, RenameDialog, etc.
    platform/    # expect declarations -- the ONLY package where expect/actual may live
  src/androidMain/ src/jvmMain/ src/wasmJsMain/      # actuals, under platform/ only
  src/commonTest/kotlin/                             # kotlin.test, runs on every target
  api/           # Committed public ABI dumps (klib + android + jvm)

languages/       # Built-in language support -- published as com.aardarch:aardink-languages
  src/commonMain/kotlin/com/aardarch/aardink/languages/
    LanguageDefinition.kt / LanguageRegistry.kt / BuiltInLanguages.kt
    internal/    # Per-language tokenizers + folding providers + services (Kotlin, XML, JSON, TOML, etc.)

languages-lsp/   # External Language Server Protocol bridge -- published as com.aardarch:aardink-languages-lsp
  src/commonMain/kotlin/com/aardarch/aardink/languages/lsp/
    LspClient.kt / LspLanguageService.kt / LspTransport.kt / LspMessage.kt
  src/jvmAndAndroidMain/   # StreamLspTransport -- java.io has no common equivalent
  src/wasmJsMain/          # WebSocketLspTransport

sample/          # Minimal Android app for local development and manual testing (not KMP)
  src/main/java/com/aardarch/aardink/sample/
  src/test/      # Roborazzi screenshot tests -- the Android zero-regression gate

tools/consumer-smoke/   # Android app depending on the PUBLISHED coordinates, not project(...)
screenshots/     # Committed Roborazzi baselines -- verifyRoborazziDebug compares against these
```

## Tech Stack

- Kotlin Multiplatform + Compose Multiplatform (no XML layouts — ever)
- Targets: Android, JVM (desktop), wasmJs (browser)
- Material 3
- `kotlin.test` in `commonTest`. JUnit 5 survives only as the `jvmTest` runner, via the
  `kotlin-test-junit5` bridge — `commonTest` cannot use `org.junit.jupiter`, and wasm has
  no `runBlocking`, so coroutine tests use `runTest` from `kotlinx-coroutines-test`
- Spotless + ktlint for formatting
- kotlinx-serialization-json — `:languages-lsp` for JSON-RPC, `:editor` for theme parsing
- Kotlin 2.4 built-in ABI validation (`checkKotlinAbi` / `updateKotlinAbi`)
- Dokka 2.2, HTML only (Dokka 2's Gradle plugin does not support GFM output)
- vanniktech Maven Publish plugin

## Build Commands

All commands run from the repo root.

```pwsh
./gradlew :editor:jvmTest :languages:jvmTest :languages-lsp:jvmTest              # JVM unit tests
./gradlew :editor:wasmJsBrowserTest :languages:wasmJsBrowserTest :languages-lsp:wasmJsBrowserTest   # Browser tests (needs Chrome)
./gradlew :editor:allTests                      # Every target for one module
./gradlew checkAbiAll                           # Public ABI vs the committed dumps
./gradlew updateAbiAll                          # Rewrite the dumps after an intentional API change
./gradlew :sample:lintDebug                     # Lint (see the note below)
./gradlew :editor:spotlessCheck :languages:spotlessCheck :languages-lsp:spotlessCheck :sample:spotlessCheck   # Formatting check
./gradlew :editor:spotlessApply :languages:spotlessApply :languages-lsp:spotlessApply :sample:spotlessApply   # Auto-format
./gradlew :sample:installDebug                  # Install sample app
./gradlew :sample:verifyRoborazziDebug          # Screenshot regression check
./scripts/capture-screenshots.ps1               # Re-record screenshots/ after an intended visual change
./scripts/verify-consumer.ps1                   # Publish to ~/.m2 and prove a real consumer still resolves -android
./gradlew dokkaAll                              # API docs (HTML)
```

The wasmJs tests run in headless Chrome via Karma. Install Chrome locally and, if it is not
on `PATH`, point `CHROME_BIN` at it. `scripts/pre-push.ps1 -SkipWasm` skips them.

> **Lint:** only `:sample` has a `lint` task. `com.android.kotlin.multiplatform.library`
> registers none for the three libraries — verify with `./gradlew :editor:tasks --all` before
> assuming otherwise. Spotless, the ABI check and the test suites cover them instead.

### Pre-push end-to-end check

Before pushing, run the full local equivalent of CI:

```pwsh
./scripts/pre-push.ps1                        # Full check + Spotless auto-fix
./scripts/pre-push.ps1 -NoFix                 # Check-only (matches CI exactly)
./scripts/pre-push.ps1 -SkipBuild -SkipTests  # Fast iteration (secrets + headers + format + lint + ABI)
./scripts/pre-push.ps1 -SkipWasm              # Skip the browser tests
```

The script runs: secret scan, Apache 2.0 header check, Spotless, lint, the ABI check,
JVM tests, wasmJs browser tests, the sample build, the Roborazzi screenshot check, and
the consumer smoke test.

## Code Conventions

- **Modern Android** - No explicit support for ancient, legacy Android versions, full support for modern Android
- **Kotlin only** - Kotlin and Kotlin-idiomatic code only, no legacy, no Java
- **Compose-only** — zero XML layouts, zero Android resource files in the editor module
- **No cross-module imports** — `editor` must not import from `sample` or any external module
- **Public API discipline** — run `./gradlew updateAbiAll` after any intentional API change
  and commit the updated dumps under `*/api/`. Through 0.5.0 the diff must be purely additive.
- **License:** Apache 2.0 — all new files must include the Apache 2.0 header
- Formatting: Spotless + ktlint (function naming and wildcard imports disabled, see `editor/build.gradle.kts`)
- Tests: `kotlin.test` (`@Test` from `kotlin.test`) in `commonTest`; `runTest` rather than
  `runBlocking`, which wasm does not have. No Mockk needed in the editor module.
- **`expect`/`actual` declarations live only under `platform/`** — nowhere else.
- **Never reference `Dispatchers.Default` or `Dispatchers.IO` directly** in `:editor` or
  `:languages-lsp`. Use `EditorDispatchers`: `Dispatchers.IO` does not exist on wasmJs, and
  `Dispatchers.Default` there is the UI event loop, not a background pool.
- **No lookbehind (`(?<=` / `(?<!`) in tokenizer rules.** Kotlin/wasm runs its own regex
  engine, which evaluates a lookbehind at every candidate position — one such rule made a
  3 KB Kotlin file take a second to highlight in the browser. Type tokens from their context
  in `RegexTokenizer.refine` instead (see `KotlinTokenizer`).
- **Do not add runtime dependencies without necessity** — `:editor` depends on Compose plus
  `kotlinx-serialization-json` (an agreed exception: `EditorThemeParser` needs it in place of
  the Android-only `org.json` so the module compiles as common Kotlin); `:languages-lsp` additionally depends on `kotlinx-serialization-json` for JSON-RPC payloads and `kotlinx-coroutines-core` for the client and transport. Both are `api` dependencies, because `JsonElement` and `CoroutineScope` appear in `LspClient`'s public signatures. `:languages-lsp` pulls in no Compose of its own.

## Separation of Concerns

The `editor` module is intentionally language-agnostic:

- Generic highlighting, gutter, undo, find/replace, folding → belongs in `editor`
- Language-specific logic (Kotlin, TS, JSON, TOML, XML, HTML, CSS, Markdown, plain text) → belongs in
  `languages` (or in a consumer override) via `IncrementalTokenizer` + `FoldingProvider`,
  packaged as `LanguageDefinition` and resolved through `LanguageRegistry`
- External LSP integration bridge → belongs in `languages-lsp` via `LspClient` + `LspLanguageService`

## Public API Surface (key entry points)

| Symbol | Description |
| --- | --- |
| `CodeEditorLayout` | Top-level composable — the main entry point for consumers |
| `CodeEditorState` / `rememberCodeEditorState()` | State holder for the editor, including `applyTextEdits()` |
| `LanguageService` | Interface — completions, diagnostics, hover, formatting, code actions, definition, signature help, rename |
| `LspLanguageService` | LanguageService adapter for external Language Servers (`:languages-lsp`) |
| `LspClient` | Coroutine JSON-RPC 2.0 client for LSP servers (`:languages-lsp`) |
| `IncrementalTokenizer` | Interface — implement for syntax highlighting |
| `FoldingProvider` | Interface — implement for code folding |
| `EditorTheme` / `LocalEditorTheme` | Theme data class and CompositionLocal |
| `EditorThemes` | Pre-built themes (VsCodeDark, VsCodeLight, MaterialDark, MaterialLight, MidnightOcean, SolarizedDark) |

## Versioning

SemVer. Breaking API changes require a major version bump. Don't introduce breaking changes lightly.
After any intentional public API change, run `./gradlew updateAbiAll` and commit the updated
dumps under `*/api/`. Until 0.5.0 ships, that diff must be purely additive.

> The 0.5.0 multiplatform work carries a `feat(editor)!:` marker, scoped to packaging only.
> `create-release.ps1` reads that `!` and will suggest a **1.0.0** bump; override it to the
> intended version. The packaging change is that each library now publishes a Gradle Module
> Metadata root plus one artifact per target rather than a single `.aar` — Android consumers
> resolve `-android` automatically and need no change.

The single source of truth for the published version is `VERSION_NAME` in
`gradle.properties`. The `editor`, `languages` and `languages-lsp` modules all read it via the
vanniktech Maven Publish plugin — never hardcode a version in `build.gradle.kts`.

## Releasing

Two scripts handle the release flow; nothing should be done by hand:

```pwsh
./scripts/update-changelog.ps1   # (optional) auto-fill [Unreleased] from commits since last tag
./scripts/create-release.ps1     # bump VERSION_NAME, cut CHANGELOG, commit + tag (no push)
git show vX.Y.Z                  # review the staged release
./scripts/release.ps1            # push main + tag → triggers Maven Central publish + GH Release
```

`scripts/create-release.ps1` runs `scripts/pre-push.ps1 -NoFix` as a gate, prompts for the next version
(default = SemVer suggestion derived from conventional-commit signals), and refuses to
proceed if the tag exists or if `[Unreleased]` is empty (override with
`-AllowEmptyChangelog`). `scripts/release.ps1` only pushes if HEAD is a `chore(release): vX.Y.Z`
commit whose tag matches `VERSION_NAME`.

The release workflow ([.github/workflows/release.yml](.github/workflows/release.yml))
verifies the tag matches `VERSION_NAME`, publishes all three artifacts, builds the sample app,
and creates a GitHub Release with the matching `## [X.Y.Z]` CHANGELOG section as the body
and two assets: the API docs tarball and `aardink-sample-vX.Y.Z.apk`.

The sample APK is signed with the default debug key (no private keystore is needed) and
reads its `versionName`/`versionCode` from `VERSION_NAME`. CI also uploads debug and release
sample APKs as a `sample-apks` workflow artifact on every push/PR for sideloading.
