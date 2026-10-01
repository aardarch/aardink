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
 * Bracket nesting per line, for bracket-pair colours and bracket matching: how many of `(`, `[`
 * and `{` each line opens net, not counting brackets inside strings and comments (as the token
 * store has them). The depth at the start of any line is a prefix sum, kept in a Fenwick tree, so
 * the renderer asks for the lines on screen in O(log n) without walking the document.
 *
 * A line's count is worked out when it is first needed after it changed (its text, or its tokens
 * after a tokenization pass), so a full retokenization costs one pass over the lines above the
 * view, not one over the document per frame.
 */
internal class BracketIndex(private val document: CodeDocument, private val tokens: TokenStore) : DocumentChangeListener {

    private var delta = IntArray(0)
    private var tree = IntArray(1)
    private var stale = BooleanArray(0)
    private var lineCount = 0
    private var firstStale = 0

    init {
        reset()
        document.addChangeListener(this)
    }

    fun dispose() {
        document.removeChangeListener(this)
    }

    /** Every line's count must be worked out again: a full tokenization pass, or a new document. */
    fun invalidateAll() {
        stale.fill(true)
        firstStale = 0
    }

    /** Lines [from]..[to] were retokenized. */
    fun invalidate(from: Int, to: Int) {
        for (line in from.coerceAtLeast(0)..to.coerceAtMost(lineCount - 1)) stale[line] = true
        firstStale = minOf(firstStale, from.coerceAtLeast(0))
    }

    override fun onDocumentChange(change: DocumentChange) {
        if (change.isReset || lineCount != document.lineCount - change.lineDelta) {
            reset()
            return
        }
        if (change.lineDelta != 0) {
            val newCount = lineCount + change.lineDelta
            val nextDelta = IntArray(newCount)
            val nextStale = BooleanArray(newCount)
            val keepAfter = change.startLine + change.removedLines + 1
            delta.copyInto(nextDelta, 0, 0, change.startLine)
            stale.copyInto(nextStale, 0, 0, change.startLine)
            delta.copyInto(nextDelta, change.startLine + change.addedLines + 1, keepAfter, lineCount)
            stale.copyInto(nextStale, change.startLine + change.addedLines + 1, keepAfter, lineCount)
            for (line in change.startLine..change.startLine + change.addedLines) nextStale[line] = true
            delta = nextDelta
            stale = nextStale
            lineCount = newCount
            rebuildTree()
        } else {
            stale[change.startLine] = true
        }
        firstStale = minOf(firstStale, change.startLine)
    }

    private fun reset() {
        lineCount = document.lineCount
        delta = IntArray(lineCount)
        stale = BooleanArray(lineCount) { true }
        tree = IntArray(lineCount + 1)
        firstStale = 0
    }

    /** Bracket depth at the start of [line]: brackets opened above it and not closed (never below 0). */
    fun depthAtLineStart(line: Int): Int {
        val target = line.coerceIn(0, lineCount)
        refreshUpTo(target)
        var sum = 0
        var i = target
        while (i > 0) {
            sum += tree[i]
            i -= i and -i
        }
        return sum.coerceAtLeast(0)
    }

    /** Recounts the stale lines above [line]. */
    private fun refreshUpTo(line: Int) {
        if (firstStale >= line) return
        for (i in firstStale until line) {
            if (!stale[i]) continue
            stale[i] = false
            val count = count(i)
            val change = count - delta[i]
            if (change != 0) {
                delta[i] = count
                var j = i + 1
                while (j <= lineCount) {
                    tree[j] += change
                    j += j and -j
                }
            }
        }
        firstStale = line
        while (firstStale < lineCount && !stale[firstStale]) firstStale++
    }

    private fun rebuildTree() {
        val built = IntArray(lineCount + 1)
        for (i in 1..lineCount) {
            built[i] += delta[i - 1]
            val parent = i + (i and -i)
            if (parent <= lineCount) built[parent] += built[i]
        }
        tree = built
    }

    /** Opening minus closing brackets on [line], outside strings and comments. */
    private fun count(line: Int): Int {
        var net = 0
        forEachBracket(line) { _, open -> net += if (open) 1 else -1 }
        return net
    }

    /**
     * Calls [action] with the column of every bracket on [line] that is code (not inside a string
     * or comment token), and whether it opens.
     */
    fun forEachBracket(line: Int, action: (column: Int, open: Boolean) -> Unit) {
        val start = document.lineStart(line)
        val end = document.lineEnd(line)
        val runs = tokens.lineTokens(line)
        var run = 0
        for (offset in start until end) {
            val column = offset - start
            val c = document[offset]
            val open = c == '(' || c == '[' || c == '{'
            if (!open && c != ')' && c != ']' && c != '}') continue
            while (run < runs.size && runs.end(run) <= column) run++
            if (run < runs.size && runs.start(run) <= column && isText(runs.types[run])) continue
            action(column, open)
        }
    }

    private fun isText(type: TokenType): Boolean = type.isCommentOrString

    companion object {
        /** How far bracket matching looks from the caret before giving up. */
        const val MATCH_LIMIT = 50_000

        fun closing(open: Char): Char = when (open) {
            '(' -> ')'
            '[' -> ']'
            else -> '}'
        }
    }
}
