# Migrating from Aardink 0.5 to 0.6

0.6.0 replaces the editor's text field with a virtualised renderer and its own text input on
every platform (Android, desktop, web). Typing cost no longer grows with document size, and the
editor gains multi-cursor editing, a minimap, sticky scroll, bracket-pair colours and
go-to-definition. The price is a deliberate API break: everything that assumed one
whole-document `BasicTextField` is gone.

Most apps need three changes: pass `EditorOptions` instead of the boolean flags, stop reading
`CodeEditorState.textFieldState`, and drop `annotatedText`. The rest of this guide lists every
removed or changed public symbol, with a before/after for each.

> **Status:** this guide is filled in PR by PR while 0.6.0 is built (see
> [AARDINK_0.6_PLAN.md](AARDINK_0.6_PLAN.md) §5 and §6). An entry marked *pending* has not
> landed on `main` yet; its symbol still works as in 0.5.

## Dependencies

```kotlin
implementation("com.aardarch:aardink:0.6.0")
implementation("com.aardarch:aardink-languages:0.6.0")
```

The web package moves to npm: `pnpm add @aardarch/aardink-web`.

## `CodeEditorLayout`

| 0.5 | 0.6 | Landed in |
| --- | --- | --- |
| `readOnly`, `softWrap`, `showGutter`, `showLineNumbers`, `showFoldMarkers`, `showDiagnosticAnnotations`, `showDiffMarkers` parameters | `options = EditorOptions(readOnly = …, softWrap = …, …)`, a data class: change one switch with `copy` | PR 7 |
| `annotatedText: AnnotatedString?` | Removed. Highlighting comes from the state's tokenizer; override colours through `EditorTheme` | PR 7 |
| `diagnostics: List<Diagnostic> = emptyList()` | `diagnostics: List<Diagnostic>? = null`. `null` collects them from the language service (500 ms after the editor appears, and after each pause in typing) and reports each list to the new `onDiagnosticsChange`; pass a list to supply your own, as before. A host that passed nothing and has a language service now shows that service's diagnostics: pass `emptyList()` to keep showing none | Signature PR 7; collecting PR 10 |

## `CodeEditorState`

| 0.5 | 0.6 | Landed in |
| --- | --- | --- |
| `textFieldState` | Removed. Read `text`, `selection` / `selections`; edit with `applyEdit` / `applyTextEdits` | PR 7 |
| `undoManager: EditorUndoManager` | Removed. Use `undo()`, `redo()`, `canUndo`, `canRedo` (snapshot state, for toolbar buttons) and `pushUndoStop()`; `alternativeVersionId` for dirty tracking; `lastChangeKind` to tell undo and redo from edits | PR 4 |
| `tokenCache: TokenCache` | Removed. Use `tokensForLine(line)`; a token that spans lines comes back as one token per line | PR 2 |
| `tokenizer` (`val`) | `var`: assigning it re-highlights and keeps text, selection and undo history | PR 3 |
| `selection` (read-only) | Settable; setting it replaces every selection with this one. `selections` / `setSelections` handle several, the primary first (as in Monaco) | PR 4 |

## Other removed symbols

| 0.5 | 0.6 | Landed in |
| --- | --- | --- |
| `EditorGutter(...)` | Removed. `CodeEditorLayout` draws the gutter; toggle it with `EditorOptions` | PR 7 |
| `DrawScope.drawSquiggles(...)` | Removed; the editor draws squiggles itself | PR 7 |
| `annotateTokens(...)` | Removed; each line is coloured as it is laid out | PR 7 |
| `applyFolding(...)` (deprecated in 0.5) | Removed. `FoldState` drives folding | PR 7 |
| `EditorTheme.fontFamily`, `fontSize`, `lineHeight` (deprecated in 0.5) | Removed. Provide `LocalEditorTypography` | PR 7 |
| `TokenCache` | Removed; the editor keeps its tokens internally. Read them with `CodeEditorState.tokensForLine` | PR 2 |
| `EditorUndoManager`, `EditOperation` | Removed; the editor keeps its undo history internally | PR 4 |

## Changed behaviour

| Area | 0.5 | 0.6 | Landed in |
| --- | --- | --- | --- |
| `FindReplaceState.show()` | Opened the panel with its replace row always shown | `show(replace = false)`: the replace row shows only with `replace = true` (Ctrl+H) or the panel's new toggle; `replaceMode` holds it | PR 3 |
| Find panel | Every match | At most `EditorLimits.maxFindMatches` (10,000), shown as "N+"; a new query cancels a running search | PR 3 |
| Built-in tokenizers' `tokenizeLines` | Returned tokens for the whole document | Returns only the lines it rescanned, with zero-length tokens marking the span, and expects `previousTokens` to be its own last result. A wrapper that passes its *own* earlier result back as `previousTokens` (as aardflex's `XmlIncrementalTokenizer` does) is not recognised, so the built-in one answers with a full scan: still correct, just not incremental. To get the speed-up, return the built-in tokenizer's list unchanged or pass that list back | PR 3 |
| `CodeDocument.dirtyLines` after a delete that joins lines | Ran to the end of the document | Just the line the delete started on: lines below keep their text and their tokens move with them | PR 3 |
| `SimpleDiffProvider` | Compared lines by position, so every line below an inserted one showed as modified | Trims the shared leading and trailing lines first | PR 3 |
| Language services and folding providers | Called with the live `CodeDocument`, which could change under a service running on another thread | Called with a read-only snapshot taken when the request was made; editing it throws `IllegalStateException` | PR 2 |
| `CodeDocument` | A plain class | A `CharSequence`: index it, slice it with `subSequence`, and run a `Regex` over it without copying `text`. `text` is built once per edit and shared | PR 2 |
| Web `updateOptions({ language })` | Rebuilt the editor state and lost undo history | Keeps undo history | PR 3 |
| Undo | Undid runs of typed characters up to a space, and put the caret where the edit was | Groups as Monaco does: typing until the caret moves, the kind of edit changes, or a space follows a word, so a word at a time; backspaces together; every other edit on its own. Undo restores every selection from before the step | PR 4 |
| Rendering | One `BasicTextField` held the whole document | The editor lays out and draws only the lines on screen and takes its own text input (keys, input methods, clipboard). UI tests find it by its semantics (editable text, set-text and insert-text actions) instead of a text field; `performTextInput` works as before | PR 7 |
| Initial caret | At the end of `initialText` | At the start, as after `loadText` and in Monaco | PR 7 |
| Replace all | One undo step per match | One undo step for all of them | PR 7 |
| Selection colour | Material's text-selection colour | `EditorTheme.selectionColor` | PR 7 |
| Find match colour | Material's tertiary colour | `EditorTheme.findMatchColor`, the current match stronger | PR 8 |
| Look | Plain text on the theme background | The caret's line highlighted, the bracket pair at the caret boxed, brackets coloured by depth. Each can be turned off in `EditorOptions`; the colours are `EditorTheme.lineHighlight` and the new `EditorTheme.bracketPairColors` | PR 8 |
| `EditorThemeParser`, 8-digit colours | Read as `#AARRGGBB` | Read as VS Code writes them, `#RRGGBBAA` | PR 8 |
| Carets | The text field's caret, blinking while focused | One caret per selection, blinking while the editor has focus, hidden without it | PR 7 |
| Mouse and touch | The text field's | Click, Shift+click, Alt+click (another caret), double and triple click, drag; touch tap, long press, selection handles with the platform's text toolbar; a right-click menu on desktop and the web | PR 7 |
| Popups | Signature help and hover at the top of the editor; completions only as a strip | Next to the caret or the symbol, flipping above or below to stay in the window. With a hardware keyboard completions are a list at the caret (Ctrl+Space; arrows, Enter or Tab, Escape); touch devices keep the strip. `HoverDocPopup`, `SignatureHelpPopup` and `CodeActionMenu` keep their signatures for hosts that show them themselves | PR 9 |
| Touch selection menu | The platform's text toolbar | The editor's own menu: cut, copy, paste, select all, and "Info" with a language service | PR 9 |
| Diagnostics while editing | Stayed at their offsets until the next list, drifting off the text they marked | Move with the text they mark, the host's list too; dropped when the whole text is replaced (`loadText`) | PR 10 |
| Web diagnostics | Only the host's, from `setDiagnostics` | The language's own until the host calls `setDiagnostics`; `setDiagnostics(null)` goes back to them, and `onDiagnosticsChange` (`onDidChangeDiagnostics` in the npm package) reports them. Call `setDiagnostics([])` at mount to keep showing none | PR 10 |
| Keyboard toolbar | Inserted the character as is | Types it through the typing rules, as a key: `(` gets its `)` | PR 7 |
| Web clipboard | The text field's | Ctrl/Cmd+C, X and V are left to the browser, whose clipboard events carry the text without a permission prompt | PR 7 |

## Example: an Android host

*Pending (PR 16): a full before/after of a real `CodeEditorLayout` call site.*
