# Migrating from Aardink 0.5 to 0.6

0.6.0 replaces the editor's text field with a virtualised renderer and its own text input on
every platform (Android, desktop, web). Typing cost no longer grows with document size, and the
editor gains multi-cursor editing, a minimap, sticky scroll, bracket-pair colours and
go-to-definition. The price is a deliberate API break: everything that assumed one
whole-document `BasicTextField` is gone.

Most apps need three changes: pass `EditorOptions` instead of the boolean flags, stop reading
`CodeEditorState.textFieldState`, and drop `annotatedText`. The rest of this guide lists every
removed or changed public symbol, with a before/after for each.

The "Landed in" column names the pull request of [AARDINK_0.6_PLAN.md](AARDINK_0.6_PLAN.md) §6
that made each change, for anyone reading the history. [The last section](#example-the-aardflex-android-app)
upgrades a real app.

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
| Several selections | One selection | Alt+click, Alt+drag, Ctrl/Cmd+D, Ctrl+Shift+L, Ctrl+Alt+Up/Down and column selection (Shift+Alt+drag, middle-drag, Ctrl+Shift+Alt+arrows) make more; every edit and input method composition happens at each, as one undo step. `CodeEditorState.selections` has them all; `selection` is the primary one | PR 4, PR 11 |
| Popups | Signature help and hover at the top of the editor; completions only as a strip | Next to the caret or the symbol, flipping above or below to stay in the window. With a hardware keyboard completions are a list at the caret (Ctrl+Space; arrows, Enter or Tab, Escape); touch devices keep the strip. `HoverDocPopup`, `SignatureHelpPopup` and `CodeActionMenu` keep their signatures for hosts that show them themselves | PR 9 |
| Touch selection menu | The platform's text toolbar | The editor's own menu: cut, copy, paste, select all, and "Info" with a language service | PR 9 |
| Language service navigation | `definition`, `references`, `format` and `formatRange` were never called | F12 and Ctrl/Cmd+click call `definition`, Shift+F12 `references`, Shift+Alt+F `format` (or `formatRange` for a selection); the right-click menu has them too. A location in another file (range `IntRange.EMPTY`) goes to the new `onNavigateToLocation` parameter; without one it is ignored | PR 12 |
| Diagnostics while editing | Stayed at their offsets until the next list, drifting off the text they marked | Move with the text they mark, the host's list too; dropped when the whole text is replaced (`loadText`) | PR 10 |
| Web `mount` defaults | A new language registry per editor, and `builtInThemes` | One shared registry and theme map, which `registerLanguage` and `registerTheme` add to; pass `registry` and `themes` to keep your own. The export template's `aardinkCreate` no longer passes them | PR 14 |
| Web editor element | Compose's element straight in the container, left there by `dispose` | An element of the editor's own inside the container, removed by `dispose`; the container is the host's as before | PR 14 |
| Web first frame | In the default monospace font until JetBrains Mono loaded | Waits up to 500 ms for JetBrains Mono (call `preloadAardink()` early) | PR 14 |
| Web `onDidChangeContent` | The text | The text and `{ versionId, isUndoing, isRedoing, isFlush }`; the template's `aardinkOnContentChange` feeds it (`aardinkOnChange` still works) | PR 14 |
| Web diagnostics | Only the host's, from `setDiagnostics` | The language's own until the host calls `setDiagnostics`; `setDiagnostics(null)` goes back to them, and `onDiagnosticsChange` (`onDidChangeDiagnostics` in the npm package) reports them. Call `setDiagnostics([])` at mount to keep showing none | PR 10 |
| Keyboard toolbar | Inserted the character as is | Types it through the typing rules, as a key: `(` gets its `)` | PR 7 |
| Web clipboard | The text field's | Ctrl/Cmd+C, X and V are left to the browser, whose clipboard events carry the text without a permission prompt | PR 7 |

## Worth adopting

Not needed to compile, but new in 0.6:

- **Toggle comment for your own tokenizer.** Ctrl/Cmd+/ works when the tokenizer says how the
  language comments: override `IncrementalTokenizer.commentSyntax` (the built-in languages do). A
  tokenizer that wraps a built-in one can pass its `commentSyntax` on.
- **`EditorOptions`** has `stickyScroll`, `showMinimap`, `bracketPairColorization`,
  `highlightCurrentLine`, `matchBrackets`, `tabSize` and `insertSpaces`.
- **`onNavigateToLocation`** opens a definition in another file; without it, F12 and
  Ctrl/Cmd+click only work within the document.
- **`onDiagnosticsChange`** reports the language service's diagnostics when `diagnostics` is
  `null`, for a problems list of your own.
- **`DeclarativeGrammar` and `DeclarativeTokenizer`** (in `aardink-languages`) highlight from a
  Monarch grammar as JSON, incrementally, with no tokenizer code. The web package's
  `registerLanguage` takes the same grammars.

## Example: the aardflex Android app

The aardflex app (`apps/android/aardflex/v1.0` in the aardflex repository) goes from 0.4.0 to
0.6.0. It passes its own diagnostics and has no custom `EditorTheme` fonts, so most of its code
compiles unchanged; one screen changes.

**`gradle/libs.versions.toml`**

```toml
aardink = "0.6.0"   # was "0.4.0"; skipping 0.5.0 is fine, the artifacts still resolve to -android
```

**`ui/screen/XmlEditorScreen.kt`**: the undo manager is gone, and `canUndo` / `canRedo` are
snapshot state, so the `derivedStateOf` that re-read them on every text change goes too.

```kotlin
// 0.4 / 0.5
val canUndo by remember(editorViewModel) {
    derivedStateOf {
        editorViewModel.codeEditorState.textVersion
        editorViewModel.codeEditorState.undoManager.canUndo
    }
}
val canRedo by remember(editorViewModel) {
    derivedStateOf {
        editorViewModel.codeEditorState.textVersion
        editorViewModel.codeEditorState.undoManager.canRedo
    }
}

// 0.6
val canUndo = editorViewModel.codeEditorState.canUndo
val canRedo = editorViewModel.codeEditorState.canRedo
```

The `CodeEditorLayout` call compiles as it is. Its `diagnostics = editorDiagnostics` is a list,
so the editor keeps showing the app's own diagnostics rather than asking the language service.
The app has no boolean flags to move into `EditorOptions`, but can now switch on what 0.6 adds:

```kotlin
CodeEditorLayout(
    state = editorViewModel.codeEditorState,
    languageService = editorViewModel.languageService,
    findReplaceState = editorViewModel.findReplaceState,
    foldState = editorViewModel.foldState,
    foldingProvider = editorViewModel.foldingProvider,
    diagnostics = editorDiagnostics,
    savedText = ui.savedText,
    onCursorChange = { line, col -> editorViewModel.syncCursorFromEditor(line, col) },
    options = EditorOptions(stickyScroll = true), // new in 0.6, optional
    modifier = modifier,
)
```

`findReplaceState.show()` still opens the find panel, now without its replace row;
`show(replace = true)` opens both. `GoToLineDialog`, `navigateTo`, `loadText`, `undo()` and
`redo()` are unchanged.

**`service/XmlIncrementalTokenizer.kt`** still compiles and highlights correctly. It post-processes
the built-in XML tokenizer's output and passes that list back as `previousTokens`, which the
built-in tokenizer does not recognise as its own, so every pass is a full scan, as in 0.4. Two
optional improvements:

- Forward the XML comment syntax, so Ctrl+/ comments lines:
  `override val commentSyntax get() = base.commentSyntax`.
- Better: highlight with the web app's grammar, so both apps share one grammar and editing is
  incremental. Export the Monarch grammar from `aardflex-web-app` to JSON once (a RegExp literal
  becomes its `source` string), keep it as an asset, and:

  ```kotlin
  val grammar = DeclarativeGrammar.parse(context.assets.open("aardflex-xml.grammar.json").reader().readText())
  val tokenizer = DeclarativeTokenizer(grammar)
  ```

  Its tokens are `NamedTokenType`s (`tag.aardflex.xml`, `variable.module.xml`, ...), coloured by
  the `EditorTheme.tokenColors` entry of the longest dotted prefix of the name that has one, as
  Monaco does: an entry for `NamedTokenType("tag.aardflex")` colours `tag.aardflex.xml`, and
  `TokenType.TypeName` (whose `scope` is `type`, the built-in themes' colour for `tag`) colours the
  rest of the tags. `EditorTheme.tokenFontStyles` makes them bold in the same way.
  `grammar.tokenNames` lists every name the grammar can produce. `XmlTokenType.Expression` and
  `ColorRef` and the post-processing then go.

Then re-run the app's screenshot tests, and check an input method (Gboard: autocorrect, voice
input, a Samsung keyboard's deletes) and TalkBack by hand, since 0.6 replaces the text field
underneath.
