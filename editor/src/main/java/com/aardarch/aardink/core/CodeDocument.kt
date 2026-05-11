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
 * Mutable text document with an efficient line-start index.
 *
 * Internally backed by a [StringBuilder]. Edits update a dirty-line range so the tokenizer
 * only re-processes changed lines. The line-start index is rebuilt lazily after each edit.
 */
class CodeDocument(initialText: String = "") {

    private val buffer = StringBuilder(initialText)

    // Sorted array of character offsets where each line starts.
    // lineStarts[0] == 0 always. Rebuilt whenever dirty.
    private var lineStarts: IntArray = computeLineStarts(initialText)
    private var lineStartsDirty = false

    /**
     * The range of lines (0-based, inclusive) that were dirtied by the last edit, or null if the
     * document is clean. Cleared by the [TokenCache] after a tokenization pass.
     */
    var dirtyLines: IntRange? = null
        internal set

    // ── Read ─────────────────────────────────────────────────────────────────

    val length: Int get() = buffer.length

    /** Full document text. O(n) copy — cache the result if you call it frequently. */
    val text: String get() = buffer.toString()

    /** Number of lines (always ≥ 1). */
    val lineCount: Int get() = freshLineStarts().size

    /** Character offset of the first character on [line] (0-based). */
    fun lineStart(line: Int): Int = freshLineStarts()[line.coerceIn(0, lineCount - 1)]

    /** Character offset one past the last character on [line] (exclusive, points at \n or end). */
    fun lineEnd(line: Int): Int {
        val starts = freshLineStarts()
        val clampedLine = line.coerceIn(0, starts.size - 1)
        return if (clampedLine + 1 < starts.size) {
            // End is one before the \n that starts the next line
            starts[clampedLine + 1] - 1
        } else {
            buffer.length
        }
    }

    /** The text of a single line, without the trailing newline. */
    fun lineText(line: Int): String {
        val start = lineStart(line)
        val end = lineEnd(line)
        return if (start <= end) buffer.substring(start, end) else ""
    }

    /**
     * Converts a character [offset] to a (0-based line, 0-based column) pair.
     * Column is measured in UTF-16 code units (same as Compose's TextRange).
     */
    fun offsetToLineCol(offset: Int): Pair<Int, Int> {
        val clamped = offset.coerceIn(0, buffer.length)
        val starts = freshLineStarts()
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= clamped) lo = mid else hi = mid - 1
        }
        return Pair(lo, clamped - starts[lo])
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
        val clampedOffset = offset.coerceIn(0, buffer.length)
        val (startLine, _) = offsetToLineCol(clampedOffset)
        buffer.insert(clampedOffset, text)
        invalidateLineStarts()
        val addedLines = text.count { it == '\n' }
        markDirty(startLine, startLine + addedLines)
    }

    /**
     * Deletes [length] characters starting at [offset].
     * Expands the dirty range to cover lines that were collapsed by newline removal.
     */
    fun delete(offset: Int, length: Int) {
        if (length <= 0) return
        val start = offset.coerceIn(0, buffer.length)
        val end = (start + length).coerceIn(start, buffer.length)
        val deletedText = buffer.substring(start, end)
        val (startLine, _) = offsetToLineCol(start)
        val removedLines = deletedText.count { it == '\n' }
        buffer.delete(start, end)
        invalidateLineStarts()
        // After deletion the dirty range is just the start line (collapsed lines are gone)
        markDirty(startLine, startLine)
        // If lines were removed, the dirty range extends from startLine to the end of the document
        // so the TokenCache can discard stale line entries.
        if (removedLines > 0) {
            markDirty(startLine, lineCount - 1)
        }
    }

    /**
     * Replaces the entire document content with [newText].
     * Marks all lines dirty.
     */
    fun replaceAll(newText: String) {
        buffer.clear()
        buffer.append(newText)
        invalidateLineStarts()
        markDirty(0, lineCount - 1)
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun invalidateLineStarts() {
        lineStartsDirty = true
    }

    private fun freshLineStarts(): IntArray {
        if (lineStartsDirty) {
            lineStarts = computeLineStarts(buffer)
            lineStartsDirty = false
        }
        return lineStarts
    }

    private fun markDirty(fromLine: Int, toLine: Int) {
        val current = dirtyLines
        dirtyLines = if (current == null) {
            fromLine..toLine
        } else {
            minOf(current.first, fromLine)..maxOf(current.last, toLine)
        }
    }

    private companion object {
        fun computeLineStarts(text: CharSequence): IntArray {
            val result = mutableListOf(0)
            for (i in text.indices) {
                if (text[i] == '\n') result.add(i + 1)
            }
            return result.toIntArray()
        }
    }
}
