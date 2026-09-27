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

**Before adopting it, read [Known limitations](#known-limitations):** a disposed editor is not
fully released until Compose Multiplatform 1.13.

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

There are two ways in:

- **Use the ready-built package.** `pnpm add @aardarch/aardink-web` installs `:sample-web`'s
  build from npm, released with every Aardink version. It has the built-in languages and themes
  and the Monaco-shaped API below, and needs no Gradle or JDK. A language of your own can be
  registered from JavaScript as a Monarch grammar, with completions, hover and diagnostics
  answered by your own functions, and a theme as Monaco's `defineTheme` data; see
  [Your own languages and themes](#your-own-languages-and-themes). Start with
  [Building an npm package](#building-an-npm-package) for the API and
  [Vite](#vite) for the bundler set-up; skip the Gradle parts.
- **Build your own executable** when you need a grammar or language service written in Kotlin: a small Gradle wasmJs module that depends on `aardink-editor-web`, adds your
  languages and themes, and declares the `@JsExport` functions your page calls. That is what lets
  a product ship its own grammar without Aardink knowing about it. `:sample-web` in this
  repository is exactly such a module, and it is the one to copy.

## The Kotlin API: `AardinkWeb`

| Function | Does |
| --- | --- |
| `mount(containerId, initialText, options, registry, themes)` | Renders an editor into the element with that id (it fills the element) and returns a handle. |
| `getValue` / `setValue` | Read or replace the whole text. `setValue` clears undo history, like Monaco's, and keeps the text exactly as given (CR LF included). |
| `updateOptions(options)` / `patchOptions(json)` / `currentOptions` | Replace all options, or apply only the JSON keys given (Monaco's `updateOptions(partial)`). |
| `onChange(callback)` | Full text after each change, typed or programmatic; at most once per frame. One listener; `null` removes it. |
| `onCursorChange(callback)` | 1-based line and column. |
| `setDiagnostics(list)` / `setDiagnosticsJson(json)` | Squiggles and gutter markers, in Monaco-marker shape: 1-based lines and columns, `endColumn` exclusive. They replace the language's own diagnostics, which show until then; `null` (`"null"` in JSON) goes back to those. Either way they move with the text as it is edited. |
| `onDiagnosticsChange(callback)` | The language's own diagnostics, as a JSON array in the same shape, each time they are collected: 500 ms after the editor appears, and after each pause in typing. Not called while the host's list is shown. One listener; `null` removes it. |
| `navigateTo(line, column)` | Scroll to and place the caret at a 1-based position, clamped to the document. |
| `showFind`, `undo`, `redo` | As named. `undo`/`redo` return whether anything changed. |
| `dispose` / `isDisposed` | Remove the editor's composition and stop its work. Idempotent. The container element is left for you to remove or reuse. **See W-1 below: memory is not fully released.** |
| `registerLanguage(definitionJson, providers)` | Adds a language highlighted by a `DeclarativeGrammar` (a Monarch subset); `extends` takes a built-in language's service and folding. `WebLanguageProviders` answers completions, hover and diagnostics as JSON. Editors mounted with the default registry see it. |
| `registerTheme(name, themeJson)` | Adds a theme from VS Code theme JSON; its `tokenColors` scopes also colour grammars' token names by dotted prefix. |
| `setResourceUrl(path, url)` | Fetch a bundled resource (e.g. `BUNDLED_FONT_PATH`) from a URL of your choosing; see [Fonts](#fonts). |

`WebEditorOptions` fields: `language`, `theme`, `fontSize`, `wordWrap`, `readOnly`,
`showGutter`, `showLineNumbers`, `showFoldMarkers`, `minimap`, `stickyScroll`. An unknown `language` falls back to plain
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
const off = editor.onDidChangeDiagnostics((markers) => showProblems(markers));
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

### Your own languages and themes

```ts
import { createEditor, defineTheme, registerLanguage } from '@aardarch/aardink-web';

await registerLanguage(
  {
    id: 'aardflex',
    extends: 'xml', // XML's completions, diagnostics and folding too
    grammar: {
      tokenPostfix: '.aardflex',
      tokenizer: {
        root: [
          [/<\/?(layer|text)\b/, 'tag'],
          [/[a-z-]+(?==)/, 'attribute.name'],
          [/"[^"]*"/, 'attribute.value'],
        ],
      },
    },
    comments: { blockComment: ['<!--', '-->'] },
  },
  {
    provideCompletionItems: (text, line, column) => [{ label: 'layer', kind: 'element' }],
    provideDiagnostics: async (text) => validate(text), // AardinkDiagnostic[]
  },
);
await defineTheme('aardflex-dark', {
  base: 'vs-dark',
  inherit: true,
  rules: [{ token: 'tag.aardflex', foreground: 'c586c0' }],
});
const editor = await createEditor(container, save, { language: 'aardflex', theme: 'aardflex-dark' });
```

- **Grammars** are a subset of Monaco's Monarch: states, rules of `[regex, action, next?]`,
  `cases` with `@array`, `@default` and `@eos`, capture groups, `include`, `@rematch`,
  `defaultToken`, `ignoreCase` and `tokenPostfix`. Lookbehind is refused when the language is
  registered: Kotlin/wasm's regex engine runs it at every position. The grammar highlights a line
  at a time, so an edit rescans only until the lines below are back in the state they were in.
- **Colours** follow token names by dotted prefix, as in Monaco: `tag.aardflex` takes the theme's
  `tag.aardflex` colour, else its `tag` colour, else the built-in themes' colour for Monaco's
  standard names (`keyword`, `tag`, `attribute.name`, `number`, `delimiter`, ...). `comment` and
  `string` tokens are the editor's own comments and strings.
- **Providers** get the whole text and a 1-based position and may answer at once or with a
  Promise; a provider that throws or rejects gives no answer rather than breaking the editor.

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
| `minimap: { enabled }` | `minimap` | The same object, or a boolean. Blocks rather than characters, as with Monaco's `renderCharacters: false`. |
| `stickyScroll: { enabled }` | `stickyScroll` | The same object, or a boolean. |
| `bracketPairColorization` | — | Always on for now. |
| Multi-cursor and column selection | The same keys and mouse | Alt+click, Ctrl/Cmd+D, Ctrl+Shift+L, Ctrl+Alt+Up/Down, Shift+Alt+drag, Ctrl+Shift+Alt+arrows. |
| `model.onDidChangeContent` | `onDidChangeContent` | Receives the full text, at most once per frame. |
| `monaco.editor.setModelMarkers` | `setDiagnostics` | Same 1-based, end-exclusive shape. `null` shows the language's own again. |
| `monaco.languages.register` + `setMonarchTokensProvider` | `registerLanguage` | One call: `{ id, grammar, extends?, comments? }`, plus providers. |
| `monaco.editor.defineTheme` | `defineTheme` / `registerTheme` | Monaco's `{ base, inherit, rules, colors }`, or VS Code theme JSON. |
| `monaco.editor.onDidChangeMarkers` | `onDidChangeDiagnostics` | The language's own diagnostics, as marker objects. |
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

Measured with `tools/vite-smoke/perf.mjs` in headless Chrome 154 on a desktop machine, on
highlighted Kotlin (0.6 renderer, 2026-09-27). Expect slower on phones.

- **Idle:** the time from the event until frames have come at display rate for a third of a
  second, so it includes every frame the event causes, re-highlighting too.
- **Keys lost:** a 30-character burst typed at 10 keys a second, about 120 words a minute.

| Document | Mount → idle | Key → idle, p50 / p95 | Key → change callback, p95 | `setValue` → idle | Keys lost at 10 keys/s |
| --- | --- | --- | --- | --- | --- |
| 50 lines (2 KB) | 34 ms | 7.8 / 15.6 ms | 1.9 ms | 4 ms | 0 of 30 |
| 250 lines (8 KB) | 56 ms | 8.1 / 13.8 ms | 2.2 ms | 4 ms | 0 of 30 |
| 1,000 lines (33 KB) | 84 ms | 9.5 / 16.8 ms | 2.3 ms | 13 ms | 0 of 30 |
| 5,000 lines (163 KB) | 82 ms | 8.6 / 18.9 ms | 2.6 ms | 12 ms | 0 of 30 |

A keystroke costs the same in any size of document: the editor lays out and draws only the lines
on screen, the input method sees a window of text around the caret rather than the whole
document, and the first highlighting pass over a large document runs in slices of about 8 ms, so
frames keep coming while it works. No main-thread task over 50 ms occurs while mounting or typing
in the 5,000-line document.

The minimap (`minimap: { enabled: true }`) adds 0.1 to 0.5 ms of main-thread work per frame while
scrolling the 5,000-line document, and scrolling stays at 60 frames a second: it draws the text as
blocks into tiles of 256 lines, kept until a line in them changes, and draws only the tiles on
screen.

For comparison, 0.5 (one `BasicTextField` holding the whole document) took 3.2 s to settle after
mounting the same 5,000 lines, 3.7 s after each keystroke, and lost 15 to 29 of 30 keys typed at
10 keys a second.

CI runs `perf.mjs --gate` on every push; a measurement past its gate fails the build. The gates
(400 ms to mount, 50 ms p95 per key, no lost keys, 2 ms per frame for the minimap) are looser
than the targets above to absorb shared-runner noise.

## Verification checklist

Automated items run against the production build: `pnpm checklist` in `tools/vite-smoke/`
covers W-1, part of W-2, W-3, W-7, W-8 and W-10 (`node checklist.mjs W-2 W-10` runs a subset), and
`pnpm perf` measures latency and lost keys. The rest need real devices or a person.

| ID | Check | Result |
| --- | --- | --- |
| W-1 | Mount + dispose 50×, heap | **Leaks ~275 KB per disposed editor.** JS heap after forced GC goes 8.5 → 22.2 → 35.9 MB over two batches of 50; no canvases are left behind. Each `ComposeViewport` adds `resize`, `focus`, `blur`, `visibilitychange` and `dragend` listeners to `window` that Compose 1.12.1 never removes, and there is no public API to tear a viewport down. Removing those listeners by hand does not free the memory, so something inside the scene (such as the Recomposer's snapshot observers) also holds it. **Reuse one editor with `setValue`/`updateOptions` instead of mounting a new one per view.** Tracked upstream as [CMP-9090](https://youtrack.jetbrains.com/issue/CMP-9090) and [CMP-10507](https://youtrack.jetbrains.com/issue/CMP-10507); fixed for Compose Multiplatform 1.13, which disposes a viewport when its element leaves the DOM ([compose-multiplatform-core#3242](https://github.com/JetBrains/compose-multiplatform-core/pull/3242)). |
| W-2 | IME composition: CJK, macOS dead keys, Android Chrome Gboard | **Automated part passes:** `checklist.mjs W-2` drives composition through the DevTools protocol (`Input.imeSetComposition` / `Input.insertText`), the path Chrome's own IME bridge uses: kana → kanji, a second composition, a simulated dead key and Pinyin-style letters all commit exactly (4/4). **Real IMEs still manual:** Windows/macOS CJK, macOS dead keys and press-and-hold, Android Chrome with Gboard. |
| W-3 | Paste: 100 KB latency, CRLF, Firefox permission prompt | A 101 KB paste reports its change after ~21 ms. **CRLF used to be kept verbatim**; typed and pasted text is now normalised to LF. The editor takes pasted text from the browser's `paste` event, which needs no permission; Firefox and Safari: **manual**. |
| W-4 | Selection: mouse drag, Shift+arrows, double-click, touch handles | **Manual.** |
| W-5 | Mobile soft keyboard: viewport resize, toolbar placement | **Manual** (needs a phone). |
| W-6 | Focus: click-to-focus; Tab stays in the field; Ctrl+F/Ctrl+S are not the browser's | Click-to-focus works (the checklist types after a click). Tab and browser shortcuts: **manual**. |
| W-7 | Wheel over the editor must not scroll the page | Contained: a 600 px wheel over the editor leaves the page's `scrollY` at 0. |
| W-8 | devicePixelRatio and zoom | At DPR 2 the canvas has a 2000×1200 backing store for 1000×600 CSS px, and text and squiggles are sharp. Browser zoom: **manual**. |
| W-9 | No flicker when Compose fetches a fallback font for a missing glyph | **Manual.** The bundled font swaps in once it loads (the first frame uses the default monospace). |
| W-10 | Typing latency, 5,000-line file | At 163 KB of Kotlin a keystroke settles in 8.6 ms (p50), no key is lost at 10 keys/s, and the initial highlighting pass causes no long task. `perf.mjs` measures it and CI gates it; see [Performance](#performance). |

## Known limitations

- A disposed editor is not fully released (W-1); reuse editors. Fixed upstream for Compose Multiplatform 1.13.
- No minimap or bracket-pair colouring yet.
