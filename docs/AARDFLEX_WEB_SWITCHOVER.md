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

# aardflex-web-app: from Monaco to Aardink

The runbook for switching `aardflex-web-app` (`C:\repos\aardarch\aardflex-web-app`: Svelte, Vite+,
pnpm, `monaco-editor ^0.55.1`) to `@aardarch/aardink-web`. It replaces `KMP_MIGRATION_PLAN.md`
§11, in which the app built its own Gradle wasm executable with its language written in Kotlin.
That design is dropped: `XmlTokenizer` is an `object` and cannot be extended, and it would have
put a JDK and Gradle into the app's CI. The app now installs the npm package and registers its
language from TypeScript, with the grammar it already has.

The work happens in that repository, in three steps: **A1** adds Aardink behind a build flag,
**A2** makes it the default, **A3** removes Monaco. The file and line references are to
`src/editor/setup.ts` and `src/lib/components/EditorPane.svelte` as of the app's commit `20951ff`.

## What carries over

| Monaco, in `setup.ts` | Aardink |
| --- | --- |
| The Monarch grammar (`setMonarchTokensProvider`, lines 261–346) | Unchanged. `registerLanguage` takes the same object, RegExp literals included. Its groups with their own `next` and `cases` (`(<\/?)(\w+)`, `(<\?)(xml)`) need Aardink 0.6.0 or later. |
| `defineTheme('aardflex-dark', …)` (lines 349–365) | Unchanged, `fontStyle: 'bold'` on `tag.aardflex` included. |
| The completion provider (lines 368–447) | A function of the whole text and a 1-based line and column that returns items in Monaco's shape, snippets (`insertTextRules: CompletionItemInsertTextRule.InsertAsSnippet`) included; see A1 step 4. |
| `renderWhitespace: 'boundary'` | The same option. |
| `register({ id: 'aardflex-xml' })` | `registerLanguage({ id: 'aardflex-xml', extends: 'xml', … })`. With `extends`, the language also gets the built-in XML language's diagnostics, completions (after the app's own), hover, closing tags as you type, indentation, formatting, tag folding and completion trigger characters (`<`, space and `"` among them). |
| `editor.create(container, { … })` | `await createEditor(container, onChange, { … })`: async, because the first call loads the WebAssembly module. |
| `getValue`, `setValue`, `updateOptions`, `dispose` | The same calls. |
| `onDidChangeModelContent` | `onDidChangeContent(listener(text, change))`: the full text, at most once per frame, **after** the change rather than during it; `change.isFlush` marks a `setValue`. |

What Aardink does not have, and the app loses with it:

- **`fontFamily`**: the editor always uses its bundled JetBrains Mono, which the current Monaco
  set-up asks for first anyway.
- `automaticLayout` and `scrollBeyondLastLine` have no counterpart: the editor always fills its
  container and follows its size.

## A1: Aardink behind `VITE_EDITOR=monaco|aardink`

The default stays `monaco`. Each step leaves `pnpm check`, `pnpm test` and `pnpm build` green.

### 1. Install

```sh
pnpm add @aardarch/aardink-web@next   # 0.6.0-rc3
pnpm add @aardarch/aardink-web@^0.6.0 # once 0.6.0 is out
```

### 2. Shared data

Move `ELEMENTS`, `ATTRIBUTES`, `MODULE_TYPES`, `TEXT_STYLES` and `TRANSFORMS` (`setup.ts:13-245`)
into `src/editor/aardflexData.ts` and export them. Both editors import them from there.

### 3. The grammar and theme, shared

Move the object passed to `setMonarchTokensProvider` into `src/editor/aardflexGrammar.ts`, and the
`defineTheme` data into `src/editor/aardflexTheme.ts`, unchanged:

```ts
// src/editor/aardflexGrammar.ts
import { ELEMENTS } from './aardflexData';

/** The aardflex-xml Monarch grammar, for Monaco's setMonarchTokensProvider and Aardink's registerLanguage. */
export const aardflexGrammar = {
  defaultToken: '',
  tokenPostfix: '.xml',
  aardflexElements: ELEMENTS,
  tokenizer: {
    // … the states at setup.ts:269-344, as they are
  },
};
```

```ts
// src/editor/aardflexTheme.ts
/** The aardflex-dark theme, for Monaco's defineTheme and Aardink's. */
export const aardflexTheme = {
  base: 'vs-dark' as const,
  inherit: true,
  rules: [
    // … setup.ts:353-360, as it is
  ],
  colors: { 'editor.background': '#1E1E2E' },
};
```

`setup.ts` then passes them to Monaco (`setMonarchTokensProvider('aardflex-xml', aardflexGrammar as any)`,
`defineTheme('aardflex-dark', aardflexTheme)`), so there is one grammar for both editors.

### 4. The completions, for Aardink

```ts
// src/editor/aardink/aardflexCompletions.ts
import { CompletionItemInsertTextRule, type AardinkCompletionItem } from '@aardarch/aardink-web';
import { ATTRIBUTES, ELEMENTS, MODULE_TYPES, TEXT_STYLES, TRANSFORMS } from '../aardflexData';

/** setup.ts's completion provider for Aardink: the whole text, and a 1-based line and column. */
export function aardflexCompletions(text: string, line: number, column: number): AardinkCompletionItem[] {
  const beforeCursor = (text.split('\n')[line - 1] ?? '').substring(0, column - 1);
  if (/<\/?$/.test(beforeCursor)) return ELEMENTS.map((label) => ({ label, kind: 'element' }));
  // The value contexts come before the tag: inside <module type="…"> the tag test matches too.
  if (/type="$/.test(beforeCursor)) return MODULE_TYPES.map((label) => ({ label, kind: 'value' }));
  if (/style="$/.test(beforeCursor)) return TEXT_STYLES.map((label) => ({ label, kind: 'value' }));
  if (/\|\s*$/.test(beforeCursor)) return TRANSFORMS.map((label) => ({ label, kind: 'value' }));
  const tag = /<(\w+)\s/.exec(beforeCursor);
  if (tag) {
    return (ATTRIBUTES[tag[1]] ?? []).map((attr) => ({
      label: attr,
      kind: 'attribute',
      // As in setup.ts: the caret between the quotes.
      insertText: `${attr}="$1"`,
      insertTextRules: CompletionItemInsertTextRule.InsertAsSnippet,
    }));
  }
  return [];
}
```

The order differs from `setup.ts`, which tests `<(\w+)\s` first. In Monaco that makes the
`type="` and `style="` suggestions unreachable inside a tag. Keep Monaco's order instead if the
golden tests in step 9 should compare completions as well.

### 5. The Aardink editor

```ts
// src/editor/aardink/index.ts
import { createEditor as createAardinkEditor, defineTheme, registerLanguage } from '@aardarch/aardink-web';
import { aardflexGrammar } from '../aardflexGrammar';
import { aardflexTheme } from '../aardflexTheme';
import type { EditorHandle, EditorOptions } from '../types';
import { aardflexCompletions } from './aardflexCompletions';

let registered: Promise<unknown> | undefined;

/** Registers aardflex-xml and aardflex-dark once per page; registering again would replace them. */
function registerAardflex(): Promise<unknown> {
  registered ??= Promise.all([
    registerLanguage(
      {
        id: 'aardflex-xml',
        extends: 'xml',
        grammar: aardflexGrammar,
        comments: { blockComment: ['<!--', '-->'] },
      },
      { provideCompletionItems: aardflexCompletions },
    ),
    defineTheme('aardflex-dark', aardflexTheme),
  ]);
  return registered;
}

export async function createEditor(
  container: HTMLElement,
  onChange: (content: string) => void,
  options: EditorOptions = {},
): Promise<EditorHandle> {
  await registerAardflex();
  const editor = await createAardinkEditor(container, undefined, {
    language: 'aardflex-xml',
    theme: 'aardflex-dark',
    fontSize: options.fontSize ?? 14,
    wordWrap: options.wordWrap ?? 'on',
    tabSize: 2,
    bracketPairColorization: { enabled: true },
  });
  // Edits only: a setValue reports itself a frame later as a flush, which is not a user edit.
  editor.onDidChangeContent((text, change) => {
    if (!change.isFlush) onChange(text);
  });
  return editor;
}
```

The flush check replaces `EditorPane`'s `applyingExternal` guard for Aardink: that guard is set
around a synchronous `setValue`, and Aardink reports the change after it has returned.

### 6. Choosing the editor

```ts
// src/editor/types.ts
export interface EditorOptions {
  fontSize?: number;
  wordWrap?: 'on' | 'off';
}

/** What EditorPane uses; Monaco's editor and Aardink's both have it. */
export interface EditorHandle {
  getValue(): string;
  setValue(text: string): void;
  updateOptions(options: EditorOptions): void;
  dispose(): void;
}
```

```ts
// src/editor/index.ts
import type { EditorHandle, EditorOptions } from './types';

export type { EditorHandle, EditorOptions } from './types';

/** The editor this build was made with: VITE_EDITOR=aardink, or Monaco by default. */
export async function createEditor(
  container: HTMLElement,
  onChange: (content: string) => void,
  options?: EditorOptions,
): Promise<EditorHandle> {
  const editor = import.meta.env.VITE_EDITOR === 'aardink' ? await import('./aardink') : await import('./setup');
  return editor.createEditor(container, onChange, options);
}
```

Vite replaces `import.meta.env.VITE_EDITOR` at build time, so the bundler can drop the branch not
taken along with its editor; step 9's bundle sizes show whether it did. Declare the variable in
`ImportMetaEnv` (`readonly VITE_EDITOR?: 'monaco' | 'aardink'`). `setup.ts`'s `createEditor`
stays as it is: Monaco's editor is an `EditorHandle`, and `await` accepts a value that is not a
Promise.

### 7. `EditorPane.svelte`

```ts
import { createEditor, type EditorHandle } from '../../editor';

let instance: EditorHandle | undefined;

onMount(() => {
  let unmounted = false;
  void createEditor(
    container,
    (value) => {
      if (applyingExternal) return; // Monaco reports setValue synchronously
      editorDoc.setFromEditor(value);
    },
    { fontSize: settings.global.editorFontSize, wordWrap: settings.global.wordWrap },
  ).then((editor) => {
    if (unmounted) {
      editor.dispose(); // the pane went away while the editor was loading
      return;
    }
    instance = editor;
    if (editorDoc.text) {
      applyingExternal = true;
      editor.setValue(editorDoc.text);
      applyingExternal = false;
      lastApplied = editorDoc.loadVersion;
    }
  });
  return () => {
    unmounted = true;
    instance?.dispose();
  };
});
```

The `onDestroy` goes: the cleanup `onMount` returns does its job. The two `$effect`s stay as they
are. Keep one `EditorPane` mounted and switch files with `setValue`, as now, and never move its
container to another place in the DOM (a `{#key}` block around it would): in the browser a
disposed Aardink editor is not fully released until Compose Multiplatform 1.13.

### 8. Build and hosting

`vite.config.ts`: exclude the package from the dev server's pre-bundling, next to Monaco's
`include`, which only matters in development and goes in A3:

```ts
optimizeDeps: {
  include: ['monaco-editor/esm/vs/editor/editor.api'],
  // Aardink's loader finds its .wasm files relative to its own module; pre-bundling would move it.
  exclude: ['@aardarch/aardink-web'],
},
```

Keep `chunkSizeWarningLimit: 3500`. `vite build` warns that `node:fs` and `node:url` were
externalised: the Kotlin loader's Node branches, which never run in a browser.

`firebase.json`: add `wasm` and `ttf` to the long-cache pattern, since Vite gives both hashed
names:

```json
{ "source": "**/*.@(js|css|woff|woff2|svg|png|ico|map|wasm|ttf)", "headers": [{ "key": "Cache-Control", "value": "public, max-age=31536000, immutable" }] }
```

The `.wasm` files must go out as `application/wasm`, which streaming compilation needs. Check a
preview channel with `curl -I`; if they do not, add a `Content-Type` header for `**/*.wasm`.

### 9. Tests

- Keep `setup.test.ts` for Monaco.
- Add a golden test for the grammar. `setup.test.ts` writes Monaco's tokens for a set of fixtures
  to `src/editor/fixtures/aardflex.tokens.json` (`expect(…).toMatchFileSnapshot(…)`); an Aardink
  test compares `await tokenize(fixture, 'aardflex-xml')` with the same file. The Aardink test
  needs a real browser, since the package is WasmGC and loads Compose: run it in Vitest's browser
  mode with Playwright's Chromium, or as a Playwright test against `vite preview`.
- The names are Monaco's, the `.xml` postfix, the `''` gaps and the merging of neighbouring
  tokens of one type included, so the two can be compared as they are.
- Aardink checks a copy of the shared grammar (`aardflex/shared/grammar/`) against its golden
  tokens, and runs the cases of the former `setup.test.ts`, in its own test suite
  (`AardflexGrammarTest`, `languages/src/jvmTest/`), so a grammar feature the app relies on
  cannot break without Aardink noticing.
- Record the production bundle with each editor (`VITE_EDITOR=monaco pnpm build` and
  `VITE_EDITOR=aardink pnpm build`): the JS and CSS, and for Aardink the two `.wasm` files and the
  font, each raw and gzipped.

**A1 is done when** `pnpm check`, `pnpm test` and `pnpm build` are green with either value of the
flag, the golden tokens match Monaco's on every fixture, and the bundle sizes are recorded.

## A2: Aardink by default

Default the flag to `aardink` (in `.env`, and in the CI and deploy workflows), deploy, and use it
for one release. Monaco stays one environment variable away.

## A3: Monaco removed

Delete the Monaco half of `setup.ts`, `setup.test.ts`'s Monaco harness (the golden file stays,
now the only source of truth), the flag and `src/editor/index.ts`'s switch, and
`monaco-editor` from `package.json`; drop the Monaco `optimizeDeps.include`. Update the note in
`specs/ui.md` about Monaco on phones, the problem that started this migration: Aardink is the
editor on every screen size.

## The Android app

The aardflex Android app moves from Aardink 0.4.0 to 0.6.0 separately; its changes are the
example in [`MIGRATION_0.6.md`](MIGRATION_0.6.md#example-the-aardflex-android-app).
