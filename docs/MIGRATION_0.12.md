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

# Migrating from Aardink 0.11 to 0.12

[AARDINK_ROADMAP.md](AARDINK_ROADMAP.md) §9.1 has the plan for 0.12, §17 the API ledger and §17.1 what
was built where it differs.

0.12 adds a diff editor and changes little else: an editor that does not use it looks and behaves
as before, except that the gutter's diff lane now marks the right lines. What can break an upgrade is
in the table, and the sections after it give the details.

## At a glance

| Change | Who it reaches | What to do |
| --- | --- | --- |
| `LineDiffKind` gains `Deleted` | An exhaustive `when` over `LineDiffKind`, such as a host's own `DiffProvider` reader | Add the branch: [Deleted lines in the lane](#deleted-lines-in-the-lane) |
| `SimpleDiffProvider` marks lines after a real diff | Hosts that read its marks, or test them | Nothing, unless a test pinned the old marks: [Deleted lines in the lane](#deleted-lines-in-the-lane) |
| `EditorCommand` gains `NextChange`, `PreviousChange` and `RevertChange`; F7 and Shift+F7 are bound in `EditorKeyBindings.Default` | An exhaustive `when` over `EditorCommand`; a host that already used F7 | Add the branches. Outside a diff editor the commands cannot run, so F7 reaches your own handler as before |
| `ChromeColors` gains eight fields | Code compiled against 0.11 that calls its constructor, `copy` or `componentN` | Recompile. Every new field is last and defaults to `Color.Unspecified` |
| `CodeEditorLayout` gains a defaulted `diffProvider`; `LanguageService` gains `semanticTokens(document, range)` with a default | Code compiled against 0.11 | Recompile |
| The export template gains the diff editor's exports | A wasm module built from `ExportsTemplate.kt` | Copy them: [A wasm module built from the export template](#a-wasm-module-built-from-the-export-template) |
| A language server is asked for `semanticTokens/range` | Servers that declare range support | Nothing: [Semantic tokens for the lines on screen](#semantic-tokens-for-the-lines-on-screen) |

## Deleted lines in the lane

The lane compared the text with `savedText` line by line in place, after trimming what the two share
at either end, so a line inserted near the top could mark every changed line below it as modified,
and a deletion was never marked. `SimpleDiffProvider` now uses `TextDiff`, the same diff as the diff
editor, so only the lines that changed are marked, and lines deleted from the baseline get a
`LineDiff(line, LineDiffKind.Deleted)`, drawn as a small red wedge on the top edge of the line below
them. A `Deleted` mark's line is the document's line count when the lines were deleted at the end.

```kotlin
// 0.11
when (diff.kind) {
    LineDiffKind.Added -> added += diff.lineIndex
    LineDiffKind.Modified -> modified += diff.lineIndex
}

// 0.12
when (diff.kind) {
    LineDiffKind.Added -> added += diff.lineIndex
    LineDiffKind.Modified -> modified += diff.lineIndex
    LineDiffKind.Deleted -> deletedAbove += diff.lineIndex
}
```

A provider of your own goes in `CodeEditorLayout(diffProvider = ...)`. The lane's colours come from
the theme's `ChromeColors.gutterAdded`, `gutterModified` and `gutterDeleted` (Monaco's
`editorGutter.*` keys); left unset, the lane is drawn in the colours it always had.

## The diff editor

`CodeDiffEditorLayout` shows two `CodeEditorState`s through a `DiffEditorState`. It is new, and
nothing existing changes for it:

```kotlin
val diff = rememberDiffEditorState(original, modified)
CodeDiffEditorLayout(state = diff, options = DiffEditorOptions(renderSideBySide = true))
```

On the web, `createDiffEditor(container, { original, modified, language })` returns an editor whose
`getOriginalEditor()` and `getModifiedEditor()` are ordinary `AardinkEditor`s. Monaco's diff editor
takes two models; until models arrive in 0.13 it takes the two texts, and `setOriginalValue` and
`setModifiedValue` replace them. `getLineChanges()` answers in Monaco's `ILineChange` shape.

## Semantic tokens for the lines on screen

`LanguageService.semanticTokens(document, range)` is new, and its default answers null, which keeps
0.11's behaviour. A service that answers it gets the lines on screen coloured while the whole
document's answer is on its way. `LspLanguageService` answers it with `textDocument/semanticTokens/range`
for a server that declares or registers range support, and the client capabilities now say it takes
range answers.

## A wasm module built from the export template

Copy `aardinkCreateDiff`, `aardinkDiffSetValues`, `aardinkDiffLineChanges`, `aardinkDiffGoTo`,
`aardinkDiffRevert`, `aardinkDiffUpdateOptions`, `aardinkDiffDispose` and `aardinkOnDidUpdateDiff`
from `ExportsTemplate.kt` into your copy. The npm package's `createDiffEditor` calls them, and a
module without them fails only when a diff editor is created.
