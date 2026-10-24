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

/** Monaco's flags for a completion's `insertText`. */
export const CompletionItemInsertTextRule = Object.freeze({ None: 0, KeepWhitespace: 1, InsertAsSnippet: 4 });

let kotlinModule;

function load() {
  // Memoised: every editor on the page shares one WebAssembly instance. A failed load is
  // forgotten, so the next call tries again rather than repeating one network blip for the
  // page's life.
  kotlinModule ??= import('./kotlin/aardink-web.mjs')
    .then((k) => {
      // Compose looks for resources relative to the page, not this package. A literal
      // new URL(..., import.meta.url) is also what makes a bundler (Vite) emit the font file.
      k.aardinkSetBundledFontUrl(
        new URL('./kotlin/composeResources/com.aardarch.aardink.web.res/font/jetbrains_mono_regular.ttf', import.meta.url).href,
      );
      return k;
    })
    .catch((error) => {
      kotlinModule = undefined;
      throw error;
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

/** The fields of a Monaco `ISelection` the Kotlin side reads. */
function toKotlinSelection(s) {
  return {
    selectionStartLineNumber: s.selectionStartLineNumber,
    selectionStartColumn: s.selectionStartColumn,
    positionLineNumber: s.positionLineNumber,
    positionColumn: s.positionColumn,
  };
}

/** The fields of a Monaco edit operation the Kotlin side reads; a Monaco `Range` is a class, so copy them. */
function toKotlinEdit(edit) {
  const r = edit.range;
  return {
    range: { startLineNumber: r.startLineNumber, startColumn: r.startColumn, endLineNumber: r.endLineNumber, endColumn: r.endColumn },
    text: edit.text ?? null,
  };
}

/** Monaco's MarkerSeverity numbers, as the words the Kotlin side reads. */
const MARKER_SEVERITY = { 1: 'hint', 2: 'info', 4: 'warning', 8: 'error' };

/**
 * A diagnostic in the Kotlin side's shape, from ours or a Monaco marker: `startLineNumber` for
 * `line`, a numeric severity, `code` as `{ value, target }`, related information with a Uri.
 */
function toKotlinDiagnostic(d) {
  const code = d.code;
  const codeIsObject = code != null && typeof code === 'object';
  return {
    line: d.line ?? d.startLineNumber,
    startColumn: d.startColumn,
    endColumn: d.endColumn,
    endLineNumber: d.endLineNumber,
    message: d.message,
    severity: typeof d.severity === 'number' ? (MARKER_SEVERITY[d.severity] ?? 'info') : d.severity,
    source: d.source,
    code: code == null ? undefined : String(codeIsObject ? code.value : code),
    codeHref: d.codeHref ?? (codeIsObject && code.target != null ? String(code.target) : undefined),
    tags: d.tags,
    relatedInformation: d.relatedInformation?.map((r) => ({
      resource: String(r.resource ?? ''),
      message: r.message,
      startLineNumber: r.startLineNumber,
      startColumn: r.startColumn,
      endLineNumber: r.endLineNumber,
      endColumn: r.endColumn,
    })),
  };
}

/** A diagnostic the Kotlin side reports, with Monaco's `startLineNumber` beside `line`. */
function fromKotlinDiagnostic(d) {
  return { startLineNumber: d.line, ...d };
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
      inheritCompletions: definition.inheritCompletions,
      schema: definition.schema,
      // On the providers, as on Monaco's completion provider; the Kotlin side reads it with the definition.
      triggerCharacters: providers.triggerCharacters,
      grammar,
    }),
    (text, line, column) => answer(providers.provideCompletionItems, text, line, column),
    (text, line, column) => answer(providers.provideHover, text, line, column),
    (text) =>
      answer(
        providers.provideDiagnostics &&
          ((t) => Promise.resolve(providers.provideDiagnostics(t)).then((list) => (list == null ? null : list.map(toKotlinDiagnostic)))),
        text,
      ),
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
    // A rule's colour and font style; its background is not drawn.
    tokenColors: theme.rules
      .filter((rule) => rule.foreground || rule.fontStyle !== undefined)
      .map((rule) => ({
        scope: rule.token,
        settings: {
          ...(rule.foreground ? { foreground: `#${rule.foreground.replace(/^#/, '')}` } : {}),
          ...(rule.fontStyle !== undefined ? { fontStyle: rule.fontStyle } : {}),
        },
      })),
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
  for (const key of ['language', 'theme', 'fontSize', 'readOnly', 'showGutter', 'showLineNumbers', 'showFoldMarkers', 'tabSize', 'insertSpaces', 'highlightCurrentLine', 'renderWhitespace']) {
    if (options[key] !== undefined) out[key] = options[key];
  }
  if (options.wordWrap !== undefined) out.wordWrap = options.wordWrap === 'on';
  if (options.renderLineHighlight !== undefined) out.highlightCurrentLine = options.renderLineHighlight !== 'none';
  if (options.quickSuggestions !== undefined) {
    // Monaco's { other, comments, strings }: the editor opens the list in code, so `other` decides.
    const value = options.quickSuggestions;
    const other = typeof value === 'object' && value !== null ? value.other : value;
    out.quickSuggestions = other !== false && other !== 'off' && other !== undefined;
  }
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
  const selectionListeners = new Set();
  const focusListeners = new Set();
  const blurListeners = new Set();
  k.aardinkOnContentChange(id, (text, versionId, kind, changesJson) => {
    if (contentListeners.size === 0) return;
    const event = {
      versionId,
      isUndoing: kind === 'undo',
      isRedoing: kind === 'redo',
      isFlush: kind === 'flush',
      changes: JSON.parse(changesJson),
    };
    contentListeners.forEach((listener) => listener(text, event));
  });
  k.aardinkOnCursorChange(id, (line, column) => cursorListeners.forEach((listener) => listener(line, column)));
  k.aardinkOnSelectionChange(id, (json) => {
    if (selectionListeners.size === 0) return;
    const { selections, reason, source } = JSON.parse(json);
    const all = selections.map(withRange);
    const event = { selection: all[0], secondarySelections: all.slice(1), reason, source };
    selectionListeners.forEach((listener) => listener(event));
  });
  k.aardinkOnFocusChange(id, (focused) => (focused ? focusListeners : blurListeners).forEach((listener) => listener()));
  k.aardinkOnDiagnosticsChange(id, (json) => {
    if (diagnosticsListeners.size === 0) return;
    const diagnostics = JSON.parse(json).map(fromKotlinDiagnostic);
    diagnosticsListeners.forEach((listener) => listener(diagnostics));
  });
  if (onChange) contentListeners.add(onChange);

  let disposed = false;
  // What the last host action run returned, so getAction().run() can wait for it: the Kotlin
  // side calls `run` as a () -> Unit and drops the value.
  let actionResult;

  const editor = {
    getValue: () => k.aardinkGetValue(id),
    setValue: (text) => k.aardinkSetValue(id, text),
    replaceValue: (text) => k.aardinkReplaceValue(id, text),
    executeEdits(source, edits, endCursorState) {
      // `source` only names the edit in Monaco; a cursor-state computer function is not called.
      const selections = Array.isArray(endCursorState) && endCursorState.length ? endCursorState.map(toKotlinSelection) : null;
      const result = k.aardinkExecuteEdits(id, JSON.stringify(edits.map(toKotlinEdit)), JSON.stringify(selections));
      if (result === 'read-only') return false;
      if (result) throw new Error(`Aardink: ${result}`);
      return true;
    },
    updateOptions: (patch) => k.aardinkUpdateOptions(id, JSON.stringify(toKotlinOptions(patch))),
    onDidChangeContent(listener) {
      contentListeners.add(listener);
      return () => contentListeners.delete(listener);
    },
    onDidChangeCursor(listener) {
      cursorListeners.add(listener);
      return () => cursorListeners.delete(listener);
    },
    onDidChangeCursorSelection(listener) {
      selectionListeners.add(listener);
      return () => selectionListeners.delete(listener);
    },
    onDidFocusEditorText(listener) {
      focusListeners.add(listener);
      return () => focusListeners.delete(listener);
    },
    onDidBlurEditorText(listener) {
      blurListeners.add(listener);
      return () => blurListeners.delete(listener);
    },
    getPosition: () => JSON.parse(k.aardinkGetPosition(id)),
    setPosition: (position) => k.aardinkSetPosition(id, position.lineNumber, position.column),
    revealLine: (line) => k.aardinkRevealLine(id, line, false),
    revealLineInCenter: (line) => k.aardinkRevealLine(id, line, true),
    revealRange: (range) => k.aardinkRevealRange(id, range.startLineNumber, range.startColumn, false),
    revealRangeInCenter: (range) => k.aardinkRevealRange(id, range.startLineNumber, range.startColumn, true),
    trigger: (source, handlerId, payload) => k.aardinkTrigger(id, handlerId, JSON.stringify(payload ?? null)),
    addCommand(keybinding, handler) {
      const commandId = k.aardinkAddCommand(id, String(keybinding), () => handler());
      return commandId || null;
    },
    addAction(descriptor) {
      const keybindings = (descriptor.keybindings ?? []).map(String);
      k.aardinkAddAction(id, descriptor.id, descriptor.label, keybindings.join('\n'), descriptor.contextMenuGroupId != null, () => {
        actionResult = descriptor.run(editor);
      });
      return { dispose: () => k.aardinkRemoveAction(id, descriptor.id) };
    },
    getAction(actionId) {
      const label = k.aardinkActionLabel(id, actionId);
      if (!label) return null;
      return {
        id: actionId,
        label,
        isSupported: () => k.aardinkCanTrigger(id, actionId),
        run() {
          actionResult = undefined;
          try {
            k.aardinkTrigger(id, actionId, 'null');
          } catch (e) {
            return Promise.reject(e);
          }
          const result = actionResult;
          actionResult = undefined;
          // Settles with a host action's own promise; one of the editor's own has only started.
          return Promise.resolve(result).then(() => undefined);
        },
      };
    },
    setDiagnostics: (diagnostics) => k.aardinkSetDiagnostics(id, JSON.stringify(diagnostics == null ? null : diagnostics.map(toKotlinDiagnostic))),
    onDidChangeDiagnostics(listener) {
      diagnosticsListeners.add(listener);
      return () => diagnosticsListeners.delete(listener);
    },
    revalidate: () => k.aardinkRevalidate(id),
    revealPosition: (line, column) => k.aardinkRevealPosition(id, line, column),
    setBaseline: (text) => k.aardinkSetBaseline(id, text ?? ''),
    format: () => new Promise((resolve) => k.aardinkFormat(id, resolve)),
    focus: () => k.aardinkFocus(id),
    getSelections: () => JSON.parse(k.aardinkGetSelections(id)).map(withRange),
    setSelections: (selections) => k.aardinkSetSelections(id, JSON.stringify(selections.map(toKotlinSelection))),
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
      selectionListeners.clear();
      focusListeners.clear();
      blurListeners.clear();
      k.aardinkDispose(id);
    },
  };
  return editor;
}
