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

/**
 * The document's tokens, one entry per line, as columns into that line.
 *
 * Kept aligned with [document] edit by edit: an edit shifts the columns on the line it touches and
 * adds or removes whole entries for the lines it adds or removes, so every line below keeps its
 * colours until the next tokenization pass instead of pointing at text that moved. A token that
 * spans lines (a block comment) is split into one run per line.
 */
internal class TokenStore(private val document: CodeDocument) : DocumentChangeListener {

    /** One line's tokens: run `i` covers columns [start(i), end(i)) and has type `types[i]`. */
    internal class LineTokens(private val bounds: IntArray, val types: Array<TokenType>) {
        val size: Int get() = types.size

        fun start(index: Int): Int = bounds[2 * index]

        fun end(index: Int): Int = bounds[2 * index + 1]
    }

    private val lines = ArrayList<LineTokens>()
    private var flat: List<Token>? = null

    init {
        resetLines()
        document.addChangeListener(this)
    }

    val lineCount: Int get() = lines.size

    fun lineTokens(line: Int): LineTokens = lines.getOrNull(line) ?: EMPTY

    /** The tokens on [line] with document-absolute offsets. */
    fun tokensForLine(line: Int): List<Token> {
        val tokens = lineTokens(line)
        if (tokens.size == 0) return emptyList()
        val base = document.lineStart(line)
        return List(tokens.size) { Token(base + tokens.start(it), base + tokens.end(it), tokens.types[it]) }
    }

    /** Every token in document order with absolute offsets; built once per change and shared. */
    fun allTokens(): List<Token> = flat ?: buildList {
        for (line in lines.indices) addAll(tokensForLine(line))
    }.also { flat = it }

    /** Replaces every line with [tokens], the result of a pass over the whole document. */
    fun replaceAll(tokens: List<Token>) {
        val built = bucket(tokens, 0, document.lineCount - 1)
        lines.clear()
        lines.addAll(built)
        flat = null
        document.dirtyLines = null
    }

    /**
     * Replaces the lines [tokens] cover, and at least [dirty], with [tokens]: the result of a pass
     * over part of the document. Lines outside that span keep their tokens.
     */
    fun merge(dirty: IntRange, tokens: List<Token>) {
        val last = document.lineCount - 1
        var from = dirty.first
        var to = dirty.last
        if (tokens.isNotEmpty()) {
            from = minOf(from, document.offsetToLineCol(tokens.first().start).first)
            to = maxOf(to, document.offsetToLineCol(tokens.maxOf { it.end }).first)
        }
        from = from.coerceIn(0, last)
        to = to.coerceIn(from, last)
        val built = bucket(tokens, from, to)
        for (line in from..to) lines[line] = built[line - from]
        flat = null
        document.dirtyLines = null
    }

    override fun onDocumentChange(change: DocumentChange) {
        flat = null
        when {
            change.isReset -> resetLines()
            change.insertedLength > 0 -> onInsert(change)
            change.deletedLength > 0 -> onDelete(change)
        }
        // A listener that missed an edit (it cannot, but a mismatch would misplace every colour
        // below it) falls back to plain text until the next pass rather than drawing wrong ones.
        if (lines.size != document.lineCount) resetLines()
    }

    private fun resetLines() {
        lines.clear()
        repeat(document.lineCount) { lines.add(EMPTY) }
    }

    /** Text was inserted at the change's start column; [LineTokens] after it move with it. */
    private fun onInsert(change: DocumentChange) {
        val line = change.startLine
        val old = lineTokens(line)
        val column = change.startColumn
        val inserted = change.insertedLength
        val head = Builder()
        val tail = Builder()
        val splits = change.addedLines > 0
        for (i in 0 until old.size) {
            val start = old.start(i)
            val end = old.end(i)
            val type = old.types[i]
            when {
                end <= column -> head.add(start, end, type)

                start >= column ->
                    if (splits) {
                        tail.add(start - column + change.tailColumn, end - column + change.tailColumn, type)
                    } else {
                        head.add(start + inserted, end + inserted, type)
                    }

                // Typing inside a token (an identifier, a string) keeps it one colour.
                !splits -> head.add(start, end + inserted, type)

                else -> {
                    head.add(start, column, type)
                    tail.add(change.tailColumn, end - column + change.tailColumn, type)
                }
            }
        }
        lines[line] = head.build()
        if (splits) {
            val added = ArrayList<LineTokens>(change.addedLines)
            repeat(change.addedLines - 1) { added.add(EMPTY) }
            added.add(tail.build())
            lines.addAll(line + 1, added)
        }
    }

    /** Text was deleted from the change's start column to its old tail column, possibly across lines. */
    private fun onDelete(change: DocumentChange) {
        val line = change.startLine
        val column = change.startColumn
        val oldTail = change.oldTailColumn
        val merged = Builder()
        if (change.removedLines == 0) {
            val old = lineTokens(line)
            val deleted = oldTail - column
            for (i in 0 until old.size) {
                val start = old.start(i)
                val end = old.end(i)
                when {
                    end <= column -> merged.add(start, end, old.types[i])

                    start >= oldTail -> merged.add(start - deleted, end - deleted, old.types[i])

                    else -> {
                        // Overlaps the deleted span: keep what is left of it on either side.
                        val newStart = minOf(start, column)
                        val newEnd = if (end > oldTail) end - deleted else column
                        if (newEnd > newStart) merged.add(newStart, newEnd, old.types[i])
                    }
                }
            }
        } else {
            val first = lineTokens(line)
            for (i in 0 until first.size) {
                if (first.start(i) < column) merged.add(first.start(i), minOf(first.end(i), column), first.types[i])
            }
            val lastLine = lineTokens(line + change.removedLines)
            for (i in 0 until lastLine.size) {
                if (lastLine.end(i) > oldTail) {
                    merged.add(
                        maxOf(lastLine.start(i), oldTail) - oldTail + column,
                        lastLine.end(i) - oldTail + column,
                        lastLine.types[i],
                    )
                }
            }
            lines.subList(line + 1, (line + 1 + change.removedLines).coerceAtMost(lines.size)).clear()
        }
        if (line < lines.size) lines[line] = merged.build()
    }

    /** [tokens] (absolute, sorted by start) split into per-line runs for lines [from]..[to]. */
    private fun bucket(tokens: List<Token>, from: Int, to: Int): List<LineTokens> {
        val builders = Array(to - from + 1) { Builder() }
        val length = document.length
        var line = 0
        for (token in tokens) {
            var start = token.start.coerceAtMost(length)
            val end = token.end.coerceAtMost(length)
            if (end <= start) continue
            // Tokens arrive sorted, so the line only moves forward; a stray one out of order is
            // located from scratch.
            if (start < document.lineStart(line)) line = document.offsetToLineCol(start).first
            while (line + 1 < document.lineCount && document.lineStart(line + 1) <= start) line++
            while (start < end && line <= to) {
                val lineStart = document.lineStart(line)
                val segmentEnd = minOf(end, document.lineEnd(line))
                if (line >= from && segmentEnd > start) {
                    builders[line - from].add(start - lineStart, segmentEnd - lineStart, token.type)
                }
                if (line + 1 >= document.lineCount) break
                start = document.lineStart(line + 1)
                if (start >= end) break
                line++
            }
        }
        return builders.map { it.build() }
    }

    private class Builder {
        private var bounds = IntArray(8)
        private val types = ArrayList<TokenType>()
        private var sorted = true

        fun add(start: Int, end: Int, type: TokenType) {
            if (end <= start) return
            val index = types.size * 2
            if (index > 0 && start < bounds[index - 2]) sorted = false
            if (index + 2 > bounds.size) bounds = bounds.copyOf(bounds.size * 2)
            bounds[index] = start
            bounds[index + 1] = end
            types.add(type)
        }

        fun build(): LineTokens {
            if (types.isEmpty()) return EMPTY
            if (sorted) return LineTokens(bounds.copyOf(types.size * 2), types.toTypedArray())
            // A tokenizer that returned its tokens out of order: put this line's runs in order.
            val order = types.indices.sortedBy { bounds[2 * it] }
            val sortedBounds = IntArray(types.size * 2)
            order.forEachIndexed { to, from ->
                sortedBounds[2 * to] = bounds[2 * from]
                sortedBounds[2 * to + 1] = bounds[2 * from + 1]
            }
            return LineTokens(sortedBounds, Array(types.size) { types[order[it]] })
        }
    }

    companion object {
        val EMPTY = LineTokens(IntArray(0), emptyArray())
    }
}
