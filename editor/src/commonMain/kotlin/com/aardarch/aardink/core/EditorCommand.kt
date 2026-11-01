/*
 * Copyright 2026 Aardarch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aardarch.aardink.core

/**
 * Something the editor can be asked to do, as a key, a menu or a toolbar button would:
 * [CodeEditorState.execute] runs it, [CodeEditorState.canExecute] says whether it would.
 *
 * Text commands ([Undo], [Redo], [ToggleComment], [SelectAllOccurrences], [InsertSnippet]) work on
 * the state alone. The others need what a [CodeEditorLayout][com.aardarch.aardink.ui.CodeEditorLayout]
 * showing the state has (the language service, the view, the find panel, the folds), and do
 * nothing while none shows it.
 */
sealed class EditorCommand {
    /** Ctrl/Cmd+Z. */
    data object Undo : EditorCommand()

    /** Ctrl/Cmd+Y, Ctrl/Cmd+Shift+Z. */
    data object Redo : EditorCommand()

    /** Ctrl/Cmd+/: line comments, or a block comment, per the tokenizer's comment syntax. */
    data object ToggleComment : EditorCommand()

    /** Ctrl/Cmd+Shift+L: a selection at every occurrence of the selection, or of the word at the caret. */
    data object SelectAllOccurrences : EditorCommand()

    /**
     * [snippet], in VS Code's snippet syntax (`$1`, `${1:placeholder}`, `${1|a,b|}`, `$0`), in place
     * of the primary selection, indented as the line it lands on; Tab steps through its stops. For a
     * phone's toolbar button that inserts an element with its required attributes, say.
     */
    data class InsertSnippet(val snippet: String) : EditorCommand()

    /** Shift+Alt+F with no selection: the whole document, through the language service's formatter. */
    data object Format : EditorCommand()

    /** Shift+Alt+F with a selection: the selection, through the language service's `formatRange`. */
    data object FormatSelection : EditorCommand()

    /** Ctrl+Space: the completion list at the caret. */
    data object TriggerSuggest : EditorCommand()

    /** The diagnostics and documentation at the caret, as a resting mouse shows them. */
    data object ShowHover : EditorCommand()

    /** Ctrl+.: the quick fixes for the diagnostic at the caret, or for the selection. */
    data object ShowCodeActions : EditorCommand()

    /** F2: rename the symbol at the caret. */
    data object Rename : EditorCommand()

    /** F12: to the definition of the symbol at the caret. */
    data object GoToDefinition : EditorCommand()

    /** Shift+F12: the references to the symbol at the caret. */
    data object FindReferences : EditorCommand()

    /** F8: select the next problem after the caret and show it. */
    data object NextProblem : EditorCommand()

    /** Shift+F8: the previous problem. */
    data object PreviousProblem : EditorCommand()

    /** Ctrl/Cmd+F: the find panel. */
    data object ShowFind : EditorCommand()

    /** Ctrl+H: the find panel with its replace row. */
    data object ShowReplace : EditorCommand()

    /** Ctrl+G: asks the host for its go-to-line dialog. */
    data object GoToLine : EditorCommand()

    /** Folds every foldable range. */
    data object FoldAll : EditorCommand()

    /** Unfolds everything. */
    data object UnfoldAll : EditorCommand()

    /** Folds the ranges nested [level] deep, 1 being the outermost, as VS Code's "Fold Level". */
    data class FoldLevel(val level: Int) : EditorCommand()

    /** Ctrl/Cmd+Shift+O: a list of the document's symbols to filter and jump to, as VS Code's "Go to Symbol". */
    data object GoToSymbol : EditorCommand()

    /** Scrolls [offset] into view, to the middle when [center], without moving the caret. */
    data class Reveal(val offset: Int, val center: Boolean = false) : EditorCommand()
}
