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

import androidx.compose.ui.text.TextRange

/**
 * Ctrl/Cmd+D and Ctrl+Shift+L, as VS Code does them.
 *
 * With only carets, the first press selects the word at each caret. After that, each press finds
 * the next occurrence of the primary selection's text after it (wrapping around the document,
 * skipping ones already selected) and adds it as the new primary selection. A search that started
 * from a word matches whole words, case-sensitively; one that started from a selection the user
 * made matches the text anywhere, case-sensitively.
 */
internal object OccurrenceFinder {

    /** The selections after one Ctrl/Cmd+D, or null when there is no further occurrence. */
    fun addNext(document: CharSequence, selections: SelectionSet, wholeWord: Boolean): SelectionSet? {
        if (selections.ranges.all { it.collapsed }) {
            val words = selections.ranges.map { TextNavigator.wordAt(document, it.start) }
            if (words.all { it.isEmpty() }) return null
            return SelectionSet.of(words.map { if (it.isEmpty()) TextRange(it.first) else TextRange(it.first, it.last + 1) })
        }
        val primary = selections.primary
        val needle = document.subSequence(primary.min, primary.max).toString()
        if (needle.isEmpty()) return null
        val taken = selections.ranges.map { it.min }.toSet()
        var from = primary.max
        var wrapped = false
        while (true) {
            val found = find(document, needle, from, wholeWord)
            if (found < 0) {
                if (wrapped) return null
                wrapped = true
                from = 0
                continue
            }
            if (wrapped && found >= primary.max) return null
            if (found !in taken) {
                return SelectionSet.of(listOf(TextRange(found, found + needle.length)) + selections.ranges)
            }
            from = found + 1
        }
    }

    /** Ctrl+Shift+L: every occurrence of the primary selection's text (or of the word at the caret). */
    fun selectAll(document: CharSequence, selections: SelectionSet): SelectionSet? {
        val primary = selections.primary
        val wholeWord = primary.collapsed
        val range = if (primary.collapsed) {
            TextNavigator.wordAt(document, primary.start).takeUnless { it.isEmpty() }?.let { TextRange(it.first, it.last + 1) }
                ?: return null
        } else {
            TextRange(primary.min, primary.max)
        }
        val needle = document.subSequence(range.min, range.max).toString()
        val found = ArrayList<TextRange>()
        var from = 0
        while (true) {
            val at = find(document, needle, from, wholeWord)
            if (at < 0) break
            found.add(TextRange(at, at + needle.length))
            from = at + needle.length
        }
        // The occurrence the primary selection was on stays primary.
        val primaryIndex = found.indexOfFirst { it.min == range.min }.coerceAtLeast(0)
        return SelectionSet.of(listOf(found[primaryIndex]) + found.filterIndexed { i, _ -> i != primaryIndex })
    }

    private fun find(document: CharSequence, needle: String, from: Int, wholeWord: Boolean): Int {
        var at = from
        while (at <= document.length - needle.length) {
            val found = indexOf(document, needle, at)
            if (found < 0) return -1
            if (!wholeWord || isWholeWord(document, found, found + needle.length)) return found
            at = found + 1
        }
        return -1
    }

    private fun indexOf(document: CharSequence, needle: String, from: Int): Int {
        val first = needle[0]
        var i = from
        val last = document.length - needle.length
        while (i <= last) {
            if (document[i] == first && matchesAt(document, needle, i)) return i
            i++
        }
        return -1
    }

    private fun matchesAt(document: CharSequence, needle: String, at: Int): Boolean {
        for (j in 1 until needle.length) if (document[at + j] != needle[j]) return false
        return true
    }

    private fun isWholeWord(document: CharSequence, start: Int, end: Int): Boolean {
        val before = start == 0 || TextNavigator.classOf(document[start - 1]) != TextNavigator.CharClass.Regular
        val after = end == document.length || TextNavigator.classOf(document[end]) != TextNavigator.CharClass.Regular
        return before && after
    }
}
