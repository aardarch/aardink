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
package com.aardarch.aardink.ui.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteAllCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.MoveCursorCommand
import androidx.compose.ui.text.input.SetComposingRegionCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.text.input.TextFieldValue
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.edit.SelectionSet
import kotlin.math.max
import kotlin.math.min

/** Where the editor is on screen, for an input method to place its candidate window and keyboard. */
internal interface EditorImeGeometry {
    /** The primary caret, in root coordinates; null before the editor is laid out. */
    fun caretRectInRoot(): Rect?

    /** The text area, in root coordinates. */
    fun boundsInRoot(): Rect?

    /** Where the text's origin is, in root coordinates. */
    fun textOriginInRoot(): Offset?

    /** The rectangle of [offset]'s character, in root coordinates; null when it is not on screen. */
    fun characterBoundsInRoot(offset: Int): Rect?
}

/**
 * Between an input method and the editor: what the input method reads and edits, in its own
 * coordinates, and how its edits reach [CodeEditorState].
 *
 * Every edit goes into an [ImeWindowBuffer] batch and arrives at the state as one minimal change
 * through [CodeEditorState.applyInput], the same path as typing on a hardware keyboard, so the
 * typing rules, undo grouping and multi-cursor mirroring are the same for both. The composition
 * lives here ([composition], in document offsets): the view underlines it, and it ends when
 * anything but the input method changes the text or moves the caret away from it.
 *
 * With [windowed] (desktop and web), the input method sees a window of the document around the
 * primary caret instead of all of it: Compose's web input writes the whole value into a hidden
 * text area on every change, so a window is what keeps a keystroke's cost independent of the
 * document's size. The window moves only between compositions. Android reads bounded slices of
 * the whole document instead ([windowed] false): moving a window there would mean restarting the
 * input method, which makes the keyboard flicker.
 */
internal class EditorImeAdapter(val state: CodeEditorState, val windowed: Boolean) {

    /** What the platform's input connection does when the editor changes by itself. */
    interface Listener {
        /** The text changed under the input method (an undo, a paste, a key command): it must start over. */
        fun restartInput()

        /** The caret, selection or composition moved; [selection] and [composition] are in input-method coordinates. */
        fun updateSelection(selection: TextRange, composition: TextRange?)
    }

    private val buffer = ImeWindowBuffer(state.document)
    private var depth = 0

    /** The text being composed, in document offsets, or null. */
    var composition by mutableStateOf<TextRange?>(null)
        private set

    /** The language service for the typing rules; read when an edit arrives. */
    var service: () -> LanguageService? = { null }

    /** Told about every input edit, with the character typed when it was one (for completion). */
    var onInput: (CodeEditorState.TypedInput?) -> Unit = {}

    var listener: Listener? = null

    /** Bumped to ask for the on-screen keyboard (a tap in the text); the Android session shows it. */
    var keyboardRequests by mutableIntStateOf(0)

    /** The text version after this adapter's own last edit; any other means the text changed elsewhere. */
    private var ownVersion = state.textVersion

    private var lastSelection = state.selection

    // The window: [windowStart, document.length - windowTail). The tail is counted from the end so
    // edits inside the window leave both numbers valid.
    private var windowStart = 0
    private var windowTail = 0
    private var windowPlaced = false

    // ── What the input method reads ─────────────────────────────────────────

    /** The text as the input method sees it: the batch's edits so far, or the document. */
    val text: CharSequence get() = if (depth > 0) buffer else state.document

    private val selectionNow: TextRange get() = if (depth > 0) buffer.selection else state.selection.let { TextRange(it.min, it.max) }

    private val compositionNow: TextRange? get() = if (depth > 0) buffer.composition else composition

    /** The first offset the input method sees. */
    val windowOffset: Int
        get() {
            ensureWindow()
            return windowStart
        }

    /** How many characters the input method sees. */
    val windowLength: Int
        get() {
            ensureWindow()
            return (text.length - windowTail - windowStart).coerceAtLeast(0)
        }

    /** The selection, in input-method coordinates, clamped to the window. */
    val imeSelection: TextRange
        get() {
            val offset = windowOffset
            val length = windowLength
            val selection = selectionNow
            return TextRange((selection.min - offset).coerceIn(0, length), (selection.max - offset).coerceIn(0, length))
        }

    /** The composition, in input-method coordinates. */
    val imeComposition: TextRange?
        get() {
            val offset = windowOffset
            val length = windowLength
            return compositionNow?.let {
                TextRange((it.min - offset).coerceIn(0, length), (it.max - offset).coerceIn(0, length))
            }?.takeUnless { it.collapsed }
        }

    /** The window's text, in input-method coordinates [start, end). */
    fun imeText(start: Int, end: Int): CharSequence {
        val offset = windowOffset
        val length = windowLength
        val from = start.coerceIn(0, length)
        val to = end.coerceIn(from, length)
        return text.subSequence(offset + from, offset + to)
    }

    /** Everything the input method sees, as Compose's skiko request hands it over. */
    fun value(): TextFieldValue {
        // Snapshot reads, so a snapshotFlow over this re-emits after every change.
        state.textVersion
        state.selection
        composition
        return TextFieldValue(imeText(0, windowLength).toString(), imeSelection, imeComposition)
    }

    /** Up to [count] characters before the selection. */
    fun textBeforeSelection(count: Int): CharSequence {
        val start = selectionNow.min
        return text.subSequence(max(0, start - max(0, count)), start)
    }

    /** Up to [count] characters after the selection. */
    fun textAfterSelection(count: Int): CharSequence {
        val end = selectionNow.max
        return text.subSequence(end, min(text.length, end + max(0, count)))
    }

    /** The selected text, or null for a caret. */
    fun selectedText(): CharSequence? = selectionNow.takeUnless { it.collapsed }?.let { text.subSequence(it.min, it.max) }

    /** The document selection (for readers that use document offsets, as on Android). */
    val documentSelection: TextRange get() = selectionNow

    /** The document composition (for readers that use document offsets). */
    val documentComposition: TextRange? get() = compositionNow

    private fun ensureWindow() {
        if (!windowed) {
            windowStart = 0
            windowTail = 0
            return
        }
        // Moved only between compositions, and only when the caret got near its edge.
        if (depth > 0 || composition != null) return
        val document = state.document
        val length = document.length
        val end = (length - windowTail).coerceIn(0, length)
        val caret = state.selection.end.coerceIn(0, length)
        val start = windowStart.coerceIn(0, end)
        val nearStart = start > 0 && caret < start + WINDOW_EDGE
        val nearEnd = end < length && caret > end - WINDOW_EDGE
        val oversized = end - start > 2 * WINDOW_CHARS + WINDOW_EDGE
        if (windowPlaced && caret in start..end && !nearStart && !nearEnd && !oversized && windowStart <= length) return
        windowPlaced = true
        val line = document.offsetToLineCol(caret).first
        windowStart = max(document.lineStart((line - WINDOW_LINES).coerceAtLeast(0)), caret - WINDOW_CHARS).coerceAtLeast(0)
        val windowEnd = min(
            document.lineEnd((line + WINDOW_LINES).coerceAtMost(document.lineCount - 1)),
            caret + WINDOW_CHARS,
        ).coerceAtMost(length)
        windowTail = length - windowEnd
    }

    // ── What the input method does ──────────────────────────────────────────

    val inBatch: Boolean get() = depth > 0

    fun beginBatch() {
        if (depth++ == 0) buffer.begin(state.selection, composition)
    }

    /** Ends one level of batching; the outermost end applies the batch. Returns whether a batch is still open. */
    fun endBatch(): Boolean {
        if (depth == 0) return false
        if (--depth == 0) flush()
        return depth > 0
    }

    /** Runs [block] as a batch of its own (or as part of the open one). */
    fun <T> edit(block: ImeWindowBuffer.() -> T): T {
        beginBatch()
        try {
            return buffer.block()
        } finally {
            endBatch()
        }
    }

    /** Compose's skiko `EditCommand`s, in input-method coordinates (desktop and web). */
    fun apply(commands: List<EditCommand>) {
        edit {
            val offset = windowStart
            for (command in commands) {
                when (command) {
                    is CommitTextCommand -> commitText(command.text, command.newCursorPosition)

                    is SetComposingTextCommand -> setComposingText(command.text, command.newCursorPosition)

                    is SetComposingRegionCommand -> setComposingRegion(offset + command.start, offset + command.end)

                    is SetSelectionCommand -> setSelection(offset + command.start, offset + command.end)

                    is DeleteSurroundingTextCommand -> deleteSurroundingText(command.lengthBeforeCursor, command.lengthAfterCursor)

                    is DeleteSurroundingTextInCodePointsCommand ->
                        deleteSurroundingTextInCodePoints(command.lengthBeforeCursor, command.lengthAfterCursor)

                    is FinishComposingTextCommand -> finishComposingText()

                    is BackspaceCommand -> backspace()

                    is MoveCursorCommand -> moveCursor(command.amount)

                    // Everything the input method can see, which in a window is not the document.
                    is DeleteAllCommand -> delete(offset, offset + windowLengthIn(this))
                }
            }
        }
    }

    private fun windowLengthIn(buffer: ImeWindowBuffer): Int = (buffer.length - windowTail - windowStart).coerceAtLeast(0)

    /** Ends any composition, leaving its text as it is (focus lost, a click elsewhere). */
    fun finishComposition() {
        if (composition == null) return
        composition = null
        state.pushUndoStop()
    }

    private fun flush() {
        val change = buffer.change()
        val selection = buffer.selection
        val newComposition = buffer.composition
        val wasComposing = composition != null
        val expectedLength = buffer.length
        if (change != null) {
            val typed = state.applyInput(
                start = change.start,
                end = change.end,
                text = change.text,
                selectionAfter = selection,
                composing = wasComposing || newComposition != null,
                service = service(),
            )
            onInput(typed)
        } else if (selection != state.selection) {
            state.replaceSelections(SelectionSet.single(selection))
        }
        composition = newComposition?.takeIf { it.max <= state.document.length }
        if (wasComposing && composition == null) state.pushUndoStop()
        ownVersion = state.textVersion
        lastSelection = state.selection
        // The typing rules can change more than the input method asked for (an auto-closed
        // bracket, an indent after Enter). Its idea of the text is then stale: start it over.
        if (state.document.length != expectedLength || state.selection != selection) {
            if (composition != null) composition = null
            listener?.restartInput()
        } else {
            listener?.updateSelection(imeSelection, imeComposition)
        }
    }

    /**
     * Called whenever the editor's text, selection or composition may have changed: tells the
     * input method about changes it did not make, and ends a composition the change broke.
     */
    fun onEditorChanged() {
        if (depth > 0) return
        val version = state.textVersion
        val selection = state.selection
        if (version != ownVersion) {
            ownVersion = version
            lastSelection = selection
            if (composition != null) finishComposition()
            listener?.restartInput()
            return
        }
        if (selection != lastSelection) {
            lastSelection = selection
            val composing = composition
            // The caret left the composition: it is finished where it stands.
            if (composing != null && !(selection.collapsed && selection.end == composing.max)) finishComposition()
            listener?.updateSelection(imeSelection, imeComposition)
        }
    }

    private companion object {
        /** Lines on each side of the caret the window takes in. */
        const val WINDOW_LINES = 20

        /** Characters on each side of the caret the window takes in at most. */
        const val WINDOW_CHARS = 4096

        /** How close the caret may come to a window edge (that is not the document's) before it moves. */
        const val WINDOW_EDGE = 256
    }
}
