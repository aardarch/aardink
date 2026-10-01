# Aardink

A Compose Multiplatform code editor with VS Code's keys and Monaco's API shape: multiple cursors,
incremental highlighting, folding, find and replace, diagnostics, completions, a minimap and
sticky scroll. It runs on Android, desktop (JVM) and the browser (Wasm) from one codebase, and
draws only the lines on screen, so a keystroke costs the same in a 5,000-line file as in a
50-line one.

[![Maven Central](https://img.shields.io/maven-central/v/com.aardarch/aardink.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.aardarch/aardink)
[![npm](https://img.shields.io/npm/v/@aardarch/aardink-web.svg?label=npm)](https://www.npmjs.com/package/@aardarch/aardink-web)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-26-brightgreen.svg)](#requirements)
[![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20JVM%20%7C%20Wasm-blue.svg)](#requirements)

> **Upgrading from 0.5?** 0.6 replaces the text field underneath the editor, and the API changes
> with it. [`docs/MIGRATION_0.6.md`](docs/MIGRATION_0.6.md) has every change with a before and
> after.

## Features

| | Android | Desktop | Web |
| --- | :---: | :---: | :---: |
| Syntax highlighting, incremental, for Kotlin, TypeScript, JSON, TOML, XML, HTML, CSS, Markdown | ✓ | ✓ | ✓ |
| Your own language: a tokenizer in Kotlin, or a Monarch grammar as data (`DeclarativeGrammar`) | ✓ | ✓ | ✓ |
| … or registered from JavaScript, with completions, hover and diagnostics in JavaScript | | | ✓ |
| Folding, find and replace, go to line | ✓ | ✓ | ✓ |
| Multiple cursors and column selection | ✓ | ✓ | ✓ |
| Undo grouped as Monaco does, `alternativeVersionId` for dirty tracking | ✓ | ✓ | ✓ |
| Completions, hover, signature help and quick fixes at the caret | ✓ | ✓ | ✓ |
| Diagnostics (squiggles and gutter markers), from the language or the host | ✓ | ✓ | ✓ |
| Go to definition, find references, format document or selection | ✓ | ✓ | ✓ |
| Bracket-pair colours and matching, current-line highlight | ✓ | ✓ | ✓ |
| Minimap and sticky scroll | ✓ | ✓ | ✓ |
| Diff lane against a saved baseline | ✓ | ✓ | ✓ |
| VS Code's keyboard shortcuts (Cmd on macOS) | with a keyboard | ✓ | ✓ |
| Input methods: composition, and a phone keyboard's autocorrect and voice input | ✓ | ✓ | ✓ |
| Touch: selection handles, text menu | ✓ | | ✓ |
| Magnifier while dragging a handle | ✓ | | |
| Keyboard toolbar above the soft keyboard: the language's characters, caret arrows, undo, redo | ✓ | | on touch devices |
| Right-click menu | | ✓ | ✓ |
| Language servers (`aardink-languages-lsp`) | streams | streams | WebSocket |
| Themes: `EditorTheme` data classes, six built in, or VS Code theme JSON | ✓ | ✓ | ✓ |

## Modules

Aardink publishes four artifacts on Maven Central:

- **`com.aardarch:aardink`** — the editor library: composables, state,
  theming, and the language-service interfaces.
- **`com.aardarch:aardink-languages`** — built-in language definitions
  (Kotlin, TypeScript, JSON, TOML, XML, HTML, CSS, Markdown, plain text).
- **`com.aardarch:aardink-languages-lsp`** — a bridge to an external
  Language Server over JSON-RPC (stream transport on JVM/Android, WebSocket in the browser).
- **`com.aardarch:aardink-editor-web`** — browser only: mounts the editor into a DOM element
  behind a small JavaScript-friendly API. See [Using Aardink in a web app](#using-aardink-in-a-web-app).

and one package on npm, **`@aardarch/aardink-web`**: the ready-built browser editor for any
JavaScript app, with the same version number.

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
import com.aardarch.aardink.ui.EditorOptions
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
            options = EditorOptions(showMinimap = true, stickyScroll = true),
        )
    }
}
```

With a language service and no `diagnostics` argument, the editor collects the service's
diagnostics itself. Read the text with `state.text`, the selections with `state.selections`, and
edit with `state.applyTextEdits(...)`; `state.canUndo`, `undo()` and `redo()` drive toolbar
buttons. See the [`sample/`](sample/) module for a runnable example.

## Using Aardink in a web app

The browser build is a WebAssembly module behind a Monaco-shaped API, published on npm as
[`@aardarch/aardink-web`](https://www.npmjs.com/package/@aardarch/aardink-web) with the built-in
languages and themes:

```sh
pnpm add @aardarch/aardink-web
```

```ts
import { createEditor } from '@aardarch/aardink-web';

const editor = await createEditor(container, (text) => save(text), { value: source, language: 'xml' });
```

For a grammar or language service of your own written in Kotlin, build the module yourself
instead: a small Gradle wasmJs module that depends on `aardink-editor-web`, with
[`sample-web/`](sample-web/) as the reference. [`docs/WEB_INTEGRATION.md`](docs/WEB_INTEGRATION.md)
covers both routes: the export template, npm packaging, Vite configuration, and a Monaco option
mapping.

A language of your own can be registered from JavaScript as a Monarch grammar, with completions,
hover and diagnostics answered by JavaScript functions, and a theme as Monaco's `defineTheme`
data. [`docs/AARDFLEX_WEB_SWITCHOVER.md`](docs/AARDFLEX_WEB_SWITCHOVER.md) moves a real Monaco
app over.

> **On the web:** a keystroke settles in about 10 ms, and nearly always under 35 ms, whatever the size of the document (measured
> in [`docs/WEB_INTEGRATION.md`](docs/WEB_INTEGRATION.md#performance)). A disposed editor is not
> fully released until Compose Multiplatform 1.13, so reuse one editor rather than mounting a new
> one per view.

## Theming

Themes are plain data classes. Wrap your editor in a
`CompositionLocalProvider(LocalEditorTheme provides …)` to apply a theme,
or pass one directly to `CodeEditorLayout`. The library ships a set of
ready-made themes via `EditorThemes` (e.g. `VsCodeDark`, `MidnightOcean`).
Custom themes are just `EditorTheme(...)` instances.

## Extending with a custom language

To add a new language, implement these interfaces from the `editor` module:

- `IncrementalTokenizer` — syntax highlighting, and `commentSyntax` for toggling comments
- `FoldingProvider` — code folding
- `LanguageService` *(optional)* — completions, diagnostics, hover, signature help, quick fixes,
  definitions, references and formatting

Bundle them as a `LanguageDefinition` and register with `LanguageRegistry`.
The [`languages/`](languages/) module is a working reference.

For highlighting alone, no tokenizer code is needed: `DeclarativeTokenizer(DeclarativeGrammar.parse(json))`
highlights from a Monarch grammar (Monaco's format) as JSON, a line at a time and incrementally.
Its tokens are `NamedTokenType`s named by the grammar, which an `EditorTheme` colours by the
longest dotted prefix it has an entry for, as Monaco's themes do.

## Sample apps

```pwsh
./gradlew :sample:installDebug                       # Android
./gradlew :sample-desktop:run                        # Desktop (JVM)
./gradlew :sample-web:wasmJsBrowserDevelopmentRun    # Browser
```

Each GitHub release also attaches the Android APK and a static build of the browser sample.

To render screenshots of the start screen and every sample page, in every bundled theme,
into [`screenshots/`](screenshots/) (no device or emulator needed):

```pwsh
./scripts/capture-screenshots.ps1
```

## Building from source

```pwsh
./gradlew :editor:jvmTest :languages:jvmTest :languages-lsp:jvmTest    # JVM unit tests
./gradlew :editor:wasmJsBrowserTest :editor-web:wasmJsBrowserTest    # Browser tests (needs Chrome)
./gradlew checkAbiAll                                                  # Public ABI check
./gradlew :editor:spotlessApply :languages:spotlessApply :languages-lsp:spotlessApply :editor-web:spotlessApply   # Auto-format
./gradlew publishToMavenLocal                                          # Publish all libraries to ~/.m2
./gradlew :sample-web:npmPackage                                       # Build the npm package
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
