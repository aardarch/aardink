<!--
  Copyright 2026 Aardarch

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Using Aardink in a web app

Aardink runs in the browser as Kotlin/Wasm through Compose Multiplatform. This page covers the
Kotlin side, which `com.aardarch:aardink-editor-web` provides today. The npm package, the Vite
set-up and the browser verification checklist are added with the `:sample-web` harness
(migration plan PR 9) and completed for the 0.5.0 release.

> Compose Multiplatform for the web is **Beta**. Expect the rendering layer underneath Aardink
> to change between Compose releases.

## How the pieces fit

```text
your web app (Svelte/React/...)  ── calls ──▶  your JS exports  (aardinkCreate, aardinkSetValue, ...)
                                                     │  your wasmJs module: binaries.executable()
                                                     ▼
                                          AardinkWeb (com.aardarch:aardink-editor-web)
                                                     │
                                          CodeEditorLayout + languages (:editor, :languages)
```

Aardink publishes libraries only. **You** build the executable: a small Gradle wasmJs module that
depends on `aardink-editor-web`, adds any languages and themes of your own, and declares the
`@JsExport` functions your page calls. That is what lets a product ship its own grammar without
Aardink knowing about it.

## The Kotlin API: `AardinkWeb`

| Function | Does |
| --- | --- |
| `mount(containerId, initialText, options, registry, themes)` | Renders an editor into the element with that id (it fills the element) and returns a handle. |
| `getValue` / `setValue` | Read or replace the whole text. `setValue` clears undo history, like Monaco's. |
| `updateOptions(options)` / `patchOptions(json)` / `currentOptions` | Replace all options, or apply only the JSON keys given (Monaco's `updateOptions(partial)`). |
| `onChange(callback)` | Full text after each change, typed or programmatic; at most once per frame. One listener; `null` removes it. |
| `onCursorChange(callback)` | 1-based line and column. |
| `setDiagnostics(list)` / `setDiagnosticsJson(json)` | Squiggles and gutter markers, in Monaco-marker shape: 1-based lines and columns, `endColumn` exclusive. |
| `navigateTo(line, column)` | Scroll to and place the caret at a 1-based position, clamped to the document. |
| `showFind`, `undo`, `redo` | As named. `undo`/`redo` return whether anything changed. |
| `dispose` / `isDisposed` | Remove the editor's composition and stop its work. Idempotent. The container element is left for you to remove or reuse. |

`WebEditorOptions` fields: `language`, `theme`, `fontSize`, `wordWrap`, `readOnly`,
`showGutter`, `showLineNumbers`, `showFoldMarkers`. An unknown `language` falls back to plain
text and an unknown `theme` to `vscode-dark`. Built-in theme keys are in
`AardinkWeb.builtInThemes`. Changing `language` keeps the text but starts a new undo history.

## The export template

Copy
[`editor-web/src/wasmJsTest/kotlin/com/aardarch/aardink/web/ExportsTemplate.kt`](../editor-web/src/wasmJsTest/kotlin/com/aardarch/aardink/web/ExportsTemplate.kt)
into your executable module, change its package, and fill in its three marked places: your
language registry, your themes and your version string. It lives in Aardink's test sources
so every Aardink build proves it still compiles, and `ExportsTemplateTest` exercises it. It is
not duplicated here because a copy in a document would drift.

Editors cross the JS boundary as integer ids. Every export is prefixed `aardink` so it cannot
collide with your module's own exports.

> With Kotlin 2.4.20, `@JsExport` functions declared in a *dependency* klib do end up in the
> consuming executable's exports: this was checked with `:editor-web`'s own test executable.
> The template stays the recommended route anyway, because only your module knows your
> registry and themes.

## Fonts

The browser canvas has no system fonts, so `aardink-editor-web` bundles **JetBrains Mono
Regular** (SIL Open Font License 1.1; see `editor-web/JETBRAINS_MONO_OFL.txt`) as a Compose
resource. Skia synthesizes the bold and italic the editor occasionally uses.

Your bundler must serve the `composeResources/` directory produced next to your `.mjs`/`.wasm`.
If it does not, the editor still works: it logs `Aardink: could not load the bundled JetBrains
Mono ...` to the console and falls back to the default monospace font.

## Browser requirements

Wasm GC and exception handling: Chrome/Edge 119+, Firefox 120+, Safari 18.2+.

## Performance on the web

Kotlin/Wasm runs tokenization on the page's only thread. Documents over 64 KB are tokenized in
chunks that yield to the browser, and documents over 2 MB are shown without highlighting or
folding. Both thresholds are in `EditorLimits`. As a guide, 64 KB of Kotlin tokenizes in about
170 ms in headless Chrome, spread over roughly nine frames.

## Known limitations

No minimap, no bracket-pair colouring, no multi-cursor.
