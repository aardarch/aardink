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

# Aardink roadmap: 0.6.3 to 1.0

Status: **draft for review** (2026-10-22). Continues [`AARDINK_0.6_PLAN.md`](AARDINK_0.6_PLAN.md),
which stays as the record of 0.6. Facts about the code are as of `v0.6.2` (commit `3fbaef6`);
facts about the aardflex apps are as of the same day (Android on 0.6.2, aardflex-web-app on
0.6.1 with a Renovate PR to 0.6.2 open).

## Progress

| Version | State | Notes |
| --- | --- | --- |
| 0.6.3 | Released 2026-10-22 | §4 items 1–5: function keys on the web, regex replacement groups, the semantics `setText` window, the `updateOptions` KDoc, CMP 1.12.2. CMP 1.13 waits for its stable release. |
| 0.7 | Done, not released (branch `feat/0.7`, 2026-10-22) | §5 items 5.1 to 5.10, with the web side of each. Ledger approved 2026-10-22 (§11); what was built where it differs from it, and the symbols the ledger did not name, are in §11.1. `docs/MIGRATION_0.7.md`. JVM 706 tests, browser 648, all green. |
| 0.8 | In progress (branch `feat/0.8`, 2026-11-01) | Ledger approved as a whole 2026-11-01 (§14). §6: document symbols, decorations, overview ruler, occurrences, linked editing, links, colour decorators, folding markers, keybindings as data, zoom. |
| 0.9 | Planned | §7: LSP completeness, Markdown hover, the inline-layout spike, inlay hints, code lens, semantic tokens, ghost text, embedded grammars and language configuration. |
| 0.10 | Planned | §8: more built-in languages, web provider parity, view options, find extras, the perf thread. |
| 1.0 | Planned | §9: API freeze, diff editor, accessibility, i18n, models and view state, device validation. |

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
  2026-10-06), so the leak stays until 1.13 is stable. The 50-cycle heap test in `checklist.mjs` is still owed.
- **Chrome 155 broke the web performance gate**, which failed on `main` from 2026-10-07 to
  2026-10-21. The same 0.6.1 code measured key to idle p95 16 ms on Chrome 154.0.8037.92 and
  232 ms on 155.0.8059.39; `setValueToIdleMs` went from 18 to about 85 ms and the longest task
  grew, while key to change stayed at 3 ms. It reproduces locally on 155.0.8059.86 with the 0.6.2
  code on Compose 1.12.1 and 1.12.2 alike, so it is neither 0.6.3's changes nor the Compose bump.
  About 225 ms is the 150 ms tokenize debounce plus one long frame. Chrome fixed it upstream:
  CI's 156.0.8078.47 measures 15 ms with the same code (2026-10-21, the v0.6.3 run included), so
  the gate is unchanged; a machine still on Chrome 155 fails it locally until Chrome updates. If
  key to idle comes back on a later Chrome, trace the pass that follows the debounce.
- The manual Gboard, Samsung, TalkBack and NVDA passes have not been run.
- TypeScript regexp literals are not tokenized.
- The KDoc of `AardinkWeb.updateOptions` says a language change starts a fresh undo history; the
  code keeps it.

## 2. What the aardflex apps need

The roadmap is weighted by what the two aardflex apps would use. Both were audited against the
current API.

### 2.1 The Android app

`aardflex/apps/android/aardflex/v1.0`: one `ui/screen/XmlEditorScreen.kt` behind four screens
(wallpaper, widget, component, theme). The sample documents are small (61 files, the largest
438 lines), soft wrap is on, the editor is always dark, and the app's own action bar and status
line sit under it.

- **Missing host events.** The app refreshes its text and preview from `onCursorChange`, runs its
  own 500 ms diagnostics timer from the same callback (an edit that leaves the caret in place is
  missed), and copies the document with `CodeDocument(document.text)` because `snapshot()` is
  internal.
- **No phone access to editor features.** Format, suggest, hover and code actions have no public
  commands; the touch menu and keyboard toolbar take no host items; `insertSnippet` is internal.
  There is no Format button. The toolbar's characters lack `|`, `:` and `$`.
- **The built-in XML checks are lost.** `service/language/AardflexXmlLanguageService.kt` (427
  lines plus a 194-line context detector) replaces the built-in XML service entirely, so precise
  tag diagnostics, closing tags on `>` and closing quotes do not run. The web's `extends: 'xml'`
  composition has no Kotlin equivalent.
- **Whole-line diagnostics.** `ValidationDiagnostic` carries only a line; the original spec asked
  for exact ranges and the friendly messages the save path uses.
- **Theme by hand.** The web theme JSON is copied into `ui/theme/AardflexEditorTheme.kt` because
  `EditorThemeParser` reads VS Code `tokenColors` but not Monaco `rules`. The editor ignores the
  app's light and dark setting and its Material 3 scheme. No font size setting or pinch zoom,
  which the original spec asked for.
- **No preview sync.** Nothing maps the caret to an element or an element to a range. Validate
  shows a snackbar with counts; there is no next-problem.
- **Structural elements get no completions.** `<page>`, `<module>`, `<var>` and the rest are not
  offered; the visual builder's descriptors and `shared/generated/reference.json` (171 KB of
  machine-readable schema) are unused by the editor.
- The reference screens show XML in a plain `Text`.

### 2.2 The web app

`aardflex-web-app`: Svelte 5, `@aardarch/aardink-web` 0.6.1. About 2,900 lines of TypeScript
language service mirror the Android Kotlin one; both read the shared grammar and `reference.json`.

- The app ranks completions itself because the editor's filter would drop its matches. Letters
  do not open the list. F8 types a `w` (`platform/TypedTextDecoder.wasmJs.kt:32` accepts any
  code point from 0x20, and the browser reports F8 as key code 119). The editor has no inline
  peek, so F8 opens the Problems panel instead. Diagnostics are cut to one line. Hover puts
  diagnostics first because the editor shows no tooltip of its own for a squiggle.
- Wanted: `fontFamily`; CMP 1.13 so the pane can remount; decorations for the assistant's edits
  and for preview hover; caret-to-element sync; colour swatches and a picker for `@color`, hex and
  theme colours; command and keybinding registration (Ctrl+S and F8 are captured in
  `AppShell.svelte`); selection events; focus and blur; reveal APIs. Accepted losses:
  `automaticLayout` and `scrollBeyondLastLine`.
- Bundle: 13 MB of wasm, 4.7 MB gzipped (Monaco was 2.5 MB and 650 KB).
- The AI assistant writes XML through `replaceValue`; ghost-text completions and change-highlight
  decorations would serve it directly.

### 2.3 The shared opportunity

The aardflex-xml language service exists twice (about 1,200 lines of Kotlin and 2,900 of
TypeScript) and the two already differ. Most of it (elements, attributes, enums, nesting,
required attributes, hover documentation, unknown-element diagnostics) follows from
`reference.json` plus structural rules. A **schema-driven XML language service in `:languages`**,
usable from Kotlin and through `registerLanguage({ extends: 'xml', schema })` on the web, removes
most of the duplication and fills the Android gaps in one move. It is pure `:languages` work with
no `:editor` API change (the built-in Android lists become the default schema), so it ships in
0.7. The expression language (`{module:prop | transform}`) stays the app's overlay and becomes
an embedded Monarch grammar in 0.9.

## 3. Principles and order

Carried over from 0.6:

- SemVer before 1.0: a minor may break the API, a patch never does. Breaking commits carry `!`.
- Every public symbol lands in the current version's ledger (§11) with updated ABI dumps, and a
  `MIGRATION_0.x.md` entry when it breaks a consumer.
- The performance gates hold on every PR. No new runtime dependencies. `expect`/`actual` only
  under `platform/`. Never `Dispatchers.Default` or `IO` directly. No lookbehind. Never lay out
  the whole document.
- Each minor is one 0.6-style wave of about ten to fifteen PRs; the estimates below are per
  item. Performance and accessibility run as threads through every version (§10).

Order of value for aardflex: 0.6.3 (bugs the web app hits today), then 0.7 (host integration and
the schema service), 0.8 (symbols and decorations, which give preview sync, and colours), 0.9
(LSP, ghost text, embedded expressions), 0.10 (languages, web parity, performance), 1.0 (freeze,
diff, accessibility).

## 4. 0.6.3: internal fixes, no API change

About five small PRs.

1. Function and navigation keys must not type a character on the web: guard
   `TypedTextDecoder.wasmJs.kt` on `event.key`. The JVM decoder never sees them.
2. A regex replacement expands `$1` to `$9` from the match groups (`ui/CodeEditorLayout.kt:786`).
3. The semantics `setText` edits only the exposed window of 50 lines each side of the caret,
   never the whole document (`ui/view/EditorInputNode.kt:205`).
4. Fix the stale `AardinkWeb.updateOptions` KDoc and the matching comment near line 103.
5. Compose Multiplatform 1.12.2 (the canary is green on it).

Separately, in whichever patch follows the first **stable** CMP 1.13: adopt it, add the 50-cycle
heap test to `checklist.mjs`, remove the "reuse one editor" guidance from the README and
`WEB_INTEGRATION.md`, and run the canary with `cmp-version` pinned to the candidate as the
pre-release gate. This is not tied to 0.7.

## 5. 0.7: host integration and the shared XML schema

About fourteen PRs. Theme: the editor becomes controllable and observable by its host, on a phone
as well as with a keyboard, and `:languages` gains the one feature that collapses the aardflex
duplication.

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
  well as VS Code JSON, so one theme file serves both aardflex apps. A rule's `background` is
  honoured.

### 5.9 Keys (`:editor`; 1 PR)

- F2 rename, with a context-menu entry. Ctrl+. quick fix, with a lightbulb in the gutter.
  F3 and Shift+F3. Find seeds from the selection. Alt+Enter selects every match.

### 5.10 Correctness (`:editor`, `:languages`, `:languages-lsp`; 1 to 2 PRs, some breaking)

- `smartIndent` returns an indent string, so tabs come out when `insertSpaces` is false.
- The built-in formatters and `LspLanguageService.format(document, options)` honour `tabSize` and
  `insertSpaces`.
- `loadText` records the line ending: `EditorOptions.eol` and `document.eol`.

**What aardflex gets.** Android drops its cursor-callback workarounds, gains a Format button and
phone access to suggest, hover and format, precise squiggles with the friendly messages,
next-problem, the built-in XML checks back under its own service, one shared theme file, and
structural-element completions from `reference.json`. The web app gets a native F8 peek,
selection and focus events, `addCommand` for Ctrl+S, quick suggestions, and can delete its own
ranking code and most of `completions.ts`, `schema.ts` and the unknown-element checks.

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
  them. **This is the caret-to-preview mapping both aardflex apps lack.**

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
  colour-typed schema attributes. aardflex supplies `@color` and theme colours.

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

**What aardflex gets.** Preview and code in sync both ways (symbols plus decorations), colour
swatches and a picker on `@color`, hex and theme tokens, tag-pair rename, clickable includes,
problems in the ruler, Ctrl+S and F8 bound without capture-phase handlers, pinch zoom and
Material 3 light and dark theming on Android.

## 7. 0.9: language depth

About eighteen PRs. Theme: everything a language or an assistant can tell the editor, rendered
where Monaco renders it. This version holds the roadmap's one architectural change, inline content
that changes line layout, and it gets a spike first, as the 0.6 renderer did.

### 7.1 LSP completeness (`:languages-lsp`, `:editor-web`; 8 to 10 PRs)

- Incremental sync from `ContentChange`; `$/cancelRequest` when the coroutine is cancelled;
  `shutdown` and `exit`; `willSave` and `didSave`.
- `documentSymbol`, `foldingRange`, `documentHighlight`, `documentColor` and `colorPresentation`,
  `documentLink`, `selectionRange`, `linkedEditingRange`, `completionItem/resolve`,
  `onTypeFormatting`, `typeDefinition`, `implementation`, `declaration`.
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
  `registerInlineCompletionsProvider` on the web. Ghost text is the natural surface for the
  aardflex AI assistant on both platforms.

### 7.4 Monarch and language configuration as data (`:languages`; 3 PRs)

- `@nextEmbedded` and `@pop` embedding of another *declarative* grammar: the tokenizer's `Stack`
  gains an embedded stack and a grammar id, structural equality keeps the incremental rescan
  rule, and a grammar resolver by id is new API. Also `switchTo`, `goBack`, `$S0`, `$#`,
  `brackets` and `log`.
- `LanguageConfiguration` (brackets, autoClosingPairs, surroundingPairs, onEnterRules,
  wordPattern, folding markers, comments, indentationRules) parsed from VS Code's
  `language-configuration.json`, giving every grammar-only language auto-close, surround and
  Enter indentation.
- TypeScript regexp literals by a lookbehind-free heuristic.
- The aardflex expression language becomes an embedded grammar with its own catalog.

## 8. 0.10: languages, web parity, performance

About fifteen PRs.

### 8.1 Built-in languages (`:languages`; 1 PR each)

- TypeScript, CSS and Markdown get configuration-driven services, with a CSS formatter and
  Markdown table formatting. Kotlin gets hover and signature help for its static catalogue.
- Grammar-as-data languages with configurations and golden-token tests: YAML, Properties and INI,
  Shell, Python, SQL, Java, Groovy and Gradle, Dockerfile, diff, SCSS and LESS.
- CSS and JavaScript embedded in HTML needs the CSS and TypeScript grammars as data first, so it
  lands here, after §7.4.

### 8.2 Web provider parity (`:editor-web`; 2 PRs)

- JavaScript providers: `provideDefinition`, `provideReferences`,
  `provideDocumentFormattingEdits`, `provideSignatureHelp`, `provideCodeActions`,
  `provideRenameEdits`, `provideFoldingRanges`, `provideDocumentSymbols`,
  `provideDocumentColors`, `provideInlineCompletions`.
- `fontFamily` through `registerFont(name, url or bytes)`: wasmJs code in `:editor-web`, no
  `expect`.
- Theme keys for the minimap, scrollbar, guides and widgets; high-contrast built-in themes.
- `wordWrap` defaults to off as in Monaco (breaking on the web only, flagged).

### 8.3 View options and find extras (`:editor`; 3 PRs)

- Indent guides with the active one highlighted, bracket-pair guides, rulers, relative and
  interval line numbers, cursor style and blinking, smooth scrolling, `scrollBeyondLastLine`,
  `wrappingIndent`, `wordWrapColumn`, `cursorSurroundingLines`, drag and drop of a selection.
- Find in selection, preserve case, a search history, a replace preview, and an
  `onSearchWorkspace` hook for Ctrl+Shift+F.

### 8.4 Performance (all modules; 3 PRs)

- The wasm bundle: measure skiko slimming options, document Brotli, load the font lazily, strip
  unused Compose resources; target under 3 MB gzipped.
- Android: startup measurement and a baseline profile generated in `:sample`
  (`profileinstaller` only in the sample, never in `:editor`).
- JVM and Android performance gates; today only the web is gated.
- A cheaper plain-text-with-find path above the `EditorLimits` size where highlighting turns off.

## 9. 1.0: stable API, diff editor, accessibility, i18n

About fifteen PRs.

1. **API freeze.** Review every public symbol against the ledgers, mark what is not ready
   `@ExperimentalAardinkApi`, publish a deprecation policy (one minor of `@Deprecated` before
   removal), stabilise `EditorKeyBindings` and the npm `index.d.ts`, and treat the ABI dumps as
   the contract.
2. **Diff editor.** `CodeDiffEditorLayout(original, modified)` side by side and inline,
   deleted-line markers in the gutter lane, revert and accept a hunk, F7 and Shift+F7; web
   `createDiffEditor`. Serves the aardflex "review the assistant's rewrite" flow.
3. **Accessibility.** A screen-reader mode (`accessibilitySupport`), semantics for popups, the
   gutter, diagnostics and folds, live-region announcements (line and column, problem count, the
   selected completion), an accessible diff, high-contrast themes, scripted TalkBack and NVDA
   checks where possible and a documented manual matrix for the rest.
4. **i18n.** An `EditorStrings` CompositionLocal with every UI string, English by default, and a
   `locale` option on the web.
5. **Models and view state.** `saveViewState()` and `restoreViewState()` (scroll, selections,
   folds), several documents behind one layout (`setModel`), URIs on documents for multi-file
   navigation and LSP; web `getModel`, `setModel` and `createModel`.
6. **Platform validation before the tag.** Gboard, Samsung, SwiftKey, CJK and dead keys; Windows
   and macOS desktop; Chrome, Firefox and Safari on desktop and iOS; TalkBack and NVDA; the tablet
   and foldable postures the aardflex app uses.

## 10. Threads through every version

- The web gates stay; JVM and Android gates arrive in 0.10; the bundle size is recorded per
  release.
- Coverage gaps from the audit: golden tokens for TypeScript, CSS, HTML and Markdown; screenshot
  scenes for the popups and the find panel; tests for `RenameDialog`, `GoToLineDialog` and
  `KeyboardToolbarRow`; an LSP server through the web path.
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
| `KeyboardToolbarItem.Action` | Sealed `KeyboardToolbarItem` with `Character` and `Action`; `CodeEditorLayout(keyboardToolbarItems)`; `KeyboardToolbarRow(items, onAction)` | The items need a way into the layout; a host also adds characters (aardflex's pipe, colon and dollar) |
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

## 12. Downstream follow-ups (in the aardflex repositories)

Found during the audits; none of them is Aardink's to fix.

- **Android app, fixable on 0.6.2:** `knownColorNames` is never passed, so `@` colour
  completions are always empty; module completions ignore the document's own module ids;
  completions for `{theme:colors.` paths, format parts and `{$var}` are missing; the theme
  editor offers wallpaper components; hover only matches the last character of a name (and so
  the touch menu's "Info"); the preview re-parses on the main thread with no delay; the
  `file_editor` route is dead code. On 0.7: pass precise ranges and the friendly messages, let
  the editor collect diagnostics itself, add a Format button, and extend the toolbar characters
  with `|`, `:` and `$`.
- **Web app:** merge the Renovate bump to 0.6.2; after 0.6.3, remove the F8 workaround; after
  0.7, delete the ranking code and the schema-derived parts of `completions.ts`, `schema.ts` and
  `diagnostics.ts`.
- **aardflex specs:** `specs/index.md` still lists the syntax highlighter as in progress and the
  Monaco web editor as built; `specs/roadmap.md` row 0.6 still says "regex-based highlighting".

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

## 13. Verification

- **Per PR:** `pwsh ./scripts/pre-push.ps1 -NoFix`, CI green on `main` before the next merge, the
  §11 ledger line approved, ABI dumps updated.
- **Performance:** `node tools/vite-smoke/perf.mjs --gate` on every PR; the results go in the PR
  description. From 0.10, the JVM and Android gates too.
- **Behaviour:** `commonTest` on JVM and wasm, the Compose UI tests, the Robolectric
  `InputConnection` test, the Vite smoke test. Each new `LanguageService` method gets a built-in
  implementation with a test and a web provider with a smoke test.
- **Pixels:** Roborazzi, with a scene for every new piece of chrome (tooltip, peek, ruler,
  swatches, guides).
- **Downstream:** the aardflex Android app and aardflex-web-app build and pass their tests on
  each release candidate, as they did for 0.6.
