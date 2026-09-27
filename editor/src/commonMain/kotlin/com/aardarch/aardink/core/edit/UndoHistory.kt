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
package com.aardarch.aardink.core.edit

/** What kind of edit an undo entry groups, for deciding whether the next edit joins it. */
internal enum class EditKind {
    /** Typing characters other than whitespace. */
    Typing,

    /** Typing whitespace; the first space after a word starts a new entry, as in Monaco. */
    TypingSpace,

    /** Backspace. */
    DeletingLeft,

    /** Forward delete. */
    DeletingRight,

    /** Everything else: paste, completion, format, replace, an IME commit, line operations. */
    Other,
}

/**
 * The editor's undo and redo stacks, modelled on Monaco's.
 *
 * Each entry holds the changes of one or more edits, the selections before and after, and the
 * alternative version ids before and after. Consecutive typing joins one entry until the caret
 * moves, the kind of edit changes, a space follows a word, or [pushStop] is called; so undo removes
 * text a word at a time. Every other edit is an entry of its own.
 *
 * [alternativeVersionId] follows Monaco's `getAlternativeVersionId`: a new value for every edit,
 * and on undo or redo the value the document had at that point, so a host can tell whether the
 * text is back at a saved state by comparing one number.
 */
internal class UndoHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    class Entry(
        val kind: EditKind,
        val changes: MutableList<AppliedChange>,
        val selectionsBefore: SelectionSet,
        var selectionsAfter: SelectionSet,
        val alternativeBefore: Long,
        var alternativeAfter: Long,
    ) {
        /** Still accepting typing of the same kind. Closed by [pushStop], undo, and any other edit. */
        var open: Boolean = true
    }

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    private var nextAlternative = 1L

    var alternativeVersionId: Long = 0L
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Records an edit: [changes] as applied, with the selections [before] and [after] it. Joins the
     * open entry when it continues it (same [kind], typing or deleting, caret where the entry left it).
     */
    fun record(kind: EditKind, changes: List<AppliedChange>, before: SelectionSet, after: SelectionSet) {
        if (changes.isEmpty()) return
        redoStack.clear()
        val alternative = nextAlternative++
        val top = undoStack.lastOrNull()
        if (top != null && top.open && continues(top, kind, before)) {
            top.changes.addAll(changes)
            top.selectionsAfter = after
            top.alternativeAfter = alternative
        } else {
            top?.open = false
            undoStack.addLast(Entry(kind, changes.toMutableList(), before, after, alternativeVersionId, alternative))
            if (kind == EditKind.Other) undoStack.last().open = false
            if (undoStack.size > capacity) undoStack.removeFirst()
        }
        alternativeVersionId = alternative
    }

    private fun continues(top: Entry, kind: EditKind, before: SelectionSet): Boolean {
        if (before != top.selectionsAfter) return false // the caret moved
        return when (kind) {
            EditKind.Typing -> top.kind == EditKind.Typing || top.kind == EditKind.TypingSpace
            EditKind.TypingSpace -> top.kind == EditKind.TypingSpace
            EditKind.DeletingLeft -> top.kind == EditKind.DeletingLeft
            EditKind.DeletingRight -> top.kind == EditKind.DeletingRight
            EditKind.Other -> false
        }
    }

    /** Ends the open entry, so the next edit starts a new one (Monaco's `pushUndoStop`). */
    fun pushStop() {
        undoStack.lastOrNull()?.open = false
    }

    /** The entry to revert, now moved to the redo stack; null when there is nothing to undo. */
    fun undo(): Entry? {
        val entry = undoStack.removeLastOrNull() ?: return null
        entry.open = false
        redoStack.addLast(entry)
        alternativeVersionId = entry.alternativeBefore
        return entry
    }

    /** The entry to apply again, now back on the undo stack; null when there is nothing to redo. */
    fun redo(): Entry? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(entry)
        alternativeVersionId = entry.alternativeAfter
        return entry
    }

    /** Forgets all history: loading a new document. The document gets a fresh alternative id. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        alternativeVersionId = nextAlternative++
    }

    companion object {
        const val DEFAULT_CAPACITY = 1_000
    }
}
