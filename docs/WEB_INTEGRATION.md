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
Kotlin side (`com.aardarch:aardink-editor-web`), turning it into an npm package, using that
package from a Vite app, and what the browser verification checklist found.

**Before adopting it, read [Performance](#performance) and [Known limitations](#known-limitations).**
Typing latency grows with document size, and a disposed editor is not fully released.

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
Aardink knowing about it. `:sample-web` in this repository is exactly such a module, and it is
the one to copy.

## The Kotlin API: `AardinkWeb`

| Function | Does |
| --- | --- |
| `mount(containerId, initialText, options, registry, themes)` | Renders an editor into the element with that id (it fills the element) and returns a handle. |
| `getValue` / `setValue` | Read or replace the whole text. `setValue` clears undo history, like Monaco's, and keeps the text exactly as given (CR LF included). |
| `updateOptions(options)` / `patchOptions(json)` / `currentOptions` | Replace all options, or apply only the JSON keys given (Monaco's `updateOptions(partial)`). |
| `onChange(callback)` | Full text after each change, typed or programmatic; at most once per frame. One listener; `null` removes it. |
| `onCursorChange(callback)` | 1-based line and column. |
| `setDiagnostics(list)` / `setDiagnosticsJson(json)` | Squiggles and gutter markers, in Monaco-marker shape: 1-based lines and columns, `endColumn` exclusive. |
| `navigateTo(line, column)` | Scroll to and place the caret at a 1-based position, clamped to the document. |
| `showFind`, `undo`, `redo` | As named. `undo`/`redo` return whether anything changed. |
| `dispose` / `isDisposed` | Remove the editor's composition and stop its work. Idempotent. The container element is left for you to remove or reuse. **See W-1 below: memory is not fully released.** |
| `setResourceUrl(path, url)` | Fetch a bundled resource (e.g. `BUNDLED_FONT_PATH`) from a URL of your choosing; see [Fonts](#fonts). |

`WebEditorOptions` fields: `language`, `theme`, `fontSize`, `wordWrap`, `readOnly`,
`showGutter`, `showLineNumbers`, `showFoldMarkers`. An unknown `language` falls back to plain
text and an unknown `theme` to `vscode-dark`. Built-in theme keys are in
`AardinkWeb.builtInThemes`. Changing `language` keeps the text but starts a new undo history.

Typed and pasted text has its line endings normalised to LF (a Windows or browser paste delivers
CR LF). Text a host passes to `setValue` is kept verbatim.

## The export template

Copy
[`editor-web/src/wasmJsTest/kotlin/com/aardarch/aardink/web/ExportsTemplate.kt`](../editor-web/src/wasmJsTest/kotlin/com/aardarch/aardink/web/ExportsTemplate.kt)
into your executable module, change its package, and fill in its three marked places: your
language registry, your themes and your version string. It lives in Aardink's test sources
so every Aardink build proves it still compiles, and `ExportsTemplateTest` exercises it.
`:sample-web`'s `Exports.kt` is the running copy, and `:sample-web:verifyExportsMatchTemplate`
fails the build if the two ever export different functions. The template is not duplicated here
because a copy in a document would drift.

Editors cross the JS boundary as integer ids. Every export is prefixed `aardink` so it cannot
collide with your module's own exports.

> With Kotlin 2.4.20, `@JsExport` functions declared in a *dependency* klib do end up in the
> consuming executable's exports. The template stays the recommended route anyway, because only
> your module knows your registry and themes.

## Building an npm package

`:sample-web` is the reference for this step; copy its `npmPackage` Gradle task and `src/npm/`
folder into your own module.

```pwsh
./gradlew :sample-web:npmPackage     # → sample-web/build/npm, the @aardarch/aardink-web package
```

The package contains the raw ES modules (`kotlin/aardink-web.mjs` and friends), both `.wasm`
files, Skiko and the bundled font. It also holds `index.js` and `index.d.ts`, which wrap the
integer-handle exports in a Monaco-shaped API:

```ts
import { createEditor } from '@aardarch/aardink-web';

const editor = await createEditor(container, (text) => save(text), {
  value: source, language: 'xml', theme: 'vscode-dark', wordWrap: 'on',
});
editor.updateOptions({ readOnly: true });
editor.setDiagnostics([{ line: 3, startColumn: 5, endColumn: 9, message: 'Unknown tag', severity: 'error' }]);
const stop = editor.onDidChangeCursor((line, column) => status(line, column));
editor.dispose();
```

`createEditor` is async: the first call loads the WebAssembly module (~13 MB of `.wasm`, ~4.7 MB
gzipped), and `preloadAardink()` starts that early. The package depends on `@js-joda/core`, which
Compose's date/time support imports. Its dependency list is copied from the one Kotlin generates
for the module, so it stays accurate.

### Vite

```js
// vite.config.js
export default defineConfig({
  // The dev-server pre-bundler rewrites the package into .vite/deps, which breaks the relative
  // new URL('./x.wasm', import.meta.url) references the loader relies on.
  optimizeDeps: { exclude: ['@aardarch/aardink-web'] },
  build: { target: 'es2022', chunkSizeWarningLimit: 3500 },
});
```

`vite build` emits both `.wasm` files and the font as hashed assets. It prints two harmless
warnings about `node:fs` and `node:url` being externalised: the Kotlin loader has Node and Deno
branches that never run in a browser. [`tools/vite-smoke/`](../tools/vite-smoke/) is a complete,
minimal example. CI runs it on every change: `pnpm smoke` builds it and drives it in headless
Chrome, failing on any failed check or failed request.

### Option names coming from Monaco

| Monaco | Aardink | Notes |
| --- | --- | --- |
| `value` | `value` | `createEditor` only; afterwards `setValue`. |
| `language` | `language` | `kotlin`, `typescript`, `json`, `toml`, `xml`, `html`, `css`, `markdown`, `plaintext`, plus any you register. |
| `theme` | `theme` | Aardink's theme keys, or your own (see the template). |
| `fontSize` | `fontSize` | CSS pixels; line height scales with it. |
| `wordWrap` (`'on'` / `'off'`) | `wordWrap` | Same values. |
| `readOnly` | `readOnly` | |
| `lineNumbers` (`'on'` / `'off'`) | `showLineNumbers` | Boolean. |
| `folding` | `showFoldMarkers` | Boolean. |
| `glyphMargin` | `showGutter` | Hides the whole gutter. |
| `minimap`, `bracketPairColorization`, multi-cursor | — | Not supported. |
| `model.onDidChangeContent` | `onDidChangeContent` | Receives the full text, at most once per frame. |
| `monaco.editor.setModelMarkers` | `setDiagnostics` | Same 1-based, end-exclusive shape. |
| `revealPositionInCenter` | `revealPosition` | Scrolls it into view; not centred. |

## Fonts

The browser canvas has no system fonts, so `aardink-editor-web` bundles **JetBrains Mono
Regular** (SIL Open Font License 1.1; see `editor-web/JETBRAINS_MONO_OFL.txt`) as a Compose
resource. Skia synthesizes the bold and italic the editor occasionally uses.

Compose looks for resources relative to the **page**, not the module, which a package installed
under `node_modules` never satisfies. So `index.js` points the font at its own copy with a literal
`new URL(..., import.meta.url)`, which is also what makes Vite emit the file. Outside the npm
package, call `aardinkSetBundledFontUrl(url)` before the first `aardinkCreate`, or serve
`composeResources/` next to the page. If the font cannot be loaded at all, the editor still works:
it logs `Aardink: could not load the bundled JetBrains Mono ...` and falls back to the default
monospace font.

## Browser requirements

Wasm GC and exception handling: Chrome/Edge 119+, Firefox 120+, Safari 18.2+.

## Performance

Measured in headless Chrome on a desktop machine (`tools/vite-smoke/checklist.mjs`, and the
harness's development build for profiles). Expect slower on phones.

**Typing latency grows linearly with document size.** Keystroke to painted frame:

| Document | Kotlin | Plain text |
| --- | --- | --- |
| 250 lines (6 KB) | ~50 ms | — |
| 1,000 lines (26 KB) | ~190 ms | — |
| 2,000 lines (54 KB) | 90–440 ms | ~100 ms |
| 5,000 lines (139 KB) | ~1 s | ~250 ms |

The cause is architectural. The editor is one `BasicTextField`, and Compose lays out the whole
document as a single paragraph on every change, then again when Skia paints it. A profile of five
keystrokes in 125 KB of Kotlin puts ~4.6 of ~6 s in `ParagraphLayouter.layoutParagraph`.
Aardink's own share is small: re-applying token styles costs ~65 ms per keystroke at that size.
Android runs the same design, but its native text stack is fast enough that it does not show.
Fixing it for the web means rendering only the visible lines, which is a redesign; see the
migration plan, §7.4.

Tokenization is not the bottleneck: documents over 64 KB are tokenized in chunks that yield to
the browser (`EditorLimits`), and 64 KB of Kotlin takes ~170 ms of total tokenizing work. The first
layout of a large document is one long task, though: ~1.4 s for 163 KB.

**Guidance:** fine for files of a few hundred lines, which is what the editor was designed
around. Beyond ~1,000 lines of highlighted code, typing is noticeably laggy.

## Verification checklist

Automated items run against the production build: `pnpm build && node checklist.mjs` in
`tools/vite-smoke/` covers W-1, W-3, W-7, W-8 and W-10. The rest need real devices or a person.

| ID | Check | Result |
| --- | --- | --- |
| W-1 | Mount + dispose 50×, heap | **Leaks ~275 KB per disposed editor.** JS heap after forced GC goes 8.5 → 22.2 → 35.9 MB over two batches of 50; no canvases are left behind. Each `ComposeViewport` adds `resize`, `focus`, `blur`, `visibilitychange` and `dragend` listeners to `window` that Compose 1.12.1 never removes, and there is no public API to tear a viewport down. Removing those listeners by hand does not free the memory, so something inside the scene (such as the Recomposer's snapshot observers) also holds it. **Reuse one editor with `setValue`/`updateOptions` instead of mounting a new one per view.** To report upstream. |
| W-2 | IME composition: CJK, macOS dead keys, Android Chrome Gboard | **Manual.** Not yet run. |
| W-3 | Paste: 100 KB latency, CRLF, Firefox permission prompt | A 101 KB paste reports its change after ~120 ms. **CRLF used to be kept verbatim**; typed and pasted text is now normalised to LF. Firefox prompt: **manual**. |
| W-4 | Selection: mouse drag, Shift+arrows, double-click, touch handles | **Manual.** |
| W-5 | Mobile soft keyboard: viewport resize, toolbar placement | **Manual** (needs a phone). |
| W-6 | Focus: click-to-focus; Tab stays in the field; Ctrl+F/Ctrl+S are not the browser's | Click-to-focus works (the checklist types after a click). Tab and browser shortcuts: **manual**. |
| W-7 | Wheel over the editor must not scroll the page | Contained: a 600 px wheel over the editor leaves the page's `scrollY` at 0. |
| W-8 | devicePixelRatio and zoom | At DPR 2 the canvas has a 2000×1200 backing store for 1000×600 CSS px, and text and squiggles are sharp. Browser zoom: **manual**. |
| W-9 | No flicker when Compose fetches a fallback font for a missing glyph | **Manual.** The bundled font swaps in once it loads (the first frame uses the default monospace). |
| W-10 | Typing latency, 5,000-line file | At 163 KB of Kotlin, keystroke → change callback has a median of ~160 ms and a worst case of ~330 ms; to a *painted frame* it is ~1 s. See [Performance](#performance). |

## Known limitations

- Typing latency grows with document size (see [Performance](#performance)).
- A disposed editor is not fully released (W-1); reuse editors.
- No minimap, no bracket-pair colouring, no multi-cursor.
