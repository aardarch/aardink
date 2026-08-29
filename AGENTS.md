# Aardink — Agent Instructions

Aardink is a standalone Jetpack Compose-native code editor library for Android published as
`com.aardarch:aardink` on Maven Central.

## Project Layout

```text
editor/          # The library module — the published artifact (com.aardarch:aardink)
  src/main/java/com/aardarch/aardink/
    core/        # Document model, tokenization, undo, find, folding
    ui/          # Composables: CodeEditorLayout, EditorGutter, etc.
  src/test/      # JUnit 5 unit tests

languages/       # Built-in language support — published as com.aardarch:aardink-languages
  src/main/java/com/aardarch/aardink/languages/
    LanguageDefinition.kt / LanguageRegistry.kt / BuiltInLanguages.kt
    internal/    # Per-language tokenizers + folding providers (regex-driven v1)

sample/          # Minimal Android app for local development and manual testing
  src/main/java/com/aardarch/aardink/sample/
```

## Tech Stack

- Kotlin + Jetpack Compose (no XML layouts — ever)
- Material 3
- JUnit 5 for tests
- Spotless + ktlint for formatting
- Binary compatibility validator (apiDump / apiCheck)
- vanniktech Maven Publish plugin

## Build Commands

All commands run from the repo root.

```pwsh
./gradlew :editor:test :languages:test          # Unit tests
./gradlew :editor:lint :languages:lint :sample:lintDebug     # Lint
./gradlew :editor:spotlessCheck :languages:spotlessCheck :sample:spotlessCheck   # Formatting check
./gradlew :editor:spotlessApply :languages:spotlessApply :sample:spotlessApply   # Auto-format
./gradlew :sample:installDebug                  # Install sample app
./gradlew :editor:publishToMavenLocal :languages:publishToMavenLocal  # Publish to ~/.m2
./gradlew dokkaAll                              # API docs (HTML) for both modules
./gradlew dokkaAllGfm                           # API docs (GitHub-Flavored Markdown)
```

### Pre-push end-to-end check

Before pushing, run the full local equivalent of CI:

```pwsh
./scripts/pre-push.ps1                        # Full check + Spotless auto-fix
./scripts/pre-push.ps1 -NoFix                 # Check-only (matches CI exactly)
./scripts/pre-push.ps1 -SkipBuild -SkipTests  # Fast iteration (lint + format + apiCheck)
```

The script runs: secret scan, Apache 2.0 header check, Spotless, lint, unit
tests, sample build, and `publishToMavenLocal` sanity.

> **Note:** `apiCheck` / `apiDump` are temporarily disabled — the
> `kotlinx.binary-compatibility-validator` plugin doesn't yet register tasks on
> Android library modules under AGP 9 + Kotlin 2.4. See the comment in the root
> `build.gradle.kts` for re-enable context.

## Code Conventions

- **Modern Android** - No explicit support for ancient, legacy Android versions, full support for modern Android
- **Kotlin only** - Kotlin and Kotlin-idiomatic code only, no legacy, no Java
- **Compose-only** — zero XML layouts, zero Android resource files in the editor module
- **No cross-module imports** — `editor` must not import from `sample` or any external module
- **Public API discipline** — run `apiDump` after any intentional API change, commit the updated `.api` file
- **License:** Apache 2.0 — all new files must include the Apache 2.0 header
- Formatting: Spotless + ktlint (function naming and wildcard imports disabled, see `editor/build.gradle.kts`)
- Tests: JUnit 5 (`@Test` from `org.junit.jupiter.api`), no Mockk needed in the editor module
- **Do not add runtime dependencies without necessity** — the library's only runtime dep is Jetpack Compose

## Separation of Concerns

The `editor` module is intentionally language-agnostic:

- Generic highlighting, gutter, undo, find/replace, folding → belongs in `editor`
- Language-specific logic (Kotlin, TS, JSON, XML, HTML, CSS, Markdown, plain text) → belongs in
  `languages` (or in a consumer override) via `IncrementalTokenizer` + `FoldingProvider`,
  packaged as `LanguageDefinition` and resolved through `LanguageRegistry`

## Public API Surface (key entry points)

| Symbol | Description |
| --- | --- |
| `CodeEditorLayout` | Top-level composable — the main entry point for consumers |
| `CodeEditorState` / `rememberCodeEditorState()` | State holder for the editor |
| `LanguageService` | Interface — implement for completions, diagnostics, hover, formatting; `triggerCharacters` has a default empty-set implementation |
| `IncrementalTokenizer` | Interface — implement for syntax highlighting |
| `FoldingProvider` | Interface — implement for code folding |
| `EditorTheme` / `LocalEditorTheme` | Theme data class and CompositionLocal |
| `EditorThemes` | Pre-built themes (VsCodeDark, VsCodeLight, MaterialDark, MaterialLight, MidnightOcean, SolarizedDark) |

## Versioning

SemVer. Breaking API changes require a major version bump. Don't introduce breaking changes lightly.
After any intentional public API change, run `./gradlew :editor:apiDump` and commit the result.

The single source of truth for the published version is `VERSION_NAME` in
`gradle.properties`. Both `editor` and `languages` modules read it via the vanniktech
Maven Publish plugin — never hardcode a version in `build.gradle.kts`.

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
verifies the tag matches `VERSION_NAME`, publishes both artifacts, and creates a
GitHub Release with the matching `## [X.Y.Z]` CHANGELOG section as the body.
