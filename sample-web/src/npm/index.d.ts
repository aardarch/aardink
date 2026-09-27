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

/** Editor options. Every field is optional; `updateOptions` changes only the ones you pass. */
export interface AardinkOptions {
  /** Initial text. Only read by `createEditor`. */
  value?: string;
  /** A language id: kotlin, typescript, json, toml, xml, html, css, markdown, plaintext. */
  language?: string;
  /** vscode-dark, vscode-light, material-dark, material-light, midnight-ocean, solarized-dark. */
  theme?: string;
  /** In CSS pixels. */
  fontSize?: number;
  wordWrap?: 'on' | 'off';
  readOnly?: boolean;
  showGutter?: boolean;
  showLineNumbers?: boolean;
  showFoldMarkers?: boolean;
  /** Width of an indent, in spaces. 4 by default. */
  tabSize?: number;
  /** Tab indents with spaces rather than a tab character. On by default. */
  insertSpaces?: boolean;
  /** Monaco's `renderLineHighlight`: anything but `'none'` highlights each caret's line. */
  renderLineHighlight?: 'none' | 'line' | 'gutter' | 'all';
  /** Colours brackets by nesting depth. On by default. */
  bracketPairColorization?: { enabled?: boolean } | boolean;
  /** A picture of the whole text at the side, with a slider over what is shown. Off by default. */
  minimap?: { enabled?: boolean } | boolean;
  /** The first lines of the blocks the top of the view is inside stay pinned at the top. Off by default. */
  stickyScroll?: { enabled?: boolean } | boolean;
}

/** Monaco-marker shape: 1-based line and columns, `endColumn` exclusive. */
export interface AardinkDiagnostic {
  line: number;
  startColumn: number;
  endColumn: number;
  message: string;
  severity: 'error' | 'warning' | 'info';
}

/** Monaco's `Selection`: from where it started to where the caret is, 1-based, with the range from start to end. */
export interface AardinkSelection {
  selectionStartLineNumber: number;
  selectionStartColumn: number;
  positionLineNumber: number;
  positionColumn: number;
  readonly startLineNumber?: number;
  readonly startColumn?: number;
  readonly endLineNumber?: number;
  readonly endColumn?: number;
}

/** What a content change was, as Monaco's `IModelContentChangedEvent` says it. */
export interface AardinkContentChange {
  /** Goes up with every change. */
  versionId: number;
  isUndoing: boolean;
  isRedoing: boolean;
  /** The whole text was replaced (`setValue`). */
  isFlush: boolean;
}

export interface AardinkEditor {
  getValue(): string;
  /** Replaces the whole text and clears undo history. Fires the change listeners. */
  setValue(text: string): void;
  updateOptions(options: Partial<AardinkOptions>): void;
  /** Full text after every change, at most once per frame, and what the change was. Returns an unsubscribe function. */
  onDidChangeContent(listener: (text: string, change: AardinkContentChange) => void): () => void;
  /** 1-based caret position. Returns an unsubscribe function. */
  onDidChangeCursor(listener: (line: number, column: number) => void): () => void;
  /**
   * Shows these diagnostics in place of the language's own; `null` goes back to the language's
   * own. Either way they move along with the text as it is edited, until the next list.
   */
  setDiagnostics(diagnostics: AardinkDiagnostic[] | null): void;
  /**
   * The language's own diagnostics each time they are collected: 500 ms after the editor appears,
   * and after each pause in typing. Not called while a list from `setDiagnostics` is shown.
   * Returns an unsubscribe function.
   */
  onDidChangeDiagnostics(listener: (diagnostics: AardinkDiagnostic[]) => void): () => void;
  revealPosition(line: number, column: number): void;
  showFind(): void;
  undo(): boolean;
  redo(): boolean;
  canUndo(): boolean;
  canRedo(): boolean;
  /** Ends the current undo step, so the next edit starts a new one. */
  pushUndoStop(): void;
  /** Comes back when undo returns the text to an earlier state: keep it on save, and the text is unsaved while it differs. */
  getAlternativeVersionId(): number;
  /** The text the diff lane in the gutter compares with, typically what was last saved; `''` turns it off. */
  setBaseline(text: string): void;
  /** Formats the document with the language's formatter, as one undo step; resolves with whether anything changed. */
  format(): Promise<boolean>;
  focus(): void;
  /** Every selection, the primary one first. */
  getSelections(): AardinkSelection[];
  /** Replaces the selections; the first becomes the primary one. */
  setSelections(selections: AardinkSelection[]): void;
  /** Stops the editor. Safe to call twice. Remove or reuse the container yourself. */
  dispose(): void;
}

/** A language highlighted by a grammar, for `registerLanguage`. */
export interface AardinkLanguageDefinition {
  /** What `createEditor`'s `language` option names it by. Registering an id again replaces it. */
  id: string;
  /**
   * A subset of Monaco's Monarch: states in `tokenizer` (`root` first), rules of
   * `[regex, action, next?]`, `cases` with `@array`/`@default`/`@eos`, capture groups (each
   * with a token name, `{ token, next }` or `cases` of its own), `include`, `@rematch`,
   * `defaultToken`, `ignoreCase`, `tokenPostfix`. RegExp literals are fine. Lookbehind is not
   * supported, and is refused.
   */
  grammar: object;
  /** A built-in or registered language whose completions, diagnostics, hover and folding this one takes too, e.g. `'xml'`. */
  extends?: string;
  displayName?: string;
  extensions?: string[];
  /** As in Monaco's language configuration: what Ctrl/Cmd+/ comments with. */
  comments?: { lineComment?: string; blockComment?: [string, string] };
}

export interface AardinkCompletionItem {
  label: string;
  /** What accepting it types; the label when absent. */
  insertText?: string;
  kind?: 'element' | 'attribute' | 'value' | 'snippet' | 'module' | 'property';
  detail?: string;
  documentation?: string;
}

/**
 * What a registered language knows beyond its grammar. Each gets the whole text and, where it
 * asks about a place, a 1-based line and column; each may answer at once or with a Promise. With
 * `extends`, the built-in language's answers come after these.
 */
export interface AardinkLanguageProviders {
  provideCompletionItems?(text: string, line: number, column: number): AardinkCompletionItem[] | null | Promise<AardinkCompletionItem[] | null>;
  provideHover?(text: string, line: number, column: number): { title?: string; contents: string } | null | Promise<{ title?: string; contents: string } | null>;
  provideDiagnostics?(text: string): AardinkDiagnostic[] | Promise<AardinkDiagnostic[]>;
}

/** Adds a language for `createEditor`'s `language` option. Rejects with what is wrong with the definition. */
export function registerLanguage(definition: AardinkLanguageDefinition, providers?: AardinkLanguageProviders): Promise<void>;

/**
 * Monaco's `defineTheme` data. Token rules name a grammar's tokens; `tag` also colours
 * `tag.aardflex`. Themes are colours only: a rule's `fontStyle` and `background` are accepted, so
 * Monaco theme data passes as it is, and ignored.
 */
export interface AardinkThemeData {
  base: 'vs' | 'vs-dark' | 'hc-black' | 'hc-light';
  inherit?: boolean;
  rules: { token: string; foreground?: string; fontStyle?: string; background?: string }[];
  colors?: Record<string, string>;
}

/** Adds a theme for `createEditor`'s `theme` option: Monaco's `defineTheme` data, or a VS Code theme's JSON. */
export function registerTheme(name: string, theme: AardinkThemeData | object): Promise<void>;

/** Monaco's name for `registerTheme`. */
export const defineTheme: typeof registerTheme;

/**
 * Mounts an editor that fills `container`. Loads the WebAssembly module on first use (see
 * `preloadAardink`). `onChange` is registered as the first content listener.
 */
export function createEditor(
  container: HTMLElement,
  onChange?: (text: string) => void,
  options?: AardinkOptions,
): Promise<AardinkEditor>;

/** For debugging a grammar: the tokens of `text` in `languageId`, per line, as Monaco's `tokenize` gives them. */
export function tokenize(text: string, languageId: string): Promise<{ offset: number; type: string }[][]>;

/** Starts loading the WebAssembly module without mounting anything. */
export function preloadAardink(): Promise<void>;

/** The Aardink version this package was built from. */
export const version: string;
