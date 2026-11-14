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
  /**
   * vscode-dark (or Monaco's `vs-dark`), vscode-light (or `vs`), hc-black, hc-light, material-dark,
   * material-light, midnight-ocean, solarized-dark, or a name given to `defineTheme`.
   */
  theme?: string;
  /** In CSS pixels. */
  fontSize?: number;
  /**
   * Monaco's `wordWrap`: `'on'` wraps at the edge of the view, `'wordWrapColumn'` and `'bounded'`
   * at `wordWrapColumn` or the edge, whichever comes first. `'off'` by default, as Monaco's (on
   * before 0.10).
   */
  wordWrap?: 'on' | 'off' | 'wordWrapColumn' | 'bounded';
  /** Where `wordWrap: 'wordWrapColumn'` or `'bounded'` wraps. 80 by default. */
  wordWrapColumn?: number;
  /** How wrapped rows are indented, as Monaco's. `'none'` by default, where Monaco's is `'same'`. */
  wrappingIndent?: 'none' | 'same' | 'indent' | 'deepIndent';
  /**
   * Monaco's `lineNumbers`: `'relative'` counts from the caret's line, `'interval'` shows every
   * tenth and the caret's. Monaco's function form is not taken: it counts as `'on'`.
   */
  lineNumbers?: 'on' | 'off' | 'relative' | 'interval';
  /** Columns with a vertical line. Monaco's `{ column, color }` takes its column; the colour is the theme's. */
  rulers?: Array<number | { column: number; color?: string | null }>;
  /**
   * Monaco's `guides`: `indentation`, a faint line down each level of indentation (the caret's
   * block's stronger), and `bracketPairs`, a line from each bracket to its partner (`'active'`
   * shows them all). Both off by default, where Monaco's indentation guides are on.
   */
  guides?: { indentation?: boolean; bracketPairs?: boolean | 'active' };
  /** The caret's shape. `'line'` by default. */
  cursorStyle?: 'line' | 'block' | 'underline' | 'line-thin' | 'block-outline' | 'underline-thin';
  /** How the caret blinks; `'phase'` and `'expand'` fade as `'smooth'` does. `'blink'` by default. */
  cursorBlinking?: 'blink' | 'smooth' | 'phase' | 'expand' | 'solid';
  /** The editor's own scrolls (to the caret, Page Up and Down) animate. Off by default. */
  smoothScrolling?: boolean;
  /** The last line can scroll up to the top of the view. Off by default, where Monaco's is on. */
  scrollBeyondLastLine?: boolean;
  /** Lines kept visible above and below the caret as it moves. 0 by default. */
  cursorSurroundingLines?: number;
  /**
   * A selection dragged with the mouse moves; with Ctrl (Alt on macOS) held, copies. Off by
   * default, where Monaco's is on.
   */
  dragAndDrop?: boolean;
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
  /** The overview ruler at the right edge, as Monaco's `overviewRulerLanes` above 0. Off by default. */
  overviewRulerLanes?: number;
  /** Highlights the other occurrences of the symbol at the caret. Off by default. */
  occurrencesHighlight?: boolean | 'off' | 'singleFile';
  /** Typing a tag name changes its partner. Off by default. */
  linkedEditing?: boolean;
  /** Swatches under the colours in the text, with a picker. Off by default. */
  colorDecorators?: boolean;
  /** The path of symbols at the caret, in a row above the text. Off by default. */
  breadcrumbs?: boolean;
  /** The font's ligatures; on by default. */
  fontLigatures?: boolean;
  /** Colours what a language server's semantic tokens say over the syntax's. Off by default. */
  semanticHighlighting?: boolean;
  /**
   * Shows what `provideInlayHints` or a language server says, among the characters of the lines
   * around the view. Off by default. Takes Monaco's `{ enabled }` too; with `'onUnlessPressed'` and
   * `'offUnlessPressed'` no key is watched, so they mean on and off.
   */
  inlayHints?: boolean | { enabled?: boolean | 'on' | 'off' | 'onUnlessPressed' | 'offUnlessPressed' };
  /**
   * Shows what `provideCodeLenses` or a language server says, in a row above its lines. Off by
   * default, where Monaco's is on.
   */
  codeLens?: boolean;
  /**
   * Typing one of `autoFormatTriggerCharacters` asks `provideOnTypeFormattingEdits` (or a language
   * server) to tidy the text around it, as Monaco's `formatOnType`. Undo takes the edits back and
   * keeps what was typed. Off by default.
   */
  formatOnType?: boolean;
}

/** What `onSearchWorkspace` is called with: VS Code's Find in Files arguments. */
export interface AardinkWorkspaceSearch {
  query: string;
  isRegex: boolean;
  isCaseSensitive: boolean;
  matchWholeWord: boolean;
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
   * `editor.action.commentLine`, `editor.action.selectHighlights`, `editor.action.quickOutline`,
   * `editor.action.smartSelect.expand` and `.shrink`, `editor.fold`, `editor.unfold`,
   * `editor.action.joinLines`, `editor.action.sortLinesAscending` and `Descending`,
   * `editor.action.transformToUppercase` and `Lowercase`, `editor.action.trimTrailingWhitespace`,
   * `editor.action.jumpToBracket`, `cursorUndo`, `editor.action.moveSelectionToNextFindMatch`,
   * `editor.action.changeAll`, `editor.action.insertCursorAtEndOfEachLineSelected`,
   * `editor.action.addCommentLine`, `editor.action.removeCommentLine`, `editor.action.blockComment`,
   * `editor.action.insertLineAfter` and `Before`, `expandLineSelection`,
   * `editor.action.showColorPicker`, `editor.action.inlineSuggest.commit`, `.showNext`,
   * `.showPrevious` and `.hide`, VS Code's `workbench.action.findInFiles` (with an
   * `onSearchWorkspace` listener), and
   * `editor.action.insertSnippet` with `{ snippet }`. `source` is accepted for Monaco's shape.
   * Returns whether it ran.
   */
  trigger(source: string | null | undefined, handlerId: string, payload?: unknown): boolean;
  /**
   * Monaco's `addCommand`: `handler` runs when `keybinding` is pressed in the editor, ahead of the
   * editor's own binding for it. A keybinding is text such as `'CtrlCmd+S'`, `'Shift+Alt+F'` or
   * `'F8'` (`CtrlCmd` is Cmd on macOS and Ctrl elsewhere; `Ctrl` is Ctrl everywhere), or Monaco's
   * `KeyMod | KeyCode` number for a letter, digit, function, navigation or punctuation key. A
   * chord of two keys is `'CtrlCmd+K CtrlCmd+S'` or `KeyMod.chord(...)`; its first key then starts
   * your chords only, not the editor's own with the same first key.
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
  /**
   * Without `owner`: shows `diagnostics` in place of the language's own, or the language's own
   * again for `null`. With `owner`: as Monaco's `setModelMarkers`, `diagnostics` become that
   * owner's, shown with every other owner's and the language's own; an empty list removes the
   * owner, and the owner `'language'` replaces the language's own.
   */
  setDiagnostics(diagnostics: AardinkDiagnostic[] | null, owner?: string): void;
  /** Monaco's `setModelMarkers(model, owner, markers)`, for this editor's model. */
  setModelMarkers(owner: string, markers: AardinkDiagnostic[]): void;
  /** The lines on screen: one range, 1-based. */
  getVisibleRanges(): AardinkRange[];
  /** How far the text is scrolled down, in CSS pixels. */
  getScrollTop(): number;
  /** Scrolls the text so `top` (CSS pixels) is at the top of the view, without moving the caret. */
  setScrollTop(top: number): void;
  /** The height of all the text, in CSS pixels (wrapped lines not laid out yet are estimated). */
  getScrollHeight(): number;
  /** Called as the text scrolls. Returns an unsubscribe function. */
  onDidScrollChange(listener: (event: { scrollTop: number; scrollLeft: number; scrollHeight: number }) => void): () => void;
  /**
   * Called with the target of every link opened in the editor, by Ctrl/Cmd+click on a URL in the
   * text or a click in a hover, signature help or completion card, whatever its scheme. A target
   * can come from a language server, so check it before following it: it may be `javascript:`. With
   * no listener, `http` and `https` links open in a new tab with `noopener`, and anything else does
   * nothing. Returns a disposable.
   */
  onOpenLink(listener: (target: string) => void): { dispose(): void };
  /**
   * Called when the editor hands a search to the page, by Ctrl/Cmd+Shift+F, the find panel's
   * "Search all files" button or `trigger('workbench.action.findInFiles')`, with VS Code's Find in
   * Files arguments: the selection on one line, else the find panel's query, and its options. While
   * there is no listener, the button is not shown and the key is left to the page. Returns a
   * disposable.
   */
  onSearchWorkspace(listener: (search: AardinkWorkspaceSearch) => void): { dispose(): void };
  /** Measures the container again now; the editor follows its container's size by itself. */
  layout(): void;
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
  /**
   * The document's structure as the language describes it (its built-in service, or your
   * `provideDocumentSymbols`): a tree in document order, its ranges moved along with the edits
   * since the last answer. Asked about 300 ms after the editor appears and after each pause in
   * typing; empty until then.
   */
  getDocumentSymbols(): AardinkDocumentSymbol[];
  /**
   * Monaco's `deltaDecorations`: removes the decorations with `oldIds` and adds `newDecorations`,
   * returning the new ones' ids. Their ranges follow the edits made after.
   */
  deltaDecorations(oldIds: string[], newDecorations: AardinkModelDeltaDecoration[]): string[];
  /** Monaco's `createDecorationsCollection`: a set of decorations of its own, replaced as a whole. */
  createDecorationsCollection(decorations?: AardinkModelDeltaDecoration[]): AardinkDecorationsCollection;
  /**
   * The symbols holding `position`, outermost first: the element the caret is in, its parent and
   * so on up. With `onDidChangeCursor`, this maps the caret to what the text describes.
   */
  getSymbolPathAt(position: AardinkPosition): AardinkDocumentSymbol[];
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
  /**
   * Runs it. For an action added with `addAction`, settles when the promise its `run` returned
   * does, and rejects with it; for the editor's own, once it has started (use `format()` to wait
   * for formatting).
   */
  run(): Promise<void>;
}

/** A language highlighted by a grammar, for `registerLanguage`. */
export interface AardinkLanguageDefinition {
  /** What `createEditor`'s `language` option names it by. Registering an id again replaces it. */
  id: string;
  /**
   * A subset of Monaco's Monarch: states in `tokenizer` (`root` first), rules of
   * `[regex, action, next?]`, `cases`, capture groups (each with a token name, `{ token, next }`
   * or `cases` of its own), `include`, `@rematch`, `switchTo`, `goBack`, `@brackets` with a
   * top-level `brackets`, `defaultToken`, `ignoreCase`, `tokenPostfix`, and `@name` in a regex
   * for the top-level string `name`, as a group of its own. A guard is
   * `@default`, `@eos`, or a subject of `$#` or `$S0`, `$S1`... then `@array`, `~regex`, `==text`
   * or `!@`, `!~`, `!=`, as in `$#==$S2`; `next` and `switchTo` take `$#` and `$Sn` too. `log` is
   * accepted and ignored. RegExp literals are fine. Lookbehind and `$` in a token name are
   * refused, as is `goBack` in a capture group's action. `nextEmbedded` hands the text to the
   * grammar of the language registered with that id, before or after this one, and
   * `nextEmbedded: '@pop'` takes it back; with no such language, the text stays on this grammar.
   * A built-in language has no grammar, so it cannot be embedded.
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
  /**
   * What the language says about typing, as VS Code's `language-configuration.json`: the object,
   * or the file's text, which may have comments and trailing commas. Regular expressions are
   * refused with a lookbehind. Its `comments` replace the definition's.
   */
  configuration?: AardinkLanguageConfiguration | string;
}

/** A regular expression in a language configuration: VS Code's two forms, or a RegExp literal. */
export type AardinkConfigurationRegExp = string | RegExp | { pattern: string; flags?: string };

/** An opening and a closing text, such as `['(', ')']`. */
export type AardinkCharacterPair = [string, string] | { open: string; close: string };

/**
 * `registerLanguage`'s `configuration`. Every field is optional. As in VS Code, without
 * `autoClosingPairs` the brackets close themselves, and without `surroundingPairs` those pairs
 * surround. `wordPattern`, `autoCloseBefore` and `folding.offSide` are not read.
 */
export interface AardinkLanguageConfiguration {
  /** What Ctrl/Cmd+/ comments with. */
  comments?: { lineComment?: string | { comment: string }; blockComment?: [string, string] };
  /** Pairs the language nests: Enter indents after an opening one. Matching and colouring brackets stay `()[]{}`. */
  brackets?: AardinkCharacterPair[];
  /**
   * Closed when the opening text is typed. `notIn` (`'string'`, `'comment'`) is worked out from the
   * caret's own line, so a block comment opened on an earlier line is not seen.
   */
  autoClosingPairs?: (AardinkCharacterPair | { open: string; close: string; notIn?: string[] })[];
  /** Wrap a selection when the opening text, one character, is typed over it. */
  surroundingPairs?: AardinkCharacterPair[];
  /** What Enter does, the first rule that matches. */
  onEnterRules?: AardinkOnEnterRule[];
  /** What Enter does when no rule and no bracket decides. */
  indentationRules?: {
    increaseIndentPattern?: AardinkConfigurationRegExp;
    decreaseIndentPattern?: AardinkConfigurationRegExp;
    indentNextLinePattern?: AardinkConfigurationRegExp;
    unIndentedLinePattern?: AardinkConfigurationRegExp;
  };
  /** Lines that open and close a fold region. */
  folding?: { markers?: { start: AardinkConfigurationRegExp; end: AardinkConfigurationRegExp } };
}

/** One of `onEnterRules`: every regular expression given must match. */
export interface AardinkOnEnterRule {
  /** Against the text before the caret. */
  beforeText?: AardinkConfigurationRegExp;
  /** Against the text after the caret. */
  afterText?: AardinkConfigurationRegExp;
  /** Against the line above the one Enter was pressed on. */
  previousLineText?: AardinkConfigurationRegExp;
  action: {
    /** `indentOutdent` leaves the closer at the opener's indentation and adds no line in between. */
    indent: 'none' | 'indent' | 'outdent' | 'indentOutdent';
    /** What the new line starts with after its indentation. Not used with `indentOutdent`. */
    appendText?: string;
    /** Characters taken off the end of the indentation. */
    removeText?: number;
  };
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

/**
 * Monaco's `DocumentSymbol`: `kind` is Monaco's `SymbolKind` number (0 File, 1 Module, 4 Class,
 * 5 Method, 6 Property, 11 Function, 12 Variable, 14 String, 17 Array, 18 Object, 19 Key...).
 * `selectionRange`, the part that names it, is `range` when absent.
 */
export interface AardinkDocumentSymbol {
  name: string;
  kind: number;
  range: AardinkRange;
  selectionRange?: AardinkRange;
  detail?: string;
  children?: AardinkDocumentSymbol[];
}

/** Monaco's `IModelDeltaDecoration`. */
export interface AardinkModelDeltaDecoration {
  range: AardinkRange;
  options: AardinkDecorationOptions;
}

/**
 * Monaco's `IModelDecorationOptions`, as far as the editor draws them. Where Monaco takes a CSS
 * class (`className`, `inlineClassName`, `linesDecorationsClassName`), give a colour instead: CSS
 * hex, `#rgb`, `#rgba`, `#rrggbb` or `#rrggbbaa`.
 */
export interface AardinkDecorationOptions {
  /** Behind the characters; behind whole lines with `isWholeLine`. */
  backgroundColor?: string;
  /** A box around the characters. */
  borderColor?: string;
  /** A line under the characters. */
  underlineColor?: string;
  /** A bar at the gutter's text edge on each line, as Monaco's `linesDecorationsClassName` places one. */
  gutterColor?: string;
  /** The colour of the range's characters, in place of Monaco's `inlineClassName`, and of `before` and `after`. */
  inlineColor?: string;
  /**
   * Text in front of the range, as Monaco's `before`. It is not in the document, moves with the range,
   * and is drawn in `inlineColor`, or faintly without it. `content` is all that is read, on one row.
   */
  before?: { content: string };
  /** Text behind the range, as Monaco's `after`, by the same rules as `before`. */
  after?: { content: string };
  isWholeLine?: boolean;
  hoverMessage?: string | { value: string } | Array<string | { value: string }>;
  overviewRuler?: { color: string; position?: number };
  minimap?: { color: string; position?: number };
}

/** Monaco's `IEditorDecorationsCollection`. */
export interface AardinkDecorationsCollection {
  readonly length: number;
  set(decorations: AardinkModelDeltaDecoration[]): string[];
  append(decorations: AardinkModelDeltaDecoration[]): string[];
  clear(): void;
  /** The ranges as they are now, moved along with the edits since they were set. */
  getRanges(): AardinkRange[];
}

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
  /** A short line about the item, such as a type or a signature, shown beside the label and above its `documentation`. */
  detail?: string;
  /** Characters that, typed while this item is selected, accept it and are then typed after it. Not for snippets. */
  commitCharacters?: string[];
  /** Markdown, either way. Shown beside the label as one line of plain text, and whole beside the list for the selected item. */
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
  /** Markdown. Raw HTML shows as written. A link goes to `onOpenLink`, or to a new tab for `http` and `https`. */
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
  /**
   * The document's structure, as Monaco's `provideDocumentSymbols`, for Go to Symbol
   * (Ctrl+Shift+O), the breadcrumbs, sticky scroll and `getSymbolPathAt`. With `extends`, the base
   * language's are used while this answers none.
   */
  provideDocumentSymbols?(text: string): AardinkDocumentSymbol[] | null | Promise<AardinkDocumentSymbol[] | null>;
  /**
   * The colours written in the text, as Monaco's `provideDocumentColors`, for the swatches drawn
   * with the `colorDecorators` option. With `extends`, the base language's are added. A colour
   * picked in the editor's picker is written back as CSS hex (`#rrggbb`, `#rrggbbaa`).
   */
  provideDocumentColors?(text: string): AardinkColorInformation[] | null | Promise<AardinkColorInformation[] | null>;
  /**
   * Labels shown among the characters of `range`, as Monaco's `provideInlayHints`, with the
   * `inlayHints` option on. `range` holds whole lines around the view: it is asked after a pause in
   * typing, in scrolling past what it last asked about, or after a fold opens or closes. A long closed
   * fold on screen makes that a few calls at once, one for the lines on either side of it. With
   * `extends`, the base language's are added, less any at a position this has one at. With a language
   * server these are shown after a second if it has not answered by then.
   */
  provideInlayHints?(text: string, range: AardinkRange): AardinkInlayHint[] | null | Promise<AardinkInlayHint[] | null>;
  /**
   * What is shown in a row above the line each `range` starts on, as Monaco's `provideCodeLenses`,
   * with the `codeLens` option on. It is about the whole text, and asked after a pause in typing;
   * between answers each lens moves with its text. Lenses on one line share a row. An array, with no
   * `{ lenses }` around it. A lens without a `command` is given to `resolveCodeLens`, if there is
   * one, as the answer comes (at most 100 of them), and left out if it still has none. With
   * `extends`, the base language's are added, less any with this one's position and title. With a
   * language server these are shown after a second if it has not answered by then.
   */
  provideCodeLenses?(text: string): AardinkCodeLens[] | null | Promise<AardinkCodeLens[] | null>;
  /**
   * A lens from `provideCodeLenses` was clicked or tapped, or a code action from `provideCodeActions`
   * with a `command` chosen (after its edits): the command's `id`, and its `arguments`, `[]` when it
   * has none. As they passed through JSON, they hold only what JSON can. There is no command
   * registry behind this: a lens or an action does what this does with its `id`.
   */
  runCodeLensCommand?(id: string, args: unknown[]): void | Promise<void>;
  /**
   * The characters whose typing asks `provideOnTypeFormattingEdits`, as on Monaco's
   * `OnTypeFormattingEditProvider`: `'}'`, `';'`, or `'\n'` for Enter.
   */
  autoFormatTriggerCharacters?: string[];
  /**
   * Edits that tidy the text around what was just typed, as Monaco's `provideOnTypeFormattingEdits`,
   * with the `formatOnType` option on. `line` and `column` are the caret's, after the character `ch`;
   * the edits are applied as an undo step of their own, and dropped if the text changed while they
   * were worked out. With `extends`, the base language's apply when this answers none.
   */
  provideOnTypeFormattingEdits?(
    text: string,
    line: number,
    column: number,
    ch: string,
    options: { tabSize: number; insertSpaces: boolean },
  ): AardinkTextEdit[] | null | Promise<AardinkTextEdit[] | null>;
  /**
   * Where the symbol at a 1-based `line` and `column` is defined, as Monaco's `provideDefinition`,
   * for F12 and Ctrl/Cmd+click: a location, an array of them (the first is followed) or Monaco's
   * `LocationLink`s. A location without `uri` is in this text; one with a `uri` is another file's,
   * which the editor does not open.
   */
  provideDefinition?(text: string, line: number, column: number): AardinkLocation | AardinkLocation[] | null | Promise<AardinkLocation | AardinkLocation[] | null>;
  /** The references to the symbol at a place, as Monaco's `provideReferences`, for Shift+F12's list. */
  provideReferences?(text: string, line: number, column: number): AardinkLocation[] | null | Promise<AardinkLocation[] | null>;
  /**
   * Edits that format the whole text, as Monaco's `provideDocumentFormattingEdits`, for Format
   * Document (Shift+Alt+F) and `format()`. With `extends`, the base language formats when this
   * changes nothing.
   */
  provideDocumentFormattingEdits?(text: string, options: { tabSize: number; insertSpaces: boolean }): AardinkTextEdit[] | null | Promise<AardinkTextEdit[] | null>;
  /** Edits that format `range`, as Monaco's `provideDocumentRangeFormattingEdits`, for Format Selection. */
  provideDocumentRangeFormattingEdits?(
    text: string,
    range: AardinkRange,
    options: { tabSize: number; insertSpaces: boolean },
  ): AardinkTextEdit[] | null | Promise<AardinkTextEdit[] | null>;
  /**
   * Accepted for Monaco's shape. The editor asks `provideSignatureHelp` whenever the caret is
   * inside a call's arguments, so no character has to start it.
   */
  signatureHelpTriggerCharacters?: string[];
  /**
   * The signatures of the call at a place, as Monaco's `provideSignatureHelp`: its
   * `SignatureHelpResult` (`{ value }`) or the `SignatureHelp` in it. A parameter's `label` is its
   * text or `[start, end]` in the signature's label.
   */
  provideSignatureHelp?(text: string, line: number, column: number): AardinkSignatureHelp | { value: AardinkSignatureHelp } | null | Promise<AardinkSignatureHelp | { value: AardinkSignatureHelp } | null>;
  /**
   * Quick fixes and refactorings for `range`, as Monaco's `provideCodeActions`, with the markers of
   * `provideDiagnostics`' last answer that touch it. An action with edits to another file (a
   * `resource`) is left out; its `command` goes to `runCodeLensCommand` after its edits.
   */
  provideCodeActions?(
    text: string,
    range: AardinkRange,
    context: { markers: AardinkDiagnostic[] },
  ): AardinkCodeAction[] | { actions: AardinkCodeAction[] } | null | Promise<AardinkCodeAction[] | { actions: AardinkCodeAction[] } | null>;
  /**
   * The edits that rename the symbol at a place to `newName`, as Monaco's `provideRenameEdits`, for
   * F2. Its presence offers rename. `{ rejectReason }`, or edits to another file, rename nothing.
   */
  provideRenameEdits?(text: string, line: number, column: number, newName: string): AardinkWorkspaceEdit | { rejectReason: string } | null | Promise<AardinkWorkspaceEdit | { rejectReason: string } | null>;
  /**
   * What F2 would rename at a place, as Monaco's `resolveRenameLocation`: `{ range, text }`, or
   * `{ rejectReason }` where nothing can be. Without it, the word at the caret.
   */
  resolveRenameLocation?(text: string, line: number, column: number): { range: AardinkRange; text: string } | { rejectReason: string } | null | Promise<{ range: AardinkRange; text: string } | { rejectReason: string } | null>;
  /**
   * The text's folds, as Monaco's `provideFoldingRanges`: 1-based `start` and `end` lines. They
   * replace the language's own (by indentation, or the base language's).
   */
  provideFoldingRanges?(text: string): { start: number; end: number; kind?: 'comment' | 'imports' | 'region' }[] | null | Promise<{ start: number; end: number; kind?: 'comment' | 'imports' | 'region' }[] | null>;
  /**
   * What `provideDocumentSemanticTokens` numbers stand for, as Monaco's `getLegend()`. The token
   * types are named as a theme colours them, as a language server's are: `class` as `type.class`,
   * `parameter` as `variable.parameter`, `method` as `function.method`.
   */
  getLegend?(): { tokenTypes: string[]; tokenModifiers: string[] };
  /** The legend as data, in place of `getLegend`. */
  legend?: { tokenTypes: string[]; tokenModifiers: string[] };
  /**
   * The text's semantic tokens, as Monaco's `provideDocumentSemanticTokens`: `{ data }`, five
   * integers a token, coloured over the syntax's with the `semanticHighlighting` option on.
   */
  provideDocumentSemanticTokens?(text: string): { data: ArrayLike<number>; resultId?: string } | null | Promise<{ data: ArrayLike<number>; resultId?: string } | null>;
  /**
   * More of an item, as Monaco's `resolveCompletionItem`: called with the item as
   * `provideCompletionItems` gave it when it is selected in the list; its `documentation` and
   * `detail` replace what the item had.
   */
  resolveCompletionItem?(item: AardinkCompletionItem): AardinkCompletionItem | null | Promise<AardinkCompletionItem | null>;
  /** A lens that came without a `command`, with one, as Monaco's `resolveCodeLens`. */
  resolveCodeLens?(text: string, lens: AardinkCodeLens): AardinkCodeLens | null | Promise<AardinkCodeLens | null>;
}

/** Monaco's `Location`: `uri` left out for a place in this text. */
export interface AardinkLocation {
  uri?: string;
  range: AardinkRange;
}

/** Monaco's `SignatureHelp`. */
export interface AardinkSignatureHelp {
  signatures: {
    label: string;
    documentation?: string | { value: string };
    parameters: { label: string | [number, number]; documentation?: string | { value: string } }[];
  }[];
  activeSignature: number;
  activeParameter: number;
}

/** Monaco's `WorkspaceEdit`, for this text: an edit with a `resource` is another file's. */
export interface AardinkWorkspaceEdit {
  edits: { resource?: unknown; textEdit: AardinkTextEdit }[];
}

/** Monaco's `CodeAction`, as far as the editor reads one. */
export interface AardinkCodeAction {
  title: string;
  /** `'quickfix'`, `'refactor...'` or `'source.organizeImports'`. */
  kind?: string;
  isPreferred?: boolean;
  edit?: AardinkWorkspaceEdit;
  command?: { id: string; title?: string; arguments?: unknown[] };
}

/** Monaco's `TextEdit`: `text` in place of `range`. */
export interface AardinkTextEdit {
  range: AardinkRange;
  text: string | null;
}

/**
 * Monaco's `CodeLens`: a title in a row above the line `range` starts on. One without a `command`
 * is shown once `resolveCodeLens` gives it one.
 */
export interface AardinkCodeLens {
  range: AardinkRange;
  command?: { id: string; title: string; arguments?: unknown[] };
}

/** Monaco's `InlayHint`, as far as the editor draws it: a label at a position, in front of the character there. */
export interface AardinkInlayHint {
  position: AardinkPosition;
  label: string;
  /** Shown, as Markdown, in a card under a mouse resting on the label; Monaco's `{ value }` too. */
  tooltip?: string | { value: string };
}

/** Monaco's `IColorInformation`: where a colour is written, and the colour, each channel from 0 to 1. */
export interface AardinkColorInformation {
  range: AardinkRange;
  color: { red: number; green: number; blue: number; alpha: number };
}

/** Adds a language for `createEditor`'s `language` option. Rejects with what is wrong with the definition. */
export function registerLanguage(definition: AardinkLanguageDefinition, providers?: AardinkLanguageProviders): Promise<void>;

/**
 * Monaco's `defineTheme` data. Token rules name tokens as Monaco does, and the longest matching
 * prefix wins: `tag` also colours `tag.aardflex`, `comment.doc` only documentation comments. A
 * rule's `fontStyle` (`'bold'`, `'italic'`, `'underline'`, `'strikethrough'`, or `''` for none)
 * applies too, and its `background` is drawn behind the token's text; the empty token's sets the
 * editor's. `colors` takes VS Code's keys, the editor's chrome too (`minimap.background`,
 * `scrollbarSlider.background`, `editorIndentGuide.background1`, `editorRuler.foreground`,
 * `editorWidget.background`, `.foreground` and `.border`, `editorInlayHint.foreground`,
 * `editorCodeLens.foreground`, `editorGhostText.foreground`...). `hc-black` and `hc-light` fill in
 * from the high-contrast themes.
 */
export interface AardinkThemeData {
  base: 'vs' | 'vs-dark' | 'hc-black' | 'hc-light';
  inherit?: boolean;
  rules: { token: string; foreground?: string; fontStyle?: string; background?: string }[];
  colors?: Record<string, string>;
}

/** Adds a theme for `createEditor`'s `theme` option: Monaco's `defineTheme` data, or a VS Code theme's JSON. */
export function registerTheme(name: string, theme: AardinkThemeData | object): Promise<void>;

/**
 * Connects to the language server at a WebSocket URL, which carries one JSON-RPC message per frame,
 * and has it follow every editor of `languageId`: those open now, those created later, and those
 * switched to it with `updateOptions`. Each is a document of its own to the server, under a
 * generated `file:///aardink/` URI.
 *
 * What the server knows joins what the language has. The providers of a language from
 * `registerLanguage` answer first and the server fills in; a built-in language's answers come after
 * the server's. Either way Enter, closing pairs and surrounding a selection stay the language's,
 * even where it does nothing, and an editor with no language service, such as plain text, types as
 * it does without a server. Completions, diagnostics, code actions, references, links and colours
 * come from both, and what a server pushes shows after a pause in typing.
 *
 * Resolves once the connection has been asked for, not once it is up. A server that cannot be
 * reached, or that drops later, is said on the console and the editors go on with their language's
 * own service. Nothing reconnects by itself: call this again for that. A rename reaching other
 * files, and `workspace/applyEdit`, are refused. `dispose()` closes each document and shuts the
 * server down.
 */
export function connectLanguageServer(url: string | URL, languageId: string): Promise<{ dispose(): void }>;

/** What `provideInlineCompletions` offers: Monaco's `InlineCompletions`, or just its `items`. */
export interface AardinkInlineCompletions {
  items: AardinkInlineCompletion[];
}

/**
 * Monaco's `InlineCompletion`, as far as the editor shows it. A `{ snippet }` shows and goes in as
 * its text: a placeholder as its default text, a choice as its first, tab stops and variables as
 * nothing.
 *
 * `range` is what it replaces, and must hold the caret. What `insertText` repeats of it is not shown
 * again: over `)`, the text `x) {` shows `x` before the bracket and ` {` after it, which is how ghost
 * text keeps from doubling what follows the caret. So it has to hold all of `range`, in order, or it
 * does not show, as in Monaco. An empty `range` is a plain insertion.
 *
 * Without `range`, it replaces the word at the caret and the rest of the line, as in Monaco. Where
 * Monaco then shows nothing for text that does not hold them, this tries the rest of the line alone,
 * and then puts the text in at the caret, so a provider that only knows what comes next can leave
 * `range` out. Text the document has already, from the caret on or from the word's start to the caret
 * or past it, shows nothing.
 */
export interface AardinkInlineCompletion {
  insertText: string | { snippet: string };
  range?: AardinkRange;
}

/** Monaco's `InlineCompletionsProvider`, as far as the editor asks one anything. */
export interface AardinkInlineCompletionsProvider {
  /**
   * What might come next at a 1-based `line` and `column`, best first: the first shows, and Alt+] and
   * Alt+[ go through the others. Asked once typing pauses, not for each key, and at once after one
   * is accepted, or by `editor.action.inlineSuggest.showNext` with none showing. While one shows,
   * typing what it says asks nothing. An answer for text that has moved on is dropped; there is no
   * cancellation token, so the work behind it is not told to stop.
   */
  provideInlineCompletions(
    text: string,
    line: number,
    column: number,
  ): AardinkInlineCompletions | AardinkInlineCompletion[] | null | Promise<AardinkInlineCompletions | AardinkInlineCompletion[] | null>;
}

/**
 * Has `provider` offer ghost text in every editor of `languageId`, open now or to come, as Monaco's
 * `languages.registerInlineCompletionsProvider`. Several for one language are all asked, and their
 * answers follow each other, oldest first. Tab accepts, as one undo step; Alt+] and Alt+[ go through
 * what is on offer; Escape dismisses, until the next edit asks again. A phone has neither key, so run
 * `editor.action.inlineSuggest.commit` and `.hide` from buttons: `getAction` says whether anything
 * shows. Nothing shows in a read-only editor, with a selection or several carets, while filling in a
 * snippet, while an input method composes, or under an open completion list, which takes Tab.
 * `dispose()` ends it.
 */
export function registerInlineCompletionsProvider(
  languageId: string,
  provider: AardinkInlineCompletionsProvider,
): Promise<{ dispose(): void }>;

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
