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
  // The font too: an editor waits up to half a second for it rather than showing in another first.
  return load().then((k) => k.aardinkPreloadFont());
}

let generatedIds = 0;

/** A selection with Monaco's range fields too: start is whichever end comes first. */
function withRange(s) {
  const forward = s.selectionStartLineNumber < s.positionLineNumber ||
    (s.selectionStartLineNumber === s.positionLineNumber && s.selectionStartColumn <= s.positionColumn);
  return {
    ...s,
    startLineNumber: forward ? s.selectionStartLineNumber : s.positionLineNumber,
    startColumn: forward ? s.selectionStartColumn : s.positionColumn,
    endLineNumber: forward ? s.positionLineNumber : s.selectionStartLineNumber,
    endColumn: forward ? s.positionColumn : s.selectionStartColumn,
  };
}

/** For debugging a grammar: the tokens of `text` in `languageId`, per line, as Monaco's tokenize gives them. */
export async function tokenize(text, languageId) {
  const k = await load();
  return JSON.parse(k.aardinkTokenize(languageId, text));
}

/**
 * A Monarch grammar as JSON: RegExp literals (/.../) become their source, as the Kotlin side reads
 * regexes from strings; everything else is kept.
 */
function monarchToJson(value) {
  if (value instanceof RegExp) return value.source;
  if (Array.isArray(value)) return value.map(monarchToJson);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, monarchToJson(item)]));
  }
  return value;
}

/** A provider's answer (a value or a Promise of one) as the Promise of JSON the Kotlin side awaits. */
function answer(provider, ...args) {
  if (!provider) return Promise.resolve(null);
  return Promise.resolve()
    .then(() => provider(...args))
    .then((value) => (value == null ? null : JSON.stringify(value)));
}

/**
 * Adds a language highlighted by a Monarch grammar, for createEditor's `language` option. See
 * index.d.ts for the definition and the providers.
 */
export async function registerLanguage(definition, providers = {}) {
  const k = await load();
  const grammar = monarchToJson(definition.grammar ?? {});
  if (definition.comments) grammar.comments = definition.comments;
  const error = k.aardinkRegisterLanguage(
    JSON.stringify({
      id: definition.id,
      extends: definition.extends,
      displayName: definition.displayName,
      extensions: definition.extensions,
      grammar,
    }),
    (text, line, column) => answer(providers.provideCompletionItems, text, line, column),
    (text, line, column) => answer(providers.provideHover, text, line, column),
    (text) => answer(providers.provideDiagnostics, text),
  );
  if (error) throw new Error(`Aardink: ${error}`);
}

/** Monaco's defineTheme data ({ base, inherit, rules, colors }) as VS Code theme JSON; VS Code JSON as it is. */
function toVsCodeTheme(theme) {
  if (!theme.rules) return theme;
  const base = { vs: 'vscode-light', 'hc-light': 'vscode-light' }[theme.base] ?? 'vscode-dark';
  return {
    type: base === 'vscode-light' ? 'light' : 'dark',
    base: theme.inherit === false ? undefined : base,
    colors: theme.colors ?? {},
    tokenColors: theme.rules
      .filter((rule) => rule.foreground)
      .map((rule) => ({ scope: rule.token, settings: { foreground: `#${rule.foreground.replace(/^#/, '')}` } })),
  };
}

/** Adds a theme for createEditor's `theme` option, from Monaco's defineTheme data or VS Code theme JSON. */
export async function registerTheme(name, theme) {
  const k = await load();
  const error = k.aardinkRegisterTheme(name, JSON.stringify(toVsCodeTheme(theme)));
  if (error) throw new Error(`Aardink: ${error}`);
}

/** Monaco's name for registerTheme. */
export const defineTheme = registerTheme;

/**
 * Keeps only the fields the Kotlin side knows, translating Monaco's wordWrap 'on'/'off' and its
 * { enabled } objects for minimap and stickyScroll (a plain boolean works too).
 */
function toKotlinOptions(options) {
  const out = {};
  for (const key of ['language', 'theme', 'fontSize', 'readOnly', 'showGutter', 'showLineNumbers', 'showFoldMarkers', 'tabSize', 'insertSpaces', 'highlightCurrentLine']) {
    if (options[key] !== undefined) out[key] = options[key];
  }
  if (options.wordWrap !== undefined) out.wordWrap = options.wordWrap === 'on';
  if (options.renderLineHighlight !== undefined) out.highlightCurrentLine = options.renderLineHighlight !== 'none';
  for (const key of ['minimap', 'stickyScroll', 'bracketPairColorization']) {
    const value = options[key];
    if (value !== undefined) out[key] = typeof value === 'object' && value !== null ? value.enabled !== false : !!value;
  }
  return out;
}

export async function createEditor(container, onChange, options = {}) {
  const k = await load();
  if (!container.id) container.id = `aardink-editor-${++generatedIds}`;
  const id = k.aardinkCreate(container.id, options.value ?? '', JSON.stringify(toKotlinOptions(options)));

  // The Kotlin side holds one listener of each kind; fan out to any number here.
  const contentListeners = new Set();
  const cursorListeners = new Set();
  const diagnosticsListeners = new Set();
  k.aardinkOnContentChange(id, (text, versionId, kind) => {
    const event = { versionId, isUndoing: kind === 'undo', isRedoing: kind === 'redo', isFlush: kind === 'flush' };
    contentListeners.forEach((listener) => listener(text, event));
  });
  k.aardinkOnCursorChange(id, (line, column) => cursorListeners.forEach((listener) => listener(line, column)));
  k.aardinkOnDiagnosticsChange(id, (json) => {
    if (diagnosticsListeners.size === 0) return;
    const diagnostics = JSON.parse(json);
    diagnosticsListeners.forEach((listener) => listener(diagnostics));
  });
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
    setDiagnostics: (diagnostics) => k.aardinkSetDiagnostics(id, JSON.stringify(diagnostics ?? null)),
    onDidChangeDiagnostics(listener) {
      diagnosticsListeners.add(listener);
      return () => diagnosticsListeners.delete(listener);
    },
    revealPosition: (line, column) => k.aardinkRevealPosition(id, line, column),
    setBaseline: (text) => k.aardinkSetBaseline(id, text ?? ''),
    format: () => new Promise((resolve) => k.aardinkFormat(id, resolve)),
    focus: () => k.aardinkFocus(id),
    getSelections: () => JSON.parse(k.aardinkGetSelections(id)).map(withRange),
    setSelections: (selections) =>
      k.aardinkSetSelections(
        id,
        JSON.stringify(selections.map((s) => ({
          selectionStartLineNumber: s.selectionStartLineNumber,
          selectionStartColumn: s.selectionStartColumn,
          positionLineNumber: s.positionLineNumber,
          positionColumn: s.positionColumn,
        }))),
      ),
    canUndo: () => k.aardinkCanUndo(id),
    canRedo: () => k.aardinkCanRedo(id),
    pushUndoStop: () => k.aardinkPushUndoStop(id),
    getAlternativeVersionId: () => k.aardinkGetAlternativeVersionId(id),
    showFind: () => k.aardinkShowFind(id),
    undo: () => k.aardinkUndo(id),
    redo: () => k.aardinkRedo(id),
    dispose() {
      if (disposed) return;
      disposed = true;
      contentListeners.clear();
      cursorListeners.clear();
      diagnosticsListeners.clear();
      k.aardinkDispose(id);
    },
  };
}
