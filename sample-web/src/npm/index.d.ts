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
  /**
   * Typing a letter in code (not in a string or comment) opens the completion list, as Monaco's
   * option; Monaco's `{ other, comments, strings }` works too, `other` deciding. Off by default.
   */
  quickSuggestions?: boolean | { other?: boolean | 'on' | 'off' | 'inline'; comments?: unknown; strings?: unknown };
}

/**
 * Monaco-marker shape: 1-based lines and columns, `endColumn` exclusive. A Monaco `IMarkerData`
 * passes as it is: `startLineNumber` for `line`, a `MarkerSeverity` number for `severity`, `code`
 * as `{ value, target }`. Reported diagnostics carry both `line` and `startLineNumber`.
 */
export interface AardinkDiagnostic {
  /** The first line; or give `startLineNumber`. */
  line?: number;
  startLineNumber?: number;
  startColumn: number;
  /** The last line, `line` when absent. */
  endLineNumber?: number;
  endColumn: number;
  message: string;
  /** `'error'` when absent; Monaco's `MarkerSeverity` numbers (8, 4, 2, 1) work too. A hint gets three dots under its start and no gutter dot. */
  severity?: 'error' | 'warning' | 'info' | 'hint' | 1 | 2 | 4 | 8;
  /** Shown with the message, e.g. `'xml'`. */
  source?: string;
  /** The producer's identifier for this kind of problem, shown after `source`. Monaco's `{ value, target }` works too. */
  code?: string | { value: string; target: string };
  /** Where `code` is explained (Monaco's `code.target`). */
  codeHref?: string;
  /** Monaco's `MarkerTag`s: `1` unnecessary (drawn faded), `2` deprecated (struck through). */
  tags?: (1 | 2)[];
  relatedInformation?: AardinkRelatedInformation[];
}

/** Monaco's `IRelatedInformation`, with the resource as a string (a Monaco `Uri` is turned into one). */
export interface AardinkRelatedInformation {
  resource: string | { toString(): string };
  message: string;
  startLineNumber: number;
  startColumn: number;
  endLineNumber: number;
  endColumn: number;
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

/**
 * One change to the text, as Monaco's `IModelContentChange`: `range` (1-based, `endColumn`
 * exclusive), or `rangeOffset` and `rangeLength`, was replaced by `text`.
 */
export interface AardinkModelContentChange {
  range: AardinkRange;
  rangeOffset: number;
  rangeLength: number;
  text: string;
}

/** What a content change was, as Monaco's `IModelContentChangedEvent` says it. */
export interface AardinkContentChange {
  /** Goes up with every change. */
  versionId: number;
  isUndoing: boolean;
  isRedoing: boolean;
  /** The whole text was replaced and the undo history cleared (`setValue`); `false` for `replaceValue` and `executeEdits`. */
  isFlush: boolean;
  /**
   * The changes since the last event, in the order they were made. Unlike Monaco's, each change's
   * range is in the text as the changes before it in the list left it, so applying them in order
   * to the previous text gives this one. Several edits within one frame come in one event.
   */
  changes: AardinkModelContentChange[];
}

/** Monaco's `IPosition`: 1-based. */
export interface AardinkPosition {
  lineNumber: number;
  column: number;
}

/** Monaco's `ICursorSelectionChangedEvent`, with the reason as a word. */
export interface AardinkSelectionChangeEvent {
  /** The primary selection. */
  selection: AardinkSelection;
  secondarySelections: AardinkSelection[];
  /** Why they changed. */
  reason: 'keyboard' | 'pointer' | 'edit' | 'undo' | 'redo' | 'flush' | 'api';
  /** Monaco's source: `'model'` when an edit, an undo or a new text moved them. */
  source: 'keyboard' | 'mouse' | 'api' | 'model';
}

/**
 * Monaco's `IIdentifiedSingleEditOperation`, for `executeEdits`: replaces `range` with `text`.
 * `null` or `''` deletes the range; an empty range inserts. A position past the end of its line is
 * the line's end, and one past the last line the end of the text, as Monaco validates them.
 */
export interface AardinkEditOperation {
  range: AardinkRange;
  text: string | null;
  /** Accepted for Monaco's shape, and ignored. */
  forceMoveMarkers?: boolean;
}

export interface AardinkEditor {
  getValue(): string;
  /**
   * Replaces the whole text and clears undo history, as when a file is opened. Fires the change
   * listeners with `isFlush: true`. For a change the user should be able to undo, use
   * `replaceValue` or `executeEdits`.
   */
  setValue(text: string): void;
  /**
   * Replaces the whole text as one undo step, keeping the history before it: for a rewrite the user
   * should be able to undo, such as an assistant's. Only what differs changes (lines compared, then
   * characters), so carets, folds and diagnostics in unchanged text stay where they are. The change
   * listeners get it with `isFlush: false` and a new `versionId`; `undo()` brings the old text back,
   * and `getAlternativeVersionId()` its value then. Works in a read-only editor, as `setValue` does.
   * Returns whether the text changed.
   */
  replaceValue(text: string): boolean;
  /**
   * Monaco's `executeEdits`: applies `edits` (which must not overlap) as one undo step, keeping the
   * history, each reduced to the characters it really changes so carets and folds elsewhere stay
   * put. Then selects `endCursorState` when given (positions in the text after the edits, as in
   * Monaco), else carries the selections through the edits;
   * a cursor-state computer function is not called. `source` is accepted for Monaco's shape.
   * Returns `false`, changing nothing, in a read-only editor, as Monaco does; throws for edits that
   * overlap. Reported to the change listeners as for `replaceValue`.
   */
  executeEdits(source: string | null | undefined, edits: AardinkEditOperation[], endCursorState?: AardinkSelection[] | ((...args: unknown[]) => unknown)): boolean;
  updateOptions(options: Partial<AardinkOptions>): void;
  /** Full text after every change, at most once per frame, and what the change was. Returns an unsubscribe function. */
  onDidChangeContent(listener: (text: string, change: AardinkContentChange) => void): () => void;
  /** 1-based caret position. Returns an unsubscribe function. */
  onDidChangeCursor(listener: (line: number, column: number) => void): () => void;
  /** Every change to the selections, with why. Not called when they are set to what they already were. Returns an unsubscribe function. */
  onDidChangeCursorSelection(listener: (event: AardinkSelectionChangeEvent) => void): () => void;
  /** The text gained the keyboard focus. Returns an unsubscribe function. */
  onDidFocusEditorText(listener: () => void): () => void;
  /** The text lost the keyboard focus. Returns an unsubscribe function. */
  onDidBlurEditorText(listener: () => void): () => void;
  /** The primary caret. */
  getPosition(): AardinkPosition;
  /** One caret at `position`, clamped to the text; it does not scroll (see `revealPosition`). */
  setPosition(position: AardinkPosition): void;
  /** Scrolls `line` into view without moving the caret. */
  revealLine(line: number): void;
  /** Scrolls `line` to the middle of the view without moving the caret. */
  revealLineInCenter(line: number): void;
  /** Scrolls the start of `range` into view without moving the caret. */
  revealRange(range: AardinkRange): void;
  /** Scrolls the start of `range` to the middle of the view without moving the caret. */
  revealRangeInCenter(range: AardinkRange): void;
  /**
   * Monaco's `trigger`: runs an action by its id, one you added with `addAction` or `addCommand`,
   * or one of the editor's own by Monaco's id: `editor.action.formatDocument`,
   * `editor.action.formatSelection`, `editor.action.triggerSuggest`, `editor.action.showHover`,
   * `editor.action.quickFix`, `editor.action.rename`, `editor.action.revealDefinition`,
   * `editor.action.goToReferences`, `editor.action.marker.next`, `editor.action.marker.prev`,
   * `actions.find`, `editor.action.startFindReplaceAction`, `editor.action.gotoLine`,
   * `editor.foldAll`, `editor.unfoldAll`, `editor.foldLevel1` to `7`, `undo`, `redo`,
   * `editor.action.commentLine`, `editor.action.selectHighlights`, and
   * `editor.action.insertSnippet` with `{ snippet }`. `source` is accepted for Monaco's shape.
   * Returns whether it ran.
   */
  trigger(source: string | null | undefined, handlerId: string, payload?: unknown): boolean;
  /**
   * Monaco's `addCommand`: `handler` runs when `keybinding` is pressed in the editor, ahead of the
   * editor's own binding for it. A keybinding is text such as `'CtrlCmd+S'`, `'Shift+Alt+F'` or
   * `'F8'` (`CtrlCmd` is Cmd on macOS and Ctrl elsewhere; `Ctrl` is Ctrl everywhere), or Monaco's
   * `KeyMod | KeyCode` number for a letter, digit, function, navigation or punctuation key.
   * Returns the command's id, for `trigger`; null for a keybinding it cannot read.
   */
  addCommand(keybinding: string | number, handler: () => void): string | null;
  /**
   * Monaco's `addAction`: `run` is called by `trigger(…, id)`, by its keybindings, and from the
   * right-click menu when it has a `contextMenuGroupId`. Adding an id again replaces it.
   */
  addAction(descriptor: AardinkActionDescriptor): { dispose(): void };
  /** An action by its id, yours or the editor's own (see `trigger`); null for an id neither has. */
  getAction(id: string): AardinkAction | null;
  /**
   * Shows these diagnostics in place of the language's own; `null` goes back to the language's
   * own. Either way they move along with the text as it is edited, until the next list.
   */
  setDiagnostics(diagnostics: AardinkDiagnostic[] | null): void;
  /**
   * The language's own diagnostics each time they are collected: 500 ms after the editor appears,
   * after each pause in typing, and on `revalidate()`. Not called while a list from
   * `setDiagnostics` is shown.
   * Returns an unsubscribe function.
   */
  onDidChangeDiagnostics(listener: (diagnostics: AardinkDiagnostic[]) => void): () => void;
  /**
   * Collects the language's own diagnostics again now, without waiting for an edit: for when what
   * your `provideDiagnostics` depends on outside the text has changed (another file, a setting).
   * They reach `onDidChangeDiagnostics` as usual. Does nothing while a list from `setDiagnostics`
   * is shown.
   */
  revalidate(): void;
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

/** What `addAction` takes, as Monaco's `IActionDescriptor`. */
export interface AardinkActionDescriptor {
  id: string;
  label: string;
  /** As `addCommand`'s keybinding. */
  keybindings?: (string | number)[];
  /** Any value puts the action in the right-click menu. */
  contextMenuGroupId?: string;
  contextMenuOrder?: number;
  run(editor: AardinkEditor): void | Promise<void>;
}

/** Monaco's `IEditorAction`. */
export interface AardinkAction {
  id: string;
  label: string;
  /** Whether it can run now. */
  isSupported(): boolean;
  run(): Promise<void>;
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
  /**
   * An XML dialect's vocabulary. Completions (elements by what the element at the caret may hold,
   * with their required attributes; attributes; values), hover documentation and checks (unknown
   * elements and attributes, elements out of place, values not among an attribute's, required
   * attributes left out) come from it, ahead of the language it `extends` (or built-in XML's
   * checks, closing tags and formatting when it extends none). Your providers still come first.
   */
  schema?: AardinkXmlSchema;
}

/** An XML vocabulary, for `registerLanguage`'s `schema`. */
export interface AardinkXmlSchema {
  /** The elements a document may start with; any when absent. */
  roots?: string[];
  /** Report elements and attributes the schema does not have. `true` by default. */
  strict?: boolean;
  /** A regex source: attribute values matching it (expressions, placeholders) are not checked. */
  dynamicValue?: string;
  elements: Record<string, AardinkXmlElement>;
}

export interface AardinkXmlElement {
  doc?: string;
  /** The elements it may hold; any when absent, none for `[]` (it is then completed self-closing). */
  children?: string[];
  attributes?: Record<string, AardinkXmlAttribute>;
  /** What completing it inserts after `<`, as a snippet, instead of its name, required attributes and end. */
  snippet?: string;
  /** Report attributes it does not have. `true` by default. */
  strictAttributes?: boolean;
}

export interface AardinkXmlAttribute {
  doc?: string;
  /** Its values; any when absent. */
  values?: (string | { value: string; doc?: string })[];
  /** Report it missing, and insert it when the element is completed. */
  required?: boolean;
  /** What its value is, e.g. `'color'`. */
  type?: string;
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
   * The icon. `'function'` is another name for `'transform'`, and `'color'` for `'colorRef'`, as in
   * 0.6. Monaco's other kind names (`'method'`, `'class'`, `'keyword'`, ...) and its
   * `CompletionItemKind` numbers work too; an unknown name shows as `'value'`.
   */
  kind?:
    | 'element' | 'attribute' | 'value' | 'snippet' | 'module' | 'property' | 'transform' | 'function' | 'colorRef' | 'color'
    | 'method' | 'constructor' | 'field' | 'variable' | 'class' | 'struct' | 'interface' | 'enum' | 'enumMember' | 'keyword'
    | 'text' | 'file' | 'folder' | 'reference' | 'constant' | 'event' | 'operator' | 'typeParameter' | 'unit' | 'user' | 'issue'
    | number;
  /** A short line about the item, such as a type or a signature, shown beside the label. */
  detail?: string;
  /** Characters that, typed while this item is selected, accept it and are then typed after it. Not for snippets. */
  commitCharacters?: string[];
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
  /**
   * `false`: this list is complete for the word being typed, so your provider is asked once per
   * word and the editor narrows the list by `filterText` (or `label`) as letters are typed. Absent
   * or `true`: your provider is asked again on each letter, as in 0.6.
   */
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
