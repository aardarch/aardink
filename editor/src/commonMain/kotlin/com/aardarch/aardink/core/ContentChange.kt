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
 * One change to the document's text, as [CodeEditorState.addContentListener] reports it: Monaco's
 * `IModelContentChange`, and enough for a language server's incremental `didChange`.
 *
 * The characters in `[offset, offset + deletedLength)` were replaced by [insertedText]. In line
 * terms the replaced text ran from ([startLine], [startColumn]) to ([oldEndLine], [oldEndColumn])
 * of the text as it was just before this change (0-based lines, columns in UTF-16 code units).
 *
 * The changes of one edit are reported together, in the order they were made, and each is
 * relative to the text the ones before it left: apply them in order to a copy of the old text and
 * you have the new one. An edit at several carets is several changes; a replacement may be a
 * deletion followed by an insertion at the same offset.
 *
 * @property version The document's version after this change. It only grows; LSP's `version`.
 * @property kind What made the change: an edit, an undo, a redo, or a whole new text.
 * @property isReset The whole text was replaced ([CodeEditorState.loadText]): [insertedText] is
 *   the new text and the other positions describe the whole old text.
 */
data class ContentChange(
    val offset: Int,
    val deletedLength: Int,
    val insertedText: String,
    val startLine: Int,
    val startColumn: Int,
    val oldEndLine: Int,
    val oldEndColumn: Int,
    val version: Long,
    val kind: EditChangeKind,
    val isReset: Boolean = false,
)

/** Why the selections changed, as [CodeEditorState.addSelectionListener] reports it. */
enum class SelectionChangeReason {
    /** A key moved or extended them (arrows, Home, Ctrl+D, Tab through a snippet), or an input method. */
    Keyboard,

    /** A click, a tap, a drag, a selection handle or a menu over the text. */
    Pointer,

    /** An edit moved them: typing, deleting, pasting, a command or a host's edit. */
    Edit,

    /** [CodeEditorState.undo] put them back where they were. */
    Undo,

    /** [CodeEditorState.redo] put them back where they were. */
    Redo,

    /** [CodeEditorState.loadText] replaced the whole text. */
    Flush,

    /** The host set them ([CodeEditorState.setSelections], [CodeEditorState.selection], a navigation). */
    Api,
}
