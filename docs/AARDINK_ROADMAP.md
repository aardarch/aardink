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

# Aardink roadmap

Status: **in progress**, drafted 2026-10-22; the table below is current as of 2026-11-22. Continues
[`AARDINK_0.6_PLAN.md`](AARDINK_0.6_PLAN.md), which stays as the record of 0.6. In §1 to §8, facts
about the code are as of `v0.6.2` (commit `3fbaef6`); §9, from 0.12 on, is as of `v0.11.0`. There
is no 1.0: each release raises the minor (§9).

## Progress

| Version | State | Notes |
| --- | --- | --- |
| 0.6.3 | Released 2026-10-22 | §4 items 1–5: function keys on the web, regex replacement groups, the semantics `setText` window, the `updateOptions` KDoc, CMP 1.12.2. CMP 1.13 waits for its stable release. |
| 0.7 | Released 2026-10-24 (0.7.1 on 2026-11-01) | §5 items 5.1 to 5.10, with the web side of each. Ledger approved 2026-10-22 (§11); what was built where it differs from it, and the symbols the ledger did not name, are in §11.1. `docs/MIGRATION_0.7.md`. JVM 706 tests, browser 648, all green. |
| 0.8 | Released 2026-11-02 | §6 items 6.1 to 6.10, with the web side of each. Ledger approved as a whole 2026-11-01 (§14); what was built where it differs, and the symbols it did not name, are in §14.1. `docs/MIGRATION_0.8.md`. JVM 798 tests, browser 726, all green; pre-push passes. Scope: document symbols, decorations, overview ruler, occurrences, linked editing, links, colour decorators, folding markers, keybindings as data, zoom. |
| 0.9 | Released 2026-11-08 | §7 items 7.1 to 7.4, with the web side of each, except TypeScript regexp literals (to §8.1) and `onTypeFormatting` (to §8.3). Ledger approved as a whole 2026-11-06 (§15), with `onOpenLink` on the web and `DismissInlineCompletion` added 2026-11-07; what was built where it differs, and the symbols it did not name, are in §15.1. `docs/MIGRATION_0.9.md`. The inline-layout spike passed every step (`docs/spikes/0.9-inline-layout.md`). Scope: the rest of LSP and `connectLanguageServer`, Markdown documentation and the detail pane, inlay hints, code lens, semantic tokens, ghost text, embedded grammars and language configuration. The two opt-in performance costs left open after release went to §8.4. |
| 0.10 | Released 2026-11-16 | §8, and what earlier versions left open (§16, "Carried in"): more built-in languages (TypeScript regexp literals among them), web provider parity (with the order of a host, a server and a base language), view options, find extras, format on type, the perf thread. Built on one branch, `feat/0.10`; ledger approved as a whole 2026-11-14 (§16); what was built where it differs is in §16.1. `docs/MIGRATION_0.10.md`. `semanticTokens/range` and the rescan of only an HTML body's changed lines were not built: moved to 0.12 (§9.1). |
| 0.11 | Released 2026-11-20 | Not in the roadmap: stylus handwriting on Android 14 and later, handed to the input method as `BasicTextField` does, and a stylus tip that taps, long-presses and scrolls as a finger does. |
| 0.12 | Built on branch `feat/0.12`; spike 2026-11-22 (`docs/spikes/0.12-diff-rows.md`), ledger approved as a whole 2026-11-22 (§17; as built in §17.1); not yet released | §9.1: the diff editor, side by side and inline, with the web's `createDiffEditor`; `semanticTokens/range` and the HTML body rescan carried in. |
| 0.13 | Planned | §9.2: models and view state. |
| 0.14 | Planned | §9.3: accessibility. |
| 0.15 | Planned | §9.4: i18n. |
| 0.16 | Planned | §9.5: the API stability policy. |

## 1. Where 0.6.2 stands

### 1.1 What the editor does

- **Rendering:** only the lines on screen are laid out, from a per-line cache; the web's
  5,000-line gates hold (mount to idle under 400 ms, key to idle p95 under 50 ms, no dropped keys;
  `tools/vite-smoke/perf.mjs`).
- **Editing:** multiple cursors, column selection, VS Code's line commands, undo grouped as Monaco
  does with `alternativeVersionId`, snippets with tab stops, choices and mirrors, find and replace
  (chunked, capped at 10,000 matches).
- **Chrome:** current-line highlight, bracket matching and pair colours, whitespace, a block
  minimap, sticky scroll from fold ranges, a diff lane (added and modified lines against
  `savedText`), squiggles and gutter dots.
- **Language:** `LanguageService` (completions, diagnostics, hover, signature help, code actions,
  definition, references, rename, format and formatRange, smartIndent, autoClose),
  `IncrementalTokenizer`, `FoldingProvider`, `DeclarativeGrammar` (a Monarch subset), nine
  built-in languages, and an LSP bridge (full-text sync, eleven requests).
- **Web:** a Monaco-shaped npm API, languages and themes registered from JavaScript (completions,
  hover and diagnostics providers).
- **Input:** the editor's own IME adapter on every platform, touch handles, a magnifier, the
  keyboard toolbar, a right-click menu.
- **Themes:** six built in, plus VS Code theme JSON (`tokenColors`).

### 1.2 What Monaco has that Aardink lacks

| Area | Monaco / VS Code | Aardink 0.6.2 |
| --- | --- | --- |
| Host events | `onDidChangeModelContent` with ranges, cursor and selection events with a reason, focus and blur, scroll, `onKeyDown` | Version counters only. `CodeDocument.addChangeListener`, `DocumentChange` and `snapshot()` are `internal` (`core/text/DocumentChange.kt:32`, `core/CodeDocument.kt:240`). The web's `onDidChangeCursor` gives a line and column. |
| Commands | `addCommand`, `addAction`, `trigger`, a command palette, customisable keybindings, chords | An internal `EditorKeyBindings` table and `EditorHostActions` callbacks that only `CodeEditorLayout` can supply (`ui/view/EditorController.kt:48`). No chords. F2, Ctrl+., F8, Ctrl+Shift+O, Ctrl+Shift+[ and ], Ctrl+J, Ctrl+L and Shift+Alt+Right are unbound. |
| Decorations | `deltaDecorations`, glyph margin, inline, line and after-content decorations, an overview ruler, view zones, content widgets | Nothing for hosts; no overview ruler. One `EditorDecorations` bag per frame (`ui/view/EditorDrawing.kt:56`). |
| Diagnostics | Multi-line ranges, `code`, `tags`, related information, a Hint severity, several marker owners, F8 navigation | `Diagnostic.range` is already an offset range and the tracker is range-based (`core/DiagnosticsTracker.kt:65`), but the web marshalling is single-line (`AardinkWeb.kt:424`). No code, tags, related information or Hint. No next-problem command. |
| Completions | The editor filters and sorts (`filterText`, `sortText`), 25 kinds, resolve, quick suggestions on letters, inline (ghost text) completions | `:editor` asks the service again on every keystroke and never reads `filterText` or `sortPriority` (`ui/CodeEditorLayout.kt:591`); only `WebLanguageService` filters. Eight XML-centric kinds. Letters only refresh an open list. |
| Navigation | Symbols and outline, breadcrumbs, go to symbol, peek, type and implementation definition, call hierarchy | None. Sticky scroll uses fold ranges. |
| Semantic UI | Occurrence highlight, linked editing of tag pairs, selection ranges, document links, colour decorators, inlay hints, code lens, semantic tokens | None. |
| Folding | Region markers, fold-all keys, LSP folding ranges, folding on indent | Provider ranges only; fold-all exists as `FoldState` methods with no key. |
| Options | Relative line numbers, rulers, indent and bracket guides, cursor style, smooth scrolling, `scrollBeyondLastLine`, wrap column and indent, wheel zoom, ligatures, `fontFamily` on the web | Fifteen `EditorOptions` fields; typography through `LocalEditorTypography` only; none of these. |
| Diff | Side-by-side and inline `DiffEditor`, deleted markers, revert hunk | The gutter lane: added and modified only. |
| Hover and docs | Markdown rendering | Plain text (documented as Markdown, not rendered). |
| Find | Regex `$1` in replacements, find in selection, preserve case, seed from the selection, F3, Alt+Enter | A regex replacement is inserted literally (`ui/CodeEditorLayout.kt:786`). None of the rest. |
| LSP | Incremental sync, cancel, shutdown, didSave, symbols, folding, highlights, colours, links, semantic tokens, inlay hints, code lens, `workspace/applyEdit`, progress, resolve | Full-text sync; eleven requests; a multi-file edit is rejected; formatting always sends `tabSize 4` and spaces (`LspLanguageService.kt:661`). |
| Languages | About 80 languages, language configuration as data, embedded languages | Nine languages. TypeScript, CSS and Markdown have no service, so no auto-close or Enter indentation. The XML completions are hard-coded Android lists (`internal/xml/XmlLanguageService.kt:777`). Monarch lacks `@nextEmbedded` and `brackets`; the only language configuration is `comments`. |
| Models | Several models per editor, `saveViewState` and `restoreViewState`, URIs, an EOL model | One document per state; no view state; `loadText` keeps CRLF while typing normalises it. |
| Accessibility and i18n | A screen-reader mode, an accessible diff, high-contrast themes, localised strings | One semantics node over 50 lines each side of the caret whose `setText` replaces the **whole document** (`ui/view/EditorInputNode.kt:205`, a data-loss risk); no semantics on popups; English only. |
| Web API | `revealLine*`, `getPosition` and `setPosition`, scroll getters, `getVisibleRanges`, `layout`, `getModel`, `fontFamily`, the full `languages.*` provider set | Missing. JavaScript providers are completions, hover and diagnostics only. `wordWrap` defaults to on (Monaco: off). |

### 1.3 Open items from 0.6

- Compose Multiplatform was pinned at 1.12.1; 1.12.2 came out on 2026-10-15. 1.13, which fixes
  the W-1 leak (about 320 KB per disposed web editor), is at `1.13.0-alpha02` (published
  2026-10-06), so the leak stays until 1.13 is stable. `checklist.mjs` W-1 already measures the
  heap over 50 mount and dispose cycles, but only reports it; a check that fails on a leak is owed
  with 1.13.
- **Chrome 155 broke the web performance gate**, which failed on `main` from 2026-10-07 to
  2026-10-21. The same 0.6.1 code measured key to idle p95 16 ms on Chrome 154.0.8037.92 and
  232 ms on 155.0.8059.39; `setValueToIdleMs` went from 18 to about 85 ms and the longest task
  grew, while key to change stayed at 3 ms. It reproduces locally on 155.0.8059.86 with the 0.6.2
  code on Compose 1.12.1 and 1.12.2 alike, so it is neither 0.6.3's changes nor the Compose bump.
  About 225 ms is the 150 ms tokenize debounce plus one long frame. Chrome fixed it upstream:
  CI's 156.0.8078.47 measures 15 ms with the same code (2026-10-21, the v0.6.3 run included), so
  the gate is unchanged; a machine still on Chrome 155 fails it locally until Chrome updates. If
  key to idle comes back on a later Chrome, trace the pass that follows the debounce.
- TypeScript regexp literals are not tokenized.
- The KDoc of `AardinkWeb.updateOptions` says a language change starts a fresh undo history; the
  code keeps it.

## 2. (Removed)

This section audited two consumer apps against the 0.6.2 API. What each version builds is
planned below on its own terms.

## 3. Principles and order

Carried over from 0.6:

- SemVer, with versions staying 0.x (there is no 1.0, §9): each release raises the minor and may
  break the API; a patch, kept for a fix that cannot wait, never does. Breaking commits carry `!`.
- Every public symbol lands in the current version's ledger (§11) with updated ABI dumps, and a
  `MIGRATION_0.x.md` entry when it breaks a consumer.
- The performance gates hold on every PR. No new runtime dependencies. `expect`/`actual` only
  under `platform/`. Never `Dispatchers.Default` or `IO` directly. No lookbehind. Never lay out
  the whole document.
- Each minor is one 0.6-style wave of about ten to fifteen PRs; the estimates below are per
  item. Performance runs as a thread through every version (§10).

Order: 0.6.3 (bugs a web host hits today), then 0.7 (host integration and the schema service),
0.8 (symbols, decorations and colours), 0.9 (LSP, ghost text, embedded grammars), 0.10 (languages,
web parity, performance), then from 0.12 one feature set per minor (§9): the diff editor, models
and view state, accessibility, i18n and the API stability policy.

## 4. 0.6.3: internal fixes, no API change

About five small PRs.

1. Function and navigation keys must not type a character on the web: guard
   `TypedTextDecoder.wasmJs.kt` on `event.key`. The JVM decoder never sees them.
2. A regex replacement expands `$1` to `$9` from the match groups (`ui/CodeEditorLayout.kt:786`).
3. The semantics `setText` edits only the exposed window of 50 lines each side of the caret,
   never the whole document (`ui/view/EditorInputNode.kt:205`).
4. Fix the stale `AardinkWeb.updateOptions` KDoc and the matching comment near line 103.
5. Compose Multiplatform 1.12.2 (the canary is green on it).

Separately, in whichever patch follows the first **stable** CMP 1.13: adopt it, make
`checklist.mjs` W-1 (which already measures the 50-cycle heap) fail on a leak, remove the "reuse one editor" guidance from the README and
`WEB_INTEGRATION.md`, and run the canary with `cmp-version` pinned to the candidate as the
pre-release gate. This is not tied to 0.7.

## 5. 0.7: host integration and the shared XML schema

About fourteen PRs. Theme: the editor becomes controllable and observable by its host, on a phone
as well as with a keyboard, and `:languages` gains a schema-driven XML service, so a host need not
write the same XML language service twice, once in Kotlin and once in TypeScript.

### 5.1 Content and selection changes (`:editor`, `:editor-web`; 2 to 3 PRs)

- A public `ContentChange(offset, deletedLength, insertedText, startLine, startColumn,
  oldEndLine, oldEndColumn, version, isReset)`, batched per edit or undo step with its
  `EditChangeKind`. `insertedText` is required: 0.9's LSP incremental sync is built on it.
- `CodeEditorState.addContentListener` and `removeContentListener`, synchronous like the internal
  listeners (editing from inside a listener is forbidden), and `contentChanges():
  Flow<List<ContentChange>>` built with `callbackFlow`, so no dispatcher is involved.
- `CodeDocument.snapshot()` becomes public (it is cached per version).
- A selection listener with every selection and a reason (`Keyboard`, `Mouse`, `Api`, `Undo`),
  and a focus state.
- Web: `onDidChangeContent` gains `changes[]` with ranges; new `onDidChangeCursorSelection`,
  `onDidFocusEditorText`, `onDidBlurEditorText`, `getPosition`, `setPosition`, `revealLine`,
  `revealLineInCenter` and `revealRange`.

### 5.2 Commands (`:editor`, `:editor-web`; 3 PRs)

- A public sealed `EditorCommand`: format, formatSelection, triggerSuggest, showHover,
  showCodeActions, rename, insertSnippet(text), goToDefinition, findReferences, foldAll,
  unfoldAll, foldLevel, toggleComment, nextProblem, previousProblem, showFind, showReplace,
  goToLine, selectAllOccurrences, undo, redo; and `CodeEditorState.execute(command)`.
- Pure text commands run in the state. UI commands are posted as a pending request that
  `CodeEditorLayout` consumes, the pattern `requestRename` and `navigateTo` already use
  (`core/CodeEditorState.kt:230`, `ui/CodeEditorLayout.kt:235`). The layout publishes
  `availableCommands` into the state from its existing `SideEffect` (`ui/CodeEditorLayout.kt:678`),
  so `canExecute` needs no knowledge of services in core. A command runs only while a layout is
  composed, as rename already does.
- The internal enum called `EditorCommand` (`ui/view/EditorKeyBindings.kt:29`) becomes
  `KeyCommand` and maps onto the public commands, so keys and hosts share one path.
- Web: `trigger(source, id)`, `addCommand(keybinding, handler)`, `addAction({ id, label,
  keybindings, contextMenuGroupId, run })` and `getAction(id)`.

### 5.3 Host items in the touch chrome (`:editor`; 1 to 2 PRs)

- `KeyboardToolbarItem.Action(icon, label, command or onClick)` beside the characters.
- `touchMenuItems` and `contextMenuItems` on `CodeEditorLayout`.
- The default touch menu gains Format and Quick Fix when a service offers them.

### 5.4 Diagnostics model (`:editor`, `:editor-web`, `:languages-lsp`; 4 PRs, breaking)

- `Diagnostic` gains `code`, `codeDescription`, `tags` (Unnecessary draws faded, Deprecated
  struck through), `relatedInformation` and a `Hint` severity (a dotted underline; three
  exhaustive `when`s to extend). `lineNumber` becomes a computed property; the tracker already
  maintains it.
- Multi-line ranges already track and draw per line. The web marshalling gets `endLineNumber`,
  and the gutter puts a dot on every spanned line.
- A tooltip on hover or tap of a squiggle. F8 and Shift+F8 cycle problems with an inline peek.
- Web: `setDiagnostics` and `onDidChangeDiagnostics` carry the new fields.

### 5.5 The completion engine in `:editor` (`:editor`, `:editor-web`; 2 to 3 PRs)

- The editor filters (prefix, then fuzzy as VS Code does) and sorts by `sortPriority` and
  `filterText`. This is safe for a service that already pre-filters.
- `LanguageService.completionsAreIncomplete`, default `true`: ask again on every keystroke, as
  today. `false`: ask once per word and filter locally, Monaco's `isIncomplete`.
- A `quickSuggestions` option so letters open the list.
- `CompletionKind` gains Monaco's kinds; the eight existing entries stay. `commitCharacters` and
  `CompletionItem.detail`.
- `WebLanguageService` drops its own filter and maps the host's `{ suggestions, incomplete }`.

### 5.6 Language composition in Kotlin (`:editor`, `:languages`, `:editor-web`; 1 PR)

- `CompositeLanguageService(primary, fallback, inheritCompletions)` in `:editor` core with the
  web's merge rules: completions are the primary's plus the fallback's minus the same kind and
  label, diagnostics merge, hover is primary then fallback, trigger characters unite,
  `supportsRename` is either, everything else comes from the fallback.
- `WebLanguageService` becomes a JSON adapter over it, so the two cannot diverge.
- `LanguageDefinition.extending(base, overlay)` in `:languages`.

### 5.7 The schema-driven XML service (`:languages`, `:editor-web`; 3 PRs)

- `XmlSchema`: elements, attributes, types and enums, required attributes, parent and child
  rules, documentation and snippets, parsed from JSON.
- `XmlSchemaLanguageService(schema, base = XmlLanguageService)`: completions by nesting,
  attributes, enum values and required-attribute snippets; hover documentation; diagnostics for
  unknown elements and attributes, bad enum values and missing required attributes.
- The hard-coded Android lists in `internal/xml/XmlLanguageService.kt:777` become the default
  schema.
- Web: `registerLanguage({ extends: 'xml', schema })`.
- Document symbols and colours for it follow in 0.8, when those interfaces exist.

### 5.8 Themes (`:editor`; 1 PR)

- `EditorThemeParser` reads Monaco `defineTheme` data (`base`, `inherit`, `rules`, `colors`) as
  well as VS Code JSON, so one theme file serves a host's web and Kotlin editors. A rule's `background` is
  honoured.

### 5.9 Keys (`:editor`; 1 PR)

- F2 rename, with a context-menu entry. Ctrl+. quick fix, with a lightbulb in the gutter.
  F3 and Shift+F3. Find seeds from the selection. Alt+Enter selects every match.

### 5.10 Correctness (`:editor`, `:languages`, `:languages-lsp`; 1 to 2 PRs, some breaking)

- `smartIndent` returns an indent string, so tabs come out when `insertSpaces` is false.
- The built-in formatters and `LspLanguageService.format(document, options)` honour `tabSize` and
  `insertSpaces`.
- `loadText` records the line ending: `EditorOptions.eol` and `document.eol`.

## 6. 0.8: structure and decoration

About sixteen PRs. Theme: the editor understands the document's structure and lets hosts draw on
it.

### 6.1 Tracked ranges (`:editor`; 1 PR, first)

- Generalise `DiagnosticsTracker.mapRange` and `mapOffset` (`core/DiagnosticsTracker.kt:68`, `:75`) into
  a `TrackedRanges<T>` document listener that diagnostics, decorations, symbols and colour
  swatches share.

### 6.2 Document symbols (`:editor`, `:languages`, `:editor-web`; 3 PRs)

- `LanguageService.documentSymbols(): List<DocumentSymbol>` with name, kind, range,
  selectionRange and children.
- A Go to Symbol picker (Ctrl+Shift+O), an optional breadcrumbs row, sticky scroll preferring
  symbols over fold ranges, `CodeEditorState.symbolAt(offset)` and `onSymbolAtCaretChange`.
- The built-in XML, HTML, JSON, TOML, Kotlin and Markdown services and the schema service provide
  them. A host can map the caret to an element of its own preview with them.

### 6.3 Decorations (`:editor`, `:editor-web`; 3 PRs)

- A `DecorationSet` on `CodeEditorState`: inline background, underline and border, whole-line
  background, gutter glyph, minimap colour and an overview-ruler lane. Ranges follow edits
  through `TrackedRanges`. All of it fits the existing per-line draw loops.
- Inline `before` and `after` text is **not** in this item: it changes a line's width and
  wrapping, and moves to 0.9 with inlay hints (§7.3).
- Web: `deltaDecorations` and `createDecorationsCollection` with Monaco's option names.

### 6.4 Overview ruler (`:editor`; 2 PRs)

- Beside the scrollbar: diagnostics, find matches, selections, diff and host decorations. The
  minimap shows the same marks.

### 6.5 Semantic helpers (`:editor`, `:languages`; 4 PRs)

- `documentHighlights`: occurrence highlight when the caret rests. The fallback word highlighter
  runs through `FindEngine.findAllCooperatively` on `state.computeDispatcher` under the
  `maxFindMatches` cap, never as a synchronous scan.
- `selectionRanges` with Shift+Alt+Right and Left to expand and shrink the selection; the fallback
  uses brackets, strings, lines and folds.
- `linkedEditingRanges`: an XML or HTML tag pair renames as you type, built in.
- `documentLinks`: URLs, and `<include component="…">` through the service, opened with
  Ctrl+click.

### 6.6 Colour decorators (`:editor`, `:languages`; 2 PRs)

- `LanguageService.documentColors()` draws a swatch before the token; a click opens a colour
  picker popup (hex, HSV, alpha); `colorPresentations` writes back as a `TextEdit`.
- Built in for `#rgb`, `#rgba`, `#rrggbb` and `#rrggbbaa` in CSS, XML, JSON and TOML, and for
  colour-typed schema attributes. A host's service supplies its own colour references.

### 6.7 Folding (`:editor`, `:languages`; 1 PR)

- Region markers as language data, folding on indent as a fallback, Ctrl+Shift+[ and ], and the
  `Ctrl+K Ctrl+0`, `Ctrl+K Ctrl+J` and `Ctrl+K Ctrl+1` to `5` chords (the first chord support).
  `FoldKind` on fold ranges.

### 6.8 Keybindings as data (`:editor`, `:editor-web`; 2 PRs)

- `EditorKeyBindings` becomes a public immutable map the host can copy and override through
  `EditorOptions.keyBindings`.
- The remaining VS Code keys: Ctrl+J join lines, Ctrl+Shift+\ jump to bracket, Ctrl+U cursor
  undo, Ctrl+K Ctrl+D skip occurrence, Ctrl+F2 change all occurrences, Shift+Alt+I carets at line
  ends, sort lines, upper and lower case, trim trailing whitespace, Ctrl+Up and Down scroll,
  Ctrl+K Ctrl+C and Ctrl+U, Shift+Alt+A block comment, Ctrl+Enter and Ctrl+Shift+Enter insert a
  line, Ctrl+L select line, a click on a line number selects it.

### 6.9 Zoom and typography (`:editor`; 2 PRs)

- `EditorOptions.fontSize` and `lineHeight` overrides, `pinchToZoom` with `onFontSizeChange`,
  Ctrl+=, Ctrl+- and Ctrl+wheel, `fontLigatures`.
- `EditorTheme.fromColorScheme(ColorScheme, dark)` for Material 3 dynamic colour.
- `CodeBlock(text, tokenizer, theme)`: a read-only highlighted composable with no input and no
  gutter, for reference screens.

### 6.10 Web (`:editor-web`; 1 PR)

- `getVisibleRanges`, `onDidScrollChange`, `getScrollTop` and `setScrollTop`, `layout()`, and
  diagnostic owners (host, language and others) in the shape of Monaco's `setModelMarkers`.

## 7. 0.9: language depth

About eighteen PRs. Theme: everything a language or an assistant can tell the editor, rendered
where Monaco renders it. This version holds the roadmap's one architectural change, inline content
that changes line layout, and it gets a spike first, as the 0.6 renderer did.

### 7.1 LSP completeness (`:languages-lsp`, `:editor-web`; 8 to 10 PRs)

- Incremental sync from `ContentChange`; `$/cancelRequest` when the coroutine is cancelled;
  `shutdown` and `exit`; `willSave` and `didSave`.
- `documentSymbol`, `foldingRange`, `documentHighlight`, `documentColor` and `colorPresentation`,
  `documentLink`, `selectionRange`, `linkedEditingRange`, `completionItem/resolve`,
  `typeDefinition`, `implementation`, `declaration`. (`onTypeFormatting` moved to §8.3.)
- `workspace/applyEdit` through an `onApplyWorkspaceEdit` host callback, so multi-file renames
  stop being rejected. `$/progress`, `logMessage` and `showMessage` to host callbacks.
  `positionEncoding` negotiation and pull diagnostics.
- The server's capabilities decide which features the editor enables.
- The npm package exposes LSP over WebSocket: `connectLanguageServer(url, languageId)`.
- Semantic tokens, inlay hints and code lens are in §7.3.

### 7.2 Markdown in hover and documentation (`:editor`; 2 PRs)

- An in-house Compose Markdown renderer (headings, code blocks highlighted by the editor's own
  tokenizers, lists, links, emphasis) for `HoverDoc`, completion `documentation` and signature
  help. No third-party renderer: the no-new-dependency rule applies.

### 7.3 The inline-layout spike and inline UI (`:editor`, `:editor-web`; 1 spike + 4 PRs)

- A spike on `VisualLineMap` and `LineLayoutCache` for content that changes a line's width or
  adds rows, kept per line and on demand.
- Then: inline `before` and `after` decorations; inlay hints through `LanguageService.inlayHints`
  and LSP; code lens rows above declarations with host commands; semantic tokens overlaying
  syntax tokens as a `TokenStore` layer; and **inline completions (ghost text)** through an
  `InlineCompletionProvider`, Tab to accept and Alt+] to cycle, with
  `registerInlineCompletionsProvider` on the web. Ghost text is the natural surface for an AI
  assistant in the host.

### 7.4 Monarch and language configuration as data (`:languages`; 3 PRs)

- `@nextEmbedded` and `@pop` embedding of another *declarative* grammar: the tokenizer's `Stack`
  gains an embedded stack and a grammar id, structural equality keeps the incremental rescan
  rule, and a grammar resolver by id is new API. Also `switchTo`, `goBack`, `$S0`, `$#`,
  `brackets` and `log`.
- `LanguageConfiguration` (brackets, autoClosingPairs, surroundingPairs, onEnterRules,
  wordPattern, folding markers, comments, indentationRules) parsed from VS Code's
  `language-configuration.json`, giving every grammar-only language auto-close, surround and
  Enter indentation.
- TypeScript regexp literals by a lookbehind-free heuristic. Not built in 0.9: moved to §8.1.

## 8. 0.10: languages, web parity, performance

About fifteen PRs.

### 8.1 Built-in languages (`:languages`; 1 PR each)

- TypeScript, CSS and Markdown get configuration-driven services, with a CSS formatter and
  Markdown table formatting. Kotlin gets hover and signature help for its static catalogue.
- Grammar-as-data languages with configurations and golden-token tests: YAML, Properties and INI,
  Shell, Python, SQL, Java, Groovy and Gradle, Dockerfile, diff, SCSS and LESS.
- CSS and JavaScript embedded in HTML needs the CSS and TypeScript grammars as data first, so it
  lands here, after §7.4.
- TypeScript regexp literals, moved from §7.4: the scanner's only state is its position, so telling
  `/` after a value from a regexp needs a hook in `RegexTokenizer` that sees the token before, and a
  measurement on wasm of what it adds to each rescan. The `regexp` theme key exists already.

### 8.2 Web provider parity (`:editor-web`; 2 PRs)

- JavaScript providers: `provideDefinition`, `provideReferences`,
  `provideDocumentFormattingEdits`, `provideSignatureHelp`, `provideCodeActions`,
  `provideRenameEdits`, `provideFoldingRanges`. (`provideDocumentSymbols` and
  `provideDocumentColors` came in 0.8; inline completions are §7.3's
  `registerInlineCompletionsProvider`.)
- `fontFamily` through `registerFont(name, url or bytes)`: wasmJs code in `:editor-web`, no
  `expect`.
- Theme keys for the minimap, scrollbar, guides and widgets; high-contrast built-in themes; a
  Monaco rule's `background` drawn (§11.1).
- `wordWrap` defaults to off as in Monaco (breaking on the web only, flagged).
- A host, then a language server, then the base language. In 0.9 a registered language's providers
  and the built-in language it `extends` both answer before a server (§15.1), so a language with
  `extends: 'json'` keeps JSON's formatter and outline ahead of a real server's. A true order needs
  `WebLanguageService`'s completion logic to tell the host's answers from the base language's.
- Alt+character keys on the web. Compose's web input takes any key that makes a character without
  Ctrl or Cmd as typing, so Alt+K in `addCommand`, and the default Shift+Alt+A and Shift+Alt+I, never
  reach the bindings; 0.9 catches only Alt+] and Alt+[ before it. Catch every Alt+character that has
  a binding, leaving AltGr (Ctrl+Alt) to typing.

### 8.3 View options and find extras (`:editor`; 3 PRs)

- Indent guides with the active one highlighted, bracket-pair guides, rulers, relative and
  interval line numbers, cursor style and blinking, smooth scrolling, `scrollBeyondLastLine`,
  `wrappingIndent`, `wordWrapColumn`, `cursorSurroundingLines`, drag and drop of a selection.
- Find in selection, preserve case, a search history, a replace preview, and an
  `onSearchWorkspace` hook for Ctrl+Shift+F.
- Format on type, moved from §7.1: `LanguageService.formatOnType` (suspend) and
  `formatOnTypeCharacters`, `EditorOptions.formatOnType`, off by default, and LSP
  `onTypeFormatting`. `attach` cannot do it alone, because the request needs `tabSize` and
  `insertSpaces` and `CodeEditorState.formattingOptions` is internal.

### 8.4 Performance (all modules; 3 PRs)

- The wasm bundle: measure skiko slimming options, document Brotli, load the font lazily, strip
  unused Compose resources; target under 3 MB gzipped.
- Android: startup measurement and a baseline profile generated in `:sample`
  (`profileinstaller` only in the sample, never in `:editor`).
- JVM and Android performance gates; today only the web is gated.
- A cheaper plain-text-with-find path above the `EditorLimits` size where highlighting turns off.
- Whole-document answers on wasm (§15.1, Still open): parse a large LSP answer in slices, ask for
  semantic tokens less often while typing or through `semanticTokens/delta`, and keep a keystroke's
  code lens cost off the document's size. Then put `perf.mjs --hints` and `--semantic` under
  `--gate`.
- An edit in an HTML `<script>` or `<style>` body rescans only its changed lines: keep each
  block's own scan, which the CSS, TypeScript and JSON tokenizers' caches of four cannot hold
  for them (§16.1, CSS and JS inside HTML; PR #45 review). Not built in 0.10: moved to 0.12
  (§9.1).

## 9. After 0.11: one feature set per minor

There is no 1.0 (decided 2026-11-22). Each release raises the minor, and each minor carries one
feature set, as 0.7 to 0.10 did. What was planned for 1.0 is spread over 0.12 to 0.16 below, the
diff editor first. A minor's ledger is drafted from its section here when its wave starts and is
approved before any code, as §11, §14, §15 and §16 were.

### 9.1 0.12: diff editor (`:editor`, `:editor-web`; about fifteen PRs)

What exists to build on, as of `v0.11.0`:

- The gutter's diff lane compares the text with the host's `savedText` through
  `SimpleDiffProvider` (`core/SimpleDiffProvider.kt`), which trims the common first and last lines
  and compares the rest line by line in place. It reports `Added` and `Modified` lines only, never
  a deletion, and only on the current side. The layout calls it directly, 300 ms after typing
  stops, so a host cannot supply its own `DiffProvider`, and the lane's green and blue are fixed
  colours (`ui/view/EditorGutterView.kt`), not theme keys.
- A real line diff, Myers's, exists only inside `MinimalEdits` (`core/edit/MinimalEdits.kt`,
  internal), which turns a format or a `replaceValue` into small edits. Past 2,000 line edits it
  gives up and returns one edit over the whole text.
- Rows inside a line's layout exist for code lenses (`Injection.rowAbove` in `ui/view/OffsetMap.kt`,
  `ui/view/InlineContent.kt`), but they carry text only, with no full-width background.
- Decorations already colour whole lines, the gutter and the overview ruler, and
  `CodeEditorState.viewport` reports the scroll. `EditorCommand.ScrollTo(top)` scrolls vertically
  only.

The work:

1. **Computing the diff.** A public diff over two texts in `:editor`, on Myers's algorithm: line
   changes with their line ranges on each side, and the changed characters inside each changed
   pair of lines. Options as Monaco's: `ignoreTrimWhitespace`, `maxComputationTimeMs`, and a size
   past which the answer is one change over the region. It runs on `computeDispatcher`, never
   while a key is handled. `MinimalEdits` then uses the same core.
2. **The gutter lane.** Markers for deleted lines; `DiffProvider` as a `CodeEditorLayout`
   parameter; the lane's colours from the theme (`ChromeColors`, read from Monaco's
   `editorGutter.addedBackground`, `modifiedBackground` and `deletedBackground`). `LineDiffKind`
   gains `Deleted`, which breaks an exhaustive `when` in a consumer.
3. **`CodeDiffEditorLayout(original, modified)`**, two `CodeEditorState`s, side by side or inline:
   - Side by side, the two sides stay line for line through filler rows: a new internal kind of
     injected row, blank and full width, with a background. Vertical and horizontal scrolling are
     shared, which needs a horizontal scroll command (`ScrollTo` with a `left`, or a command of its
     own; the ledger decides). The split can be dragged. The original is read-only unless
     `originalEditable`.
   - Inline, the deleted lines are read-only rows above the modified line, coloured by the
     original's tokens.
   - Below a width (`renderSideBySideInlineBreakpoint`) a side-by-side editor shows inline, so a
     phone gets the inline view.
   - Inserted and removed lines and characters take their backgrounds from the theme (Monaco's
     `diffEditor.insertedTextBackground`, `removedTextBackground`, `insertedLineBackground` and
     `removedLineBackground`), drawn as decorations. Each change has a mark in the overview ruler.
   - `hideUnchangedRegions` folds long unchanged runs, with a few lines of context either side and
     a row that shows them again.
   - The inline view's rows of another document's text inside a line's layout are the risk, as the
     inline layout was in 0.9. A spike (`docs/spikes/0.12-diff-rows.md`) answers it before the
     ledger is approved.
4. **Moving through changes and acting on them.** `EditorCommand.NextChange` and `PreviousChange`,
   bound to F7 and Shift+F7 in `EditorKeyBindings.Default`. Revert a change on the modified side,
   or accept it into the original when the original is editable, from the gutter and the context
   menu, each as one undo step. The host hears of each new diff and can read the current one.
5. **Web.** `createDiffEditor(container, options)` returning an `AardinkDiffEditor`:
   `getOriginalEditor()` and `getModifiedEditor()` (each an `AardinkEditor`),
   `setOriginalValue` and `setModifiedValue` until 0.13's models, `getLineChanges()`,
   `onDidUpdateDiff`, `goToDiff('next' | 'previous')` and `updateOptions` with
   `renderSideBySide`, `ignoreTrimWhitespace`, `originalEditable` and `hideUnchangedRegions`. A
   new template export, `aardinkCreateDiff`, in `Exports.kt` and `ExportsTemplate.kt` alike, which
   breaks template copies.
6. **Tests and samples.** The diff against a brute-force longest common subsequence on random
   texts, whitespace ignored, and the size fallback; Roborazzi scenes for side by side, inline,
   filler rows and folded regions; a diff page in `:sample` and `sample-web`; the smoke test
   drives `createDiffEditor`; `perf.mjs --diff` on the reference document with edits scattered
   through it, gated as the plain run is.
7. **Carried in from 0.10.**
   - `semanticTokens/range`, which needs an `:editor` symbol that gives a service the lines on
     screen (§16.1).
   - An edit in an HTML `<script>` or `<style>` body rescans only its changed lines (§8.4).

### 9.2 0.13: models and view state (`:editor`, `:editor-web`, `:languages-lsp`)

- `saveViewState()` and `restoreViewState()`: scroll, selections and folds.
- Several documents behind one layout (`setModel`), and URIs on documents.
- On the web, `getModel`, `setModel` and `createModel`, and the diff editor's `setModel` in place
  of its two values.
- A location in another file is followed: a definition, reference or rename across files opens the
  document through a host hook, where today it is left out (§16.1).
- `registerLanguage` takes `filenames` on the web, as `LanguageDefinition.fileNames` does in
  Kotlin.
- LSP over several open documents: one client, `didOpen` and `didClose` per model.

### 9.3 0.14: accessibility (`:editor`, `:editor-web`)

- A screen-reader mode (`accessibilitySupport`).
- Semantics for the popups, the gutter, diagnostics and folds.
- Live-region announcements: line and column, the problem count, the selected completion.
- An accessible diff viewer for 0.12's diff editor, as Monaco's.
- Outline decorations, so the high-contrast themes can draw the current line, the selection and
  find matches as outlines (§16.1).
- Checked by tests over the semantics tree.

### 9.4 0.15: i18n (`:editor`, `:editor-web`)

- An `EditorStrings` CompositionLocal with every string the editor shows, English by default.
- A `locale` option on the web, and strings registered from JavaScript.

### 9.5 0.16: API stability policy (all modules)

- Every public symbol reviewed against the ledgers; what is not ready is marked
  `@ExperimentalAardinkApi`.
- A deprecation policy: one minor of `@Deprecated` before a removal, still within 0.x.
- `EditorKeyBindings` and the npm `index.d.ts` declared stable, and the ABI dumps the contract.

### 9.6 Backlog, not in a version

- Compose Multiplatform 1.13, which fixes the W-1 leak, and a W-1 heap check that fails on a leak:
  in the first minor after 1.13's stable release (§4).
- On the web, a document of 60,000 lines with code lenses takes about 400 ms to settle after a key
  (§16.1).
- TypeScript colours a template literal as one string, `${}` and all; CSS reads the `.png` in
  `url(x.png)` as a class (§16.1).
- An inlay hint's label parts: their tooltips, commands and locations (§16.1).
- `signatureHelpTriggerCharacters` is accepted on the web and not used (§16.1).
- A regex tokenizer's partial pass can miss a string or comment opener that matched nothing until an
  edit more than 256 characters after it closed it; in JSON, TypeScript, CSS and HTML bodies (§17.1).
- A diff's character changes are by character, so two different lines can share scattered letters
  (`pr`, `intln(`) and a run across lines takes the next line's indentation; Monaco extends them
  to word boundaries (§17.1).
- A full highlighting pass's first slices can run one task of 65 to 100 ms on the web after
  `setValue` or a new diff original: a regex rule's first search can run far through the text before
  the pacer, which checks the clock every 256 tokens, can yield. Bound each rule's first search, or
  search in windows with the pacer between them (§17.1, `perf.mjs --diff --profile`).

## 10. Threads through every version

- The web gates stay, now with `--hints` and `--semantic` too; the JVM gate (`:editor:jvmPerfTest`)
  and the Android gate (CI's `android-perf`, a change against `main` on one emulator) arrived in
  0.10, and the bundle size is recorded per release in `tools/vite-smoke/bundle-size.json` with a
  ceiling of 2% over it.
- Coverage gaps from the audit: golden tokens for TypeScript, CSS, HTML and Markdown; screenshot
  scenes for the popups and the find panel; tests for `RenameDialog`, `GoToLineDialog` and
  `KeyboardToolbarRow`; an LSP server through the web path. All done: the LSP server in 0.9
  (`smoke.mjs` runs `connectLanguageServer` against `loopback-lsp.mjs`), the rest in 0.10 (§16.1;
  the two dialogs are tested on the desktop JVM rather than as scenes).
- Each minor keeps its API ledger in this document (or in a per-version plan once its wave
  starts) and gets a `MIGRATION_0.x.md` only when something breaks. The ledger pointer in
  `AGENTS.md` changes in the same PR as the first 0.7 API change.

## 11. API ledger for 0.7

Every line needs approval before it is written, as in the 0.6 plan's §5. Run
`./gradlew updateAbiAll` and commit the dumps with each one.

### `:editor`, new

| Symbol | Item |
| --- | --- |
| `ContentChange` data class; `CodeEditorState.addContentListener`, `removeContentListener`, `contentChanges()` | §5.1 |
| `CodeDocument.snapshot()` made public | §5.1 |
| `SelectionChangeReason`; `CodeEditorState.addSelectionListener`, `removeSelectionListener`, `isFocused` | §5.1 |
| `EditorCommand` sealed class; `CodeEditorState.execute`, `canExecute`, `availableCommands` | §5.2 |
| `KeyboardToolbarItem.Action`; `CodeEditorLayout(touchMenuItems, contextMenuItems)`; `EditorMenuItem` | §5.3 |
| `DiagnosticTag`, `DiagnosticRelatedInformation`, `CodeDescription`; `DiagnosticSeverity.Hint` | §5.4 |
| `EditorOptions.quickSuggestions`; `LanguageService.completionsAreIncomplete`; `CompletionItem.detail`, `commitCharacters`; new `CompletionKind` entries | §5.5 |
| `CompositeLanguageService` | §5.6 |
| `EditorThemeParser.parseMonaco` (or a format switch on `parse`) | §5.8 |
| `EditorOptions.eol`, `LineEnding`; `CodeDocument.eol` | §5.10 |

### `:editor`, changed (breaking)

| Symbol | Change | Item |
| --- | --- | --- |
| `Diagnostic` | `code`, `codeDescription`, `tags`, `relatedInformation` added with defaults; `lineNumber` becomes a computed property (constructor, `copy` and `componentN` change) | §5.4 |
| `DiagnosticSeverity` | `Hint` added (exhaustive `when`s in consumers break) | §5.4 |
| `LanguageService.smartIndent` | returns `String` instead of a count of spaces | §5.10 |
| `LanguageService.format`, `formatRange` | take a `FormattingOptions(tabSize, insertSpaces)` with a default | §5.10 |

### `:languages`, new

| Symbol | Item |
| --- | --- |
| `LanguageDefinition.extending(base, overlay)` | §5.6 |
| `XmlSchema` and its parser; `XmlSchemaLanguageService` | §5.7 |

### `:editor-web` and the npm package, new

| Symbol | Item |
| --- | --- |
| `onDidChangeContent` `changes[]`; `onDidChangeCursorSelection`; `onDidFocusEditorText`; `onDidBlurEditorText`; `getPosition`; `setPosition`; `revealLine`; `revealLineInCenter`; `revealRange` | §5.1 |
| `trigger`; `addCommand`; `addAction`; `getAction` | §5.2 |
| Diagnostics with `endLineNumber`, `code`, `tags`, `relatedInformation`, severity `Hint` | §5.4 |
| `quickSuggestions` option; completion answers with `incomplete`; items with `detail`, `commitCharacters` | §5.5 |
| `registerLanguage({ schema })` | §5.7 |

### `:languages-lsp`, changed

| Symbol | Change | Item |
| --- | --- | --- |
| `LspLanguageService.format`, `formatRange` | honour `FormattingOptions` | §5.10 |

### 11.1 As built

The 0.7 work follows the ledger above, with these differences and additions, each forced by the
design or found while building it:

| Ledger | Built | Why |
| --- | --- | --- |
| `Diagnostic.lineNumber` a computed property | Removed; `Diagnostic.lineIn(document)`, and the 0.6 constructor kept, deprecated, ignoring the line | A line cannot be computed from a range without the document; the deprecated constructor keeps 0.6 callers compiling |
| `smartIndent` returns `String` | `smartIndent(document, lineIndex, options: FormattingOptions): String` | Tabs or spaces need the editor's options; the same `FormattingOptions` as `format` |
| `FormattingOptions(tabSize, insertSpaces)` | With `indentUnit`, `indentOf`, `indentWidth`, `indentAfter` | So a service turns columns into the editor's whitespace without its own code |
| `EditorThemeParser.parseMonaco` | `EditorThemeParser.fromMonacoJson` | Named as the existing `fromJson`. A rule's `background` is still not drawn: `EditorTheme` has no field for it |
| `KeyboardToolbarItem.Action` | Sealed `KeyboardToolbarItem` with `Character` and `Action`; `CodeEditorLayout(keyboardToolbarItems)`; `KeyboardToolbarRow(items, onAction)` | The items need a way into the layout; a host also adds characters of its own, such as a pipe, a colon and a dollar |
| Commands as pending requests | `execute` hands UI commands to a handler the layout installs while it shows the state | A synchronous result for `execute`'s return value; the same lifetime rule |
| `EditorCommand` members | Also `Reveal(offset, center)` | The web's `revealLine` and `revealRange` reach the view only through `:editor`'s public API |
| Completions filtered by the editor always | Ordered always; filtered only for a service with `completionsAreIncomplete = false` | The web app showed a word-start filter dropping matches a service had chosen; filtering is opt-in with the flag that also stops the re-asking |
| `quickSuggestions` | Off by default | 0.6's behaviour, so an upgrade does not change how phones open the list |
| `WebLanguageService` drops its filter | Keeps it for items with `filterText`, as 0.6 documented; `incomplete: false` turns on the editor's | The npm contract says items without `filterText` are not filtered |
| Diagnostic dots on every line a range spans | One dot, on its first line | As VS Code; a range over a whole file would otherwise fill the gutter |
| — | `XmlSchema.Element`, `.Attribute`, `.Value`; `XmlSchemaLanguageService(schema, base)` | The schema's model, named under it |
| — | `LspDiagnostic.codeDescription`, `tags`, `relatedInformation`; `LspCodeDescription`, `LspDiagnosticRelatedInformation`; `LspCompletionItem.commitCharacters` | The LSP fields the new `Diagnostic` and `CompletionItem` fields come from |
| — | `WebPosition`; `WebDiagnostic.endLineNumber`, `source`, `code`, `codeHref`, `tags`, `relatedInformation`; `WebRelatedInformation`; `WebEditorOptions.quickSuggestions` | The web shapes of the approved web lines |
| — | `AardinkWeb.onSelectionChange`, `onFocusChange`, `getPosition(Json)`, `setPosition`, `trigger`, `canTrigger`, `actionLabel`, `addAction`, `removeAction`, `addCommand`, `revealLine`, `revealPosition`; `onContentChange`'s callback takes the changes | The Kotlin side of the approved npm lines |

Also changed without new API: the gutter dot shows a line's most severe diagnostic (it showed the
least); Escape closes the hover and the diagnostic banner; the find field selects its query when
the panel opens; the built-in XML completions come from a default `XmlSchema`.

## 12. (Removed)

This section listed follow-ups in a consumer's own repositories. A consumer's work is not tracked
here.

## 14. API ledger for 0.8

Drafted from §6 once 0.7 was built, and approved as a whole on 2026-11-01. What was built where
it differs, and the symbols it does not name, go in §14.1.

### `:editor`, new

| Symbol | Item |
| --- | --- |
| `DocumentSymbol(name, kind, range, selectionRange, detail, children)`; `SymbolKind` (LSP's kinds) | §6.2 |
| `LanguageService.documentSymbols(document): List<DocumentSymbol>`, default empty | §6.2 |
| `CodeEditorState.symbols` (snapshot state), `symbolPathAt(offset): List<DocumentSymbol>` | §6.2: the caret-to-preview mapping |
| `EditorCommand.GoToSymbol` (Ctrl+Shift+O); `EditorOptions.breadcrumbs` | §6.2 |
| `Decoration(range, style)`; `DecorationStyle(background, underline, border, wholeLine, gutterColor, minimapColor, overviewRulerColor, hoverMessage)`; `CodeEditorState.setDecorations(owner, list)`, `decorations(owner)`, `clearDecorations(owner)` | §6.3 |
| `EditorOptions.overviewRuler`; `EditorTheme.overviewRulerColors` or keys read by the parser | §6.4 |
| `LanguageService.documentHighlights`, `selectionRanges`, `linkedEditingRanges`, `documentLinks`; `DocumentLink(range, target)`; `CodeEditorLayout(onOpenLink)` | §6.5 |
| `EditorOptions.occurrencesHighlight`, `linkedEditing`; `EditorCommand.ExpandSelection`, `ShrinkSelection` | §6.5 |
| `LanguageService.documentColors`, `colorPresentations`; `ColorInfo(range, color)`; `EditorOptions.colorDecorators` | §6.6 |
| `FoldRange.kind`, `FoldKind`; `EditorCommand.Fold`, `Unfold` at the caret | §6.7 |
| `EditorKeyBinding(chord, command)`, a public `KeyChord`; `EditorOptions.keyBindings` (overrides and additions) | §6.8 |
| `EditorOptions.fontSize`, `lineHeight`, `pinchToZoom`, `fontLigatures`; `CodeEditorLayout(onFontSizeChange)` | §6.9 |
| `EditorTheme.fromColorScheme(ColorScheme, dark)`; `CodeBlock(text, tokenizer, modifier)` | §6.9 |

### `:languages`, new

| Symbol | Item |
| --- | --- |
| Document symbols and links in the built-in XML, HTML, JSON, TOML, Kotlin and Markdown services, and in `XmlSchemaLanguageService` | §6.2, §6.5 |
| Colours for `#rgb`-style values, and `XmlSchema.Attribute.type == "color"` in `XmlSchemaLanguageService` | §6.6 |
| `FoldingRegionMarkers` on `LanguageDefinition`; an indentation folding provider | §6.7 |

### `:editor-web` and the npm package, new

| Symbol | Item |
| --- | --- |
| `deltaDecorations`, `createDecorationsCollection` | §6.3 |
| `getVisibleRanges`, `onDidScrollChange`, `getScrollTop`, `setScrollTop`, `layout` | §6.10 |
| `setDiagnostics(list, owner)`, Monaco's `setModelMarkers` with several owners | §6.10 |
| `provideDocumentSymbols`, `provideDocumentColors` in `registerLanguage`'s providers | §6.2, §6.6 |

### Breaking

| Symbol | Change | Item |
| --- | --- | --- |
| `FoldRange` | gains `kind` (data class signature) | §6.7 |
| `EditorOptions` | gains the fields above (data class signature) | §6.3 to §6.9 |

### 14.1 As built

The 0.8 work follows the ledger above, with these differences and additions, each forced by the
design or found while building it:

| Ledger | Built | Why |
| --- | --- | --- |
| `onSymbolAtCaretChange` (§6.2) | Not built; `symbols` is snapshot state and `symbolPathAt` is read in `snapshotFlow` | One mechanism for every reader; a callback would duplicate it |
| `LanguageService.documentHighlights`, `selectionRanges`, `linkedEditingRanges` | With their return types `DocumentHighlight`, `DocumentHighlightKind`, `SelectionRange`, `LinkedEditingRanges` | LSP's shapes; the ledger named only the methods |
| `colorPresentations` | Returns `ColorPresentation(label, textEdit)` | LSP's shape |
| Colour swatch drawn before the token (§6.6) | A 3 dp bar in the colour under the text; the picker opens from `EditorCommand.ShowColorPicker`, the hover and the touch menu | A swatch before the token changes the line's layout, which §6.3 moves to 0.9 |
| `FoldingRegionMarkers` on `LanguageDefinition` | `FoldingRegionMarkers(start, end)` with presets, and `FoldingProvider.withRegionMarkers`, which the built-in definitions use | A data class cannot wrap its own folding provider; a field the host had to apply would be easy to miss |
| An indentation folding provider | `IndentationFoldingProvider(tabSize)`; the web's registered languages without a base use it | Plain text keeps none, so no existing screen changes |
| `EditorKeyBinding(chord, command)`, `KeyChord` | `KeyChord(key, ctrlCmd, shift, alt, winCtrl)` with `matches` and `parse`; `EditorKeyBinding(chord: List<KeyChord>, command: EditorCommand?)`; `EditorKeyBindings.Default` | Monaco's modifier names, so web and Kotlin bindings read alike; two strokes for chords; a null command removes a binding |
| `EditorKeyBindings` a public map | A list, platform-aware; caret movement, typing, the clipboard, line moves, Ctrl+D, F3, Alt+Enter and Shift+Alt+F stay fixed keys | Only keys that run public commands can be data; the rest have no command to bind |
| `EditorCommand.GoToSymbol`, `ExpandSelection`, `ShrinkSelection`, `Fold`, `Unfold` | Also `ShowColorPicker`, `JoinLines`, `SortLines`, `UpperCase`, `LowerCase`, `TrimTrailingWhitespace`, `JumpToBracket`, `CursorUndo`, `SkipOccurrence`, `ChangeAllOccurrences`, `CursorsAtLineEnds`, `ScrollLines`, `AddLineComment`, `RemoveLineComment`, `BlockComment`, `InsertLineBelow`, `InsertLineAbove`, `SelectLine`, `ZoomIn`, `ZoomOut`, `ZoomReset`, `ScrollTo` | Every bindable key needs a command, and the web reaches the view only through commands |
| — | A bound key whose command cannot run goes on to other handlers | Zoom keys must not take a browser's page zoom from an editor that does not zoom |
| `EditorOptions.overviewRuler`; `EditorTheme.overviewRulerColors` | `OverviewRulerColors(background, border, findMatch, selection, added, modified)`, from `editorOverviewRuler.*`, unspecified colours following the theme | One field on the theme for the whole set |
| `EditorOptions.occurrencesHighlight`, `linkedEditing`, `colorDecorators`, `fontLigatures` | All off by default except `fontLigatures`, which is on as text was drawn | An upgrade changes nothing on screen |
| `EditorTheme.fromColorScheme` | An extension on a new, empty `EditorTheme.Companion` | The function lives with Material 3 in `ui` |
| — | `FoldState.foldLevel`, `foldAt`, `unfoldAt` | The fold commands run on the fold state |
| — | `CodeEditorState.viewport` (`ViewportInfo`, in dp) | The web's scroll and visible-range APIs read the view through the state |
| Decorations' gutter glyph and minimap colour | A bar at the gutter's text edge; a mark at the minimap's edge, over its tiles | No new gutter lane, so the text never shifts; the minimap's tiles are not redrawn |
| `CodeEditorLayout(onOpenLink)` | Ctrl/Cmd+click opens a link before trying go to definition; plain URLs are found by the editor | Links need no service for URLs |
| — | Markdown and CSS get built-in (internal) services, for headings and colours | Symbols and colours come from a service |
| — | `WebDocumentSymbol`; `WebLanguageProviders.documentSymbols`, `documentColors`; `AardinkWeb.getDocumentSymbolsJson`, `getSymbolPathAtJson`, `setDecorationsJson`, `getDecorationRangesJson`, `setDiagnosticsForOwner(Json)`, `getVisibleRangesJson`, `getScrollTop`, `setScrollTop`, `getScrollHeight`, `onScrollChange`, `layout`; `WebEditorOptions.overviewRuler`, `occurrencesHighlight`, `linkedEditing`, `colorDecorators`, `breadcrumbs`, `fontLigatures` | The Kotlin side of the approved npm lines, and the web's reads of symbols, without which the caret-to-preview mapping does not reach the web |
| `setDiagnostics(list, owner)` | Owners' lists are shown with the language's own, which the web bridge collects while owners are set; owner `language` replaces them | Monaco's `setModelMarkers` model |
| — | npm `getDocumentSymbols`, `getSymbolPathAt`, `getScrollHeight`, `setModelMarkers`; two-key chords in `addCommand` and `addAction` | As above; the host's chords take their first key from the editor's own |

Also changed without new API: linked editing ends at a space or any character that is not part
of a name, and does not follow an input method's composition; the built-in XML service reads and
writes colours alpha first (`#AARRGGBB`), as Android does, and colours element text that is a
colour; the minimap marks errors, warnings and find matches.

The web performance gate's key-to-idle p95 measures about 265 ms on this machine's Chrome
156.0.8078.74, for v0.7.1 (261 ms) and for 0.8 (267 ms) alike: a local Chrome effect like the
Chrome 155 one in §1.3, not 0.8's. Every other measurement meets its target.

## 15. API ledger for 0.9

Drafted from §7 against commit `2d1186f`, and approved as a whole on 2026-11-06. What is built
where it differs, and the symbols it does not name, go in §15.1 as the work lands. Lines
under §7.3 depend on the inline-layout spike (`docs/spikes/0.9-inline-layout.md`): a part the spike
rules out moves to 0.10 and its line is struck here.

### Choices behind the ledger

| Choice | Decision | Why |
| --- | --- | --- |
| How 0.9 lands | One `feat/0.9` branch and one PR, as 0.8 | Agreed 2026-11-06 |
| Completion documentation (§7.2) | A detail pane beside the list for the selected item, on desktop and web; phones keep the one-line label | Agreed 2026-11-06. The list has one ellipsized line today, so Markdown needs a surface |
| Web provider callbacks | `aardinkRegisterLanguage` takes one `(method, text, argsJson)` dispatcher | Agreed 2026-11-06. Positional callbacks break template consumers with each provider, and §8.2 adds more |
| `$S0` and `$#` | In guards and `next`; rejected in token names with a clear error | Token names are resolved once at parse, and `tokenNames` promises every name a grammar can produce |
| `language-configuration.json` | Read as JSONC, through `allowComments` and `allowTrailingComma` | VS Code's files use both, and the JSON library already has them |
| `ExperimentalAardinkApi` | Stays in the API stability minor (§9.5) | A 0.x minor already allows a break, and the marker would be one more symbol |
| `positionEncoding` | Declares UTF-16 only | `ContentChange` columns are UTF-16; every server must accept it |
| Server capabilities (§7.1) | Decided inside `LspLanguageService`: an unsupported request returns empty without being sent. No new `supportsX` flags in `:editor` | An empty answer already turns a passive feature off. Commands stay listed, as they do for built-in services, except Rename, which keeps its `supportsRename` gate: `LspLanguageService.supportsRename` follows `LspClient.serverCapabilities` when the host passed no `serverRenameSupport` |
| `onEnterRules` actions | Honoured through `smartIndent`: `none`, `indent`, `outdent`, `appendText` and `removeText`. `indentOutdent` adds no second line: the closer stays at the opener's indentation with the caret before it, as the built-in services do today | `smartIndent` inserts one string with the caret after it, so it cannot put the caret between two lines. A new Enter hook is not worth it for 0.9 |
| `wordPattern` | Left out of `LanguageConfiguration` | Nothing would read it |

### `:editor`, new

| Symbol | Item |
| --- | --- |
| `LanguageService.inlayHints(document, range): List<InlayHint>`, default empty; `InlayHint(offset, label)` | §7.3 |
| `LanguageService.codeLenses(document): List<CodeLens>`, default empty; `CodeLens(offset, title, onClick)` | §7.3 |
| `LanguageService.semanticTokens(document): List<Token>`, default empty | §7.3 |
| `DecorationStyle.before`, `after`, `inlineColor` | §7.3 |
| `fun interface InlineCompletionProvider`; `InlineCompletion(text, range)`; `CodeEditorLayout(inlineCompletionProvider)` | §7.3 |
| `EditorCommand.AcceptInlineCompletion`, `CycleInlineCompletion(forward)`, `DismissInlineCompletion` | §7.3: a soft keyboard has no Tab or Escape. Escape reaches dismissal as an `EditorKeyBindings` entry |
| `EditorOptions.inlayHints`, `codeLens`, `semanticHighlighting`, all off by default | §7.3 |
| `LocalCodeFenceTokenizers`, a `(languageId) -> IncrementalTokenizer?` CompositionLocal, default `{ null }` | §7.2 |
| `LanguageService.resolveCompletion(item): CompletionItem`, default `item` | §7.1 |
| `FoldingProvider.requestFoldableRanges(document)`, a default suspend method calling `foldableRanges` | §7.1: a server's answer cannot be awaited in a synchronous call |
| `LanguageService.surroundingClose(typed): String?`, default null | §7.4: `:editor` has no surround feature, and `autoClose` runs after the selection is gone |

Each new `LanguageService` method also gets a merge rule on `CompositeLanguageService`. The
Markdown renderer and the detail pane are internal.

### `:languages`, new

| Symbol | Item |
| --- | --- |
| `DeclarativeTokenizer(grammar, embedded: (String) -> DeclarativeGrammar? = { null })` | §7.4 |
| Grammar data, with no new symbols: `nextEmbedded`, `switchTo`, `goBack`, `$S` and `$#` in guards and `next`, `@brackets`, `log` | §7.4 |
| `LanguageConfiguration` with its rule types, and `LanguageConfiguration.parse(json)`, which refuses lookbehind | §7.4 |
| `LanguageDefinition.withConfiguration(configuration)` | §7.4 |

### `:languages-lsp`, new

| Symbol | Item |
| --- | --- |
| `LspClient.serverCapabilities`, `shutdown()`; `DefaultClientCapabilities` | §7.1 |
| `LspClientHost`, with defaulted `applyWorkspaceEdit`, `showMessageRequest`, `showMessage`, `logMessage`, `progress`; `LspClient(host)` | §7.1 |
| `LspLanguageService.attach(state)`, `detach()`, `willSave(reason)`, `didSave(document)`, `supports(provider: String)`, where `provider` is a `ServerCapabilities` member name such as `renameProvider` | §7.1 |
| `LspLanguageService.typeDefinition`, `implementation`, `declaration` | §7.1: for a host's own menu items, with no new commands |
| `LspFoldingProvider(service)` | §7.1 |
| Overrides of `documentSymbols`, `documentHighlights`, `selectionRanges`, `linkedEditingRanges`, `documentLinks`, `documentColors`, `colorPresentations`, and of the new `inlayHints`, `codeLenses`, `semanticTokens`, `resolveCompletion` | §7.1, §7.3 |

New wire types stay internal unless a public signature needs them.

### `:editor-web` and the npm package, new

| Symbol | Item |
| --- | --- |
| `connectLanguageServer(url, languageId)`, returning a disposable; `AardinkWeb.connectLanguageServer`; a dependency on `:languages-lsp` | §7.1 |
| `registerInlineCompletionsProvider(languageId, provider)` | §7.3 |
| `editor.action.inlineSuggest.commit`, `showNext`, `showPrevious` and `hide` in `trigger` and `getAction`, for `AcceptInlineCompletion`, `CycleInlineCompletion` and `DismissInlineCompletion` | §7.3: the web reaches commands only through action ids |
| `provideInlayHints(text, range)`, `provideCodeLenses(text)`, `runCodeLensCommand(id, args)` in `registerLanguage`'s providers, answering Monaco's shapes `AardinkInlayHint { position, label }` and `AardinkCodeLens { range, command: { id, title, arguments? } }`; `WebLanguageProviders.inlayHints`, `codeLenses`, `runCodeLensCommand` | §7.3: a click comes back through the provider, because an answer is JSON and a language's service has no editor handle |
| `before`, `after` (Monaco's `{ content }`) and `inlineColor` (a CSS hex colour, in place of Monaco's `inlineClassName`) in `AardinkDecorationOptions`, for `deltaDecorations` and `createDecorationsCollection` | §7.3 |
| `WebEditorOptions.inlayHints`, `codeLens`, `semanticHighlighting` | §7.3 |
| `configuration` in `registerLanguage`'s definition; `nextEmbedded` resolved from registered grammars | §7.4 |
| `editor.onOpenLink(listener)`, returning a disposable; `AardinkWeb.onOpenLink`; `aardinkOnOpenLink` in the template. A listener gets every target; with none, `http` and `https` links open in a new tab with `noopener`, and other schemes do nothing | §7.2: added and approved 2026-11-07. Markdown links in hover and signature cards need somewhere to go, and hover text can come from a server, so the default never opens a `javascript:` URL |

### Breaking

| Symbol | Change | Item |
| --- | --- | --- |
| `aardinkRegisterLanguage` | Positional callbacks become one dispatcher; a wasm module built from the template copies the new version, npm users are unaffected | §7.1 |
| `DecorationStyle`, `EditorOptions`, `WebEditorOptions` | Gain fields (data class signatures) | §7.3 |
| `CodeEditorLayout`, `LspClient`, `DeclarativeTokenizer`, `WebLanguageProviders` | Gain a defaulted parameter (recompile) | §7.1, §7.3, §7.4 |
| `EditorCommand` | Gains members (an exhaustive `when` needs a branch) | §7.3 |
| Grammar parser | `$` in a token name is rejected; `$#==$S2`-style guards, a literal regex today, start matching | §7.4 |

### Not in 0.9

- Non-UTF-16 position encodings; `semanticTokens/delta` and `/range`; label parts and tooltips on
  inlay hints; `willSaveWaitUntil`.
- A Monaco theme rule's `background` (§11.1): `EditorTheme` has no field for it. Moved to §8.2.
- JS providers for `semanticTokens` and `resolveCompletion`: on the web these come through
  `connectLanguageServer` only, and a JS host gives `documentation` with the item. No built-in
  `inlayHints`, `codeLenses`, `semanticTokens` or `resolveCompletion`: each is tested through its
  `LspLanguageService` override, and `inlayHints` and `codeLenses` also through their web
  providers. §13's rule of a built-in and a web provider per method is waived for these in 0.9.
- `onTypeFormatting`, moved to §8.3: it needs three more `:editor` symbols.
- If the wave overruns, in this order: TypeScript regexp literals to §8.1, pull diagnostics,
  `goBack`, then multi-line ghost text and code lens.

### Notes for the build

These need no public symbol, and are kept here so they are not worked out twice:

- `resolveCompletion` reads the document through `attach`, or the last `didOpen` and `didChange`.
  The server's raw item, with its `data`, stays in an internal table beside the returned item.
- `connectLanguageServer` builds one `LspLanguageService` per editor, with a generated document URI.
- An LSP lens's `onClick` sends `workspace/executeCommand` through `LspClient.sendRequest`.
- A feature registered through `client/registerCapability`, or a server whose capabilities are
  unknown, counts as supported, as `serverRenameSupport = null` does today.
- Kotlin symbols behind an npm line, and types behind `LspClientHost`, go in §15.1 as they land,
  as §11.1 and §14.1 did.

### 15.1 As built

The 0.9 work follows the ledger above, with these differences and additions, each forced by the
design or found while building it. Everything not listed is internal: the Markdown renderer, the
detail pane, `LineLayout` and `OffsetMap`, the inline content behind hints, lenses and ghost text,
the semantic token store, the LSP sync queue and wire types. The klib dumps also gain compiler
`$stableprop` entries for some internal classes, and `ComposableSingletons` lambdas are renamed;
neither is API.

#### `:editor`

| Ledger | Built | Why |
| --- | --- | --- |
| `LanguageService.resolveCompletion(item)` | Asked for the item the detail pane shows, and on accepting an item with no `additionalEdits`. Edits the answer adds are about the text at the call, and go in after the item as an undo step of their own, unless the text has moved on. `CompositeLanguageService`: the primary's when it changes the item, else the fallback's | A server places edits in the text it was asked about, which accepting has changed |
| `FoldingProvider.requestFoldableRanges(document)` | Throwing means no answer this time and the editor keeps its ranges; an empty list opens every fold. Asked after a 200 ms pause in typing. `withRegionMarkers` forwards it | A server's `ContentModified` during typing would otherwise open every fold. The contract is in the KDoc only, with no symbol for it |
| `LanguageService.inlayHints(document, range)` | `range` is whole lines: the view and a screen of rows either side, asked 300 ms after typing pauses, and again when scrolling leaves it or a fold opens or closes. A closed fold hiding more than a screen parts it into runs, one call each, at most 8 | Scrolling inside the window asks nothing, and hidden lines are not asked about |
| `InlayHint(offset, label)` | One row: a line break or control character shows as a space. Drawn faint, with a faint background, from the theme's text colour; `EditorTheme` has no field for it | Monaco's hints are single-line too |
| `CompositeLanguageService` rule for `inlayHints` | Both, less any of the fallback's at an offset the primary has one at. The primary's show after 1 s if the fallback has not answered | A server that is starting or stuck would otherwise hold a host's hints back |
| `CodeLens(offset, title, onClick)` | `onClick: () -> Unit = {}`. A blank `title` means not known yet: the lens keeps its row, cannot be clicked, and the editor asks `codeLenses` again when it comes on screen (agreed 2026-11-07). Rows start at the line's indentation, at most 40 columns, with tabs to their stops, and are cut at 1,000 characters. A press on a lens takes no focus and opens no keyboard | `EditorMenuItem`'s shape. The blank title keeps every lens's row from the first answer, so the height is steady while titles resolve near the view, as in VS Code |
| `CompositeLanguageService` rule for `codeLenses` | Both, less any of the fallback's with the same offset and title, shown without waiting for ever on the fallback | As for hints |
| `LanguageService.semanticTokens(document)` | Drawn in line layouts only: the minimap, `tokensForLine` and `tokenVersion` stay the syntax's. An empty or failed answer clears the layer. Bracket colours stay over semantic ones. Composite: the primary's, or the fallback's when the primary has none | An empty list is all the return type can say |
| `DecorationStyle.before`, `after`, `inlineColor` | `inlineColor` colours the range's own characters and its `before` and `after`; without it they are faint (the text colour at 0.6 alpha). The caret goes in front of a hint and of `before`, behind `after` on a range that is not empty | Read from the ledger's "in place of Monaco's `inlineClassName`". Injected text must not read as document text |
| `fun interface InlineCompletionProvider` | `suspend fun provideInlineCompletions(document: CodeDocument, cursorOffset: Int): List<InlineCompletion>`. Cancellation stands in for Monaco's token; Monaco's context argument is left out | The context would be one more public type |
| `InlineCompletion(text, range)` | `range: IntRange? = null`. Without a range, the word at the caret to the line's end as in Monaco, then the rest of the line alone, then a plain insertion at the caret, where Monaco shows nothing. Text the document already has at the caret shows nothing. Accepting inserts only what shows, and leaves the caret at the end of what it added | An assistant often knows only what comes next, and a bracket after the caret is not doubled |
| `EditorCommand.AcceptInlineCompletion`, `CycleInlineCompletion(forward)` | `forward = true` by default. Tab, Alt+] and Alt+[ in `EditorKeyBindings.Default`; a binding whose command cannot run leaves its key alone, so Tab indents when no ghost text shows | Keys as data, as 0.8's |
| `EditorCommand.DismissInlineCompletion` | Added to the ledger 2026-11-07, after review. Escape is its default binding. A null binding of Escape takes the editor's Escape away whole, as in 0.8; another dismiss key is bound beside Escape. Run by key it closes an open list first and does not reveal the caret | A soft keyboard has no Escape, and a host's button needs a command |
| `LocalCodeFenceTokenizers` | `ProvidableCompositionLocal<(String) -> IncrementalTokenizer?>`, in `ui/CodeBlock.kt`. An untagged fence takes the state's tokenizer. Fences use `tokenizeLines` with an empty `previousTokens`, so they never enter a tokenizer's scan cache | The ledger named the type only |
| `LanguageService.surroundingClose(typed)` | Composite: the primary's, or else the fallback's | |
| `CodeEditorLayout(onOpenLink)` | Its default is now a no-op the layout recognises: with it, links in documentation show as text. The public `HoverDocPopup` and `SignatureHelpPopup` take no handler, so their links are text | A link that looks live and does nothing is worse than text |

Also changed without new API: the hover card stays 300 ms after the mouse leaves and while it is
over the card; folding is asked after a pause in typing; an empty or collapsed decoration follows
edits, as its KDoc said; with an inline completion provider, Up and Down end ghost text before
moving; `estimateRows` counts a wrapped lens row before the line is laid out.

#### `:languages`

| Ledger | Built | Why |
| --- | --- | --- |
| `LanguageConfiguration` with its rule types | `LanguageConfiguration(comments, brackets, autoClosingPairs, surroundingPairs, onEnterRules, indentationRules, foldingMarkers)`, with nested `CharacterPair(open, close)`, `AutoClosingPair(open, close, notInString, notInComment)`, `OnEnterRule(action, beforeText, afterText, previousLineText)`, `EnterAction(indent, appendText, removeText)`, `IndentAction` (`None`, `Indent`, `Outdent`, `IndentOutdent`) and `IndentationRules(increase, decrease, indentNextLine, unIndentedLine)` | The ledger left the rule types unnamed |
| `IndentAction.IndentOutdent` | Adds no second line: the closer stays at the opener's indentation with the caret before it, and `appendText` is ignored | As §15's choice: `smartIndent` cannot put the caret between two lines |
| `wordPattern` | Left out, as decided; `colorizedBracketPairs` is also ignored | Nothing reads them |
| `LanguageConfiguration.parse(json)` | JSONC; JavaScript regexes translated, `\p{...}` kept, only the `i` flag honoured, lookbehind refused. Enter tries `onEnterRules`, then `brackets`, then `indentationRules`, then the language's own service, then keeps the line's indentation; a rule that decides is final. A rule's regex skips lines over 256 characters. Closing pairs know strings and comments from a scan of the caret's line, and `autoCloseBefore` is not read | Julia's `increaseIndentPattern` took 1.4 s on a 512-character line on wasm; the cost is roughly cubic |
| `LanguageDefinition.withConfiguration` | The configuration decides over the language's service for Enter, closing and surround; completions are the service's. On the web it sits under the JS providers | A language with no `extends` lost Enter indentation under its providers |
| `DeclarativeTokenizer(grammar, embedded)` | As named. `DeclarativeGrammar.tokenNames` is the grammar's own, never what it embeds | Embedded grammars are known by id only until tokenizing |
| Grammar data | `$` is refused in `defaultToken` and `tokenPostfix` as well as token names, and in a bracket's token only in a grammar with a `@brackets` action, which is the only one that reads `brackets`. A non-string or empty `nextEmbedded` is refused. `@name` attributes expand as `(?:value)` | Names are fixed when the grammar is read; a `brackets` key nothing uses stays an ordinary key, as in 0.8 |
| TypeScript regexp literals (§7.4) | Not built; moved to §8.1, first on the overrun list | The scanner's only state is its position, so a rule cannot see the token before; it needs a hook in `RegexTokenizer` and a measurement on wasm |

**Monarch, where this differs from Monaco.** Checked against Monaco's `monarchCompile`,
`monarchCommon` and `monarchLexer` sources:

- A state named in `next` or `switchTo` that does not exist at run time leaves the stack as it is,
  where Monarch throws, since typed text can make the name anything. A literal unknown state in
  `switchTo` is refused at parse.
- `@brackets` on text that is no bracket gets the default token; Monarch throws. `brackets` is read
  only for a `@brackets` action; Monarch validates it always.
- `goBack` never goes back past the start of the match, and is refused in a capture group's action.
- A `!~` guard with a substitution is negated, which Monarch's dynamic branch loses. A guard's
  regex is wrapped as `^(?:...)$`, so an alternation tests all of it. Text filled into a guard's
  regex is escaped. `==` and `!=` compare case-insensitively under `ignoreCase`.
- Not supported: `$1`-style groups in guards, `next` and `nextEmbedded`, `$Sn` in a rule's regex,
  `$@attr`, and `@@`. They stay literal text. An unknown `@name` in a regex stays literal, where
  Monarch throws. A rule's third element still applies to array and `cases` actions, which Monarch
  drops.
- Embedding: an unknown id stays on the parent's rules; `@pop` outside an embedded grammar does
  nothing, and a state with no leaving rule stays embedded to the end, where Monarch throws. While
  embedded, only the parent's leaving rules run, and one whose whole-match `cases` do not leave for
  that match is looked past. A rule whose capture groups can leave counts as leaving for any match;
  if its groups then do not leave, the parent reads that text and the embedded language carries on
  after it in the state it was in. A group's `nextEmbedded` takes effect after the whole match, and
  its id reads the group's text and the state at that group. Languages nest up to 16 deep
  (`MAX_EMBEDDING_DEPTH`, internal), past which an id counts as unknown, so a grammar can embed
  itself. If every level closes on the same text, the outermost searches first and one closer leaves
  them all. Ids match exactly: Monaco's MIME-style ids are not resolved.

#### `:languages-lsp`

| Ledger | Built | Why |
| --- | --- | --- |
| `LspClientHost` | `suspend fun applyWorkspaceEdit(edit: LspWorkspaceEdit, label: String?): Boolean = false`; `suspend fun showMessageRequest(type: Int, message: String, actions: List<String>): String? = null`; `fun showMessage(type: Int, message: String)`; `fun logMessage(type: Int, message: String)`; `fun progress(token: JsonElement, value: JsonElement)` | LSP's `MessageType` as an `Int` and actions by title, so no new public types. `window/workDoneProgress/create` is acknowledged with no host member; the token first reaches the host in `progress` |
| `LspClient(host)` | `LspClient(transport, scope, host: LspClientHost = NoLspClientHost)`, the default internal. Server requests run concurrently as children of the receive loop; a host callback that throws gets a `-32603` reply. Diagnostics reach listeners one at a time, under their own lock, not always from the receive loop | A suspending host callback inside the loop would block every answer |
| `LspClient.shutdown()` | Sends `shutdown` and `exit` on the client's scope, at most 5 s for the answer and 11 s in all; a server that stopped reading is closed without `exit` | A blocking write that ignores cancellation must not hang the caller |
| `DefaultClientCapabilities` | A top-level `JsonObject` (JVM facade `LspClientCapabilitiesKt`), `dynamicRegistration: false` throughout. Declares Markdown for hover, signature help and documentation, completion resolve, `textDocument.diagnostic`, semantic tokens with `multilineTokenSupport`, `augmentsSyntaxTokens` and no overlapping tokens, folding `collapsedText`, and UTF-16 positions only. No `refreshSupport` for inlay hints, code lens or semantic tokens | Nothing in `:editor` can tell the editor to ask again without a new symbol |
| `LspLanguageService.willSave(reason)` | `enum class LspSaveReason { Manual, AfterDelay, FocusOut }`, with its wire value internal | Not named by the ledger; an enum, as `LspRenameSupport` is, so no invalid reason reaches the wire |
| `attach(state)`, `detach()` | Not suspend: called after `initialize`, on the thread that edits the state, so no edit falls between reading the text and listening. A private mirror of the text, updated from each batch, is what whole-text sync reads, never `document.text` off the editing thread. `textDocumentSync` decides what `attach`, `willSave` and `didSave` send, with registrations for sync merged in (their `documentSelector` is not read). A host's own `didOpen` and `didChange` go as in 0.8 and throw while attached; a second `didOpen` of an open document becomes a whole-text `didChange`, or nothing. Whole-text sync waits 400 ms, and any request, save or close flushes at once. A lone CR, or an edit splitting a CR LF, sends whole text until it is gone | `CodeDocument.text` is an unsynchronised read of the gap buffer |
| Every request in order behind the edits | All of `LspLanguageService`'s requests go through the same queue as the edits | A completion typed after `.` could overtake its own `didChange` |
| `supports(provider)` | Unknown capabilities count as supported. `supports("textDocumentSync")` reads the number form and `did*` registrations; an empty `{}` is false | |
| `typeDefinition`, `implementation`, `declaration` | `suspend fun (document: CodeDocument, offset: Int): List<Location>`, every target | A host's menu needs all of them, unlike `definition`'s one |
| `LspFoldingProvider(service)` | Waits for the document to open, then throws on a server error or a null answer, so the editor keeps its folds; `ContentModified`-style errors are retried after 1, 2 and 4 s. A server that will not fold, or `[]`, is empty. Its synchronous `foldableRanges` is always empty | A request sent before the handshake would fail, and an empty answer would open every fold |
| Overrides of the existing features | A flat `SymbolInformation` answer is nested by range, other files dropped. Links with no target are left out (`documentLink/resolve` is not used). Colour presentations with `additionalTextEdits` are left out. Selection ranges whose count differs from the offsets give none | What `:editor`'s types can carry |
| `resolveCompletion` | Matches the item by instance, or a copy differing only in `replaceRange` and `additionalEdits` when exactly one offered item fits. Merges `documentation`, `detail` and `additionalTextEdits`. Gated on `resolveProvider`, a registration without the flag counting | The editor re-addresses items as the user types |
| `semanticTokens` | `textDocument/semanticTokens/full` only. With capabilities unknown nothing is sent, unlike the note above: there is no legend to decode with. It waits for the document to open, and gives up with nothing sent if the handshake fails or the connection ends. Token types become theme names: `namespace`, `class`, `enum`, `interface`, `struct`, `typeParameter` go under `type.`, `parameter`, `property`, `enumMember`, `event` under `variable.`, `method`, `macro` under `function.`, `modifier` is `keyword.modifier` and `decorator` `annotation.decorator`; modifiers follow in legend order. Out-of-range tokens are dropped; malformed data empties the answer. The decode runs in 8 ms slices | A theme colours `type` and `variable` already; a Monaco rule named `class` does not match `type.class` |
| `inlayHints` | `paddingLeft` and `paddingRight` become a space. Label parts' tooltips, locations and commands are not read, and `inlayHint/resolve` is not used | |
| `codeLenses` | A lens is blank until `codeLens/resolve` answers, and only lenses within a screen of the view are resolved, read through the attached state's public `viewport` (all of them, 16 at once, with no state or no layout yet). Resolves get 500 ms, then blank lenses are returned and the rest cancelled; asking again about the same text waits for the first resolve without limit. Kept answers are compared by text | `CodeDocument.version` is internal. A slow resolve must not cost a host's lenses, which show after 1 s |
| An LSP lens's `onClick` sends `workspace/executeCommand` | Only for a command the server lists in `executeCommandProvider` or registered, or when capabilities are unknown. Other commands are its client's own, and that lens only reads. A registration counts as resolving lenses only with `resolveProvider: true` | Narrower than the note above: VS Code's "show references" is not the server's to run |
| Pull diagnostics | Driven by `LspLanguageService`, 400 ms after `didOpen` or any `didChange`; a newer edit cancels the pull in flight. With capabilities unknown it pulls until the first `MethodNotFound`. `previousResultId`, `unchanged` reports, `relatedDocuments` and `workspace/diagnostic/refresh` are not used. `diagnostics(document)` still reads only the cache | A host that re-reads diagnostics from a listener would loop if `diagnostics` pulled |
| `workspace/applyEdit` and rename | The whole edit goes to the host, which alone knows each state's thread. A rename reaching other files asks the host first, with this document's edits removed and a null label, in the caller's coroutine, and applies its own only if the host returned true; it is given up if the text moved while the server worked. A file operation on this document, or `documentChanges` that cannot be read, refuses the rename or code action. Multi-file code actions stay left out | Nothing hears when a code action is chosen, so sending one to the host would apply it unchosen |
| `positionEncoding` | A server answering anything but `utf-16` is kept to whole-text sync and pushed diagnostics; the host gets `logMessage(1, ...)`, `supports` is false for everything else, and its `workspace/applyEdit` is refused | |

Known limits: a pending change with no net change in length, delivered across `detach` and
`attach`, is not detected, since `CodeDocument.version` is internal; a host that does its handshake
through `sendRequest("initialize")` leaves `serverCapabilities` null; a server that never answers
`initialize`, with a host that never times it out, keeps folding and semantic tokens waiting.

#### `:editor-web` and the npm package

| Ledger | Built | Why |
| --- | --- | --- |
| `connectLanguageServer(url, languageId)` | npm: `Promise<{ dispose(): void }>`, resolved once asked and never rejected on the server's account. `AardinkWeb.connectLanguageServer` returns `kotlinx.coroutines.DisposableHandle`. Template exports `aardinkConnectLanguageServer(url, languageId): Int` and `aardinkDisconnectLanguageServer(id)`. One client per connection, one `LspLanguageService` per editor, under `file:///aardink/editor-N.<ext>` with a null `rootUri`. An editor counts as the language if its option or its resolved language is that id | It needs the wasm loaded; a hung server must not withhold the disposable |
| Who answers first (agreed 2026-11-07) | A registered language: its providers and the language it extends, then the server. A built-in language: the server, then the language. Enter, closing pairs and surround are always the language's, and an editor with no language service types as it does without a server. A second server adds to the first. Pushed diagnostics show after a 500 ms pause in typing | Built-in languages come second so a real server's formatter and outline are not hidden by JSON's, TOML's, Kotlin's or XML's. A registered language extending one keeps that language's ahead: a true host, server, base order is §8.2 |
| `registerInlineCompletionsProvider(languageId, provider)` | Its own call, not a dispatcher method. `AardinkWeb.registerInlineCompletionsProvider(languageId, provider: suspend (text, line, column) -> String?)` returning `DisposableHandle`; exports `aardinkRegisterInlineCompletionsProvider` and `aardinkDisposeInlineCompletionsProvider`; npm types `AardinkInlineCompletions`, `AardinkInlineCompletion`, `AardinkInlineCompletionsProvider`, and a `Promise<{ dispose(): void }>`. Several for a language are all asked, oldest first. `{ snippet }` items are skipped; `additionalTextEdits`, `command`, the `handle*` callbacks, `context` and cancellation are left out | As Monaco's: works for built-in languages and ones registered later |
| `editor.action.inlineSuggest.*` | `hide` labelled "Hide Inline Suggestion", as Monaco 0.55.1. A capturing key listener on the editor's viewport takes Alt+] and Alt+[ to the bindings, a host's `addCommand` first | Compose's web input treats every Alt+character as typing; other Alt+character bindings still never fire (§8.2) |
| `provideInlayHints`, `provideCodeLenses`, `runCodeLensCommand` | `WebLanguageProviders.inlayHints: suspend (text, rangeJson) -> String?`, `codeLenses: suspend (text) -> String?`, `runCodeLensCommand: suspend (id, argsJson) -> Unit`; the dispatcher sends `[range]` and `[id, args]`, the latter with empty text. npm `AardinkInlayHint { position, label }`, `AardinkCodeLens { range, command }`. No `resolveCodeLens`, so each lens has its command | One dispatcher, so the template does not change |
| `AardinkWeb.registerLanguage` with the dispatcher | `registerLanguage(definitionJson, dispatcher: suspend (method, text, argsJson) -> String?)`; an unknown method answers null | A new provider is one line, with no template change |
| `editor.onOpenLink(listener)` | `aardinkOnOpenLink` takes a nullable callback, null removing it. Ctrl/Cmd+click opens in the pointer-down task, a card's link in pointer-up, both with user activation; checked in Chrome with its popup blocker on | |
| `configuration` | Also the file's text as a string. npm types `AardinkLanguageConfiguration`, `AardinkOnEnterRule`, `AardinkCharacterPair`, `AardinkConfigurationRegExp`; a RegExp literal becomes `{ pattern, flags }` | |
| `nextEmbedded` resolved from registered grammars | Looked up by id as text is tokenized, so a grammar registered before or after works and one registered again is followed. An open editor is not recoloured by a registration; text already scanned changes on its next read | |
| `WebEditorOptions.semanticHighlighting` and the npm option | The plain key `semanticHighlighting`; Monaco's `'semanticHighlighting.enabled'` is not read | |
| `WebEditorOptions.inlayHints` and the npm option | Also Monaco's `{ enabled }`, where `'onUnlessPressed'` and `'offUnlessPressed'` read as on and off, as no key is watched; `null` is off | |

Also changed without new API: `AardinkWeb.revalidate` also reaches the language's diagnostics
collected beside owners' markers; the npm package's `before` and `after` decoration options, ignored
until now, are drawn.

The web bundle: `aardink-web.wasm` went from 5,201,007 to 5,422,720 bytes with
`connectLanguageServer` (+221,713, 4.3%), 1,585,294 to 1,648,018 gzipped (+62,724, 4.0%), nearly
all of it `:languages-lsp`; `skiko.wasm` did not change. As built for 0.9,
`aardink-web.wasm` is 5,537,300 bytes (1.7 MB gzipped) and `skiko.wasm` 8,648,883 (3.3 MB). Against
the npm package's 0.8.0, whose `aardink-web.wasm` is 5,051,231 bytes (1,548,756 gzipped) and whose
`skiko.wasm` is the same, 0.9 adds 486,069 bytes (9.6%), 145,013 gzipped (9.4%; both gzipped at
gzip's default level, 1,693,769 for 0.9). The 5,201,007 above was a mid-wave baseline that already
held earlier 0.9 work.

#### Still open

0.9 shipped on 2026-11-08 with these open; the performance items are carried in §8.4.

- **Timing with real hosts**, measured on 2026-11-08 with nothing else running on the machine:
  medians of three interleaved runs in headless Chrome 157. With nothing opted in, 0.9 matches 0.8.0:
  key-to-idle p95 272.1 ms against 270.3, key-to-change p95 4.1 ms against 4.3, and the same at
  100,000 lines (23.5 ms against 24.4). Two opt-in paths cost more. In both, typing at a steady pace
  stays fast (key-to-change p95 at most 6 ms, no key lost), but a key pressed while an answer lands
  waits for it. Both costs move to §8.4.
  - Semantic tokens from a server (`--semantic`): key-to-idle p95 is 484 ms, against 272 ms with them
    off, because each edit asks for the whole document again. As an answer of about 28,000 tokens
    (272 KB) lands, one task of about 73 ms runs and a frame takes about 72 ms. The decode and the
    store are paced; the JSON parse of the answer in `LspClient` is one block on wasm.
  - Hints and lenses at 60,000 lines (`--hints --lines 60000`, about 3,000 lenses): key-to-idle p95
    is 215 ms, against 20 ms at 5,000 lines, and set-value to idle is about 520 ms. Lenses are asked
    about the whole document after each pause, and `InlineContent` maps over the lens list on each
    edit. Not profiled further.
  - Above the 2 MiB analysis limit, about 62,000 lines of this Kotlin, nothing asks for hints, lenses
    or semantic tokens, so `--hints --lines 100000` has nothing to measure and stops.
  - `perf.mjs --gate` fails on this machine for 0.8.0 and 0.9 alike, on key-to-idle p95 (285 ms
    against the 50 ms gate); CI's run is the gate. One `--gate` run read key-to-change p95 at
    19.2 ms, under the 20 ms target. In seven runs 0.9 has shown two such slow keys (13.8 and
    19.2 ms) and 0.8.0 none in six, which is too few runs to tell from noise.

## 16. API ledger for 0.10

Drafted on 2026-11-14 from §8, and from what 0.7 to 0.9 left open, against commit `fafec9e`
(`main` after `v0.9.0`), and approved as a whole the same day, with the Android and bundle choices
below. What is built where it differs, and the symbols it does not name, go in §16.1 as the work
lands.

### Carried in from earlier versions

| Item | From | In 0.10 |
| --- | --- | --- |
| Colour swatch before the token, now that inline content exists; the 3 dp bar goes | §6.6, §14.1 | §8.3, no new symbol |
| A Monaco rule's `background` drawn | §11.1, §15 | §8.2 |
| A server's `inlayHint/refresh`, `codeLens/refresh`, `semanticTokens/refresh` and `diagnostic/refresh` honoured, with `refreshSupport` declared | §15.1 (`DefaultClientCapabilities`; KDoc at `LspLanguageService.kt:835`, `:878`) | One `:editor` symbol, below |
| `semanticTokens/delta` and `/range` | §15, Not in 0.9 | §8.4, internal |
| Inlay hint tooltips; padding as space, not text | §15, Not in 0.9 | `InlayHint.tooltip`; label parts' commands and locations stay out |
| `willSaveWaitUntil` | §15, Not in 0.9 | `:languages-lsp` |
| JS providers for semantic tokens and `resolveCompletion`; `resolveCodeLens`; `{ snippet }` inline completions | §15, §15.1 | §8.2 |
| Code actions that reach other files or run a server command | §15.1 (rename and `workspace/applyEdit`) | `CodeAction.onApply` |
| Links in the public `HoverDocPopup` and `SignatureHelpPopup` | §15.1 (`onOpenLink`) | A defaulted `onOpenLink` on each |
| `serverCapabilities` after a host's own `sendRequest("initialize")` | §15.1, Known limits | Internal |
| A colour for inlay hints, lenses and ghost text in the theme | §15.1 (`InlayHint`) | §8.2's theme keys |
| Token tests for TypeScript, JSON, CSS, Markdown and HTML; tests for `RenameDialog`, `GoToLineDialog`, `KeyboardToolbarRow`; Roborazzi scenes for the find panel, rename, go to line, signature help and the keyboard toolbar | §10 | Tests only |

Not carried:

- CMP 1.13 and a W-1 heap check that fails on a leak (§4). 1.13 is at `1.13.0-beta02` on
  2026-11-14 and still goes in the patch after its stable release, not tied to 0.10.

### Choices behind the ledger

| Choice | Proposed | Why |
| --- | --- | --- |
| How 0.10 lands | One `feat/0.10` branch and one PR, as 0.8 and 0.9 | |
| Grammar-as-data languages | `DeclarativeGrammar` JSON plus a `LanguageConfiguration`, both as internal Kotlin string constants in `:languages`, parsed on first use | No resource loading on wasm, no new dependency; a language that is never opened costs nothing |
| CSS and JS inside HTML | The HTML tokenizer hands `<style>` and `<script>` bodies to the built-in CSS and TypeScript tokenizers through its line state. Internal; HTML stays hand-written | Rewriting HTML, CSS and TypeScript as data first, as §8.1 assumed, would replace three tested tokenizers to reach the same colours |
| TypeScript regexp literals | `RegexTokenizer.scan` sees the previous significant token (internal); measured on wasm against the key-to-change gate | `refine` can retype a token but not split one, and `/a"b/` must be one token |
| Format on type | Asked after the typed character, off the typing path. Its edits apply only if nothing was typed since, and join that keystroke's undo step | A server's answer cannot be awaited inside a keystroke; a late edit must not land on newer text |
| A host, then a server, then the base language (§8.2) | `WebLanguageService` splits into the host's providers and its base; `servedLanguage` composes host, server, base, with `exclusive` and `inheritCompletions` applied at the first step | Internal: no symbol changes |
| Wheel and keyboard scroll animation (`smoothScrolling`) | On the scroll the editor already owns; touch flings are unchanged | |
| New options' defaults | Off, or what the editor does today, except `wordWrap` on the web (§8.2) | An upgrade changes nothing on screen, as in 0.8 |
| The wasm bundle (§8.4) | Record `aardink-web.wasm` and `skiko.wasm`, raw and gzipped, per release and fail the build above the recorded size plus 2%; measure Brotli and skiko's options; drop the 3 MB target (agreed 2026-11-14) | `skiko.wasm` alone is 3.3 MB gzipped, so 3 MB cannot be reached from this repo |
| Android performance (§8.4) | An Android emulator job in CI generates `:sample`'s baseline profile and runs the Android gate (agreed 2026-11-14). The gate compares against a baseline measured on the same runner image, not fixed milliseconds. A JVM gate runs beside it | An emulator stands in for the device checks dropped from 0.10; its timings are only comparable with itself |

### `:editor`, new

| Symbol | Item |
| --- | --- |
| `LanguageService.formatOnType(document, offset, typed: Char, options: FormattingOptions): List<TextEdit>` (suspend, default empty); `LanguageService.formatOnTypeCharacters: Set<Char>` (default empty); `EditorOptions.formatOnType` (false) | §8.3 |
| `EditorOptions.lineNumbers: LineNumberStyle` (`Absolute`, `Relative`, `Interval`; `Absolute`); `showLineNumbers` stays the switch | §8.3 |
| `EditorOptions.indentGuides`, `bracketPairGuides` (false); `rulers: List<Int>` (empty) | §8.3 |
| `EditorOptions.cursorStyle: CursorStyle` (`Line`, `Block`, `Underline`, `LineThin`, `BlockOutline`, `UnderlineThin`; `Line`), `cursorBlinking: CursorBlinking` (`Blink`, `Smooth`, `Solid`; `Blink`) | §8.3 |
| `EditorOptions.smoothScrolling` (false), `scrollBeyondLastLine` (false), `cursorSurroundingLines` (0), `dragAndDrop` (false) | §8.3 |
| `EditorOptions.wrappingIndent: WrappingIndent` (`None`, `Same`, `Indent`, `DeepIndent`; today's), `wordWrapColumn: Int?` (null) | §8.3 |
| `FindReplaceState.inSelection`, `preserveCase`; `CodeEditorLayout(onSearchWorkspace: ((query: String, options: FindEngine.Options) -> Unit)?)` | §8.3. The search history and the replace preview are internal |
| `EditorTheme.chromeColors: ChromeColors(minimapBackground, minimapSlider, scrollbarSlider, scrollbarSliderHover, indentGuide, activeIndentGuide, bracketPairGuide, ruler, widgetBackground, widgetForeground, widgetBorder, inlayHint, inlayHintBackground, codeLens, ghostText)`, unspecified colours following the theme; `EditorTheme.tokenBackgrounds: Map<TokenType, Color>` (empty) | §8.2, read by the parser from Monaco's keys |
| `EditorThemes.HighContrastDark`, `HighContrastLight` | §8.2 |
| `InlayHint.tooltip: String?` (Markdown, null) | Carried in |
| `CodeAction.onApply: (suspend () -> Unit)?` (null), run after `edits` when the action is chosen | Carried in |
| `CodeEditorState.refreshLanguageFeatures()`: ask again for diagnostics, hints, lenses, semantic tokens and folds | Carried in |
| `HoverDocPopup(onOpenLink)`, `SignatureHelpPopup(onOpenLink)`, defaulted to links as text | Carried in |
| `InlineCompletion.isSnippet: Boolean` (false), so ghost text can be a snippet with tab stops | Carried in; asked for on 2026-11-15 |

Each new `LanguageService` member gets a merge rule on `CompositeLanguageService`. `formatOnType`
follows `format`: the primary's when it has edits, else the fallback's. The characters are united.

### `:languages`, new

| Symbol | Item |
| --- | --- |
| `BuiltInLanguages.Yaml`, `Properties`, `Ini`, `Shell`, `Python`, `Sql`, `Java`, `Groovy` (also `.gradle`), `Dockerfile`, `Diff`, `Scss`, `Less`, in `all` and `LanguageRegistry.withBuiltIns()` | §8.1 |
| `LanguageDefinition.fileNames: List<String>` (empty), whole names with `*` as VS Code's `filenames` and `filenamePatterns`; `LanguageRegistry.byFileName(fileName)`, a name or a path, its names before its extension | Asked for on 2026-11-14, for `Dockerfile`, `Jenkinsfile` and `.bashrc` |
| No new symbol: services for TypeScript, CSS and Markdown from configurations, a CSS formatter, Markdown table formatting, hover and signature help for Kotlin's catalogue, CSS and JS inside HTML, TypeScript regexp literals | §8.1 |

### `:languages-lsp`, new

| Symbol | Item |
| --- | --- |
| Overrides of `formatOnType` and `formatOnTypeCharacters` from `documentOnTypeFormattingProvider` | §8.3 |
| `LspLanguageService.willSaveWaitUntil(reason: LspSaveReason): List<TextEdit>` (suspend) | Carried in |
| No new symbol: `semanticTokens/delta` and `/range`, refresh requests through `attach`'s state, code actions with a `command` or other files through `onApply` and `LspClientHost.applyWorkspaceEdit`, inlay hint tooltips, the answer parse in slices | §8.4, Carried in |

### `:editor-web` and the npm package, new

| Symbol | Item |
| --- | --- |
| `provideDefinition`, `provideReferences`, `provideDocumentFormattingEdits`, `provideDocumentRangeFormattingEdits`, `provideSignatureHelp` (with `signatureHelpTriggerCharacters`), `provideCodeActions`, `provideRenameEdits` and `resolveRenameLocation`, `provideFoldingRanges`, `provideOnTypeFormattingEdits` (with `autoFormatTriggerCharacters`), `provideDocumentSemanticTokens` (with a `legend`), `resolveCompletionItem`, `resolveCodeLens` in `registerLanguage`'s providers, in Monaco's shapes; the matching `WebLanguageProviders` fields, all through the 0.9 dispatcher | §8.2, Carried in |
| `{ snippet }` inline completion items, inserted as snippets | Carried in |
| `registerFont(name, urlOrBytes)` returning a `Promise`; `fontFamily` option; `AardinkWeb.registerFont(name, bytes)`; template export `aardinkRegisterFont` | §8.2 |
| `WebEditorOptions` and npm options in Monaco's names: `lineNumbers` (`'on'`, `'off'`, `'relative'`, `'interval'`), `rulers`, `guides: { indentation, bracketPairs }`, `cursorStyle`, `cursorBlinking`, `smoothScrolling`, `scrollBeyondLastLine`, `wrappingIndent`, `wordWrapColumn` with `wordWrap: 'wordWrapColumn'`, `cursorSurroundingLines`, `dragAndDrop`, `formatOnType`, `fontFamily` | §8.2, §8.3 |
| Built-in themes `hc-black` and `hc-light`; `defineTheme` reads the chrome keys and a rule's `background` | §8.2 |
| `editor.onSearchWorkspace(listener)`, returning a disposable | §8.3 |

### Breaking

| Symbol | Change | Item |
| --- | --- | --- |
| `wordWrap` on the web | Defaults to off, as in Monaco. `MIGRATION_0.10.md` | §8.2 |
| `EditorOptions`, `EditorTheme`, `InlayHint`, `CodeAction`, `InlineCompletion`, `FindReplaceState`, `WebEditorOptions`, `WebLanguageProviders` | Gain fields (data class signatures) | All |
| `CodeEditorLayout`, `HoverDocPopup`, `SignatureHelpPopup` | Gain a defaulted parameter (recompile) | §8.3, Carried in |
| `BuiltInLanguages.all` | Lists the new languages, so a host that offers every language offers more | §8.1 |
| `LanguageDefinition` | Gains `fileNames` (data class signature) | §8.1 |
| Template copies | Copy `aardinkRegisterFont` | §8.2 |

### If the wave overruns

In this order, each to a 0.10 patch or a later minor: Groovy, Dockerfile, diff, SCSS and LESS; the replace
preview and search history; drag and drop; `semanticTokens/range`; inlay hint tooltips; Kotlin's
hover and signature help.

### 16.1 As built

The 0.10 work follows the ledger above, with these differences and additions, each forced by the
design or found while building it.

| Ledger | Built | Why |
| --- | --- | --- |
| Format on type: edits join that keystroke's undo step | An undo step of their own, as VS Code's: the first undo takes the formatting back and keeps what was typed. Asked with a single caret only, not while an input method composes, and cancelled by the next keystroke | A server's answer comes after the keystroke's step is recorded, and VS Code's behaviour is what users expect from undo |
| `LanguageService.formatOnType(document, offset, typed, options)` | `offset` is the caret after the character and after what the editor added at once (Enter's indentation, an auto-closed bracket after the caret), as LSP's `position`. `CompositeLanguageService` asks each side only for its own characters | |
| LSP `onTypeFormatting` | `formatOnTypeCharacters` from `documentOnTypeFormattingProvider`, declared or registered, worked out once per change of either; none while the capabilities are unknown. `textDocument.onTypeFormatting` is declared in `DefaultClientCapabilities` | Read at every keystroke |
| `CodeEditorState.refreshLanguageFeatures()` | Asks again for diagnostics, symbols, folds, colours, links, semantic tokens, hints and lenses, each after its usual pause. `LspLanguageService` hears the four `workspace/.../refresh` requests while attached, through an internal listener list on `LspClient`; `diagnostic/refresh` pulls at once and then revalidates. `refreshSupport` is declared for all four | The server's requests are about the workspace, so each attached document asks again |
| `LspLanguageService.willSaveWaitUntil(reason)` | `willSaveWaitUntil(document, reason)`: the edits are offsets in `document`, read on the caller's thread, for the host to apply while the text is still that. Sent only to a server that declared or registered it (not while its capabilities are unknown), and given up after 1.5 s, as VS Code gives up on a slow save participant | Offsets need the text they are about, and the attached state cannot be read off its thread |
| `CodeAction.onApply` | Run in the layout's scope after `edits`, a failure ignored. From LSP: a server command of an action or a bare `Command` runs through `workspace/executeCommand` after the edit, if the server runs it. An edit reaching other files has no `edits` of its own: `onApply` sends the other files to the host first, and applies this document's part through the attached state only if the host made them and the text has not moved; without a state attached the action is left out | 0.9's rule that a workspace edit is never half applied, as rename keeps it |
| `InlayHint.tooltip` | Shown under a resting mouse in the hover card, with the label as its title, in place of the hover of the text under the hint. From LSP the hint's own `tooltip` (a plain string escaped as Markdown); a label part's is still not read. On the web, a hint's `tooltip`, a string or Monaco's `{ value }`, read as Markdown | |
| `HoverDocPopup(onOpenLink)`, `SignatureHelpPopup(onOpenLink)` | `onOpenLink: ((target: String) -> Unit)? = null` after `modifier`; null keeps links as text | |
| `CursorStyle.Block` | Fills the character's box at half strength, so the character shows through; VS Code redraws it in the background colour instead | The caret layer is drawn over the text, and redrawing the character there would put text drawing in the caret's layer |
| `indentGuides`, `bracketPairGuides` | Worked out for the lines on screen and 500 either side, once per change of text, tokens or window, not per frame. A bracket pair is found only within that window: a block more than 500 lines taller than the view has no bracket guide. A pair's guide runs between its lines at the smaller of its two lines' indentation. Colours from the text colour (and `bracketPairColors` at half strength) until the theme keys of §8.2 | The rendering rule: never a pass over the whole document |
| `scrollBeyondLastLine`, `cursorSurroundingLines` | Off and 0 by default, where VS Code's are on and 0; 0 keeps the one line the editor has always kept. `cursorSurroundingLines` is capped at half the view | An upgrade changes nothing on screen |
| `smoothScrolling` | Animates the scrolls the editor makes to keep the caret in view (arrows, Page Up and Down, Go to Line, `Reveal`), by more than half a line; wheel, touch and fling stay the platform's | The scroll the editor owns, as §16's choice has it |
| `wrappingIndent`, `wordWrapColumn` | `WrappingIndent.None` is the default, as the editor has always wrapped (VS Code's is `same`). Through the line layout's `TextIndent`, with a monospaced font only; an indent past half the wrap width falls back to none. `wordWrapColumn` wraps at the column or the edge, whichever comes first, VS Code's `bounded` | |
| `dragAndDrop` | Mouse only; Ctrl (Alt on macOS) held at the release copies. The drop is shown as a caret, and the result is one `executeEdits` step with the text selected | Touch drags select and scroll |
| `provideOnTypeFormattingEdits` (with `autoFormatTriggerCharacters`) | `provideOnTypeFormattingEdits(text, line, column, ch, { tabSize, insertSpaces })`, answering Monaco's `TextEdit`s (npm `AardinkTextEdit`); `autoFormatTriggerCharacters` goes with the definition, as `triggerCharacters` does, and counts only with the provider. `WebLanguageProviders.formatOnType: suspend (text, line, column, ch, optionsJson) -> String?`, through the dispatcher as `formatOnType` | The shape of the other providers, which take the text and a 1-based place rather than a model |
| `EditorTheme.chromeColors: ChromeColors(...)` | As listed, each unspecified by default and following the theme as before, so the six existing themes draw as they did. The parser also reads the older `editorIndentGuide.background` and `.activeBackground`, and `contrastBorder` where `editorWidget.border` is missing. `bracketPairGuide` is one colour for every depth (`editorBracketPairGuide.background1`), in place of the bracket-pair colours at half strength. The scrollbar colours reach desktop and the web; Android draws no scrollbar. `widgetBackground` is the cards' surface (Material's `surfaceContainerHigh`), the shades either side step from it, and `widgetForeground` is still moved towards white or black where it would not read. `widgetBorder` draws a 1 dp border on the cards, menus and find panel, not on the completion strip | A theme that sets nothing must look the same after the upgrade |
| `EditorTheme.tokenBackgrounds` | Filled from a Monaco rule's `background`, keyed by the rule's token, and found by the longest scope prefix, as token colours are; drawn as the token's span background. The empty token's `background` sets the editor's background unless `editor.background` is given, as in Monaco. A VS Code theme's `tokenColors` `background` is not read, as VS Code ignores it | |
| `EditorThemes.HighContrastDark`, `HighContrastLight` | Colours after VS Code's High Contrast and High Contrast Light, every token colour at 7:1 or more on the background (a test holds it), with a border on the cards. The current line, the selection and find matches stay fills, where VS Code draws outlines. Monaco's `base: 'hc-black'` and `'hc-light'` now fill in from these, where they took Dark+ and Light+ before | The editor draws no outline decorations; an outline style is 0.14's accessibility work (§9.3) |
| `FindReplaceState.inSelection` | Searches the text selected when the option goes on, or the caret's line with nothing selected, and again on each opening of the panel with text selected (not when the selection is the current match, which find itself selected). The place moves with edits and is shaded, under the selection. A match must lie wholly inside it; a regular expression still sees the text around it (`\b` at its edge). A chip in the panel | VS Code's behaviour with `editor.find.autoFindInSelection` at its default, `never` |
| `FindReplaceState.preserveCase` | VS Code's rules: all capitals, all small letters, else the first letter's case; part by part where the match and the replacement have as many `-` or `_` separated parts. Applies to regex replacements after `$1` is expanded. A chip shown in replace mode | |
| `CodeEditorLayout(onSearchWorkspace)` | Called by the new `EditorCommand.SearchWorkspace`, bound to Ctrl+Shift+F (Cmd+Shift+F on macOS) in `EditorKeyBindings.Default`, and by a "Search all files" button in the find panel, both only when it is set, with the selection on one line, else the find panel's query, and the panel's options. Without it the command cannot run, so the key goes to the host. On the web, `trigger('workbench.action.findInFiles')`, VS Code's id, runs it | `EditorCommand.SearchWorkspace` is not in the ledger: added at the owner's request (2026-11-14) so the key can be rebound like any other |
| Search history, replace preview (internal) | Up and Down in the find and replace fields step through the last 50 entries, each kept once, newest first, and back to what was being typed. An entry is kept on Enter, on a replace, on Ctrl+Shift+F and when the panel closes. The preview, under the replace row, is the current match's line with the match struck through and the replacement after it, 30 characters either side, read from that line only. The public `FindReplacePanel` keeps its signature and has the history; the button and the preview come through the layout | The rendering rule: a keystroke reads one line, not the document |
| Colour swatch before the token (carried in) | Two non-breaking spaces of inline content before each colour, last at its column (after a hint there), with a square in the colour drawn in them over a grey edge; the 3 dp bar under the colour is gone. A click on the square opens the colour picker for it, where VS Code opens it on hover. The colours moved from the layout into the state, which moves them with each edit as it does hints, so the room is in place in the frame the edit draws | The room is part of the line's layout, so it must move with the text before the view lays the line out again |
| `WebEditorOptions` in Monaco's names | As listed, plus `wordWrapColumn`; `guides` is two flat fields on the Kotlin side, `indentGuides` and `bracketPairGuides`, so a patch of one does not reset the other. The npm package takes Monaco's `guides: { indentation, bracketPairs }`, `rulers` as numbers or `{ column }`, `wordWrap: 'wordWrapColumn'` and `'bounded'` (both wrap at the column or the edge, whichever comes first) and Monaco's `vs` and `vs-dark` theme names. `wordWrap` is off by default | `patchOptions` merges at the top level only |
| `hc-black`, `hc-light`; `defineTheme` reads the chrome keys and a rule's `background` | The two as built-in theme keys. Monaco's `defineTheme` data now goes to the Kotlin side as it is and is read by `EditorThemeParser.fromMonacoJson`, where the npm package turned it into VS Code JSON before; VS Code theme JSON takes the chrome colours it leaves out from its base | Turning Monaco's rules into VS Code JSON lost their backgrounds |
| `editor.onSearchWorkspace(listener)` | As listed, with VS Code's Find in Files arguments, `{ query, isRegex, isCaseSensitive, matchWholeWord }`. Through a new template export, `aardinkOnSearchWorkspace`, so template copies take that one as well as `aardinkRegisterFont` | Breaking for template copies, as `aardinkRegisterFont` is |
| The JS providers of §8.2 and those carried in | All of them, in Monaco's shapes with the text and a 1-based place for the model and position, as the earlier ones have. The definition says which providers the page has (`provided`, filled in by the npm package), so the dispatcher asks only those and `supportsRename` and the folds follow them; a template's definition without it has them all. Locations without a `uri` are in this text; with one, another file's, which the editor does not open (no page hook yet; models are §9's). An edit to another file leaves a code action out and a rename undone. `provideCodeActions` gets the markers of the provider's own last diagnostics. A code action's `command` goes to `runCodeLensCommand`. `signatureHelpTriggerCharacters` is accepted and unused: the editor asks whenever the caret is in a call's arguments. Folds replace the language's own. The semantic token legend is the provider's `getLegend()` or `legend`, sent with the definition as `semanticTokensLegend`, and names types as `:languages-lsp` does; the decoder is a copy of its internal one. `resolveCodeLens` is asked for every lens without a command as the answer comes, at most 100, where Monaco asks for those in view | `:editor-web` asks through the definition and the dispatcher, with no model; a lens's place on screen is not known to the language service |
| `{ snippet }` inline completions, inserted as snippets | Through the new `InlineCompletion.isSnippet`. The ghost text shows what the snippet expands to where it lands: a placeholder as its default, a choice as its first, a variable as its value, its lines indented as the caret's line, which the shown pieces are then worked out from as for any text. Accepting replaces the whole range, typed part and all, with the snippet, as Monaco does, rather than putting in only the pieces shown, and selects its first stop; Tab then steps through the stops, and nothing is asked for until the snippet ends. One undo step. An empty `{ snippet }` is left out | Inserting a snippet from ghost text needs `InlineCompletion` to say it is one, an `:editor` symbol the ledger did not list; added at the owner's request (2026-11-15) |
| `registerFont(name, urlOrBytes)`, `fontFamily`, `AardinkWeb.registerFont(name, bytes)`, `aardinkRegisterFont` | As listed. `fontFamily` is a CSS font list, and its first name that was registered is the font, else the bundled one; a registered font is used at once, with no wait. Bytes that do not start as a TrueType or OpenType file (or collection) are refused: a development server answers a missing file with its page, which would otherwise be registered. The export returns what was wrong, as `aardinkRegisterTheme` does, and the npm package rejects with it | Found by the smoke test: Vite serves `index.html` for a path that is not there |
| A host, then a server, then the base language | As chosen: `WebLanguageService.withServer` puts the server between the host's providers and the language extended. `exclusive` and `inheritCompletions: false` still leave out only the base's completions; a server's come as before | A host's `exclusive` answer was never about a server |
| `serverCapabilities` after a host's own `sendRequest("initialize")` (carried in) | Set from the answer, as `initialize` sets it, with the same notice when its positions cannot be read; the host still sends `initialized` itself | |
| `BuiltInLanguages.Yaml`, `Properties`, `Ini`, `Shell`, `Python`, `Sql`, `Java` | Each a `DeclarativeGrammar` and a VS Code language configuration as internal string constants, behind a tokenizer that parses the grammar at its first use and a service that reads the configuration at the first key it is asked about; comments, folding and the toolbar are given in Kotlin so that neither is read before then. Folding: YAML, Shell, Python and SQL by indentation, INI by section, Java by braces, Properties none; each with its comment syntax's region markers. Token names are Monaco's. What a grammar written as data cannot do is not done: YAML block scalars (`|`, `>`) are not told from the keys and values inside them, a shell heredoc's body is shell, a Python f-string's expressions are string, and `Shell` is one language for sh, Bash, zsh and ksh | A grammar is data, read on demand; one with a stack of states and no counting of indentation |
| `BuiltInLanguages.Groovy`, `Dockerfile`, `Diff`, `Scss`, `Less` | As the seven above. Groovy is also `.gradle`, `.gvy`, `.gy` and `.gsh`; its `$` and `${}` are read in double-quoted strings, and a slashy string only after `~`, `=~` and `==~` (elsewhere a `/` is division), with `$/ /$` anywhere. Dockerfile is found by `byFileName` (`Dockerfile`, `Dockerfile.*`, `Containerfile`, `Containerfile.*`) or by the extension `.dockerfile`; what `RUN` runs is not read as shell. Diff colours inserted lines `string.inserted` and removed ones `invalid.deleted`, as no built-in theme has a colour for either, and folds each file and each hunk, a hunk by its header's line counts. SCSS and LESS have CSS's colour swatches, through `grammarLanguage`'s internal `service` | |
| `LanguageDefinition.fileNames`, `LanguageRegistry.byFileName` | As listed. The built-ins' names: Dockerfile's above, Groovy `Jenkinsfile`, Shell `.bashrc`, `.bash_profile`, `.bash_aliases`, `.bash_logout`, `.profile`, `.zshrc`, `.zprofile` and `.zshenv`, INI `.gitconfig` and `.gitmodules`. A name with no dot and no match is no language, as `Makefile` is. Not on the web: `registerLanguage` has no `filenames`, and the web has no lookup by path | |
| Services for TypeScript, CSS and Markdown; a CSS formatter; Markdown table formatting | Each from a VS Code language configuration, read at the first key that asks it something, over the language's own service where it has one. TypeScript: C's pairs with backticks, `/**` closed by ` */` and ` * ` carried on inside it, and Monaco's indentation rules. CSS: its pairs outside strings and comments; formatting puts a rule's selectors one to a line and a declaration to a line as `name: value;` (the last one given its `;`), a blank line after a top-level rule, and keeps strings, comments (a trailing one on its line) and one blank line where there were more; CR LF stays. Markdown: a `-`, `*` or `+` item, a task (as `- [ ] `) or a quote carries on when Enter ends one that has text, a numbered item does not, and `<` is not closed; formatting lines up each table's columns as its delimiter row aligns them, each at least three wide, counting characters (a wide one counts as one), and leaves tables in fenced or indented code alone. SCSS and LESS keep CSS's colours but not its formatter | CSS's formatter would read SCSS's and LESS's `//` comments and nesting as CSS |
| Hover and signature help for Kotlin's catalogue | From an internal catalogue of 24 standard-library functions (each overload's declaration, what it does and what each parameter is for), the 11 types Kotlin completes and the 8 annotations it offers after `@`, from which the completions now take their names. By name alone: a function is hovered only where it is called (`name(`, `name {`, `name<T>(`), so a `val map` is not `map`, but a function the document declares with a catalogue name is not told apart, and a name in a comment or a string is hovered as any other. Signature help is for the innermost `(` the caret is directly in (not in a lambda or brackets inside it), read from at most 4,000 characters before the caret, skipping strings and comments: the first overload with a parameter for the arguments typed so far is active, and a `vararg` takes every argument from its own on. `KotlinLanguageService`, a public object, gains the two overrides in its ABI dump | |
| CSS and JS inside HTML | Through the scan's constructs, not a line state: XML's scanner is now an internal `MarkupTokenizer`, behind `XmlTokenizer` and `HtmlTokenizer` alike (both public objects keep their shape). In HTML, a `<style>` body is CSS and a `<script>` body JavaScript, or JSON for a JSON `type` (an import map's and `speculationrules` included), or plain text for any other type, such as a template's; a self-closed one has none. A body runs to `</style` or `</script` in any case, then whitespace, `/` or `>`, as in a browser, or to the end without one. The opening tag and the body are one construct, so a rescan never takes the body as in step on its own: an edit to the tag recolours it, and an edit in it rescans the whole body (only its changed lines is §8.4). The embedded scans are not kept in the CSS, TypeScript and JSON tokenizers' own caches of four (an internal `RegexTokenizer.tokenizeEmbedded`); a cooperative pass yields inside a body too. A script's `type` is read attribute by attribute, never from inside another attribute's value. SCSS and LESS colours skip `//` comments, which CSS does not have. XML has no embedded languages. HTML's service already read `<script>` and `<style>` as raw text; its tag folding still reads a `<` in them as markup | Following the constructs is how the scanner already rescans; a line state would have been a second mechanism |
| TypeScript regexp literals | An internal hook in `RegexTokenizer`, `contextual`, asked about each token the rules find when a tokenizer opts in, with the last token before it that is not a comment: it may take a longer token, or a shorter one, in its place. TypeScript takes a `/` as a regular expression literal (Monaco's `regexp`, coloured as a string) after an operator, an opening bracket, `,`, `;`, `:`, `}`, a keyword other than `this`, `super`, `true`, `false`, `null` and `undefined`, or nothing; as division after a name, a number, a string, `)`, `]`, `++` or `--`. A literal ends at its first `/` outside a class, on its line, with its flags. An operator run with a `/` in it (`=/re/`) is cut before the `/`, except `</`, and nothing after a lone `>` is a literal, so JSX tags read as before. A rescan gets back in step only on a token with the old one's type and text, so an edit before a `/` re-reads it. Measured on wasm in headless Chrome on 5,000 lines, three interleaved runs each way: a key's rescan p95 7.2 to 7.6 ms with it against 7.4 to 7.7 ms without, a full scan 461 ms against 451 ms (median), within the runs' noise | |
| Alt+character keys | Every key with Alt and a letter, a digit or punctuation, with or without Shift, read by `KeyboardEvent.code` and run by the host's actions, then the editor's bindings, where only Alt+] and Alt+[ were before. Not with Ctrl or Meta held, so AltGr types | Compose's web input types such a key and never hands it over |
| The wasm bundle (§8.4) | `tools/vite-smoke/bundle-size.mjs` measures what the npm package loads, raw, gzipped and with Brotli, and fails when `aardink-web.wasm` or `skiko.wasm` is more than 2% over the gzipped size in `bundle-size.json` (the `.mjs` files that load them are reported, not gated: each export grows them a little); in CI after the smoke test, and in pre-push. Recorded for 0.10: `aardink-web.wasm` 5,756,284 bytes, 1,769,514 gzipped, 1,292,372 Brotli; `skiko.wasm` 8,648,883, 3,358,368, 2,624,012; everything 15.0 MB, 5.2 MB, 4.0 MB. Brotli is 24% smaller than gzip, and `WEB_INTEGRATION.md` says to serve it. Skiko has no size options in CMP 1.12; `wasm-opt -Oz` over the prebuilt `skiko.wasm` saved 1.0% gzipped (35 KB) and was left out, and it has no debug sections to strip. The font: an editor in a registered font no longer asks for the bundled one. Unused resources: the package no longer ships `skikod8.mjs` (Skiko for the d8 shell, 502 KB) or `js-reexport-symbols.mjs` (the webpack bundle's, 119 KB), which nothing in it imports, nor the empty `composeResources` folders of the modules without resources | The 3 MB target was dropped for the ceiling (§16's choice) |
| A JVM gate beside the web one (§8.4) | `EditorPerfTest` in `:editor`'s `jvmTest`, tagged `perf`: `jvmTest` leaves it out and `./gradlew :editor:jvmPerfTest` runs it alone, in CI after the unit tests, writing `editor/build/perf-jvm.json`. On the web gate's 5,000-line reference document, in `runComposeUiTest` with a 1000 × 600 dp `CodeEditorLayout` and the built-in Kotlin language, after a warm-up mount and 20 keys: mount to idle (gate 400 ms), key to idle p50 and p95 for 30 keys in the middle of the text (gate 50 ms at p95), `replaceText` to idle, `FindEngine.findAll` (gate 50 ms) and a full tokenize (gate 300 ms), each with a target as `perf.mjs` has. On the dev machine (JDK 21): mount 71 ms, key p50 9.9 ms and p95 13.5 ms, set value 20 ms, find 2.4 ms, tokenize 65 ms. Not in pre-push, whose machine is busy with the rest | Fixed limits as on the web: the JVM's timings on a CI runner are steady enough, unlike an emulator's |
| Android: a baseline profile in `:sample`, startup measured, an emulator gate (§8.4, §16's choice) | A new `:sample-benchmark` module (`com.android.test`, androidx.benchmark 1.5.0 and its `androidx.baselineprofile` plugin, which takes AGP 9.5.1 as it is). Its journey starts the app, opens the Kotlin sample, taps into the editor, types three times through `input text` and scrolls down and back. `BaselineProfileGenerator` collects `:sample`'s profile and startup profile over it, committed in `sample/src/release/generated/baselineProfiles/` (23,872 rules, 3,757 of them Aardink's, from an API 35 emulator); `:sample` gets `profileinstaller` and the plugin, `:editor` neither. `SampleBenchmarks` measures a cold start (`StartupTimingMetric`, 10 runs) and the journey's frames (`FrameTimingMetric`, 5 runs; with no startup mode, as COLD kills the app between the setup and what is measured), compiled with the profile when there is one. CI's `android-perf` job, on pull requests only, checks out main beside the change and runs both on one API 34 emulator, main first (`tools/android-perf/run.sh`); `tools/android-perf/compare.mjs` fails when the change's median time to initial display is over main's by 15% plus 30 ms, or its p90 frame CPU time by 20% plus 2 ms, and only records while main has no `:sample-benchmark`. The job also generates the change's profile, uploaded beside the results. On the headless API 35 emulator here: cold start 2,168 ms, frame p90 86 ms, software rendering, comparable only with the same emulator. Not in pre-push | A baseline from the same runner, measured in the same job (owner, 2026-11-15) |
| A cheaper plain-text-with-find path above the `EditorLimits` size (§8.4) | Measured, and nothing built: above `plainTextFallbackChars` the editor already skips highlighting, folding and every language feature, and find already runs through `FindEngine.findAllCooperatively`, 200 ms after typing pauses, yielding every 64 K characters and stopping at `maxFindMatches`. On 100,000 lines (3.3 MB) in headless Chrome: key to idle p95 35 ms (34 ms on the 5,000-line document), mount 218 ms, set value 217 ms | The path was there already; the numbers show it costs what the reference document does |
| Parse a large LSP answer in slices (§8.4) | `LspClient` parses each message once, where it parsed every response twice (once for its id, once to decode it). On wasm, a message of 64 K characters or more goes through an internal `parseJsonInSlices`, which lets the page paint every 8 ms as the token decode already did. Its tree is the one `parseToJsonElement` gives (a test holds it over escapes, numbers, duplicate keys and nesting; deeper than 200 levels is left to `parseToJsonElement`). `perf.mjs --semantic` on the reference document: the first answer (27,848 tokens, 272 KB) now lands with no long task (117 ms before) and a longest frame of 18.5 ms (107 ms) | |
| `semanticTokens/delta` and `/range` | Delta only. `semanticTokens.requests.full.delta` is declared. For a server that declares or registers `full: { delta: true }`, every request after a whole answer with a `resultId` is `full/delta` from it. Its edits are applied in order of their starts; edits that do not fit, an error, or an answer with neither edits nor data ask for the whole answer again, and the last answer is forgotten at `didOpen`, `didClose` and a `semanticTokens/refresh`. The `perf.mjs` server answers deltas: 22 of the 23 requests on the reference document were deltas. Range is left to a 0.10 patch | A range request needs the lines on screen, which `LanguageService.semanticTokens(document)` is not given; that needs a new `:editor` symbol (owner, 2026-11-15, as the overrun order allows) |
| Keep a keystroke's code lens cost off the document's size (§8.4) | Profiled first, with a new `perf.mjs --profile` (a CPU profile of the keys, read from a development build for the names). At 60,000 lines with hints and lenses, the cost was highlighting, not lenses. `DeclarativeTokenizer`'s partial pass read the whole text twice more for its line starts. The editor gave up partial passes for the document's life after one pass over 50 ms, which the first passes after a mount always were while the browser compiled, so every key highlighted the whole document again. Now the line starts come from the old ones and the edit (internal `lineStartsAfter`), and the editor gives up only after three slow passes in a row. Busy time over 10 keys at 60,000 lines went from 6.6 s to 1.8 s, and hints at p95 on the reference document from 84 ms to 30 ms. At 60,000 lines key to idle stays near 400 ms: after the 300 ms pause, the answer for about 3,000 lenses is decoded within one frame of more than 34 ms, and the measure waits for the frames to settle. Moving lenses with an edit was under 1% of the profile and is unchanged | 0.9's note blamed lenses without a profile |
| `perf.mjs --hints` and `--semantic` under `--gate` | Both in CI with `--gate`. `--hints` is held to the plain run's limits. `--semantic` is held to its landing frame and longest task (gate 50 ms each) and to key to idle; its mount, which includes connecting to the server, is reported but not gated. On the dev machine: hints p95 35 ms; semantic p95 35 ms, landing frame 25 ms, no long task | |
| Token tests for TypeScript, JSON, CSS, Markdown and HTML; tests for `RenameDialog`, `GoToLineDialog`, `KeyboardToolbarRow`; Roborazzi scenes for the find panel, rename, go to line, signature help and the keyboard toolbar (carried in) | Token tests in `:languages` for each language's text and Monaco scope, in order: 23 tests over five files (`TypeScriptTokenizerTest` and the rest). UI tests on the desktop JVM: `ChromeDialogsUiTest` covers both dialogs. For rename, the current name in the field and the question, Rename offered only for a new name that is not blank, the name trimmed, and Cancel. For go to line, the lines there are, digits only and at most seven, a line outside the document refused, and Cancel. `KeyboardToolbarRowUiTest` covers each button, undo and redo following `canUndo` and `canRedo`, the host's items after the quick characters, and the row hidden with no keyboard. New scenes are `signature-help` (two overloads, the active parameter and its documentation) and `keyboard-toolbar` (`BottomFixed`). The find panel's new chips and its replace preview are in the existing `find-replace` scene, and rulers are in `guides` and `high-contrast`. Not as scenes: rename and go to line, which are `AlertDialog`s with a field whose caret keeps Compose from ever being idle under Robolectric, even with the clock stopped; and the inlay hint tooltip, whose resting mouse Robolectric does not deliver. The UI tests above, and `InlayHintsUiTest`'s tooltip test, cover them. Found while writing the token tests and left as they are: TypeScript's template literal is one string, `${}` and all, where Monaco colours what is inside; and in CSS, `url(x.png)` reads `.png` as a class | The dialogs' and tooltip's scenes cannot be taken in the sample's Robolectric tests |

## 17. API ledger for 0.12

Drafted on 2026-11-22 from §9.1, against `feat/0.12` after the spike (`docs/spikes/0.12-diff-rows.md`,
every step passed), and approved as a whole the same day. What is
built where it differs, and the symbols it does not name, will go in §17.1.

The spike's code is internal and stays so: the diff computer in `core/diff/`, rows with a
background and token spans on the inline layout, a per-line table of a diff's rows on the state, and
`DiffRows`, which builds them.

### Choices behind the ledger

| Choice | Proposed | Why |
| --- | --- | --- |
| How 0.12 lands | One `feat/0.12` branch and one PR, as 0.8 to 0.10 | |
| The diff | Myers's, in linear space, over interned lines, then over the characters of each change of at most 10,000 characters a side. A time limit (5 s, as Monaco's) ends the search with the rest as one change per region, and says so | Optimal and complete (the spike's property test). Monaco's newer algorithm also finds moved code; that is not in 0.12 |
| When it runs | On `computeDispatcher`, 300 ms after typing stops on either side, never while a key is handled. Until then an edit moves the rows with the lines | As the gutter's lane does today |
| Side by side | Filler rows keep the two sides aligned by rows, so soft wrap and a split of any width line up (D5). One scroll position serves both sides (D3) | Monaco aligns under wrap too; nothing maps positions across |
| Inline | The original's deleted lines are rows above the modified line, in the original's token colours, out of reach of the caret. At most 1,000 rows of a block show, then a row that shows the rest on a click | D6: a block of 10,000 lines costs 178 ms to lay out the first time on the JVM |
| Which view | Side by side, inline below `renderSideBySideInlineBreakpoint` (900 dp, Monaco's 900 px) or with `renderSideBySide = false` | So a phone gets the inline view |
| What can be edited | The modified side always (unless `EditorOptions.readOnly`). The original only with `originalEditable`, side by side | Monaco's defaults |
| Revert and accept | Revert puts a change's original lines back in the modified side; accept puts its modified lines into the original (only when it is editable). One undo step each, on the side it changes | |
| Unchanged regions | `hideUnchangedRegions` folds runs of unchanged lines longer than twice the context, on both sides at once, with a row to show them. Off by default, as Monaco | The fold machinery already hides lines and rows |
| The gutter's lane | `SimpleDiffProvider` uses the new diff: lines are marked after Myers's, not by position, and deletions get a marker | A line moved down by an insertion was marked modified before |
| New defaults | Nothing changes on screen for a `CodeEditorLayout` that does not use the new symbols, except the lane's marks, which are now right | |

### `:editor`, new

| Symbol | Item |
| --- | --- |
| `TextDiff.compute(original: CharSequence, modified: CharSequence, options: TextDiffOptions = TextDiffOptions()): TextDiffResult`; `TextDiffOptions(ignoreTrimWhitespace = true, maxComputationTimeMs = 5_000, computeCharChanges = true)`; `TextDiffResult(changes: List<LineChange>, identical: Boolean, hitTimeout: Boolean)`; `LineChange(originalLines: IntRange, modifiedLines: IntRange, charChanges: List<CharChange>?)` (line indices, `until` ranges, one side empty for an insertion or a deletion); `CharChange(original: IntRange, modified: IntRange)` (offsets) | §9.1 item 1 |
| `DiffEditorState(original: CodeEditorState, modified: CodeEditorState)` and `rememberDiffEditorState(original, modified)`: `changes: List<LineChange>` and `hitTimeout` (snapshot state), `changeAt(line, modifiedSide = true)`, `revert(change)`, `accept(change)`, `addDiffListener` and `removeDiffListener` | §9.1 items 3, 4 |
| `CodeDiffEditorLayout(state: DiffEditorState, modifier, options: DiffEditorOptions = DiffEditorOptions(), editorOptions: EditorOptions = EditorOptions(), languageService: LanguageService? = null, originalLanguageService: LanguageService? = languageService)` | §9.1 item 3 |
| `DiffEditorOptions(renderSideBySide = true, renderSideBySideInlineBreakpoint: Dp = 900.dp, ignoreTrimWhitespace = true, maxComputationTimeMs = 5_000, originalEditable = false, hideUnchangedRegions = false, unchangedContextLines = 3, renderIndicators = true, enableSplitViewResizing = true, splitRatio = 0.5f)` | §9.1 item 3 |
| `EditorCommand.NextChange`, `PreviousChange`, `RevertChange` (the change at the caret), bound to F7, Shift+F7 and nothing in `EditorKeyBindings.Default`; available only in a diff editor | §9.1 item 4 |
| `ChromeColors.diffInsertedLine`, `diffRemovedLine`, `diffInsertedText`, `diffRemovedText`, `diffFiller`, `gutterAdded`, `gutterModified`, `gutterDeleted`, read by the parser from Monaco's `diffEditor.insertedLineBackground`, `removedLineBackground`, `insertedTextBackground`, `removedTextBackground`, `diagonalFill` and `editorGutter.addedBackground`, `modifiedBackground`, `deletedBackground`; unspecified colours follow the theme | §9.1 items 2, 3 |
| `LineDiffKind.Deleted`; `CodeEditorLayout(diffProvider: DiffProvider = SimpleDiffProvider)` | §9.1 item 2 |
| `LanguageService.semanticTokens(document: CodeDocument, range: IntRange): List<Token>?` (suspend, default null: not supported, so the whole document is asked), with a `CompositeLanguageService` rule (the primary's when not null) | §9.1 item 7, carried in |

### `:languages-lsp`, new

| Symbol | Item |
| --- | --- |
| No new symbol: `LspLanguageService` overrides `semanticTokens(document, range)` with `textDocument/semanticTokens/range` for a server that declares or registers `range`, and the editor asks for the lines on screen while the whole answer is on its way | §9.1 item 7, carried in |

### `:languages`, new

| Symbol | Item |
| --- | --- |
| No new symbol: an edit in an HTML `<script>` or `<style>` body rescans only its changed lines | §9.1 item 7, carried in |

### `:editor-web` and the npm package, new

| Symbol | Item |
| --- | --- |
| `createDiffEditor(container, options): Promise<AardinkDiffEditor>`; `AardinkDiffEditor`: `getOriginalEditor()`, `getModifiedEditor()` (each an `AardinkEditor`), `setOriginalValue`, `setModifiedValue`, `getLineChanges()` in Monaco's `ILineChange` shape (1-based, with `charChanges`), `onDidUpdateDiff(listener)` returning a disposable, `goToDiff('next' \| 'previous')`, `revertChange(index)`, `updateOptions(options)`, `dispose()` | §9.1 item 5 |
| Diff options in Monaco's names: `renderSideBySide`, `renderSideBySideInlineBreakpoint`, `ignoreTrimWhitespace`, `maxComputationTime`, `originalEditable`, `hideUnchangedRegions: { enabled, contextLineCount }`, `renderIndicators`, `enableSplitViewResizing`, with the editor options (`language`, `theme` and the rest) for both sides | §9.1 item 5 |
| `defineTheme` reads the `diffEditor.*` and `editorGutter.*` keys above | §9.1 items 2, 3 |
| `AardinkWeb.mountDiff`, its handle, and template exports `aardinkCreateDiff`, `aardinkDiffSetValues`, `aardinkDiffLineChanges`, `aardinkDiffGoTo`, `aardinkDiffRevert`, `aardinkDiffUpdateOptions`, `aardinkDiffDispose`, `aardinkOnDidUpdateDiff` | §9.1 item 5 |

### Breaking

| Symbol | Change | Item |
| --- | --- | --- |
| `LineDiffKind` | Gains `Deleted` (an exhaustive `when` in a consumer breaks) | §9.1 item 2 |
| `SimpleDiffProvider` | Marks lines after Myers's diff, with deletions; the lines it marks change for the better. `MIGRATION_0.12.md` | §9.1 item 2 |
| `ChromeColors` | Gains fields (data class signature) | §9.1 items 2, 3 |
| `CodeEditorLayout` | Gains a defaulted parameter (recompile) | §9.1 item 2 |
| Template copies | Copy the new exports | §9.1 item 5 |

### Not in 0.12

- Moved code (Monaco's `experimental.showMoves`), three-way merge, and word-level diffs other than by
  characters.
- The accessible diff viewer: 0.14 (§9.3).
- Models: the diff editor takes two states, and the web's `setModel` comes with 0.13 (§9.2).

### If the wave overruns

In this order, each to the next minor: `hideUnchangedRegions`, the draggable split, accept into the
original, the inline view's cap row (a block then shows in full).

### 17.1 As built

Approved as a whole on 2026-11-22. The 0.12 work follows the ledger above, with these differences and
additions, each forced by the design or found while building it.

| Ledger | Built | Why |
| --- | --- | --- |
| `TextDiff.compute` | As listed. `maxComputationTimeMs` of 0 or less is no limit. With `ignoreTrimWhitespace`, texts that differ only in such whitespace have no changes and are not `identical`. `MinimalEdits`, behind formatting and `replaceValue`, runs on the same linear-space core in place of its O(D²) trace | One diff in the editor |
| `SimpleDiffProvider` marks lines after Myers's diff | As listed, with whitespace counting (the lane is about what saving changes). Of a change that replaces lines, as many lines as it replaced are `Modified` and the rest `Added`, as before; VS Code marks them all modified. A `Deleted` mark's `lineIndex` is the line below the deleted lines, or the line count for after the last line, where the lane draws a wedge on the bottom edge | Less change for a host reading the marks |
| Lane colours from the theme | An unset `gutterAdded`, `gutterModified` or `gutterDeleted` draws the lane's old green and blue, and red, so a theme without the keys looks as before | |
| `LanguageService.semanticTokens(document, range)` | As listed. The range is a line's start to the end of the last line, as `inlayHints` takes it, and an answer may reach past it. While the whole answer is on its way, the editor asks for the lines on screen with a screen's margin, split where folds hide long runs as the inlay hint window is, at each pause in typing; a range answer replaces those lines' tokens only, and the whole answer replaces everything when it lands. A scroll asks nothing. An empty list is an answer; null changes nothing | |
| LSP `semanticTokens/range` | As listed, for a server that declares or registers `range` (`true` or an object) with a legend. Its numbers are relative to the document's start, as the specification has them. It leaves the last whole answer's `resultId` alone, so the next request is still `full/delta`. `semanticTokens.requests.range` is declared. The legend of a range-only server is used for range requests; its whole requests are still not sent | |
| An edit in an HTML body rescans only its changed lines | Each `<script>` and `<style>` body keeps its own scan. An edit inside a body rescans from a little before the edit until the body's tokenizer is back in step, and the tokens after the body are moved by the edit, not scanned again: a one-character edit in the middle of a 2,000-line body scans 270 characters where it scanned 112,001. An edit to the tag, to the closing tag, or one that makes an earlier closing tag falls back to the whole body. An edit after a body's closing tag no longer rescans the body. The bodies now share the top-level tokenizers' one limit: a string or comment opener that matched nothing can become a token's start more than 256 characters before an edit that closes it, and a partial pass misses that, as it already does in a JSON, TypeScript or CSS document (§9.6) | |
| `DiffEditorState(original, modified)` | As listed, with `original` and `modified` as public properties, which its constructor makes them. The layout drives the diff: the first at once, then 300 ms after typing stops on either side, on the modified state's compute dispatcher, and an answer for text edited since is dropped. A state shown by no layout works nothing out, and its `changes` stay empty. `revert` and `accept` do nothing when the text has changed since the last diff, or when the side they would change is read-only, so `accept` needs `originalEditable`. `changeAt` finds a deletion at the line after it, or at the last line for one at the end | A diff is worth working out only while it is shown |
| `CodeDiffEditorLayout` | As listed. Both sides show their overview ruler, which the change marks need. The context menu has Revert change (Accept change on an editable original), Next change and Previous change, and the lane has an arrow on each change's first line that reverts it. In the inline view the original is not shown, so `originalEditable` does nothing there. A change found after the time limit shows as any other | |
| Filler rows and one scroll position | Each unit (a block, or an unchanged line and its other side's) gives its shorter side the rows it lacks, after its last line. Without soft wrap that is one pass over the blocks per diff. Under soft wrap every unit is worked out once per diff and when a pane keeps a new width for 100 ms; after that each frame works out only the units of the lines on screen, a search each, and moves only the lines whose filler changed, so a scroll costs one more layout pass at most. Lines off screen are aligned by the row estimate, so a long jump can show a small shift until the lines on screen settle. The side that scrolled sets the other's position, on both axes | The rendering rule: never a pass over the whole document per frame |
| `hideUnchangedRegions` | A hidden run is a fold on each side, its first line the last line of context, with "N unchanged lines" after it. Opening one opens the other side's. A region opened stays open when the diff is worked out again; new ones start folded. `unchangedContextLines` is at least 1 | The fold machinery hides lines and rows already |
| The inline view's cap | 1,000 rows of a block, then a row with the number of lines left, which shows them all when clicked. The deleted lines take the original's token colours, and follow them as the original is highlighted | §17's choice |
| — | `EditorView.attach` shows rows published before the view was attached, which it missed before | Found by the layout's first UI test |
| — | The inline view's deleted rows mark the characters a change removed, as the original side does | Found looking at the `diff-inline` scene |
| `createDiffEditor` and `AardinkDiffEditor` | As listed. The options take `original` and `modified`, the two texts, until 0.13's models; `hideUnchangedRegions` is `true` or `{ enabled, contextLineCount }`, and Monaco's `minimumLineCount` and `revealLineCount` are taken and ignored. `getLineChanges()` is Monaco's legacy `ILineChange`, 1-based and inclusive, with an end line of 0 and the line before the change as the start on the side of an insertion or a deletion, and `charChanges` left out where the editor has none; it is `[]` before the first diff. Both sides share one set of editor options, as `CodeDiffEditorLayout` takes one; the original's own options choose only its language. A side's host diagnostics, markers, baseline, find state, `onOpenLink` and `onSearchWorkspace` are not shown, as the layout takes none per side, and disposing a side does nothing. `npm` also exports the types `AardinkLineChange` and `AardinkCharChange` | The layout's one `EditorOptions` |
| `trigger` ids | `editor.action.accessibleDiffViewer.next` and `.prev`, `editor.action.diffReview.next` and `.prev`, and `workbench.action.compareEditor.nextChange` and `.previousChange` run `NextChange` and `PreviousChange`, only on a side a diff layout shows | Monaco's current and older names |
| `AardinkWeb.mountDiff`, its handle | `mountDiff(containerId, original, modified, optionsJson, …)` takes the options as JSON, as the template passes them, and returns `AardinkDiffHandle` with `original` and `modified`, each an `AardinkEditorHandle`, so every existing per-editor export works on either side. Also public, as the Kotlin side of the approved npm lines: `diffSetValues`, `diffLineChangesJson`, `diffGoTo`, `diffRevert`, `diffUpdateOptions`, `diffDispose` and `onDidUpdateDiff` on `AardinkWeb` | The exports in `:sample-web` and in the template are in other modules |
| `defineTheme` reads the diff keys | The Kotlin parser read them already; the npm path dropped them, as a registered theme's `ChromeColors` copied only the older fields. It copies them all now | Found by the smoke test's pixels |
| — | A read-only original refuses `executeEdits` from the web unless `originalEditable` | The state's read-only flag stops typing, not edits through the API |
| `perf.mjs --diff` | The 5,000-line reference document as the modified side, a change about every 20 lines, side by side; in CI with `--gate`. Key to change is held to the plain run's limits. Key to idle is reported, not gated: the diff runs 300 ms after typing stops, inside the window that ends a key's time, so a key's time takes it in. On the dev machine: about 250 changes found 370 ms after mount, key to change p95 17 to 19 ms. A new original starts with one task of 65 to 96 ms, gated at 100 ms: a profile (`--diff --profile`) shows it is the first slices of the original's full highlighting pass, not the diff, which is one task of 13 to 14 ms, with 20 ms to publish | The highlighting pass is §9.6's, and plain `setValue` has the same task |
| — | The wasm bundle grows 2.5 % gzipped with the diff editor: `aardink-web.wasm` 1,813,565 bytes against 0.10's 1,769,514. Recorded anew in `bundle-size.json`, as a change meant to grow the bundle is | Over the 2 % ceiling |

## 13. Verification

- **Per PR:** `pwsh ./scripts/pre-push.ps1 -NoFix`, CI green on `main` before the next merge, the
  current version's ledger line approved, ABI dumps updated. A version built on one branch, as 0.8
  and 0.9 are, runs the same check after each unit of work, before the next starts.
- **Performance:** `node tools/vite-smoke/perf.mjs --gate` on every PR; the results go in the PR
  description. From 0.10, the JVM and Android gates too.
- **Behaviour:** `commonTest` on JVM and wasm, the Compose UI tests, the Robolectric
  `InputConnection` test, the Vite smoke test. Each new `LanguageService` method gets a built-in
  implementation with a test and a web provider with a smoke test.
- **Pixels:** Roborazzi, with a scene for every new piece of chrome (tooltip, peek, ruler,
  swatches, guides).
