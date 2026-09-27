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

import com.aardarch.aardink.core.TextEdit

/**
 * The edits that turn one text into another while touching as little as they can, for a formatter
 * that returns the whole formatted document: applied as they are, carets, folds and diagnostics
 * outside what changed stay where they were, as with Monaco's minimal edits.
 *
 * Lines are compared first (Myers' diff), then each run of differing lines is trimmed to the
 * characters that differ. A diff that needs more than [MAX_LINE_EDITS] line insertions and
 * deletions, as reindenting a whole large file does, falls back to one edit spanning from the
 * first difference to the last.
 */
internal object MinimalEdits {

    const val MAX_LINE_EDITS = 2_000

    fun between(old: String, new: String): List<TextEdit> {
        if (old == new) return emptyList()
        val oldLines = splitLines(old)
        val newLines = splitLines(new)
        val hunks = diff(oldLines, newLines) ?: return listOf(trimmed(old, 0, old.length, new))
        val oldStarts = starts(oldLines)
        val newStarts = starts(newLines)
        return hunks.map { hunk ->
            trimmed(old, oldStarts[hunk.oldStart], oldStarts[hunk.oldEnd], new.substring(newStarts[hunk.newStart], newStarts[hunk.newEnd]))
        }
    }

    /** Lines [oldStart, oldEnd) of the old text become lines [newStart, newEnd) of the new one. */
    private class Hunk(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int)

    /** [text]'s lines, each with its line break, so that they join back into [text]. */
    private fun splitLines(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) {
                if (start < text.length) lines += text.substring(start)
                return lines
            }
            lines += text.substring(start, nl + 1)
            start = nl + 1
        }
    }

    /** Offset of the start of every line, and of the end of the text. */
    private fun starts(lines: List<String>): IntArray {
        val starts = IntArray(lines.size + 1)
        for (i in lines.indices) starts[i + 1] = starts[i] + lines[i].length
        return starts
    }

    /** The edit replacing [start, end) of [old] with [replacement], less what they share at either end. */
    private fun trimmed(old: String, start: Int, end: Int, replacement: String): TextEdit {
        var prefix = 0
        val maxPrefix = minOf(end - start, replacement.length)
        while (prefix < maxPrefix && old[start + prefix] == replacement[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(end - start, replacement.length) - prefix
        while (suffix < maxSuffix && old[end - 1 - suffix] == replacement[replacement.length - 1 - suffix]) suffix++
        val from = start + prefix
        val to = end - suffix
        return TextEdit(from until to, replacement.substring(prefix, replacement.length - suffix))
    }

    /**
     * Myers' diff of two line lists, as the runs of lines that differ; null past [MAX_LINE_EDITS].
     * `trace[d]` holds, for every diagonal k in -d..d, how far along the old lines the furthest
     * path with d edits reaches.
     */
    private fun diff(a: List<String>, b: List<String>): List<Hunk>? {
        val n = a.size
        val m = b.size
        val limit = minOf(n + m, MAX_LINE_EDITS)
        val trace = ArrayList<IntArray>()
        var found = false
        for (d in 0..limit) {
            val v = IntArray(2 * d + 1)
            val previous = trace.lastOrNull()
            var k = -d
            while (k <= d) {
                var x = when {
                    d == 0 -> 0
                    k == -d || (k != d && previous!![k - 1 + d - 1] < previous[k + 1 + d - 1]) -> previous!![k + 1 + d - 1]
                    else -> previous!![k - 1 + d - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[k + d] = x
                if (x >= n && y >= m) found = true
                k += 2
            }
            trace += v
            if (found) break
        }
        if (!found) return null

        // Back from the end: the diagonal runs (equal lines) of the path, last first.
        val snakes = ArrayList<IntArray>()
        var x = n
        var y = m
        for (d in trace.size - 1 downTo 1) {
            val previous = trace[d - 1]
            val k = x - y
            val down = k == -d || (k != d && previous[k - 1 + d - 1] < previous[k + 1 + d - 1])
            val previousK = if (down) k + 1 else k - 1
            val previousX = previous[previousK + d - 1]
            val previousY = previousX - previousK
            val midX = if (down) previousX else previousX + 1
            val midY = midX - k
            snakes += intArrayOf(midX, midY, x, y)
            x = previousX
            y = previousY
        }
        snakes += intArrayOf(0, 0, x, y)
        snakes.reverse()

        val hunks = ArrayList<Hunk>()
        var oldAt = 0
        var newAt = 0
        for (snake in snakes) {
            if (snake[2] == snake[0]) continue // no equal lines: the edits on either side are one run
            if (snake[0] > oldAt || snake[1] > newAt) hunks += Hunk(oldAt, snake[0], newAt, snake[1])
            oldAt = snake[2]
            newAt = snake[3]
        }
        if (oldAt < n || newAt < m) hunks += Hunk(oldAt, n, newAt, m)
        return hunks
    }
}
