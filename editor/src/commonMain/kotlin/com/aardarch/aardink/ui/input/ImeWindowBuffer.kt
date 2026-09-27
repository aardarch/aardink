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

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.edit.TextChange
import com.aardarch.aardink.core.edit.TextNavigator
import kotlin.math.max
import kotlin.math.min

/**
 * The text an input method edits during one batch: the document seen through the edits the batch
 * has made so far, with a selection and a composition, all in document offsets.
 *
 * Input methods send edits in batches (Android's `beginBatchEdit`/`endBatchEdit`, one list of
 * `EditCommand`s on desktop and the web), and rewrite more than they change: a keyboard that
 * adds a letter to a composing word often replaces the whole word. Each command here edits a
 * copy of just the stretch of text the batch has touched ([lo]..[hi] of the document); [change]
 * then turns the whole batch into one minimal change by trimming what the old and new stretch
 * have in common at either end.
 *
 * The commands follow `androidx.compose.ui.text.input.EditingBuffer` exactly: a replacement
 * places the caret after the new text and ends the composition; a deletion moves the selection
 * and composition along, and a composition deleted to nothing ends.
 */
internal class ImeWindowBuffer(private val document: CharSequence) : CharSequence {

    // The document with [lo, hi) replaced by [scratch]; untouched until the first edit.
    private var lo = 0
    private var hi = 0
    private val scratch = StringBuilder()
    private var edited = false

    var selection: TextRange = TextRange.Zero
        private set

    var composition: TextRange? = null
        private set

    /** Starts a batch over the document as it is, with its [selection] and [composition]. */
    fun begin(selection: TextRange, composition: TextRange?) {
        lo = 0
        hi = 0
        scratch.clear()
        edited = false
        this.selection = TextRange(selection.min, selection.max)
        this.composition = composition?.takeUnless { it.collapsed }?.let { TextRange(it.min, it.max) }
    }

    override val length: Int get() = document.length - (hi - lo) + scratch.length

    override fun get(index: Int): Char = when {
        index < lo -> document[index]
        index < lo + scratch.length -> scratch[index - lo]
        else -> document[index - lo - scratch.length + hi]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        val out = StringBuilder(endIndex - startIndex)
        var i = startIndex
        if (i < min(endIndex, lo)) {
            out.appendRange(document, i, min(endIndex, lo))
            i = min(endIndex, lo)
        }
        val scratchEnd = lo + scratch.length
        if (i < endIndex && i < scratchEnd) {
            val to = min(endIndex, scratchEnd)
            out.appendRange(scratch, i - lo, to - lo)
            i = to
        }
        if (i < endIndex) out.appendRange(document, i - scratchEnd + hi, endIndex - scratchEnd + hi)
        return out.toString()
    }

    override fun toString(): String = subSequence(0, length).toString()

    /**
     * The batch as one change against the document: the stretch it rewrote, less what the old and
     * new text of that stretch share at either end. Null when the text is what it was.
     */
    fun change(): TextChange? {
        if (!edited) return null
        val old = document.subSequence(lo, hi)
        val limit = min(old.length, scratch.length)
        var prefix = 0
        while (prefix < limit && old[prefix] == scratch[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && old[old.length - 1 - suffix] == scratch[scratch.length - 1 - suffix]) suffix++
        if (prefix == old.length && prefix == scratch.length) return null
        return TextChange(lo + prefix, hi - suffix, scratch.substring(prefix, scratch.length - suffix))
    }

    // ── EditingBuffer's primitives ──────────────────────────────────────────

    /** Makes [start, end) (buffer offsets) part of the copied stretch. */
    private fun cover(start: Int, end: Int) {
        if (!edited) {
            lo = start
            hi = start
            edited = true
        }
        if (start < lo) {
            scratch.insertRange(0, document, start, lo)
            lo = start
        }
        val scratchEnd = lo + scratch.length
        if (end > scratchEnd) {
            val extra = end - scratchEnd
            scratch.appendRange(document, hi, hi + extra)
            hi += extra
        }
    }

    /** Replaces [start, end) with [text]; the caret goes after it and any composition ends. */
    fun replace(start: Int, end: Int, text: CharSequence) {
        val from = start.coerceIn(0, length)
        val to = end.coerceIn(from, length)
        cover(from, to)
        scratch.setRange(from - lo, to - lo, text.toString())
        selection = TextRange(from + text.length)
        composition = null
    }

    /** Deletes [start, end); the selection and composition move with the text around it. */
    fun delete(start: Int, end: Int) {
        val from = min(start, end).coerceIn(0, length)
        val to = max(start, end).coerceIn(from, length)
        if (from == to) return
        cover(from, to)
        scratch.deleteRange(from - lo, to - lo)
        selection = afterDelete(selection, from, to)
        composition = composition?.let { afterDelete(it, from, to) }?.takeUnless { it.collapsed }
    }

    private fun afterDelete(range: TextRange, from: Int, to: Int): TextRange {
        fun map(offset: Int) = when {
            offset <= from -> offset
            offset >= to -> offset - (to - from)
            else -> from
        }
        return TextRange(map(range.min), map(range.max))
    }

    private fun setSelectionCoerced(start: Int, end: Int) {
        val a = start.coerceIn(0, length)
        val b = end.coerceIn(0, length)
        selection = TextRange(min(a, b), max(a, b))
    }

    /** The collapsed caret, or -1 with a selection. */
    private val cursor: Int get() = if (selection.collapsed) selection.start else -1

    // ── EditCommands ────────────────────────────────────────────────────────

    /** `CommitTextCommand`: replaces the composition, or the selection, and places the caret. */
    fun commitText(text: CharSequence, newCursorPosition: Int) {
        val target = composition ?: selection
        replace(target.min, target.max, text)
        placeCursor(target.min + text.length, text.length, newCursorPosition)
    }

    /** `SetComposingTextCommand`: like [commitText], but the text stays composing. */
    fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        val target = composition ?: selection
        replace(target.min, target.max, text)
        if (text.isNotEmpty()) composition = TextRange(target.min, target.min + text.length)
        placeCursor(target.min + text.length, text.length, newCursorPosition)
    }

    /** The input method's `newCursorPosition`: relative to the end of the text if positive, else to its start. */
    private fun placeCursor(textEnd: Int, textLength: Int, newCursorPosition: Int) {
        val caret = if (newCursorPosition > 0) textEnd + newCursorPosition - 1 else textEnd + newCursorPosition - textLength
        val clamped = caret.coerceIn(0, length)
        selection = TextRange(clamped)
    }

    /** `SetComposingRegionCommand`: existing text becomes the composition (none when empty). */
    fun setComposingRegion(start: Int, end: Int) {
        composition = null
        val a = start.coerceIn(0, length)
        val b = end.coerceIn(0, length)
        if (a != b) composition = TextRange(min(a, b), max(a, b))
    }

    /** `FinishComposingTextCommand`: the composing text stays, as ordinary text. */
    fun finishComposingText() {
        composition = null
    }

    /** `DeleteSurroundingTextCommand`: UTF-16 units before and after the selection. */
    fun deleteSurroundingText(lengthBeforeCursor: Int, lengthAfterCursor: Int) {
        val end = selection.max
        val afterEnd = if (lengthAfterCursor > length - end) length else end + lengthAfterCursor
        delete(end, afterEnd)
        val start = selection.min
        val beforeStart = if (lengthBeforeCursor > start) 0 else start - lengthBeforeCursor
        delete(beforeStart, start)
    }

    /** `DeleteSurroundingTextInCodePointsCommand`: code points before and after the selection. */
    fun deleteSurroundingTextInCodePoints(lengthBeforeCursor: Int, lengthAfterCursor: Int) {
        var after = selection.max
        repeat(lengthAfterCursor) { if (after < length) after = TextNavigator.charRight(this, after) }
        delete(selection.max, after)
        var before = selection.min
        repeat(lengthBeforeCursor) { if (before > 0) before = TextNavigator.charLeft(this, before) }
        delete(before, selection.min)
    }

    /** `SetSelectionCommand`. */
    fun setSelection(start: Int, end: Int) {
        setSelectionCoerced(start, end)
    }

    /** `BackspaceCommand`: the composition, else the selection, else the character before the caret. */
    fun backspace() {
        val composing = composition
        when {
            composing != null -> delete(composing.min, composing.max)

            cursor == -1 -> {
                val range = selection
                selection = TextRange(range.min)
                delete(range.min, range.max)
            }

            cursor > 0 -> delete(TextNavigator.charLeft(this, cursor), cursor)
        }
    }

    /** `MoveCursorCommand`: [amount] characters right (negative: left). */
    fun moveCursor(amount: Int) {
        var caret = if (cursor == -1) selection.min else cursor
        if (amount >
            0
        ) {
            repeat(amount) { caret = TextNavigator.charRight(this, caret) }
        } else {
            repeat(-amount) {
                caret =
                    TextNavigator.charLeft(this, caret)
            }
        }
        selection = TextRange(caret)
    }

    /** `DeleteAllCommand`. */
    fun deleteAll() {
        replace(0, length, "")
    }
}
