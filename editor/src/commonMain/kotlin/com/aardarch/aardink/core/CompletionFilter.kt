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
 * How the editor arranges a completion list: by [CompletionItem.sortPriority], keeping the
 * service's order among equals, and, for a service that is not asked again per keystroke
 * ([LanguageService.completionsAreIncomplete] false), filtered by what has been typed as VS Code
 * filters: the typed characters in order, ignoring case, the first at the start of the item's
 * [CompletionItem.filterText] or of a word in it. Among items left, those whose filter text starts
 * with what was typed come first.
 */
internal object CompletionFilter {

    /**
     * [items] in the order to show them. [typedFor] gives the text typed so far for an item (from
     * the start of what it replaces to the caret); with [filter] off it is not read.
     */
    fun arrange(items: List<CompletionItem>, filter: Boolean, typedFor: (CompletionItem) -> String): List<CompletionItem> {
        if (!filter) return if (items.all { it.sortPriority == items.first().sortPriority }) items else items.sortedBy { it.sortPriority }
        return items
            .map { it to typedFor(it) }
            .filter { (item, typed) -> matches(typed, item.filterText) }
            .sortedWith(
                compareBy({
                    it.first.sortPriority
                }, { (item, typed) -> if (item.filterText.startsWith(typed, ignoreCase = true)) 0 else 1 }),
            )
            .map { it.first }
    }

    /** What has been typed of [item] at [cursor]: from the start of what it replaces to the caret. */
    fun typedFor(text: CharSequence, cursor: Int, item: CompletionItem): String {
        val start = completionReplaceRange(text, cursor, item).first
        return if (start < cursor) text.subSequence(start, cursor).toString() else ""
    }

    /** Whether [typed] is in [candidate] as VS Code matches a completion: in order, from a word start. */
    fun matches(typed: String, candidate: String): Boolean {
        if (typed.isEmpty()) return true
        for (start in candidate.indices) {
            if (!candidate[start].equals(typed[0], ignoreCase = true) || !isWordStart(candidate, start)) continue
            var next = start + 1
            var matched = 1
            while (matched < typed.length && next < candidate.length) {
                if (candidate[next].equals(typed[matched], ignoreCase = true)) matched++
                next++
            }
            if (matched == typed.length) return true
        }
        return false
    }

    private fun isWordStart(text: String, index: Int): Boolean {
        if (index == 0) return true
        val before = text[index - 1]
        return !before.isLetterOrDigit() || (text[index].isUpperCase() && before.isLowerCase())
    }
}

/**
 * Characters a completion never reaches back across when guessing what it replaces.
 *
 * `.` is one of them: after `list.`, accepting a member completion must insert after the receiver,
 * not swallow it. A provider that knows better says so with [CompletionItem.replaceRange].
 */
private val COMPLETION_BOUNDARY_CHARS =
    setOf('<', '>', '{', '}', '(', ')', '[', ']', '"', '\'', '=', ',', ';', '.', ' ', '\n', '\t', '@', '|', ':')

/**
 * Half-open-as-inclusive range in [text] that accepting [item] at [cursorPos] replaces.
 *
 * A provider that knows the exact range it means (a language server's `textEdit`) wins; only when
 * [CompletionItem.replaceRange] is absent does the editor guess the token before the cursor.
 */
internal fun completionReplaceRange(text: CharSequence, cursorPos: Int, item: CompletionItem): IntRange {
    val cursor = cursorPos.coerceIn(0, text.length)
    item.replaceRange?.let { provided ->
        val start = provided.first.coerceIn(0, text.length)
        val end = (provided.last + 1).coerceIn(start, text.length)
        return start until end
    }
    var start = cursor
    while (start > 0 && text[start - 1] !in COMPLETION_BOUNDARY_CHARS) {
        start--
    }
    return start until cursor
}
