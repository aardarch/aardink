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
  /** Which spaces and tabs are drawn, as dots and arrows. `'selection'` by default. */
  renderWhitespace?: 'none' | 'boundary' | 'selection' | 'trailing' | 'all';
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
  /**
   * Opens the find panel and moves the keyboard focus to its find field, from wherever on the
   * page it was. Enter goes to the next match, Shift+Enter to the previous one, and Escape closes
   * the panel and gives the focus back to the text.
   */
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
  /**
   * With `extends`: `false` leaves out that language's completions (and, when the providers give
   * `triggerCharacters`, its trigger characters), keeping its diagnostics, hover, folding and the
   * rest. `true` by default. To leave them out for one request only, answer with
   * `{ suggestions, exclusive: true }`.
   */
  inheritCompletions?: boolean;
  displayName?: string;
  extensions?: string[];
  /** As in Monaco's language configuration: what Ctrl/Cmd+/ comments with. */
  comments?: { lineComment?: string; blockComment?: [string, string] };
}

/** Monaco's flags for a completion's `insertText`. */
export declare const CompletionItemInsertTextRule: {
  readonly None: 0;
  /** Insert the snippet's whitespace as written, rather than indented as the line it lands on. */
  readonly KeepWhitespace: 1;
  /** `insertText` is a snippet: `$1`, `${1:placeholder}` and `${1|a,b|}` are tab stops, `$0` the final caret. */
  readonly InsertAsSnippet: 4;
};

/** Monaco's `IRange`: 1-based lines and columns, `endColumn` exclusive. */
export interface AardinkRange {
  startLineNumber: number;
  startColumn: number;
  endLineNumber: number;
  endColumn: number;
}

export interface AardinkCompletionItem {
  label: string;
  /** What accepting it types; the label when absent. A snippet with `InsertAsSnippet`. */
  insertText?: string;
  /** `CompletionItemInsertTextRule` flags, as in Monaco. */
  insertTextRules?: number;
  /**
   * The icon. `'function'` is another name for `'transform'`, and `'color'` for `'colorRef'`, as
   * Monaco calls them; any other name shows as `'value'`.
   */
  kind?: 'element' | 'attribute' | 'value' | 'snippet' | 'module' | 'property' | 'transform' | 'function' | 'colorRef' | 'color';
  /** Shown beside the label when there is no `documentation`. */
  detail?: string;
  /** Shown beside the label; Monaco's `{ value }` is shown as plain text. */
  documentation?: string | { value: string };
  /**
   * What the text typed so far is matched against, as in Monaco: its characters in order, ignoring
   * case, the first at the start of `filterText` or of a word in it. Typed so far is the text from
   * the start of what the item replaces to the caret. Without it the item is not filtered: your
   * provider is asked again on each letter typed, and offers what fits.
   */
  filterText?: string;
  /** When any item has one, your items are ordered by it (by `label` where it is missing), as in Monaco. */
  sortText?: string;
  /**
   * The text accepting the item replaces, which must contain the caret, as in Monaco. Of
   * `{ insert, replace }`, `replace` is used. Without it the item replaces the word before the
   * caret, back to the first of `< > { } ( ) [ ] " ' = , ; . @ | :` or whitespace.
   */
  range?: AardinkRange | { insert: AardinkRange; replace: AardinkRange };
}

/** Monaco's `CompletionList`, plus `exclusive`: `true` leaves out the extended language's completions for this request. */
export interface AardinkCompletionList {
  suggestions: AardinkCompletionItem[];
  exclusive?: boolean;
  /** Accepted for Monaco's shape, and ignored: your provider is asked again on each letter typed anyway. */
  incomplete?: boolean;
}

export interface AardinkHover {
  title?: string;
  /** Plain text. */
  contents: string;
  /** Shown as code below `contents`. */
  example?: string;
}

/**
 * What a registered language knows beyond its grammar. Each gets the whole text and, where it
 * asks about a place, a 1-based line and column; each may answer at once or with a Promise. With
 * `extends`, the built-in language's answers come after these, less any completion with the same
 * `kind` and `label` as one of yours (see `inheritCompletions` to leave them all out).
 */
export interface AardinkLanguageProviders {
  /**
   * Characters that open the completion list when typed, as on Monaco's completion provider, e.g.
   * `['{', '|', '@', '$']`. With `extends` they add to that language's (`'xml'`: `< / space : " =`),
   * or replace them with `inheritCompletions: false`; without, that language's apply. Letters and
   * digits always ask again while the list is open.
   */
  triggerCharacters?: string[];
  provideCompletionItems?(
    text: string,
    line: number,
    column: number,
  ): AardinkCompletionItem[] | AardinkCompletionList | null | Promise<AardinkCompletionItem[] | AardinkCompletionList | null>;
  provideHover?(text: string, line: number, column: number): AardinkHover | null | Promise<AardinkHover | null>;
  provideDiagnostics?(text: string): AardinkDiagnostic[] | Promise<AardinkDiagnostic[]>;
}

/** Adds a language for `createEditor`'s `language` option. Rejects with what is wrong with the definition. */
export function registerLanguage(definition: AardinkLanguageDefinition, providers?: AardinkLanguageProviders): Promise<void>;

/**
 * Monaco's `defineTheme` data. Token rules name tokens as Monaco does, and the longest matching
 * prefix wins: `tag` also colours `tag.aardflex`, `comment.doc` only documentation comments. A
 * rule's `fontStyle` (`'bold'`, `'italic'`, `'underline'`, `'strikethrough'`, or `''` for none)
 * applies too; its `background` is accepted, so Monaco theme data passes as it is, and ignored.
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

/**
 * Starts loading the WebAssembly module without mounting anything. Every call shares one load.
 * When it fails (a network error, say), this and every call waiting on it reject, and the next
 * call of any function here starts a new load. A browser may still hold on to a module whose
 * download failed until the page is reloaded (Chrome does), so that new load can fail the same way.
 */
export function preloadAardink(): Promise<void>;

/** The Aardink version this package was built from. */
export const version: string;
