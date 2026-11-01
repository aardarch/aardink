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

# Migrating from Aardink 0.7 to 0.8

0.8 lets the editor understand the document's structure and lets hosts draw on it: document
symbols, decorations, an overview ruler, occurrence highlights, linked editing, links, colour
decorators, folding markers, keybindings as data, and zoom. [AARDINK_ROADMAP.md](AARDINK_ROADMAP.md)
§6 has the plan, §14 the API ledger and §14.1 what was built where it differs.

Almost all of it is additive, and every new option is off by default, so an upgrade looks and
behaves as 0.7 did. Source breaks in two places:

- `FoldRange` has a new property, `kind`, so destructuring it or calling `copy` by position
  changes.
- `EditorCommand` has new members, so an exhaustive `when` over it needs a branch for them (or
  an `else`).

Every data class and composable that gained a parameter (`EditorOptions`, `FoldRange`,
`EditorTheme`, `CodeEditorLayout`, the web's option and message classes) changed its binary
signature: recompile against 0.8. Calls by name or by position keep compiling.

## Dependencies

```kotlin
implementation("com.aardarch:aardink:0.8.0")
implementation("com.aardarch:aardink-languages:0.8.0")
```

On the web: `pnpm add @aardarch/aardink-web@^0.8.0`.

## `FoldRange`

| 0.7 | 0.8 |
| --- | --- |
| `FoldRange(startLine, endLine, placeholder)` | `FoldRange(startLine, endLine, placeholder, kind: FoldKind? = null)` |
| `val (start, end, placeholder) = range` | The same; a fourth component, `kind`, follows |

A folding provider written for 0.7 compiles unchanged. Set `kind` to `FoldKind.Comment`,
`Imports` or `Region` when a range is one of those.

## `EditorCommand`

0.8 adds `GoToSymbol`, `ExpandSelection`, `ShrinkSelection`, `ShowColorPicker`, `Fold`,
`Unfold`, `JoinLines`, `SortLines`, `UpperCase`, `LowerCase`, `TrimTrailingWhitespace`,
`JumpToBracket`, `CursorUndo`, `SkipOccurrence`, `ChangeAllOccurrences`, `CursorsAtLineEnds`,
`ScrollLines`, `AddLineComment`, `RemoveLineComment`, `BlockComment`, `InsertLineBelow`,
`InsertLineAbove`, `SelectLine`, `ZoomIn`, `ZoomOut`, `ZoomReset` and `ScrollTo`.

```kotlin
// 0.7: compiled without an else
val label = when (command) {
    EditorCommand.Undo -> "Undo"
    // ... every 0.7 member ...
}

// 0.8
val label = when (command) {
    EditorCommand.Undo -> "Undo"
    // ... the members you name ...
    else -> command.toString()
}
```

## `LanguageService`

New members, each with a default, so a 0.7 service compiles and behaves as before:
`documentSymbols`, `documentHighlights`, `selectionRanges`, `linkedEditingRanges`,
`documentLinks`, `documentColors` and `colorPresentations`. `CompositeLanguageService` merges
them (see its KDoc).

## Keys

The keys that run public commands are now data, `EditorKeyBindings.Default`, which
`EditorOptions.keyBindings` overrides. The bindings themselves are those of 0.7, plus the new
ones in the [changelog](../CHANGELOG.md). Two behaviours changed:

- A bound key whose command cannot run now (Ctrl+Z with nothing to undo, a zoom key in an editor
  that does not zoom) is no longer swallowed: it goes on to whatever else takes it, a browser's
  page zoom say.
- Ctrl+K (Cmd+K) now starts a chord and waits for a second key.

## Changed behaviour

- **Markdown and CSS** have a language service now (Markdown for its headings as symbols, CSS for
  its colours). With `showDiagnosticAnnotations` on, the gutter keeps a diagnostics lane for them,
  as for every language with a service.
- **Built-in folding** includes region markers: `// #region` in Kotlin and TypeScript,
  `<!-- #region -->` in XML, HTML and Markdown, `/* #region */` in CSS, `# region` in TOML.
- **The web:** a language registered without `extends` folds by indentation, where it had no
  folding.
- **The web:** `aardinkRegisterLanguage` in the export template takes two more provider
  functions, for document symbols and colours. A wasm module built from the template copies the
  new version (`editor-web/src/wasmJsTest/.../ExportsTemplate.kt`). The npm package needs nothing.

## Worth adopting

- **Caret to preview:** `CodeEditorState.symbolPathAt(state.selection.end)` (in `snapshotFlow`)
  gives the elements around the caret, from the language's `documentSymbols`. On the web,
  `getSymbolPathAt(editor.getPosition())`.
- **Preview to code:** `state.setDecorations("preview", listOf(Decoration(range, DecorationStyle(
  background = ..., overviewRulerColor = ...))))`. On the web, `deltaDecorations` and
  `createDecorationsCollection`.
- **An assistant's edits:** a decorations owner of their own, with `wholeLine` and `gutterColor`.
- **Problems at a glance:** `EditorOptions(overviewRuler = true)`.
- **Colours:** `EditorOptions(colorDecorators = true)`; the built-in XML service reads Android's
  `#AARRGGBB`. A service of your own adds `documentColors` for `@color/...` references.
- **Tag pairs:** `EditorOptions(linkedEditing = true)`.
- **Phones:** `EditorOptions(pinchToZoom = true)`, and `EditorTheme.fromColorScheme(colorScheme,
  dark)` to follow the app's Material 3 colours. `CodeBlock` for code on reference screens.
- **Keys:** `EditorOptions(keyBindings = ...)` to move or remove the editor's keys.
