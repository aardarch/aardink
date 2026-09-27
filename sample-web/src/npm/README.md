# @aardarch/aardink-web

The [Aardink](https://github.com/aardarch/aardink) code editor for the browser: Compose
Multiplatform compiled to WebAssembly, behind a small Monaco-shaped API. It covers syntax
highlighting for Kotlin, TypeScript, JSON, TOML, XML, HTML, CSS and Markdown, plus folding,
find and replace, diagnostics, undo/redo and six themes.

## Install

```sh
pnpm add @aardarch/aardink-web     # or: npm install @aardarch/aardink-web
```

The package is plain ES modules plus two `.wasm` files and a font. It needs a bundler that
understands `new URL('./file', import.meta.url)`, which Vite, webpack 5 and Rollup all do.

## Use

```ts
import { createEditor, preloadAardink } from '@aardarch/aardink-web';

preloadAardink(); // optional: start loading the WebAssembly module early

const editor = await createEditor(document.getElementById('editor')!, (text) => save(text), {
  value: source,
  language: 'xml',
  theme: 'vscode-dark',
  wordWrap: 'on',
});

editor.updateOptions({ readOnly: true });
editor.setDiagnostics([
  { line: 3, startColumn: 5, endColumn: 9, message: 'Unknown tag', severity: 'error' },
]);
const stop = editor.onDidChangeCursor((line, column) => showPosition(line, column));
// The language's own diagnostics (shown until you call setDiagnostics; null goes back to them).
const off = editor.onDidChangeDiagnostics((markers) => showProblems(markers));
editor.dispose();
```

The editor fills its container, so give the container a size. `createEditor` is async because
the first call loads the WebAssembly module (about 13 MB, 4.7 MB gzipped); every editor on the
page shares that one module. See [`index.d.ts`](./index.d.ts) for the full API.

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

## Browser support

Chrome and Edge 119+, Firefox 120+, Safari 18.2+: the module needs WebAssembly garbage
collection and exception handling.

## Known limitations

Read these before adopting the package; the
[integration guide](https://github.com/aardarch/aardink/blob/main/docs/WEB_INTEGRATION.md) has
the measurements and the device checklist.

- **A disposed editor is not fully released** (about 275 KB each), because Compose
  Multiplatform cannot yet tear down its viewport. Reuse one editor with `setValue` and
  `updateOptions` rather than mounting one per view.
- There is no minimap or bracket-pair colouring yet.

## Licence

Apache-2.0. The bundled JetBrains Mono font is under the SIL Open Font License 1.1; see
`JETBRAINS_MONO_OFL.txt`.
