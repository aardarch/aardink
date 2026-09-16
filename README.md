# Aardink

A Compose Multiplatform code editor — incremental tokenization, LSP-lite
language services, code folding, find/replace, and rich gutter annotations.
Runs on Android, desktop (JVM) and the browser (Wasm) from one codebase.

[![Maven Central](https://img.shields.io/maven-central/v/com.aardarch/aardink.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.aardarch/aardink)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-26-brightgreen.svg)](#requirements)
[![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20JVM%20%7C%20Wasm-blue.svg)](#requirements)

## Features

- Compose-native editor surface — zero XML, zero Android resources
- Incremental tokenization with pluggable per-language tokenizers
- Code folding with pluggable folding providers
- Find / replace panel
- Rich gutter: line numbers, fold handles, annotations
- LSP-lite language services: completions, diagnostics, hover, formatting
- Themable via `EditorTheme` + `LocalEditorTheme`, with pre-built themes
  (e.g. `EditorThemes.VsCodeDark`, `EditorThemes.MidnightOcean`)

## Modules

Aardink publishes three artifacts on Maven Central:

- **`com.aardarch:aardink`** — the editor library: composables, state,
  theming, and the language-service interfaces.
- **`com.aardarch:aardink-languages`** — built-in language definitions
  (Kotlin, TypeScript, JSON, XML, HTML, CSS, Markdown, plain text).
- **`com.aardarch:aardink-languages-lsp`** — a bridge to an external
  Language Server over JSON-RPC.

The `editor` module is intentionally language-agnostic. Pull in
`aardink-languages` for the bundled definitions, or implement your own via
`LanguageDefinition` + `LanguageRegistry`.

Each is a Kotlin Multiplatform publication: a Gradle Module Metadata root plus one
artifact per target. Declaring `com.aardarch:aardink` resolves to `aardink-android`,
`aardink-jvm` or `aardink-wasm-js` automatically, the same way `kotlinx.coroutines`
works — you do not name the target yourself.

## Requirements

| Platform | Requirement |
| --- | --- |
| Android | `minSdk` 26, `compileSdk` 37 |
| Desktop (JVM) | JDK 21. Add `kotlinx-coroutines-swing` — `CodeEditorState` defaults its scope to `Dispatchers.Main`, which on the JVM has no implementation without it |
| Browser (Wasm) | Chrome/Edge 119+, Firefox 120+, Safari 18.2+ (WasmGC + exception handling) |

Kotlin with Compose Multiplatform (Material 3) on every target.

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    // Use the current version from the Maven Central badge above.
    implementation("com.aardarch:aardink:<version>")
    implementation("com.aardarch:aardink-languages:<version>")
}
```

## Quick start

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.aardarch.aardink.core.rememberCodeEditorState
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.LocalEditorTheme

@Composable
fun MyEditor() {
    val kotlinLanguage = LanguageRegistry.withBuiltIns().byId("kotlin")!!
    val state = rememberCodeEditorState(
        initialText = "fun main() = println(\"Hello, Aardink\")",
        tokenizer = kotlinLanguage.tokenizer,
    )

    CompositionLocalProvider(LocalEditorTheme provides EditorThemes.VsCodeDark) {
        CodeEditorLayout(
            state = state,
            languageService = kotlinLanguage.languageService,
            foldingProvider = kotlinLanguage.foldingProvider,
        )
    }
}
```

See the [`sample/`](sample/) module for a runnable example.

## Theming

Themes are plain data classes. Wrap your editor in a
`CompositionLocalProvider(LocalEditorTheme provides …)` to apply a theme,
or pass one directly to `CodeEditorLayout`. The library ships a set of
ready-made themes via `EditorThemes` (e.g. `VsCodeDark`, `MidnightOcean`).
Custom themes are just `EditorTheme(...)` instances.

## Extending with a custom language

To add a new language, implement these interfaces from the `editor` module:

- `IncrementalTokenizer` — syntax highlighting
- `FoldingProvider` — code folding
- `LanguageService` *(optional)* — completions, diagnostics, hover,
  formatting

Bundle them as a `LanguageDefinition` and register with `LanguageRegistry`.
The [`languages/`](languages/) module is a working reference.

## Sample app

A minimal Android app for manual testing lives under [`sample/`](sample/):

```pwsh
./gradlew :sample:installDebug
```

To render screenshots of the start screen and every sample page, in every bundled theme,
into [`screenshots/`](screenshots/) (no device or emulator needed):

```pwsh
./scripts/capture-screenshots.ps1
```

## Building from source

```pwsh
./gradlew :editor:jvmTest :languages:jvmTest :languages-lsp:jvmTest    # JVM unit tests
./gradlew :editor:wasmJsBrowserTest                                    # Browser tests (needs Chrome)
./gradlew checkAbiAll                                                  # Public ABI check
./gradlew :editor:spotlessApply :languages:spotlessApply :languages-lsp:spotlessApply   # Auto-format
./gradlew :editor:publishToMavenLocal :languages:publishToMavenLocal :languages-lsp:publishToMavenLocal   # Publish to ~/.m2
```

For the full build/test/release workflow — including the `scripts/pre-push.ps1`
end-to-end check — see [AGENTS.md](AGENTS.md).

## Contributing

Contributions are welcome. Please read:

- [CONTRIBUTING.md](CONTRIBUTING.md) — how to propose changes
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — community expectations
- [SECURITY.md](SECURITY.md) — reporting vulnerabilities

## Versioning

Aardink follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Breaking public-API changes require a major version bump. Release notes live
in [CHANGELOG.md](CHANGELOG.md).

## License

Aardink is licensed under the [Apache License, Version 2.0](LICENSE).
