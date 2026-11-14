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

Status: **in progress**, drafted 2026-10-22; the table below is current as of 2026-11-14. Continues
[`AARDINK_0.6_PLAN.md`](AARDINK_0.6_PLAN.md), which stays as the record of 0.6. In §1 to §10, facts
about the code are as of `v0.6.2` (commit `3fbaef6`) and facts about the aardflex apps are as of
the same day (Android on 0.6.2, aardflex-web-app on 0.6.1 with a Renovate PR to 0.6.2 open).

## Progress

| Version | State | Notes |
| --- | --- | --- |
| 0.6.3 | Released 2026-10-22 | §4 items 1–5: function keys on the web, regex replacement groups, the semantics `setText` window, the `updateOptions` KDoc, CMP 1.12.2. CMP 1.13 waits for its stable release. |
| 0.7 | Released 2026-10-24 (0.7.1 on 2026-11-01) | §5 items 5.1 to 5.10, with the web side of each. Ledger approved 2026-10-22 (§11); what was built where it differs from it, and the symbols the ledger did not name, are in §11.1. `docs/MIGRATION_0.7.md`. JVM 706 tests, browser 648, all green. |
| 0.8 | Released 2026-11-02 | §6 items 6.1 to 6.10, with the web side of each. Ledger approved as a whole 2026-11-01 (§14); what was built where it differs, and the symbols it did not name, are in §14.1. `docs/MIGRATION_0.8.md`. JVM 798 tests, browser 726, all green; pre-push passes. Scope: document symbols, decorations, overview ruler, occurrences, linked editing, links, colour decorators, folding markers, keybindings as data, zoom. |
| 0.9 | Released 2026-11-08 | §7 items 7.1 to 7.4, with the web side of each, except TypeScript regexp literals (to §8.1) and `onTypeFormatting` (to §8.3). Ledger approved as a whole 2026-11-06 (§15), with `onOpenLink` on the web and `DismissInlineCompletion` added 2026-11-07; what was built where it differs, and the symbols it did not name, are in §15.1. `docs/MIGRATION_0.9.md`. The inline-layout spike passed every step (`docs/spikes/0.9-inline-layout.md`). Scope: the rest of LSP and `connectLanguageServer`, Markdown documentation and the detail pane, inlay hints, code lens, semantic tokens, ghost text, embedded grammars and language configuration. Still open after release: the Android device check (§15.1) and the aardflex apps' builds against 0.9 (§13); the two opt-in performance costs are in §8.4. |
| 0.10 | In progress on branch `feat/0.10`. Ledger approved as a whole 2026-11-14 (§16) | §8, and what earlier versions left open (§16, "Carried in"): more built-in languages (TypeScript regexp literals among them), web provider parity (with the order of a host, a server and a base language), view options, find extras, format on type, the perf thread. |
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
- The manual Gboard, Samsung, TalkBack and NVDA passes have not been run. Not carried into 0.10
  (decided 2026-11-14): checks on devices and by hand belong to 1.0's platform validation (§9.6)
  alone.
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

Separately, in whichever patch follows the first **stable** CMP 1.13: adopt it, make
`checklist.mjs` W-1 (which already measures the 50-cycle heap) fail on a leak, remove the "reuse one editor" guidance from the README and
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
- TypeScript regexp literals by a lookbehind-free heuristic. Not built in 0.9: moved to §8.1.
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
  `KeyboardToolbarRow`; an LSP server through the web path (done in 0.9: `smoke.mjs` runs
  `connectLanguageServer` against `loopback-lsp.mjs`). The rest are in 0.10 (§16).
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

On 0.9, from its Markdown documentation and embedded grammars:

- **Web app, hover:** `src/editor/aardflex/hover.ts` says in its header that Aardink shows hover
  text as written, and `hover.test.ts` asserts there is no Markdown. Hover `contents` are Markdown
  now: lines joined with a single newline (`problems.join('\n')`) run into one paragraph and need a
  blank line or a trailing backslash, and text built from the document or sample data, such as an
  expression's preview, needs `*`, `_`, `` ` ``, `[`, `<` and line-start `#`, `-` and `1.` escaped.
- **Both apps, schema documentation:** schema `doc` strings and the catalogue descriptions are
  Markdown on hover and in the completion list. A scan on 2026-11-07 of `shared/generated/reference.json`
  (960 documentation strings) and the web app's `reference.json` (896) found no emphasis, no line
  that starts a heading or list, and no single newline; backticks now show as code, which is
  intended. Strings built at run time, and any added later, need the same check.
- **Template copies:** neither app copies `ExportsTemplate.kt` (both use the published npm package or
  the Kotlin API), so `aardinkRegisterLanguage`'s dispatcher needs nothing there. Check again before
  either builds its own wasm module.
- **`tokenNames`:** the Android app's `AardflexXmlTokenizerTest.everyGrammarTokenHasAColour` reads
  `AardflexXmlGrammar.grammar.tokenNames`. When the expression language moves into a grammar of its
  own through `nextEmbedded`, that set loses the expression names (`function.transform.xml`,
  `variable.module.xml`, `operator.pipe.xml`), and the test goes on passing while it checks less.
  It must read the union of both grammars' `tokenNames`, and the expression grammar needs
  `tokenPostfix: '.xml'` to keep today's names. The web app has no such test.

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
| `ExperimentalAardinkApi` | Stays in 1.0 (§9) | SemVer before 1.0 already allows a break, and the marker would be one more symbol |
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
- `onTypeFormatting`, moved to §8.3: it needs three more `:editor` symbols, and neither aardflex
  app uses it.
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
- The aardflex expression language's own grammar and catalog are downstream (§12). Here, only the
  test fixture and its goldens change.
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

0.9 shipped on 2026-11-08 with these open; the performance items are carried in §8.4. The device
check is not carried into 0.10 (decided 2026-11-14); it belongs to §9.6.

- **Android on a device.** Code lens rows and multi-line ghost text rest on hard breaks inside a
  line's layout. They pass under Robolectric with native graphics, but the spike's device check
  (`docs/spikes/0.9-inline-layout.md`) has not been done: the `rowShift` branch, Gboard and Samsung
  Keyboard on a line with a hint, and ghost text while a keyboard composes, which on Android may be
  every word.
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

- Checks on devices and by hand: 0.9's Android device check and the Gboard, Samsung, TalkBack and
  NVDA passes (decided 2026-11-14). They belong to §9.6.
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

Each new `LanguageService` member gets a merge rule on `CompositeLanguageService`. `formatOnType`
follows `format`: the primary's when it has edits, else the fallback's. The characters are united.

### `:languages`, new

| Symbol | Item |
| --- | --- |
| `BuiltInLanguages.Yaml`, `Properties`, `Ini`, `Shell`, `Python`, `Sql`, `Java`, `Groovy` (also `.gradle`), `Dockerfile`, `Diff`, `Scss`, `Less`, in `all` and `LanguageRegistry.withBuiltIns()` | §8.1 |
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
| `EditorOptions`, `EditorTheme`, `InlayHint`, `CodeAction`, `FindReplaceState`, `WebEditorOptions`, `WebLanguageProviders` | Gain fields (data class signatures) | All |
| `CodeEditorLayout`, `HoverDocPopup`, `SignatureHelpPopup` | Gain a defaulted parameter (recompile) | §8.3, Carried in |
| `BuiltInLanguages.all` | Lists the new languages, so a host that offers every language offers more | §8.1 |
| Template copies | Copy `aardinkRegisterFont` | §8.2 |

### If the wave overruns

In this order, each to 1.0 or a 0.10 patch: Groovy, Dockerfile, diff, SCSS and LESS; the replace
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
| `EditorThemes.HighContrastDark`, `HighContrastLight` | Colours after VS Code's High Contrast and High Contrast Light, every token colour at 7:1 or more on the background (a test holds it), with a border on the cards. The current line, the selection and find matches stay fills, where VS Code draws outlines. Monaco's `base: 'hc-black'` and `'hc-light'` now fill in from these, where they took Dark+ and Light+ before | The editor draws no outline decorations; an outline style is 1.0's accessibility work (§9) |
| `FindReplaceState.inSelection` | Searches the text selected when the option goes on, or the caret's line with nothing selected, and again on each opening of the panel with text selected (not when the selection is the current match, which find itself selected). The place moves with edits and is shaded, under the selection. A match must lie wholly inside it; a regular expression still sees the text around it (`\b` at its edge). A chip in the panel | VS Code's behaviour with `editor.find.autoFindInSelection` at its default, `never` |
| `FindReplaceState.preserveCase` | VS Code's rules: all capitals, all small letters, else the first letter's case; part by part where the match and the replacement have as many `-` or `_` separated parts. Applies to regex replacements after `$1` is expanded. A chip shown in replace mode | |
| `CodeEditorLayout(onSearchWorkspace)` | Called by the new `EditorCommand.SearchWorkspace`, bound to Ctrl+Shift+F (Cmd+Shift+F on macOS) in `EditorKeyBindings.Default`, and by a "Search all files" button in the find panel, both only when it is set, with the selection on one line, else the find panel's query, and the panel's options. Without it the command cannot run, so the key goes to the host. On the web, `trigger('workbench.action.findInFiles')`, VS Code's id, runs it | `EditorCommand.SearchWorkspace` is not in the ledger: added at the owner's request (2026-11-14) so the key can be rebound like any other |
| Search history, replace preview (internal) | Up and Down in the find and replace fields step through the last 50 entries, each kept once, newest first, and back to what was being typed. An entry is kept on Enter, on a replace, on Ctrl+Shift+F and when the panel closes. The preview, under the replace row, is the current match's line with the match struck through and the replacement after it, 30 characters either side, read from that line only. The public `FindReplacePanel` keeps its signature and has the history; the button and the preview come through the layout | The rendering rule: a keystroke reads one line, not the document |
| Colour swatch before the token (carried in) | Two non-breaking spaces of inline content before each colour, last at its column (after a hint there), with a square in the colour drawn in them over a grey edge; the 3 dp bar under the colour is gone. A click on the square opens the colour picker for it, where VS Code opens it on hover. The colours moved from the layout into the state, which moves them with each edit as it does hints, so the room is in place in the frame the edit draws | The room is part of the line's layout, so it must move with the text before the view lays the line out again |
| `WebEditorOptions` in Monaco's names | As listed, plus `wordWrapColumn`; `guides` is two flat fields on the Kotlin side, `indentGuides` and `bracketPairGuides`, so a patch of one does not reset the other. The npm package takes Monaco's `guides: { indentation, bracketPairs }`, `rulers` as numbers or `{ column }`, `wordWrap: 'wordWrapColumn'` and `'bounded'` (both wrap at the column or the edge, whichever comes first) and Monaco's `vs` and `vs-dark` theme names. `wordWrap` is off by default | `patchOptions` merges at the top level only |
| `hc-black`, `hc-light`; `defineTheme` reads the chrome keys and a rule's `background` | The two as built-in theme keys. Monaco's `defineTheme` data now goes to the Kotlin side as it is and is read by `EditorThemeParser.fromMonacoJson`, where the npm package turned it into VS Code JSON before; VS Code theme JSON takes the chrome colours it leaves out from its base | Turning Monaco's rules into VS Code JSON lost their backgrounds |
| `editor.onSearchWorkspace(listener)` | As listed, with VS Code's Find in Files arguments, `{ query, isRegex, isCaseSensitive, matchWholeWord }`. Through a new template export, `aardinkOnSearchWorkspace`, so template copies take that one as well as `aardinkRegisterFont` | Breaking for template copies, as `aardinkRegisterFont` is |
| The JS providers of §8.2 and those carried in | All of them, in Monaco's shapes with the text and a 1-based place for the model and position, as the earlier ones have. The definition says which providers the page has (`provided`, filled in by the npm package), so the dispatcher asks only those and `supportsRename` and the folds follow them; a template's definition without it has them all. Locations without a `uri` are in this text; with one, another file's, which the editor does not open (no page hook yet; models are §9's). An edit to another file leaves a code action out and a rename undone. `provideCodeActions` gets the markers of the provider's own last diagnostics. A code action's `command` goes to `runCodeLensCommand`. `signatureHelpTriggerCharacters` is accepted and unused: the editor asks whenever the caret is in a call's arguments. Folds replace the language's own. The semantic token legend is the provider's `getLegend()` or `legend`, sent with the definition as `semanticTokensLegend`, and names types as `:languages-lsp` does; the decoder is a copy of its internal one. `resolveCodeLens` is asked for every lens without a command as the answer comes, at most 100, where Monaco asks for those in view | `:editor-web` asks through the definition and the dispatcher, with no model; a lens's place on screen is not known to the language service |
| `{ snippet }` inline completions, inserted as snippets | Shown and inserted as the snippet's text: placeholders as their default, a choice as its first, tab stops and variables as nothing | Inserting a snippet from ghost text needs `InlineCompletion` to say it is one, an `:editor` symbol the ledger does not list; asked of the owner |
| Alt+character keys | Every key with Alt and a letter, a digit or punctuation, with or without Shift, read by `KeyboardEvent.code` and run by the host's actions, then the editor's bindings, where only Alt+] and Alt+[ were before. Not with Ctrl or Meta held, so AltGr types | Compose's web input types such a key and never hands it over |

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
- **Downstream:** the aardflex Android app and aardflex-web-app build and pass their tests on
  each release candidate, as they did for 0.6.
