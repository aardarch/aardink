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
  build from [npmjs.com](https://www.npmjs.com/package/@aardarch/aardink-web), published with
  every Aardink version from GitHub Actions with npm provenance; pre-releases go to the `next`
  tag (`pnpm add @aardarch/aardink-web@next`). It has the built-in languages and themes
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
| `onContentChange(callback)` | Like `onChange`, with the text's version and what the change was: `"edit"`, `"undo"`, `"redo"` or `"flush"`. |
| `onCursorChange(callback)` | 1-based line and column. |
| `setDiagnostics(list)` / `setDiagnosticsJson(json)` | Squiggles and gutter markers, in Monaco-marker shape: 1-based lines and columns, `endColumn` exclusive. They replace the language's own diagnostics, which show until then; `null` (`"null"` in JSON) goes back to those. Either way they move with the text as it is edited. |
| `onDiagnosticsChange(callback)` | The language's own diagnostics, as a JSON array in the same shape, each time they are collected: 500 ms after the editor appears, and after each pause in typing. Not called while the host's list is shown. One listener; `null` removes it. |
| `revalidate` | Collects the language's own diagnostics again at once, without an edit: for when what they depend on outside the text has changed. They reach `onDiagnosticsChange`. Nothing happens while the host's list is shown. |
| `navigateTo(line, column)` | Scroll to and place the caret at a 1-based position, clamped to the document. |
| `showFind`, `undo`, `redo` | As named. `showFind` moves the keyboard focus to the find field. `undo`/`redo` return whether anything changed. |
| `canUndo`, `canRedo`, `pushUndoStop`, `getAlternativeVersionId` | Monaco's undo surface. Keep the alternative version id when saving: the text is unsaved while it differs, and undo brings it back. |
| `getSelections` / `setSelections` (and `…Json`) | Every selection in Monaco's `Selection` shape, the primary one first. |
| `setBaseline(text)` | The text the gutter's diff lane compares with, typically what was last saved; `""` turns it off. |
| `format(then)` | The language's formatter, as one undo step changing only what differs; `then` gets whether anything changed. |
| `focus` | Moves the keyboard focus to the editor. |
| `tokenize(languageId, text)` | For debugging a grammar: the tokens per line as JSON, in the shape of Monaco's `tokenize`. A grammar's comments and strings, being the editor's own, come back as `comment` and `string`, and the built-in languages' tokens under Monaco's standard names. |
| `dispose` / `isDisposed` | Stop the editor's work and take its element out of the container (the editor mounts into an element of its own inside it). Idempotent. The container itself is left for you to remove or reuse. **See W-1 below: on Compose Multiplatform 1.12 memory is not fully released.** |
| `preloadFont()` | Starts fetching the bundled font, so the first editor shows in it at once; `preloadAardink()` in the npm package calls it. |
| `registerLanguage(definitionJson, providers)` | Adds a language highlighted by a `DeclarativeGrammar` (a Monarch subset); `extends` takes a built-in language's service and folding, `inheritCompletions: false` leaves out its completions, and `triggerCharacters` open the completion list. `WebLanguageProviders` answers completions, hover and diagnostics as JSON. Editors mounted with the default registry see it. |
| `registerTheme(name, themeJson)` | Adds a theme from VS Code theme JSON; its `tokenColors` scopes also colour grammars' token names by dotted prefix. |
| `setResourceUrl(path, url)` | Fetch a bundled resource (e.g. `BUNDLED_FONT_PATH`) from a URL of your choosing; see [Fonts](#fonts). |

`WebEditorOptions` fields: `language`, `theme`, `fontSize`, `wordWrap`, `readOnly`,
`showGutter`, `showLineNumbers`, `showFoldMarkers`, `minimap`, `stickyScroll`,
`bracketPairColorization`, `highlightCurrentLine`, `tabSize`, `insertSpaces`. An unknown `language` falls back to plain
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
    triggerCharacters: ['{', '|', '@'], // besides XML's < / space : " =
    provideCompletionItems: (text, line, column) => [{ label: 'layer', kind: 'element' }],
    provideHover: (text, line, column) => ({ title: 'layer', contents: 'A group of elements.', example: '<layer>…</layer>' }),
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
  `cases` with `@array`, `@default` and `@eos`, capture groups (each with a token name,
  `{ token, next }` or `cases` of its own), `include`, `@rematch`, `defaultToken`, `ignoreCase`,
  `tokenPostfix` and `@name` regex attributes. Not supported: `@brackets`, `nextEmbedded`,
  `log`, `$1`-style substitutions in token names, and lookbehind, which is refused when the
  language is registered because Kotlin/wasm's regex engine runs it at every position. The
  grammar highlights a line at a time, so an edit rescans only until the lines below are back in
  the state they were in. [`AARDFLEX_WEB_SWITCHOVER.md`](AARDFLEX_WEB_SWITCHOVER.md) moves a real
  Monaco language over this way.
- **Colours and font styles** follow token names by dotted prefix, as in Monaco: `tag.aardflex`
  takes the theme's `tag.aardflex` rule, else its `tag` rule, else the built-in themes' colour for
  Monaco's standard names (`keyword`, `tag`, `attribute.name`, `number`, `delimiter`, ...). A
  name with a `comment` or `string` part (`comment.doc`, `string.format`) is a comment or a string
  to the editor, as in Monaco: no bracket colours or matching inside it.
- **Providers** get the whole text and a 1-based position and may answer at once or with a
  Promise; a provider that throws or rejects gives no answer rather than breaking the editor.
- **Completions** take Monaco's item fields `label`, `insertText`, `insertTextRules`, `kind`,
  `detail`, `documentation`, `filterText`, `sortText` and `range`, in an array or in Monaco's
  `{ suggestions }`. The editor does not filter on its own: it asks again on each letter typed
  while the list is open, so a provider offers what fits what has been typed. An item with
  `filterText` is also dropped while what has been typed of it (from the start of its `range`, or
  of the word before the caret, to the caret) does not match it; with `sortText` on any item they
  are ordered by it. `range` (Monaco's `IRange`, or the `replace` of `{ insert, replace }`) names
  exactly what the item replaces; without it the editor replaces the word before the caret, back
  to the first of `< > { } ( ) [ ] " ' = , ; . @ | :` or whitespace, which is why `@accent` typed
  after `@` would otherwise need an `insertText` of `accent`. Kinds are `element`, `attribute`,
  `value`, `snippet`, `module`, `property`, `transform` (Monaco's `function`) and `colorRef`
  (`color`).
- **With `extends`**, the extended language's completions follow yours, less any with the same
  `kind` and `label` as one of yours. `inheritCompletions: false` in the definition leaves them
  out (XML's are Android-flavoured: `LinearLayout`, `android:*`, `match_parent`), keeping its
  diagnostics, folding and hover; answering `{ suggestions, exclusive: true }` leaves them out for
  one request. `triggerCharacters` on the providers open the list when typed, besides the extended
  language's, or in place of them with `inheritCompletions: false`.
- **Hover** is `{ title?, contents, example? }`: plain text, and `example` shown as code below it.
- **`revalidate()`** collects the language's diagnostics again at once, for when what your
  `provideDiagnostics` depends on outside the text has changed (another file, a setting).

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
| `bracketPairColorization: { enabled }` | `bracketPairColorization` | The same object, or a boolean. |
| `renderLineHighlight` | `renderLineHighlight` / `highlightCurrentLine` | Anything but `'none'` highlights each caret's line. |
| `tabSize`, `insertSpaces` | `tabSize`, `insertSpaces` | |
| Multi-cursor and column selection | The same keys and mouse | Alt+click, Ctrl/Cmd+D, Ctrl+Shift+L, Ctrl+Alt+Up/Down, Shift+Alt+drag, Ctrl+Shift+Alt+arrows. |
| `model.onDidChangeContent` | `onDidChangeContent` | Receives the full text, at most once per frame, and `{ versionId, isUndoing, isRedoing, isFlush }`. |
| `getSelections` / `setSelections` | The same | Monaco's `Selection` fields. |
| `model.canUndo`, `canRedo`, `pushStackElement`, `getAlternativeVersionId` | `canUndo`, `canRedo`, `pushUndoStop`, `getAlternativeVersionId` | |
| `getAction('editor.action.formatDocument').run()` | `format()` | Resolves with whether anything changed. |
| `monaco.editor.tokenize` | `tokenize` | Same arguments and shape: a grammar's own names, and Monaco's names for the built-in languages' tokens (`keyword.flow`, `comment.doc`, `string.escape`, ...). |
| `monaco.editor.setModelMarkers` | `setDiagnostics` | Same 1-based, end-exclusive shape. `null` shows the language's own again. |
| `monaco.languages.register` + `setMonarchTokensProvider` | `registerLanguage` | One call: `{ id, grammar, extends?, comments? }`, plus providers. |
| `monaco.editor.defineTheme` | `defineTheme` / `registerTheme` | Monaco's `{ base, inherit, rules, colors }`, or VS Code theme JSON. A rule's `foreground` and `fontStyle` apply; its `background` is ignored. |
| `monaco.editor.onDidChangeMarkers` | `onDidChangeDiagnostics` | The language's own diagnostics, as marker objects. |
| Re-running a model's validation | `revalidate()` | Monaco has no single call; this asks the language's diagnostics again without an edit. |
| `revealPositionInCenterIfOutsideViewport` | `revealPosition` | Also places the caret there. A position already on screen does not scroll; one off screen comes to the middle of the view. |
| `renderWhitespace` | `renderWhitespace` | `'none'`, `'boundary'`, `'selection'` (the default), `'trailing'`, `'all'`; the colour is the theme's `editorWhitespace.foreground`. |
| `registerCompletionItemProvider(id, { triggerCharacters, provideCompletionItems })` | `registerLanguage`'s providers | `triggerCharacters` and `provideCompletionItems` on the same object; items with `filterText`, `sortText`, `range`. |
| Completion snippets (`insertTextRules: InsertAsSnippet`) | `insertTextRules` | `CompletionItemInsertTextRule.InsertAsSnippet`: tab stops, placeholders, choices and the `TM_` variables; Tab and Shift+Tab step through them. `KeepWhitespace` as in Monaco. |
| `fontFamily`, `automaticLayout` | None | The font is the bundled JetBrains Mono; the editor always follows its container's size. |

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

A new editor waits up to half a second for the font before it shows, rather than appearing in the
fallback and then jumping to JetBrains Mono (W-9); `preloadAardink()` starts the fetch early, so
usually it waits for nothing. If the font takes longer, the editor shows in the fallback and
switches when the font arrives.

## Host page CSS

- **Size the container.** The editor fills it. For a full-height editor on phones use
  `height: 100dvh` rather than `100vh`, which ignores the browser's own toolbars.
- **The editor follows its container's size**, also when only the container changes: a
  splitter dragged, or a container that was `display: none` when the editor was mounted and is
  shown later. Compose measures on the window's `resize` event, so the editor sends one
  (at most once a frame) whenever its container's size changes; a page's own `resize` listeners
  see those too.
- **The soft keyboard (W-5).** With
  `<meta name="viewport" content="width=device-width, initial-scale=1, interactive-widget=resizes-content">`
  the page shrinks when the keyboard opens, so the editor (and its keyboard toolbar) stay above
  it; without it, the keyboard covers the bottom of the page. The editor keeps the caret in view
  either way, and the browser's hidden input element follows the caret.
- **Do not move a mounted editor's container** to another place in the DOM. From Compose
  Multiplatform 1.13, an editor whose element leaves the document is torn down. Framework
  features that re-parent DOM nodes (a Svelte `{#key}` block, React portals moving between
  parents) do that: mount the editor where it stays, or dispose it and mount a new one.

## Browser requirements

Wasm GC and exception handling: Chrome/Edge 119+, Firefox 120+, Safari 18.2+.

## Performance

Measured with `tools/vite-smoke/perf.mjs` in headless Chrome 154 on a desktop machine, on
highlighted Kotlin with the language's own diagnostics running (the 0.6.0 code, 2026-09-27).
Expect slower on phones.

- **Idle:** the time from the event until frames have come at display rate for a third of a
  second, so it includes every frame the event causes, re-highlighting too.
- **Keys lost:** a 30-character burst typed at 10 keys a second, about 120 words a minute.

| Document | Mount → idle | Key → idle, p50 / p95 | Key → change callback, p95 | `setValue` → idle | Keys lost at 10 keys/s |
| --- | --- | --- | --- | --- | --- |
| 50 lines (2 KB) | 26 ms | 9.6 / 32.7 ms | 9.4 ms | 8 ms | 0 of 30 |
| 250 lines (8 KB) | 74 ms | 10.6 / 24.0 ms | 8.6 ms | 14 ms | 0 of 30 |
| 1,000 lines (33 KB) | 101 ms | 8.2 / 23.3 ms | 7.8 ms | 19 ms | 0 of 30 |
| 5,000 lines (163 KB), two runs | 62–70 ms | 7.8–9.9 / 16.9–21.6 ms | 3.3–6.0 ms | 11–18 ms | 0 of 30 |

A keystroke costs the same in any size of document: the editor lays out and draws only the lines
on screen, the input method sees a window of text around the caret rather than the whole
document, and the first highlighting pass over a large document runs in slices of about 8 ms, so
frames keep coming while it works. No main-thread task over 50 ms occurs anywhere in these runs:
mounting, typing, `setValue` or scrolling.

The tails are the language's diagnostics: they run half a second after each pause in typing, as
for a user, and `perf.mjs` pauses longer than that between keys, so a key sometimes arrives while
a slice of them is running and waits for it. Before automatic diagnostics (PR 7 of the 0.6 plan),
the 5,000-line document measured 8.6 / 18.9 ms per key and 2.6 ms to the change callback.
Measurements on a busy machine (a build running alongside) show single 50–60 ms tasks and p95s
past 30 ms; the table is from an otherwise idle one.

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
| W-1 | Mount + dispose 50×, heap | **Leaks ~320 KB per disposed editor** (275 KB in 0.5). JS heap after forced GC goes 9.1 → 25.1 → 41.2 MB over two batches of 50; no canvases or other elements are left behind, since `dispose` removes the editor's own element. Each `ComposeViewport` adds `resize`, `focus`, `blur`, `visibilitychange` and `dragend` listeners to `window` that Compose 1.12.1 never removes, and there is no public API to tear a viewport down. Removing those listeners by hand does not free the memory, so something inside the scene (such as the Recomposer's snapshot observers) also holds it. **Reuse one editor with `setValue`/`updateOptions` instead of mounting a new one per view.** Tracked upstream as [CMP-9090](https://youtrack.jetbrains.com/issue/CMP-9090) and [CMP-10507](https://youtrack.jetbrains.com/issue/CMP-10507); fixed for Compose Multiplatform 1.13, which disposes a viewport when its element leaves the DOM ([compose-multiplatform-core#3242](https://github.com/JetBrains/compose-multiplatform-core/pull/3242)). |
| W-2 | IME composition: CJK, macOS dead keys, Android Chrome Gboard | **Automated part passes:** `checklist.mjs W-2` drives composition through the DevTools protocol (`Input.imeSetComposition` / `Input.insertText`), the path Chrome's own IME bridge uses: kana → kanji, a second composition, a simulated dead key and Pinyin-style letters all commit exactly (4/4). **Real IMEs still manual:** Windows/macOS CJK, macOS dead keys and press-and-hold, Android Chrome with Gboard. |
| W-3 | Paste: 100 KB latency, CRLF, Firefox permission prompt | A 101 KB paste reports its change after ~21 ms. **CRLF used to be kept verbatim**; typed and pasted text is now normalised to LF. The editor takes pasted text from the browser's `paste` event, which needs no permission; Firefox and Safari: **manual**. |
| W-4 | Selection: mouse drag, Shift+arrows, double-click, touch handles | **Manual.** |
| W-5 | Mobile soft keyboard: viewport resize, toolbar placement | **Manual** (needs a phone). |
| W-6 | Focus: click-to-focus; Tab stays in the field; Ctrl+F/Ctrl+S are not the browser's | Click-to-focus works (the checklist types after a click). Tab and browser shortcuts: **manual**. |
| W-7 | Wheel over the editor must not scroll the page | Contained: a 600 px wheel over the editor leaves the page's `scrollY` at 0. |
| W-8 | devicePixelRatio and zoom | At DPR 2 the canvas has a 2000×1200 backing store for 1000×600 CSS px, and text and squiggles are sharp. A change of density or font scale (zoom, another screen) lays every line out again (`EditorViewTest`). Browser zoom by hand: **manual**. |
| W-9 | No flicker when Compose fetches a fallback font for a missing glyph | **Manual.** A new editor waits up to 500 ms for the bundled font instead of showing a first frame in the default monospace; `preloadAardink()` fetches it early. |
| W-10 | Typing latency, 5,000-line file | At 163 KB of Kotlin a keystroke settles in 8–10 ms (p50), no key is lost at 10 keys/s, and the initial highlighting pass causes no long task. `perf.mjs` measures it and CI gates it; see [Performance](#performance). |

## Known limitations

- A disposed editor is not fully released (W-1) on Compose Multiplatform 1.12; reuse editors.
  `dispose()` takes the editor's element out of the page, which from 1.13 frees it entirely.
- The console shows "Accessing `memory` via `wasmExports` is deprecated" once, when the editor
  loads its font. Kotlin 2.4.20's glue logs it the first time anything reads `wasmExports.memory`,
  and Compose Multiplatform's resources library (1.12.1, unchanged in 1.13.0-alpha01) reads it to
  copy a fetched file into WebAssembly memory. It is harmless and goes away with a Compose
  Multiplatform release that uses `kotlin.wasm.unsafe` instead.
- A theme rule's `background` is not drawn. Bold and italic come from the bundled font's own
  faces where it has them; otherwise the text renderer slants or thickens the regular face.
- The items marked **manual** in the checklist above wait for real devices before 0.6.0.
