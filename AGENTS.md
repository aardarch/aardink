# AardInk — Agent Instructions

AardInk is a standalone Jetpack Compose-native code editor library for Android.
It is extracted from [Aardflex](https://github.com/aardarch/aardflex) and published as
`com.aardarch:aardink` on Maven Central.

## Project Layout

```text
editor/          # The library module — the published artifact
  src/main/java/com/aardarch/editor/
    core/        # Document model, tokenization, undo, find, folding
    ui/          # Composables: CodeEditorLayout, EditorGutter, etc.
  src/test/      # JUnit 5 unit tests

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

All commands run from the repo root (`c:\repos\aardarch\aardink`).

```pwsh
./gradlew :editor:test              # Unit tests
./gradlew :editor:lint              # Lint
./gradlew :editor:spotlessCheck     # Formatting check
./gradlew :editor:spotlessApply     # Auto-format
./gradlew :sample:installDebug      # Install sample app
./gradlew :editor:publishToMavenLocal  # Publish to ~/.m2 for local testing
```

### Pre-push end-to-end check

Before pushing, run the full local equivalent of CI:

```pwsh
./pre-push.ps1                      # Full check + Spotless auto-fix
./pre-push.ps1 -NoFix               # Check-only (matches CI exactly)
./pre-push.ps1 -SkipBuild -SkipTests  # Fast iteration (lint + format + apiCheck)
```

The script runs: secret scan, Apache 2.0 header check, Spotless, lint, unit
tests, sample build, and `publishToMavenLocal` sanity.

> **Note:** `apiCheck` / `apiDump` are temporarily disabled — the
> `kotlinx.binary-compatibility-validator` plugin doesn't yet register tasks on
> Android library modules under AGP 9 + Kotlin 2.3. See the comment in the root
> `build.gradle.kts` for re-enable context.

## Code Conventions

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
- Language-specific logic (XML, Kotlin, etc.) → belongs in the consumer app via `LanguageService` + `IncrementalTokenizer`
- Aardflex-specific state (WallpaperRepository, ViewModels) → never touches `editor`

## Public API Surface (key entry points)

| Symbol | Description |
| --- | --- |
| `CodeEditorLayout` | Top-level composable — the main entry point for consumers |
| `CodeEditorState` / `rememberCodeEditorState()` | State holder for the editor |
| `LanguageService` | Interface — implement for completions, diagnostics, hover, formatting |
| `IncrementalTokenizer` | Interface — implement for syntax highlighting |
| `FoldingProvider` | Interface — implement for code folding |
| `EditorTheme` / `LocalEditorTheme` | Theme data class and CompositionLocal |
| `EditorThemes` | Pre-built themes (VsCodeDark, MidnightOcean, etc.) |

## Versioning

SemVer. Breaking API changes require a major version bump.
After any intentional public API change, run `./gradlew :editor:apiDump` and commit the result.
