/*
 * Copyright 2026 Aardarch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Adapts the Kotlin module's integer-handle exports (aardinkCreate, aardinkGetValue, ...) into
// the Monaco-shaped object described in index.d.ts.

export const version = '@VERSION@';

let kotlinModule;

function load() {
  // Memoised: every editor on the page shares one WebAssembly instance.
  kotlinModule ??= import('./kotlin/aardink-web.mjs').then((k) => {
    // Compose looks for resources relative to the page, not this package. A literal
    // new URL(..., import.meta.url) is also what makes a bundler (Vite) emit the font file.
    k.aardinkSetBundledFontUrl(
      new URL('./kotlin/composeResources/com.aardarch.aardink.web.res/font/jetbrains_mono_regular.ttf', import.meta.url).href,
    );
    return k;
  });
  return kotlinModule;
}

export function preloadAardink() {
  return load().then(() => undefined);
}

let generatedIds = 0;

/** Keeps only the fields the Kotlin side knows, translating Monaco's wordWrap 'on'/'off'. */
function toKotlinOptions(options) {
  const out = {};
  for (const key of ['language', 'theme', 'fontSize', 'readOnly', 'showGutter', 'showLineNumbers', 'showFoldMarkers']) {
    if (options[key] !== undefined) out[key] = options[key];
  }
  if (options.wordWrap !== undefined) out.wordWrap = options.wordWrap === 'on';
  return out;
}

export async function createEditor(container, onChange, options = {}) {
  const k = await load();
  if (!container.id) container.id = `aardink-editor-${++generatedIds}`;
  const id = k.aardinkCreate(container.id, options.value ?? '', JSON.stringify(toKotlinOptions(options)));

  // The Kotlin side holds one listener of each kind; fan out to any number here.
  const contentListeners = new Set();
  const cursorListeners = new Set();
  k.aardinkOnChange(id, (text) => contentListeners.forEach((listener) => listener(text)));
  k.aardinkOnCursorChange(id, (line, column) => cursorListeners.forEach((listener) => listener(line, column)));
  if (onChange) contentListeners.add(onChange);

  let disposed = false;

  return {
    getValue: () => k.aardinkGetValue(id),
    setValue: (text) => k.aardinkSetValue(id, text),
    updateOptions: (patch) => k.aardinkUpdateOptions(id, JSON.stringify(toKotlinOptions(patch))),
    onDidChangeContent(listener) {
      contentListeners.add(listener);
      return () => contentListeners.delete(listener);
    },
    onDidChangeCursor(listener) {
      cursorListeners.add(listener);
      return () => cursorListeners.delete(listener);
    },
    setDiagnostics: (diagnostics) => k.aardinkSetDiagnostics(id, JSON.stringify(diagnostics)),
    revealPosition: (line, column) => k.aardinkRevealPosition(id, line, column),
    showFind: () => k.aardinkShowFind(id),
    undo: () => k.aardinkUndo(id),
    redo: () => k.aardinkRedo(id),
    dispose() {
      if (disposed) return;
      disposed = true;
      contentListeners.clear();
      cursorListeners.clear();
      k.aardinkDispose(id);
    },
  };
}
