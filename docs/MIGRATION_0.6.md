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
| `readOnly`, `softWrap`, `showGutter`, `showLineNumbers`, `showFoldMarkers`, `showDiagnosticAnnotations`, `showDiffMarkers` parameters | `options = EditorOptions(...)` | *pending* (PR 7) |
| `annotatedText: AnnotatedString?` | Removed. Highlighting comes from the state's tokenizer; override colours through `EditorTheme` | *pending* (PR 7) |
| `diagnostics: List<Diagnostic> = emptyList()` | `diagnostics: List<Diagnostic>? = null`. `null` collects them from the language service; pass a list to supply your own, as before | *pending* (PR 10) |

## `CodeEditorState`

| 0.5 | 0.6 | Landed in |
| --- | --- | --- |
| `textFieldState` | Removed. Read `text`, `selection` / `selections`; edit with `applyEdit` / `applyTextEdits` | *pending* (PR 7) |
| `undoManager: EditorUndoManager` | Removed. Use `undo()`, `redo()`, `canUndo`, `canRedo`, `pushUndoStop()` | *pending* (PR 4) |
| `tokenCache: TokenCache` | Removed. Use `tokensForLine(line)`; a token that spans lines comes back as one token per line | PR 2 |
| `tokenizer` (`val`) | `var`: assigning it re-highlights and keeps undo history | *pending* (PR 3) |
| `selection` (read-only) | Settable; setting it collapses to one cursor | *pending* (PR 4) |

## Other removed symbols

| 0.5 | 0.6 | Landed in |
| --- | --- | --- |
| `EditorGutter(...)` | Internal. `CodeEditorLayout` draws the gutter; toggle it with `EditorOptions` | *pending* (PR 7) |
| `DrawScope.drawSquiggles(...)` | Internal | *pending* (PR 7) |
| `annotateTokens(...)` | Internal | *pending* (PR 7) |
| `applyFolding(...)` (deprecated in 0.5) | Removed. `FoldState` drives folding | *pending* (PR 7) |
| `EditorTheme.fontFamily`, `fontSize`, `lineHeight` (deprecated in 0.5) | Removed. Provide `LocalEditorTypography` | *pending* (PR 7) |
| `TokenCache` | Removed; the editor keeps its tokens internally. Read them with `CodeEditorState.tokensForLine` | PR 2 |
| `EditorUndoManager`, `EditOperation` | Internal | *pending* (PR 4) |

## Changed behaviour

| Area | 0.5 | 0.6 | Landed in |
| --- | --- | --- | --- |
| `FindReplaceState.show()` | Find only | `show(replace = false)`; Ctrl+H opens replace | *pending* (PR 3) |
| Language services and folding providers | Called with the live `CodeDocument`, which could change under a service running on another thread | Called with a read-only snapshot taken when the request was made; editing it throws `IllegalStateException` | PR 2 |
| `CodeDocument` | A plain class | A `CharSequence`: index it, slice it with `subSequence`, and run a `Regex` over it without copying `text`. `text` is built once per edit and shared | PR 2 |
| Web `updateOptions({ language })` | Rebuilt the editor state and lost undo history | Keeps undo history | *pending* (PR 3) |

## Example: an Android host

*Pending (PR 16): a full before/after of a real `CodeEditorLayout` call site.*
