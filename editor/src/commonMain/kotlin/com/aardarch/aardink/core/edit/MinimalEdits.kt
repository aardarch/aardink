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
import com.aardarch.aardink.core.diff.LineDiff
import com.aardarch.aardink.core.diff.LineHunk

/**
 * The edits that turn one text into another while touching as little as they can, for a formatter
 * that returns the whole formatted document: applied as they are, carets, folds and diagnostics
 * outside what changed stay where they were, as with Monaco's minimal edits.
 *
 * Lines are compared first (Myers' diff, in [LineDiff]), then each run of differing lines is trimmed to the
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
            trimmed(old, oldStarts[hunk.aStart], oldStarts[hunk.aEnd], new.substring(newStarts[hunk.bStart], newStarts[hunk.bEnd]))
        }
    }

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

    /** The runs of lines that differ, with the lines compared as they are, line breaks and whitespace included; null past [MAX_LINE_EDITS]. */
    private fun diff(a: List<String>, b: List<String>): List<LineHunk>? {
        val ids = HashMap<String, Int>()
        val aIds = IntArray(a.size) { ids.getOrPut(a[it]) { ids.size } }
        val bIds = IntArray(b.size) { ids.getOrPut(b[it]) { ids.size } }
        return LineDiff.diff(aIds, bIds, MAX_LINE_EDITS)?.hunks
    }
}
