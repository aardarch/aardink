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

# Migrating from Aardink 0.6 to 0.7

0.7 makes the editor controllable and observable by its host: content and selection events,
commands a toolbar or a menu can run, host items in the touch chrome, richer diagnostics, a
completion list the editor arranges itself, language composition in Kotlin, Monaco theme data,
and an XML language service generated from a schema. [AARDINK_ROADMAP.md](AARDINK_ROADMAP.md) §5
has the plan and §11 the API ledger.

Most of it is additive. Three changes break source that implements or builds the editor's types:

- `LanguageService.smartIndent` returns the indentation as a `String`, and it, `format` and
  `formatRange` take `FormattingOptions`.
- `Diagnostic.lineNumber` is gone; the old constructor still compiles, deprecated.
- `CompletionKind` and `DiagnosticSeverity` have new entries, so an exhaustive `when` over either
  needs a branch for them.

Every data class and composable that gained a parameter (`CompletionItem`, `Diagnostic`,
`EditorOptions`, `CodeEditorLayout`, `KeyboardToolbarRow`, the LSP and web message classes)
changed its binary signature: recompile against 0.7. Calls by name or by position keep compiling.

## Dependencies

```kotlin
implementation("com.aardarch:aardink:0.7.0")
implementation("com.aardarch:aardink-languages:0.7.0")
```

On the web: `pnpm add @aardarch/aardink-web@^0.7.0`.

## `LanguageService`

| 0.6 | 0.7 |
| --- | --- |
| `fun smartIndent(document, lineIndex): Int`, a number of spaces | `fun smartIndent(document, lineIndex, options: FormattingOptions): String`, the whitespace itself, `""` for none. With `insertSpaces` off the editor now gets tabs |
| `suspend fun format(document): String` | `suspend fun format(document, options: FormattingOptions): String`: indent with `options` |
| `suspend fun formatRange(document, range): List<TextEdit>` | `suspend fun formatRange(document, range, options: FormattingOptions): List<TextEdit>` |
| (none) | `val completionsAreIncomplete: Boolean`, default `true`: as 0.6, asked again on every letter. Return `false` to be asked once per word and let the editor filter |

A service written for 0.6:

```kotlin
override fun smartIndent(document: CodeDocument, lineIndex: Int): Int {
    if (lineIndex <= 0) return 0
    val previous = document.lineText(lineIndex - 1)
    val indent = previous.takeWhile { it == ' ' }.length
    return if (previous.trimEnd().endsWith("{")) indent + 4 else indent
}

override suspend fun format(document: CodeDocument): String = myFormatter(document.text, indent = "    ")
```

becomes:

```kotlin
override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String {
    if (lineIndex <= 0) return ""
    val previous = document.lineText(lineIndex - 1)
    return options.indentAfter(previous, levels = if (previous.trimEnd().endsWith("{")) 1 else 0)
}

override suspend fun format(document: CodeDocument, options: FormattingOptions): String =
    myFormatter(document.text, indent = options.indentUnit)
```

`FormattingOptions` measures indentation in columns: `indentWidth(line)` reads a line's,
`indentOf(columns)` writes one as spaces or tabs, `indentAfter(line, levels)` does both, and
`indentUnit` is one level. The editor passes its `EditorOptions.tabSize` and `insertSpaces`.
Callers can leave `options` out: it defaults to four spaces, as 0.6 assumed.

## `Diagnostic`

| 0.6 | 0.7 |
| --- | --- |
| `Diagnostic(range, lineNumber, message, severity, source)` | `Diagnostic(range, message, severity, source, code, codeDescription, tags, relatedInformation)`. The old constructor still compiles, deprecated, and ignores the line |
| `diagnostic.lineNumber` | `diagnostic.lineIn(document)`: the line is worked out from `range`, which the editor keeps on its text as it is edited |
| `DiagnosticSeverity.Error`, `Warning`, `Info` | `Hint` too: three dots under the start, no gutter dot. Add a branch to an exhaustive `when` |
| (none) | `code` and `codeDescription` are shown after the source; `tags` draw `Unnecessary` code faded and `Deprecated` code struck through; `relatedInformation` points elsewhere |

```kotlin
// 0.6
Diagnostic(range = start until end, lineNumber = line, message = "Unknown module", severity = DiagnosticSeverity.Error)
// 0.7
Diagnostic(range = start until end, message = "Unknown module", severity = DiagnosticSeverity.Error, code = "AF102")
```

A range may now run over several lines: the squiggle follows it, and on the web the marker
carries `endLineNumber`.

## `CompletionItem` and `CompletionKind`

| 0.6 | 0.7 |
| --- | --- |
| Eight kinds, for XML and aardflex | The eight, then Monaco's: `Method`, `Function`, `Constructor`, `Field`, `Variable`, `Class`, `Struct`, `Interface`, `Enum`, `EnumMember`, `Keyword`, `Text`, `Color`, `File`, `Folder`, `Reference`, `Constant`, `Event`, `Operator`, `TypeParameter`, `Unit`, `User`, `Issue`. A `when` over the kind needs an `else` |
| `filterText` and `sortPriority` documented, never read | The editor orders the list by `sortPriority`, keeping your order among equals. With `completionsAreIncomplete = false` it also filters by `filterText` |
| (none) | `detail`, shown beside the label; `commitCharacters`, which accept the selected item when typed |

## The language server bridge

| 0.6 | 0.7 |
| --- | --- |
| Formatting always asked for four spaces | The editor's `tabSize` and `insertSpaces` |
| Severity 4 became `Info` | `Hint` |
| Completion kinds squashed into eight | LSP's kinds, one for one |
| `detail` shown as the documentation | `CompletionItem.detail`; `documentation` stays the documentation |
| Every keystroke asked the server again | A list the server marks complete (`isIncomplete: false`, or a plain array) is asked for once per word and filtered by the editor; `sortText` orders it |
| `LspDiagnostic`, `LspCompletionItem` | Gained `codeDescription`, `tags`, `relatedInformation` and `commitCharacters` (new `LspCodeDescription`, `LspDiagnosticRelatedInformation`) |

## The web

### The npm package

Nothing is removed. These change what an existing app sees:

| Area | 0.6 | 0.7 |
| --- | --- | --- |
| A completion's `detail` | Shown in place of `documentation` when there was none | Its own line beside the label |
| `kind` names | Unknown names showed as `value` | Monaco's names (`'method'`, `'class'`, `'keyword'`, ...) and `CompletionItemKind` numbers are understood. `'function'` and `'color'` still mean `transform` and `colorRef` |
| Diagnostics from `onDidChangeDiagnostics` | One line each, `{ line, startColumn, endColumn, message, severity }` | Also `endLineNumber`, `startLineNumber`, `source`, `code`, `codeHref`, `tags` and `relatedInformation` when they have them |
| `setDiagnostics` and `provideDiagnostics` | Our shape only | Monaco's `IMarkerData` passes as it is: `startLineNumber`, numeric `MarkerSeverity`, `code` as `{ value, target }`; `severity: 'hint'` |
| F8 | Typed a `w` in some browsers (fixed in 0.6.3) | Goes to the next problem, with Shift+F8 the previous one |

### A wasm module built from the export template

`AardinkWeb.onContentChange`'s callback takes a fourth argument, the changes as JSON. Copy the
template's new `aardinkOnContentChange` and the new exports (`aardinkOnSelectionChange`,
`aardinkOnFocusChange`, `aardinkGetPosition`, `aardinkSetPosition`, `aardinkTrigger`,
`aardinkCanTrigger`, `aardinkActionLabel`, `aardinkAddAction`, `aardinkRemoveAction`,
`aardinkAddCommand`, `aardinkRevealLine`, `aardinkRevealRange`) from
`editor-web/src/wasmJsTest/.../ExportsTemplate.kt`.

`WebDiagnostic` gained optional fields; `WebEditorOptions` gained `quickSuggestions`.

## Changed behaviour

| Area | 0.6 | 0.7 |
| --- | --- | --- |
| Gutter dots | A line with an error and an info showed the info's dot | The most severe one's |
| Ctrl+F / Ctrl+H | Opened the panel with the last query | A selection on one line becomes the query, and the query is selected in its field, so typing replaces it, as in VS Code |
| The touch menu | Cut, Copy, Paste, Select all, Info | Also Format and Quick Fix when a language service can, and your `touchMenuItems`; it scrolls sideways when it does not fit |
| The right-click menu | No rename or quick fix | Rename Symbol (F2) and Quick Fix (Ctrl+.), and your `contextMenuItems` |
| Escape | Closed completions or the find panel | Also the hover and the diagnostic banner |
| Enter and paste in a CR LF file | Inserted LF, mixing line endings | Insert the file's own line ending (`CodeDocument.eol`), or `EditorOptions.eol` |

## Worth adopting

- **Listen instead of polling.** `state.addContentListener { changes -> … }` (or
  `contentChanges()`, a `Flow`) reports every edit with its range and inserted text;
  `addSelectionListener` reports the selections with why they changed; `isFocused` is snapshot
  state. `CodeDocument.snapshot()` is public, for work on another thread.
- **Commands for buttons.** `state.execute(EditorCommand.Format)`, `TriggerSuggest`,
  `ShowCodeActions`, `NextProblem`, `InsertSnippet("…")`, `FoldLevel(2)` and the rest;
  `availableCommands` (snapshot state) or `canExecute` to enable them.
- **Phone chrome.** `CodeEditorLayout(keyboardToolbarItems = listOf(KeyboardToolbarItem.Character('|'),
  KeyboardToolbarItem.Action("Format", icon, EditorCommand.Format)), touchMenuItems = …,
  contextMenuItems = …)`.
- **A dialect over a built-in language.** `LanguageDefinition.extending(BuiltInLanguages.Xml, myService)`
  or `CompositeLanguageService(myService, base)` keeps the base's checks, closing tags,
  indentation and formatting under your own service.
- **Markup from data.** `XmlSchemaLanguageService(XmlSchema.parse(json))` gives an XML dialect
  completions by nesting, hover and checks from one JSON description; on the web
  `registerLanguage({ extends: 'xml', schema, … })`.
- **One theme file.** `EditorThemeParser.fromMonacoJson(json)` reads Monaco's `defineTheme`
  data, so the web theme serves a Kotlin editor too.
- **Faster completions.** A service whose answer is complete for the word returns
  `completionsAreIncomplete = false`; on the web, answer `{ suggestions, incomplete: false }`.
  `EditorOptions(quickSuggestions = true)` opens the list on letters.

## Example: the aardflex Android app

The app (`apps/android/aardflex/v1.0`) is on 0.6.2. Its upgrade:

1. `gradle/libs.versions.toml`: `aardink = "0.7.0"`.
2. `service/language/AardflexXmlLanguageService.kt`: `smartIndent(document, lineIndex, options)`
   returns `options.indentAfter(previousLine, levels)`; `format(document, options)` passes
   `options.indentUnit` to `XmlPrettyPrinter`; the `Diagnostic(...)` call drops `lineNumber` and
   can now give the validator's exact range and `FriendlyErrorMapper`'s message.
3. Compose it with the built-in XML service, so its tag checks, closing tags on `>` and quotes come
   back: `CompositeLanguageService(AardflexXmlLanguageService(...), BuiltInLanguages.Xml.languageService!!)`,
   or better, generate an `XmlSchema` from `shared/generated/reference.json` plus the
   structural descriptors and use `XmlSchemaLanguageService(schema)` with the expression
   completions as the primary.
4. `viewmodel/XmlEditorViewModel.kt`: replace the cursor-callback refresh and the 500 ms timer
   with `state.addContentListener { … }`, and `CodeDocument(document.text)` with
   `document.snapshot()`.
5. `ui/screen/XmlEditorScreen.kt`: `keyboardToolbarItems = listOf(KeyboardToolbarItem.Character('|'),
   KeyboardToolbarItem.Character(':'), KeyboardToolbarItem.Character('$'),
   KeyboardToolbarItem.Action("Format", command = EditorCommand.Format))`, and a Validate
   button that runs `EditorCommand.NextProblem`.
6. `ui/theme/AardflexEditorTheme.kt`: `EditorThemeParser.fromMonacoJson(sharedThemeJson)` instead
   of the hand-copied palette.

## Example: aardflex-web-app

1. `pnpm add @aardarch/aardink-web@^0.7.0`.
2. `src/editor/editor.ts`: `quickSuggestions: true`; answer completions with
   `{ suggestions, incomplete: false }` and drop the app's own ranking in `completions.ts`.
3. `AppShell.svelte`: replace the capture-phase Ctrl+S and F8 handlers with
   `editor.addCommand('CtrlCmd+S', save)`; F8 and Shift+F8 are the editor's own now. Remove the
   comment about F8 typing a `w`.
4. `registerLanguage({ id: 'aardflex-xml', extends: 'xml', schema, … })` with the schema built
   from `reference.json`, and delete what it replaces in `schema.ts`, `completions.ts` and
   `diagnostics.ts`. Keep the expression completions and hover in the providers.
5. `ProblemsPanel.svelte`: diagnostics now span lines (`endLineNumber`); `scan.ts` no longer needs
   to cut them to one.
