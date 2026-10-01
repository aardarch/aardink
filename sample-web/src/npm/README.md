# @aardarch/aardink-web

The [Aardink](https://github.com/aardarch/aardink) code editor for the browser: Compose
Multiplatform compiled to WebAssembly, behind a small Monaco-shaped API. It highlights Kotlin,
TypeScript, JSON, TOML, XML, HTML, CSS and Markdown, and any language you register as a Monarch
grammar. It has folding, find and replace, diagnostics, undo and redo, multiple cursors and column
selection, bracket-pair colours, a minimap and sticky scroll, and six themes. It draws only the
lines on screen, so a keystroke costs the same in a 5,000-line file as in a 50-line one.

## Install

```sh
pnpm add @aardarch/aardink-web     # or: npm install @aardarch/aardink-web
pnpm add @aardarch/aardink-web@next   # the latest pre-release
```

The package is plain ES modules plus two `.wasm` files and a font. It needs a bundler that
understands `new URL('./file', import.meta.url)`, which Vite, webpack 5 and Rollup all do. Each
version is published from GitHub Actions with npm provenance.

## Use

```ts
import { createEditor, preloadAardink } from '@aardarch/aardink-web';

preloadAardink(); // optional: start loading the WebAssembly module and the font early

const editor = await createEditor(document.getElementById('editor')!, (text) => save(text), {
  value: source,
  language: 'xml',
  theme: 'vscode-dark',
  wordWrap: 'on',
  minimap: { enabled: true },
});

editor.updateOptions({ readOnly: true });
editor.setDiagnostics([
  { line: 3, startColumn: 5, endColumn: 9, message: 'Unknown tag', severity: 'error' },
]);
const stop = editor.onDidChangeCursor((line, column) => showPosition(line, column));
// The language's own diagnostics (shown until you call setDiagnostics; null goes back to them).
const off = editor.onDidChangeDiagnostics((markers) => showProblems(markers));
const savedAt = editor.getAlternativeVersionId(); // unsaved while it differs
editor.dispose();
```

A language of your own is a Monarch grammar registered before use, optionally extending a built-in
language's completions, diagnostics and folding, with Monaco's `defineTheme` for its colours:

```ts
import { defineTheme, registerLanguage } from '@aardarch/aardink-web';

await registerLanguage(
  { id: 'toy', extends: 'xml', grammar: { tokenizer: { root: [[/\bshout\b/, 'keyword']] } } },
  {
    triggerCharacters: ['{'], // besides XML's
    provideCompletionItems: (text, line, column) => [{ label: 'shout', kind: 'element' }],
  },
);
await defineTheme('toy-dark', { base: 'vs-dark', inherit: true, rules: [{ token: 'keyword', foreground: 'ff00ff' }] });
```

Completion items take Monaco's `filterText`, `sortText` and `range` too, and
`inheritCompletions: false` in the definition leaves out the extended language's completions
(XML's are Android-flavoured) while keeping its diagnostics and folding. A hover can carry an
`example`, shown as code.

The editor fills its container, so give the container a size. `createEditor` is async because
the first call loads the WebAssembly module (about 13 MB, 4.7 MB gzipped); every editor on the
page shares that one module. If that load fails, the call rejects and the next call loads again
(though Chrome keeps a failed module download until the page is reloaded). See
[`index.d.ts`](./index.d.ts) for the full API, and the
[integration guide](https://github.com/aardarch/aardink/blob/main/docs/WEB_INTEGRATION.md) for how
Monaco's options and calls map onto it.

## Vite

```js
// vite.config.js
import { defineConfig } from 'vite';

export default defineConfig({
  // The dev-server pre-bundler would move the package into .vite/deps and break the
  // new URL(..., import.meta.url) references the loader relies on.
  optimizeDeps: { exclude: ['@aardarch/aardink-web'] },
  build: { target: 'es2022', chunkSizeWarningLimit: 3500 },
});
```

`vite build` prints two harmless warnings about `node:fs` and `node:url` being externalised:
the Kotlin loader has Node and Deno branches that never run in a browser. Serve `.wasm` with
`Content-Type: application/wasm`.

## The page

- Mount an editor where it stays: do not move its container to another place in the DOM, as a
  Svelte `{#key}` block or a React portal can. Dispose it and mount a new one instead.
- On phones, `<meta name="viewport" content="width=device-width, initial-scale=1, interactive-widget=resizes-content">`
  keeps the editor above the soft keyboard.

## Browser support

Chrome and Edge 119+, Firefox 120+, Safari 18.2+: the module needs WebAssembly garbage
collection and exception handling.

## Known limitations

Read these before adopting the package; the
[integration guide](https://github.com/aardarch/aardink/blob/main/docs/WEB_INTEGRATION.md) has
the measurements and the device checklist.

- **A disposed editor is not fully released** (about 275 KB each) until Aardink moves to Compose
  Multiplatform 1.13. Reuse one editor with `setValue` and `updateOptions` rather than mounting
  one per view.
- A theme rule's `background` is ignored; its `foreground` and `fontStyle` apply.

## Licence

Apache-2.0. The bundled JetBrains Mono font is under the SIL Open Font License 1.1; see
`JETBRAINS_MONO_OFL.txt`.
