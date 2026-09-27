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

import com.aardarch.aardink.core.text.DocumentChange
import com.aardarch.aardink.core.text.DocumentChangeListener
import com.aardarch.aardink.core.text.GapBuffer
import com.aardarch.aardink.core.text.LineIndex

/**
 * Mutable text document with an efficient line-start index.
 *
 * The text lives in a gap buffer and the line starts in an index that is updated edit by edit, so
 * typing costs the size of the edit rather than the size of the document. The document is also a
 * [CharSequence]: read characters, slices ([subSequence]) and regex matches straight from it
 * instead of copying the whole [text].
 *
 * Edits update a dirty-line range so the tokenizer only re-processes changed lines.
 *
 * The documents [LanguageService] methods receive from the editor are read-only snapshots taken
 * when the request was made: they never change under a service running on another thread, and
 * editing one throws [IllegalStateException].
 */
class CodeDocument(initialText: String = "") : CharSequence {

    // An editable document has a buffer and a line index; a snapshot has frozen text and line
    // starts instead. Exactly one pair is set.
    private var buffer: GapBuffer? = GapBuffer(initialText)
    private var lines: LineIndex? = LineIndex(initialText)
    private var frozenText: String? = null
    private var frozenStarts: IntArray? = null

    /** A read-only snapshot over [text] with the given line starts. */
    private constructor(text: String, lineStarts: IntArray) : this() {
        buffer = null
        lines = null
        frozenText = text
        frozenStarts = lineStarts
        cachedText = text
    }

    /**
     * The range of lines (0-based, inclusive) that were dirtied by the last edit, or null if the
     * document is clean. Cleared by the editor after a tokenization pass.
     */
    var dirtyLines: IntRange? = null
        internal set

    /** Bumped by every edit; a snapshot carries the version it was taken at. */
    internal var version: Long = 0
        private set

    private var cachedText: String? = null
    private var cachedSnapshot: CodeDocument? = null
    private var listeners: List<DocumentChangeListener> = emptyList()

    // ── Read ─────────────────────────────────────────────────────────────────

    override val length: Int
        get() {
            val frozen = frozenText
            return if (frozen != null) frozen.length else buffer!!.length
        }

    /** Full document text. Built once per edit and then shared, so repeated reads are free. */
    val text: String
        get() = cachedText ?: buffer!!.toString().also { cachedText = it }

    /** Number of lines (always ≥ 1). */
    val lineCount: Int
        get() {
            val frozen = frozenStarts
            return if (frozen != null) frozen.size else lines!!.lineCount
        }

    override fun get(index: Int): Char {
        val frozen = frozenText
        return if (frozen != null) frozen[index] else buffer!![index]
    }

    /** The characters in [startIndex, endIndex) as a String. */
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        val frozen = frozenText
        return if (frozen != null) frozen.substring(startIndex, endIndex) else buffer!!.substring(startIndex, endIndex)
    }

    override fun toString(): String = text

    /** Character offset of the first character on [line] (0-based). */
    fun lineStart(line: Int): Int = rawLineStart(line.coerceIn(0, lineCount - 1))

    /** Character offset one past the last character on [line] (exclusive, points at \n or end). */
    fun lineEnd(line: Int): Int {
        val clampedLine = line.coerceIn(0, lineCount - 1)
        // End is one before the \n that starts the next line
        return if (clampedLine + 1 < lineCount) rawLineStart(clampedLine + 1) - 1 else length
    }

    /** The text of a single line, without the trailing newline. */
    fun lineText(line: Int): String {
        val start = lineStart(line)
        val end = lineEnd(line)
        return if (start <= end) subSequence(start, end).toString() else ""
    }

    /**
     * Converts a character [offset] to a (0-based line, 0-based column) pair.
     * Column is measured in UTF-16 code units (same as Compose's TextRange).
     */
    fun offsetToLineCol(offset: Int): Pair<Int, Int> {
        val clamped = offset.coerceIn(0, length)
        val line = lineOfOffset(clamped)
        return Pair(line, clamped - rawLineStart(line))
    }

    /** Converts (0-based [line], 0-based [column]) to a character offset. */
    fun lineColToOffset(line: Int, column: Int): Int {
        val start = lineStart(line)
        val end = lineEnd(line)
        return (start + column).coerceIn(start, end)
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Inserts [text] at character [offset].
     * Updates the dirty-line range to cover at minimum the line containing [offset] plus any
     * additional lines introduced by newlines in [text].
     */
    fun insert(offset: Int, text: String) {
        val buffer = writableBuffer()
        if (text.isEmpty()) return
        val lines = lines!!
        val clampedOffset = offset.coerceIn(0, buffer.length)
        val startColumn = clampedOffset - lines.lineStart(lines.lineOf(clampedOffset))
        val startLine = lines.onInsert(clampedOffset, text)
        buffer.insert(clampedOffset, text)
        val addedLines = text.count { it == '\n' }
        val tailColumn = if (addedLines == 0) startColumn + text.length else text.length - (text.lastIndexOf('\n') + 1)
        markDirty(startLine, startLine + addedLines)
        changed(
            DocumentChange(
                offset = clampedOffset,
                deletedLength = 0,
                insertedLength = text.length,
                startLine = startLine,
                startColumn = startColumn,
                removedLines = 0,
                addedLines = addedLines,
                oldTailColumn = startColumn,
                tailColumn = tailColumn,
                version = version + 1,
            ),
        )
    }

    /**
     * Deletes [length] characters starting at [offset].
     * Expands the dirty range to cover lines that were collapsed by newline removal.
     */
    fun delete(offset: Int, length: Int) {
        val buffer = writableBuffer()
        if (length <= 0) return
        val start = offset.coerceIn(0, buffer.length)
        val end = (start + length).coerceIn(start, buffer.length)
        if (start == end) return
        val lines = lines!!
        val startColumn = start - lines.lineStart(lines.lineOf(start))
        val oldTailColumn = end - lines.lineStart(lines.lineOf(end))
        val (startLine, removedLines) = lines.onDelete(start, end)
        buffer.delete(start, end)
        // After deletion the dirty range is just the start line: the collapsed lines are gone, and
        // the lines below kept their text, just further up.
        markDirty(startLine, startLine)
        changed(
            DocumentChange(
                offset = start,
                deletedLength = end - start,
                insertedLength = 0,
                startLine = startLine,
                startColumn = startColumn,
                removedLines = removedLines,
                addedLines = 0,
                oldTailColumn = oldTailColumn,
                tailColumn = startColumn,
                version = version + 1,
            ),
        )
    }

    /**
     * Replaces the entire document content with [newText].
     * Marks all lines dirty.
     */
    fun replaceAll(newText: String) {
        val buffer = writableBuffer()
        val oldLength = buffer.length
        val oldLineCount = lineCount
        buffer.clear()
        buffer.insert(0, newText)
        lines!!.reset(newText)
        markDirty(0, lineCount - 1)
        changed(
            DocumentChange(
                offset = 0,
                deletedLength = oldLength,
                insertedLength = newText.length,
                startLine = 0,
                startColumn = 0,
                removedLines = oldLineCount - 1,
                addedLines = lineCount - 1,
                oldTailColumn = 0,
                tailColumn = 0,
                version = version + 1,
                isReset = true,
            ),
        )
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * A read-only copy of this document as it is now, for work that runs on another thread or
     * outlives the next edit. Shares the text with [text]; the same snapshot is returned until
     * the document changes.
     */
    internal fun snapshot(): CodeDocument {
        if (buffer == null) return this
        cachedSnapshot?.let { if (it.version == version) return it }
        return CodeDocument(text, lines!!.toArray())
            .also {
                it.version = version
                cachedSnapshot = it
            }
    }

    internal val isSnapshot: Boolean get() = buffer == null

    internal fun addChangeListener(listener: DocumentChangeListener) {
        listeners = listeners + listener
    }

    internal fun removeChangeListener(listener: DocumentChangeListener) {
        listeners = listeners - listener
    }

    private fun writableBuffer(): GapBuffer =
        buffer ?: throw IllegalStateException("This CodeDocument is a read-only snapshot; edit the editor's own document")

    private fun changed(change: DocumentChange) {
        version = change.version
        cachedText = null
        cachedSnapshot = null
        listeners.forEach { it.onDocumentChange(change) }
    }

    private fun rawLineStart(line: Int): Int {
        val frozen = frozenStarts
        return if (frozen != null) frozen[line] else lines!!.lineStart(line)
    }

    private fun lineOfOffset(offset: Int): Int {
        val starts = frozenStarts ?: return lines!!.lineOf(offset)
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun markDirty(fromLine: Int, toLine: Int) {
        val current = dirtyLines
        dirtyLines = if (current == null) {
            fromLine..toLine
        } else {
            minOf(current.first, fromLine)..maxOf(current.last, toLine)
        }
    }
}
