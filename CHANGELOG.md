# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

<!-- Release workflow:
  Day-to-day:
    Add entries under [Unreleased] in your PR. You can run ./update-changelog.ps1
    on main to auto-classify commits since the last tag into the right sections.

  Cutting a release:
    1. ./create-release.ps1            # bumps gradle.properties VERSION_NAME,
                                       # cuts [Unreleased] -> [X.Y.Z] - YYYY-MM-DD,
                                       # commits, and creates an annotated tag.
    2. git show vX.Y.Z                 # review the staged commit and tag.
    3. ./release.ps1                   # pushes main + tag, triggering CI publish.

  CI (.github/workflows/release.yml) verifies the tag matches VERSION_NAME, publishes
  to Maven Central, then extracts this matching ## [X.Y.Z] section to create the
  GitHub Release. It will fail if no matching section exists. -->

<!-- Commit prefix → Changelog heading (1:1 mapping)

  feat:        → Added        New features
  refactor:    → Changed      Changes to existing functionality
  deprecated:  → Deprecated   Soon-to-be removed features
  removed:     → Removed      Now removed features
  fix:         → Fixed        Bug fixes
  security:    → Security     Vulnerability fixes
  chore:       → (omitted)    Maintenance, tooling, config
  docs:        → (omitted)    Documentation-only changes

  Append ! for breaking changes: feat!:, fix!:, removed!: etc. -->

## [Unreleased]

0.6.0 replaces the editor's text field with a renderer and text input of its own on every
platform, so typing costs the same in any size of document, and builds a wave of features on it:
multiple cursors, column selection, a minimap, sticky scroll, bracket-pair colours, go to
definition, references and format. The web editor is on npm, and takes languages and themes
registered from JavaScript. The public API breaks where it assumed one whole-document text field;
[`docs/MIGRATION_0.6.md`](docs/MIGRATION_0.6.md) lists every change with a before and after.

### Added

- **The editor's own renderer, on every platform.** `CodeEditorLayout` no longer wraps a
  `BasicTextField` holding the whole document: it lays out and draws only the lines on screen, from
  a cache of recently drawn ones, and takes its own text input. Keys follow VS Code's bindings
  (Cmd on macOS); input methods compose and auto-correct as in a text field, the web's seeing a
  window of text around the caret instead of the whole document; the clipboard goes through
  Compose's `Clipboard`, or on the web through the browser's clipboard events, which need no
  permission. Mouse: click, Shift+click, Alt+click for another caret, double and triple click,
  drag, and a right-click menu. Touch: tap, long press, selection handles with the platform's text
  toolbar, and Android's magnifier. On the web's 5,000-line reference document the editor settles
  about 70 ms after mounting (3.2 s in 0.5) and 8 to 10 ms after a keystroke (3.7 s), and loses
  no keys at any typing speed (15 to 29 of 30 at 10 keys a second). See `docs/MIGRATION_0.6.md`.
- `EditorOptions`, passed as `CodeEditorLayout(options = …)`: read-only, soft wrap and the gutter
  switches, plus `highlightCurrentLine`, `matchBrackets`, `bracketPairColorization`,
  `stickyScroll`, `showMinimap`, `tabSize`, `insertSpaces` and `renderWhitespace`.
- Whitespace drawn as VS Code draws it: `EditorOptions.renderWhitespace` (`RenderWhitespace`:
  `None`, `Boundary`, `Selection`, `Trailing`, `All`; `Selection` by default, as in VS Code) shows
  spaces as dots and tabs as arrows in `EditorTheme.whitespaceColor` (read from a VS Code theme's
  `editorWhitespace.foreground`; the text colour, faint, when unset). Tabs are now as wide as the
  columns to the next tab stop of `tabSize`, instead of about one character.
- Snippet completions, as Monaco's `InsertAsSnippet`: a `CompletionItem` with `isSnippet` inserts
  `insertText` in VS Code's snippet syntax (`$1`, `${1:placeholder}`, `${1|one,two|}`, `$0`,
  `$TM_SELECTED_TEXT` and the other `TM_` variables) and selects its first tab stop. Tab and
  Shift+Tab step through the stops, a stop written twice is edited at both places, a choice offers
  its options in the completion list, and Escape, the final stop or a caret moved elsewhere ends
  it. Its lines follow the indentation of the line they land on unless `keepWhitespace` is set.
  The built-in Kotlin, XML, TOML and JSON completions use them (an XML attribute leaves the caret
  between its quotes), and a language server's snippets (`insertTextFormat` 2) are filled in
  instead of stripped. On the web, `insertTextRules` (`CompletionItemInsertTextRule`).
- Token colours by Monaco's token names: `TokenType.scope` gives every type a dotted name
  (`keyword`, `comment`, `delimiter`, ...), and a type without its own entry in
  `EditorTheme.tokenColors` takes the entry of the longest prefix of its name that has one, so
  `comment.doc` takes `comment`'s colour unless the theme gives it its own. `NamedTokenType` moved
  to `:editor` (`com.aardarch.aardink.core`) for this. The built-in languages name more: KDoc and
  JSDoc are `comment.doc`, `if`/`return`/`throw` and the other control keywords `keyword.flow`,
  escapes in strings `string.escape`, and numbers `number.hex`, `number.binary` and
  `number.float`; the VS Code themes colour `keyword.flow`, `string.escape` and `regexp` as Dark+
  and Light+ do.
- Bold, italic, underline and strikethrough in themes: `EditorTheme.tokenFontStyles`
  (`TokenFontStyle`), found by name as colours are, and read from a VS Code theme's `fontStyle`
  or a Monaco rule's.
- Multiple selections: `CodeEditorState.selections` and `setSelections` (the first is the primary
  one, as in Monaco), and a settable `selection`. Ctrl/Cmd+D adds the next occurrence, Ctrl+Shift+L
  selects them all, Alt+click and Ctrl+Alt+Up/Down add carets (Alt+click on a caret removes it,
  Alt+drag adds a selection), and typing, deleting, pasting, commenting and moving lines work at
  every one, each as one undo step that brings every selection back.
- Column (box) selection, as in VS Code: Shift+Alt+click or Shift+Alt+drag from the caret, a
  middle-button drag from where it starts, or Ctrl+Shift+Alt+arrows and PageUp/PageDown
  (Cmd+Shift+Option on macOS). Rows are the ones on screen, so wrapped rows count and folded lines
  do not; a row whose text ends before the box is left out.
- An input method composing with several carets composes at the primary one and, when the
  composition ends, puts the result at every other caret in the same undo step; an input method's
  own backspace and corrections of the word before the caret happen at every caret too.
- Line commands with VS Code's keys: toggle comment (Ctrl/Cmd+/), move lines (Alt+Up/Down), copy
  lines (Shift+Alt+Up/Down), delete lines (Ctrl/Cmd+Shift+K). Tab and Shift+Tab work on every
  selection. `CommentSyntax` and `IncrementalTokenizer.commentSyntax` say how a language comments;
  the built-in languages fill it in.
- A Monaco-style undo surface: `canUndo`, `canRedo`, `pushUndoStop()`, `alternativeVersionId`
  (compare it with the value stored at save time to know whether the document is dirty) and
  `lastChangeKind` (`EditChangeKind.Edit`, `Undo`, `Redo`, `Flush`).
- Find and replace: `FindReplaceState.show(replace)` and `replaceMode`, Ctrl+H opens the panel in
  replace mode, and the panel has a toggle for the replace row. Searches stop at
  `EditorLimits.maxFindMatches` (10,000, shown as "10000+"), run in chunks on the web, and a new
  query cancels one still running.
- Editor chrome, each switchable in `EditorOptions`: the caret's line is highlighted in
  `EditorTheme.lineHighlight`; the bracket pair the caret touches is boxed; and brackets are
  coloured by nesting depth from the new `EditorTheme.bracketPairColors` (VS Code's colours by
  default; `EditorThemeParser` reads `editorBracketHighlight.foreground1` to `6`). Brackets in
  strings and comments are left alone. Tab and Shift+Tab indent by `tabSize` spaces, or by a tab
  without `insertSpaces`.
- Sticky scroll (`EditorOptions.stickyScroll`): the first lines of the blocks the top of the view
  is inside (up to five, from the folding provider's ranges) stay pinned at the top, each pushed
  up as its block ends; a click scrolls to it.
- A minimap (`EditorOptions.showMinimap`): the whole text in small at the side, as blocks in its
  token colours, with a slider over what is shown; a press jumps there and dragging scrolls. It
  adds under half a millisecond per frame while scrolling 5,000 lines on the web.
- Popups where the work is. With a hardware keyboard, completions open as a list at the caret
  (Ctrl+Space asks for them; the arrows move through them, Enter or Tab takes one, Escape closes
  it); touch devices keep the strip above the keyboard. Signature help sits above the caret,
  quick fixes at their diagnostic, and all of them flip to the other side of the line or move
  sideways to stay inside the window. Hover documentation (`LanguageService.hoverDoc`, which the
  editor never showed before) appears when the mouse rests on a symbol for half a second, and
  from the touch menu's "Info".
- Go to definition, references and format, which language services offered but the editor never
  used. F12 or Ctrl/Cmd+click goes to the definition of the symbol there (holding Ctrl/Cmd
  underlines a symbol that has one); a definition in another file goes to the new
  `CodeEditorLayout(onNavigateToLocation)` for the host to open. Shift+F12 lists the references at
  the caret, to step through with the arrows and open with Enter or a click. Shift+Alt+F formats
  the selection (`formatRange`) or the whole document (`format`), changing only the lines that
  differ, as one undo step, so carets and folds elsewhere stay put. The right-click menu has all
  three. The mouse pointer is a text cursor over the text.
- Diagnostics without a host. `CodeEditorLayout(diagnostics = null)`, the default, asks the
  language service 500 ms after the editor appears and after each pause in typing, drops any
  answer for text that has changed since it asked, and reports each list through the new
  `onDiagnosticsChange`. On the web the built-in languages' checks pause for frames as they go.
  While the text is edited, every diagnostic (the host's too) moves along with the text it marks
  instead of staying at its old offset until the next list.
- Grammars as data: `DeclarativeGrammar.parse(json)` reads a subset of Monaco's Monarch (states,
  rules of regex, action and next state, `cases`, capture groups each with an action of its own,
  `include`, `@rematch`, `defaultToken`, `ignoreCase`, `tokenPostfix`, `@name` regex attributes),
  and `DeclarativeTokenizer(grammar)` highlights with it, a line at a time and incrementally.
  Lookbehind is refused when the grammar is read, with where it is. Tokens get a
  `NamedTokenType(name)`, dotted as in Monaco (`tag.aardflex`, `comment.doc`); the bare names
  `comment` and `string` get the editor's own types, and any name with a `comment` or `string`
  part is a comment or a string to the editor (no bracket colours inside it). aardflex-web-app's Monaco grammar reads unchanged and passes the cases its
  Monaco tests check.
- `CodeEditorState.tokensForLine(line)`: the syntax tokens of one line, replacing the removed
  `tokenCache`.
- `CodeEditorState.tokenizer` is settable: switching the language of an open document keeps its
  text, selection and undo history. On the web, `updateOptions({ language })` now does this too.
- The keyboard toolbar types through the typing rules, as a key does: its `(` gets a `)`.
- **Web:** `@aardarch/aardink-web` is published to npm with every release, through npm trusted
  publishing with provenance: `pnpm add @aardarch/aardink-web`. Pre-release versions go to the
  `next` dist-tag.
- **Web:** languages and themes of your own without building a wasm executable.
  `registerLanguage` takes a Monarch grammar (with `extends: "xml"` to keep a built-in language's
  service) and JavaScript functions for completions, hover and diagnostics, answered at once or
  with a Promise; `defineTheme` / `registerTheme` take Monaco's theme data or VS Code theme JSON,
  whose colours and font styles reach token names by dotted prefix (`tag.aardflex`, then `tag`). In
  Kotlin, `AardinkWeb.registerLanguage` with `WebLanguageProviders`, and `registerTheme`.
- **Web:** more of Monaco's editor API: `getSelections` / `setSelections`, `canUndo`, `canRedo`,
  `pushUndoStop`, `getAlternativeVersionId`, `format()`, `focus()`, `setBaseline(text)` for the
  diff lane, a second `onDidChangeContent` argument (`{ versionId, isUndoing, isRedoing, isFlush }`),
  the options `tabSize`, `insertSpaces`, `renderLineHighlight`, `renderWhitespace`, `bracketPairColorization`,
  `minimap` and `stickyScroll` (in Monaco's `{ enabled }` shape or as booleans), and
  `tokenize(text, languageId)` for debugging a grammar.
- **Web:** the language's own diagnostics show until the host calls `setDiagnostics`;
  `setDiagnostics(null)` goes back to them, and `AardinkWeb.onDiagnosticsChange` (in the npm
  package, `onDidChangeDiagnostics`) reports them in the same Monaco-marker shape.
- **Web:** a new editor waits up to half a second for its bundled font instead of flashing another
  font first, and `preloadAardink()` fetches the font early (`AardinkWeb.preloadFont()`).
  `dispose()` takes the editor's element out of the page (the editor now mounts into an element of
  its own inside the container), which from Compose Multiplatform 1.13 releases it entirely.

### Changed

- **Breaking:** `CodeEditorLayout`'s `readOnly`, `softWrap` and `show*` parameters are now fields
  of `options: EditorOptions`, and `diagnostics` is nullable: `null`, the default, collects them
  from the language service.
- **Breaking:** `CodeDocument` implements `CharSequence` and keeps its text in a gap buffer with a
  line index updated edit by edit, so a keystroke costs the size of the edit instead of a copy
  and a rescan of the whole document (10,000 keystrokes into 2 MB take ~20 ms on the JVM).
  `text` is built once per edit and shared.
- **Incremental tokenization.** The built-in tokenizers remember their last scan and, after an
  edit, rescan only from just before the change until the scan is back in step with the old one,
  so a keystroke in a large file costs a few lines instead of the whole document. On the web a
  large document now gets its visible lines highlighted first. `tokenizeLines` of a built-in
  tokenizer returns only the lines it rescanned, and recognises its own previous result as
  `previousTokens`; see `IncrementalTokenizer.tokenizeLines`. A wrapper that post-processes the
  list keeps working, with full scans.
- The first highlighting pass over a new document or language starts at once instead of after the
  typing debounce, and on the web a large document's pass runs in slices of about 8 ms, so frames
  keep coming while it works.
- Language services and folding providers receive a read-only snapshot of the document, taken
  when the request was made, instead of the live document that a service running on the compute
  dispatcher could see change mid-request.
- Undo groups edits as Monaco does: a run of typing until the caret moves, the kind of edit
  changes or a space follows a word (so undo removes a word at a time), backspaces together, and
  every other edit on its own. Undo and redo restore the selections from before and after the step.
- Replace All is one undo step instead of one per match.
- The find panel shows its replace row only in replace mode.
- A new `CodeEditorState` starts with the caret at the start of the text, as after `loadText`,
  instead of at its end.
- A touch selection gets the editor's own menu (cut, copy, paste, select all, and "Info" with a
  language service) instead of the platform's text toolbar, which cannot take items of an app's
  own.
- Selections are drawn in `EditorTheme.selectionColor` and find matches in
  `EditorTheme.findMatchColor` (the current one stronger), neither of which was used before; a
  find match inside a selection stays visible.
- The diff lane is recomputed 300 ms after the last edit rather than on every keystroke.
- **Web:** `AardinkWeb.mount` uses one shared language registry and theme map by default, which
  `registerLanguage` and `registerTheme` add to.

### Removed

- **Breaking:** `CodeEditorState.textFieldState`, the `annotatedText` parameter of
  `CodeEditorLayout`, `EditorGutter`, `DrawScope.drawSquiggles`, `annotateTokens`, `applyFolding`,
  and `EditorTheme`'s deprecated `fontFamily`, `fontSize` and `lineHeight`. See
  `docs/MIGRATION_0.6.md`.
- **Breaking:** `TokenCache` and `CodeEditorState.tokenCache`. Use
  `CodeEditorState.tokensForLine`.
- **Breaking:** `EditorUndoManager`, its `EditOperation` classes, and `CodeEditorState.undoManager`.
  Use `CodeEditorState.undo()`, `redo()`, `canUndo` and `canRedo`.

### Fixed

- Fast typing lost keys in large documents on the web (about half of them at 10 keys a second
  past 1,000 lines). None are lost now.
- After a line was inserted or removed, lines below it could show the previous line's syntax
  colours until the next tokenization pass, because cached tokens stayed keyed to their old line
  numbers. Colours now move with their text.
- The diff lane marked every line below an inserted line as modified; it now marks just the
  inserted one.
- Squiggles were placed by document offset against the folded layout, so they drifted once
  anything above them was folded; each is now drawn on its own line.
- Closed folds now follow edits above them instead of hiding the wrong lines until the next
  folding pass.
- `EditorThemeParser` gave a built-in type the colour of whichever of its theme's rules came last,
  so a theme with rules for both `keyword` and `keyword.control` could colour every keyword as a
  control keyword. Each type now takes its most general rule, and the more specific ones colour
  the sub-names.
- `EditorThemeParser` read an 8-digit colour as `#AARRGGBB`; VS Code themes write `#RRGGBBAA`,
  so translucent colours such as a line highlight came out wrong. It also reads `#RGB` and
  `#RGBA` now.
- A touch tap in the web editor did nothing once a mouse had been over the page (a hovering mouse
  pointer never lifts, and the tap waited for it).

### Known issues

- **Web: a disposed editor is not fully released** (about 320 KB each) on Compose Multiplatform
  1.12. Reuse one editor with `setValue` and `updateOptions` rather than mounting one per view.
  Aardink adopts Compose Multiplatform 1.13, which fixes it, once it is stable.
- Some checks need real devices and a person: input methods (CJK, dead keys, Gboard and Samsung
  keyboards), touch selection, the mobile soft keyboard, browser zoom, TalkBack and NVDA. See the
  checklist in `docs/WEB_INTEGRATION.md`.

## [0.6.0-rc1] - 2026-09-27

A release candidate for 0.6.0, on Maven Central and on npm's `next` tag. Its changes are the
ones under [Unreleased], which become 0.6.0.

## [0.5.0] - 2026-09-27

### Added

- Kotlin Multiplatform: `:editor`, `:languages` and `:languages-lsp` now build for JVM
  (desktop) and wasmJs (browser) in addition to Android.
- `CodeEditorState.textFieldState`, the `BasicTextField` interop point for the new
  `TextFieldState` input model.
- `com.aardarch.aardink.platform.EditorDispatchers` — platform-appropriate `compute` and
  `io` dispatchers. `Dispatchers.IO` does not exist on wasmJs.
- `com.aardarch.aardink.platform.PlatformInfo` and
  `KeyboardToolbarPlacement.platformDefault`, so a host can take the toolbar placement and
  modifier-key convention that suit the platform.
- `EditorTypography` and `LocalEditorTypography` — the editor's font family and metrics are
  now provided rather than hardcoded. `:editor` bundles no font; the defaults are unchanged.
- `CodeEditorLayout(onRequestGoToLine = ...)`, hardware-keyboard shortcuts (undo/redo,
  find, replace, go-to-line, Tab/Shift+Tab indent, Escape) and desktop/web scrollbars.
  `CodeEditorState.indentSelection()` / `outdentSelection()`.
- `WebSocketLspTransport` (wasmJs only) — a browser transport for `LspClient`, alongside the
  existing `StreamLspTransport` (JVM/Android) and `ChannelLspTransport` (common).
- Public ABI validation for every library, with committed dumps under `*/api/`.
- `com.aardarch:aardink-editor-web` (wasmJs), a browser bridge that mounts the editor into a DOM
  element behind a flat `AardinkWeb` API (`mount`, `getValue`, `setValue`, `patchOptions`,
  `onChange`, `setDiagnostics`, `dispose`, ...). It bundles JetBrains Mono (SIL OFL 1.1) for
  the font-less browser canvas, and comes with a compiled `@JsExport` template for the app
  module that builds the executable. See `docs/WEB_INTEGRATION.md`.
- `:sample-web`, a browser harness, and the `@aardarch/aardink-web` npm package it builds: the
  editor behind a Monaco-shaped `createEditor(...)` API, checked end to end in a real Vite app
  (`tools/vite-smoke/`). `AardinkWeb.setResourceUrl` lets a bundler serve the bundled font from
  wherever it emitted it.
- `IncrementalTokenizer.tokenizeFullCooperative` and `EditorLimits` — on a single-threaded
  host (wasmJs) documents over 64 KB are tokenized in chunks that yield to the event loop,
  and documents over 2 MB skip highlighting and folding. Both thresholds are configurable;
  Android and JVM tokenize off the UI thread and never consult them. The built-in tokenizers
  implement the chunked pass. The default delegates to `tokenizeFull`, so custom tokenizers
  need no change.

### Changed

- **Packaging.** Each library now publishes a Gradle Module Metadata root plus one artifact
  per target instead of a single `.aar`: `com.aardarch:aardink` resolves to
  `aardink-android`, `aardink-jvm` or `aardink-wasm-js` automatically. Existing Android
  consumers keep writing `implementation("com.aardarch:aardink:<version>")` unchanged — the
  mechanism is the one `kotlinx.coroutines` already relies on. A consumer that pinned the
  `.aar` classifier explicitly would need to stop doing so. Compose itself now arrives through
  Compose Multiplatform 1.12.1, which resolves to the matching AndroidX Compose artifacts on
  Android.
- The editor's text input moved from the deprecated `BasicTextField(TextFieldValue)` +
  `VisualTransformation` to `BasicTextField(TextFieldState)` + `InputTransformation` /
  `OutputTransformation`. `CodeEditorState`'s public surface is unchanged.
- `EditorThemeParser` parses with `kotlinx.serialization` instead of the Android-only
  `org.json`, which makes `:editor` compile as common Kotlin. This adds
  `kotlinx-serialization-json` as `:editor`'s only non-Compose runtime dependency.
- `LspClient` synchronises with a `Mutex` instead of `@Synchronized` / `ConcurrentHashMap` /
  `AtomicLong`, none of which exist in common Kotlin.
- `:languages` no longer applies the Compose compiler plugin — it contains no Compose code.
- The gutter sizes its line-number column by measuring the digits rather than assuming a
  fixed 0.7 em advance. The gutter is slightly narrower at the default font size.

### Known issues

- **Web: typing latency grows with document size.** Comfortable for a few hundred lines,
  noticeably laggy beyond ~1,000 lines of highlighted code (~1 s per keystroke at 5,000
  lines). Compose lays out the whole text field as one paragraph on every change. Measurements
  are in `docs/WEB_INTEGRATION.md#performance`.
- **Web: a disposed editor is not fully released** (~275 KB each), because Compose
  Multiplatform 1.12.1 offers no way to tear down a `ComposeViewport`. Reuse one editor with
  `setValue`/`updateOptions` rather than mounting one per view.
- Several browser checks (IME composition, touch selection, mobile soft keyboard, some
  browser shortcuts) have not yet been run on real devices; see the checklist in
  `docs/WEB_INTEGRATION.md`.

### Binary compatibility

- `CodeEditorLayout` gained a trailing defaulted `onRequestGoToLine` parameter. This is
  **source-compatible but not binary-compatible**: the Compose compiler encodes every
  parameter in a composable's JVM signature, so any added parameter changes it and a
  consumer compiled against an earlier build must be recompiled. That is inherent to
  adding a parameter to a `@Composable` and applies to any future one too. Recompiling
  is all that is required; no source change.

### Deprecated

- `applyFolding` — superseded by `EditorOutputTransformation`; kept for hosts that built
  their own field around it.
- `EditorTheme.fontFamily`, `EditorTheme.fontSize` and `EditorTheme.lineHeight` — these were
  never read by the editor. Provide `LocalEditorTypography` instead.

### Fixed

- Pasted or typed text now has CR LF (and a lone CR) normalised to LF. A paste from a Windows
  or browser clipboard used to leave invisible carriage returns in the document, which the caret
  could stop on, and mixed line endings. Text a host passes to `loadText` is still kept verbatim.
- An input pass that changed no text (on the web, every programmatic update of the field is
  echoed back this way) no longer advances `textVersion`. It used to schedule a second
  tokenization and report the change to listeners twice.
- `LspClient` no longer skips connection teardown when the host cancels its scope while the
  receive loop is running, which could leave pending requests waiting forever and the
  transport open.
- `LspClient.addDiagnosticsListener` / `removeDiagnosticsListener` now take effect before
  they return. Under lock contention they could previously be reordered or deferred past the
  notification they were registered for.
- `LspClient.stop()` no longer silently does nothing when its scope has already been
  cancelled.
- `CodeEditorState.loadText` now clears the text field's own undo history *after* syncing the
  field, so a freshly loaded document can no longer be undone back to the previous one.
- Tokenization no longer reads the token cache from a background thread while the main thread
  may be mutating it.
- The built-in regex tokenizers (Kotlin, TypeScript, JSON, TOML, CSS, Markdown) no longer take
  time quadratic in document size. Every rule was searched again from the cursor at every
  token, so a rule with no nearby match rescanned the rest of the document each time; one
  ~105 KB Kotlin file took over a minute on the JVM. Rule matches are now reused until the
  cursor passes them. Tokens are unchanged.
- Kotlin highlighting no longer freezes the browser. Its `class`/`object`/`interface`/`enum`
  declaration-name rule used a lookbehind, which Kotlin/wasm's regex engine evaluates at every
  position: a 3 KB file took about a second, and cost grew quadratically. Declaration names are
  now typed after scanning; 64 KB of Kotlin tokenizes in under 200 ms in the browser. Tokens
  are unchanged except in two contrived cases, a name after an `@fun` annotation or after a
  line comment ending in "fun", which were highlighted as functions and no longer are.

## [0.4.1] - 2026-09-12

### Changed

The Aardink icon in the sample app now styles better 

## [0.4.0] - 2026-09-07

### Added

- **TOML Language Support**: Built-in TOML tokenizer (`TomlTokenizer`), folding provider (`TomlFoldingProvider`), and language service (`TomlLanguageService`) supporting Android Version Catalog completions, duplicate key diagnostics, auto-closing, and formatting. Diagnostics, formatting and folding all read the document through one string-and-bracket scanner, so multiline values, inline comments after a table header, quoted keys containing `=`, and brackets or `#` inside a quoted value are treated as the data they are. Table headers are recognised only at the start of a line, so an array value such as `deps = ["a", "b"]` is highlighted as strings rather than as a header.
- **Enhanced XML & HTML Services**: Smart tag auto-closing (`<tag>` -> `</tag>`, `</` tag completion), Android XML element/attribute/value completions, duplicate attribute diagnostics, unescaped `&` warnings, and tag-depth formatting. Formatting re-indents whole-line markup only, leaving text nodes and mixed content verbatim; attribute names are scanned with quote state so `foo=` inside a value is not an attribute; element and attribute names fold case only in HTML mode; a bare `&` inside an XML attribute value is flagged the same way as one in a text node, while HTML tolerates it and treats `<script>` and `<style>` contents as raw text; and a `>` typed in text content no longer auto-closes a tag that was already closed.
- **Enhanced JSON Service**: Auto-closing (`{`, `[`, `"`), value/property completions, duplicate key diagnostics on the decoded key so two escapes naming one member are one member, smart indentation, and 4-space JSON formatting. Completions after a comma follow the innermost container, skipping strings, so an array gets value completions and an object gets property completions, and an opening `[` offers values.
- **Kotlin Language Service**: Built-in `KotlinLanguageService` with syntax error diagnostics, dot completions for stdlib methods (`map`, `filter`, `let`, `apply`), `@` annotation completions (`@Composable`, `@OptIn`, `@Preview`), `@Composable` snippets, auto-closing, and formatting. Character literals, raw strings and nested block comments are recognised as such, so a `'}'`, a brace in a comment, or whitespace inside a `"""` string is never mistaken for structure, and a lone apostrophe in a backtick-quoted name opens no character literal when formatting.
- **Extended LSP Protocol Models & Contract**: `TextEdit`, `Location`, `CodeAction`, `SignatureHelp` data models in `:editor`, and extended `LanguageService` default methods for code actions, definition lookup, references, signature help, range formatting, and rename refactorings. `ParameterInformation.labelRange` lets a provider say where a parameter sits in the signature, so `foo(Int, Int)` highlights the parameter the server meant rather than the first text match. `CompletionItem.additionalEdits` carries edits that belong with a completion but sit elsewhere in the document, so accepting an auto-import completion inserts the symbol and its import in one undo batch. `LanguageService.supportsRename` lets a host tell in advance whether a rename affordance is worth offering, since `prepareRename` and `rename` both default to declining.
- **Atomic Multi-Edit Batch Application**: `CodeEditorState.applyTextEdits()` to apply multi-edit batches in reverse offset order and record them as single undoable operations in `EditorUndoManager`. Edits sharing an offset are applied last-to-first so that, as LSP requires, several inserts at one position appear in the order the batch lists them, and the selection is carried through the batch, so a rename or an import inserted above the caret moves it along instead of leaving it at an offset that now points into unrelated text.
- **LSP Editor UI Components**: Floating `SignatureHelpPopup` for parameter hints, `CodeActionMenu` popup for quick fixes/refactorings, "💡 Quick Fix" button in `AnnotationTooltip`, and `RenameDialog` prompt for symbol renaming. A rename whose edits arrive after the document has changed is discarded rather than applied to offsets that no longer mean what the server measured. The tooltip's dismiss and quick-fix controls are real buttons with accessibility labels and minimum touch targets (as are the find panel's arrow and close buttons), the rename dialog's text field is labelled, and the signature-help popup is taken down when the host detaches the language service. A diagnostic banner and its quick-fix menu are dismissed by any edit, including the fix itself, since the diagnostic's range no longer describes the text; a rename is dropped when the document was replaced while its dialog was open; and a completion list that outlives a keystroke has its provider-supplied ranges re-addressed, so an item accepted before the fresh list arrives still replaces the right span.
- **Rename & Completion Editor Hooks**: `CodeEditorState.requestRename()` opens the rename dialog for the symbol at an offset (resolved through the language service's `prepareRename`, which also decides whether rename is offered at all - a null answer means the symbol cannot be renamed and no dialog opens), so a host app can drive the rename refactoring from its own menu; `CompletionItem.replaceRange` lets a provider state the exact range a completion replaces - used for a language server's `textEdit` / `InsertReplaceEdit` - instead of the editor guessing a token boundary.
- **CSS LSP Sample**: The sample app now demonstrates the external language-server bridge end to end with an in-process CSS language server, including initialization, document synchronization, CSS completions, hover text, and live `!important` diagnostics.
- **External Language Server Bridge Module (`:languages-lsp`)**: New published module `com.aardarch:aardink-languages-lsp` shipping `LspClient` (coroutine JSON-RPC 2.0 client built on kotlinx-serialization, with multi-listener `publishDiagnostics` fan-out, an `initialize`/`initialized` handshake helper, and generic replies to `client/registerCapability`, `window/showMessageRequest` and `workspace/configuration` server requests), `LspTransport` (stdio/socket stream framing, with whole-frame writes serialised across concurrent senders), and `LspLanguageService` — a full `LanguageService` adapter that maps completions, diagnostics, hover, formatting, code actions, definition/references, signature help, prepare-rename and rename onto LSP requests with offset ↔ line/column conversion. Completion trigger characters come from the server's own `completionProvider.triggerCharacters` via `LspLanguageService.triggerCharactersFrom()`, so member completion after `.` works; code actions that pair an edit with a command are omitted rather than applied by halves; and a closed connection stays closed, so a request sent afterwards fails instead of waiting for an answer that cannot arrive. A rename or code action whose workspace edit reaches another file, or creates, renames or deletes one, is declined outright rather than half-applied, and an action the server marked `disabled` is never offered. The transport bounds the frame size it will allocate for, and a stream failure ends the connection instead of escaping the receive loop into the host's uncaught-exception handler. `core.Location` gained optional `line`/`column` fields so a cross-file definition/reference still carries a usable position. `LspRenameSupport` and `LspLanguageService.renameSupportFrom()` carry the server's `renameProvider` capability, so a server without rename support shows no rename command and one without `prepareRename` is not asked for a range it cannot give; any failed write or read, not only an `IOException`, ends the connection, and the transport is closed exactly once; a client whose coroutine scope was cancelled fails requests instead of hanging them; an edit ending on a line past the last one reaches the end of the document, as a whole-file formatting edit intends; and a code-action request forwards only the diagnostics that actually overlap its range, not a neighbour that merely abuts it. The module's only runtime dependencies beyond `:editor` are kotlinx-serialization-json and kotlinx-coroutines-core - it pulls in no Compose of its own.

## [0.3.1] - 2026-08-29

### Fixed

- Use compileDebugKotlin classpath for Dokka fix: keep commit lists as arrays in release scripts fix: update note on binary compatibility validator and enhance LanguageService description

## [0.3.0] - 2026-06-22

### Added

- Add adaptive launcher icons and update themes for Android 12+
- Refactor Dokka configuration and add version management
- Add spotless to sample app, floss files
- Add proper mascot to the sample app

### Fixed

- Rename variable for user input in version prompt for clarity

## [0.2.0] - 2026-05-12

### Added

- Show find and replace functionality in sample editor screen
- Add dokka markdown output

### Changed

- Simplify the sample ui logo/mascot placeholder
- Fix aardink namespace typo

## [0.1.0] - 2026-05-12

### Added

- Initial version 0.1.0 of Aardink
