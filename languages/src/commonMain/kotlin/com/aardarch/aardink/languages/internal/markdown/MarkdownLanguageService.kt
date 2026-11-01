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
package com.aardarch.aardink.languages.internal.markdown

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.DocumentSymbol
import com.aardarch.aardink.core.SymbolKind
import com.aardarch.aardink.languages.internal.BaseLanguageService
import com.aardarch.aardink.languages.internal.diagnosticsPacer

/**
 * Markdown's service: its document symbols, the headings, nested by level as VS Code's outline
 * nests them. A heading's range runs to the line before the next heading of the same or a higher
 * level. Headings inside fenced code blocks are not headings.
 */
internal object MarkdownLanguageService : BaseLanguageService() {

    /** An ATX heading; a closing run of `#`s counts only after whitespace, so `# C#` keeps its `#`. */
    private val HEADING = Regex("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$")

    /** An open fenced code block: what it was opened with, which only as long a run of it closes. */
    private class Fence(val char: Char, val length: Int)

    private class Open(val level: Int, val name: String, val start: Int, val nameRange: IntRange) {
        val children = ArrayList<DocumentSymbol>()

        fun build(last: Int) = DocumentSymbol(name, SymbolKind.String, start..maxOf(start, last), nameRange, children = children)
    }

    override suspend fun documentSymbols(document: CodeDocument): List<DocumentSymbol> {
        val lineCount = document.lineCount
        if (lineCount == 0 || document.length == 0) return emptyList()
        val pacer = diagnosticsPacer()
        val top = ArrayList<DocumentSymbol>()
        val stack = ArrayList<Open>()
        var fence: Fence? = null

        fun closeTo(level: Int, last: Int) {
            while (stack.isNotEmpty() && stack.last().level >= level) {
                val closed = stack.removeAt(stack.size - 1).build(last)
                (stack.lastOrNull()?.children ?: top) += closed
            }
        }

        for (i in 0 until lineCount) {
            pacer?.onProgress(i)
            val line = document.lineText(i)
            // A fence, like a heading, is indented at most three spaces; any further is indented code.
            val indent = line.takeWhile { it == ' ' }.length
            val trimmed = if (indent <= 3) line.substring(indent) else ""
            val first = trimmed.firstOrNull()
            val run = if (first == '`' || first == '~') trimmed.takeWhile { it == first }.length else 0
            val open = fence
            if (run >= 3 && first != null) {
                val rest = trimmed.substring(run)
                if (open == null) {
                    // A backtick fence's info string has no backtick in it.
                    if (first == '~' || '`' !in rest) {
                        fence = Fence(first, run)
                        continue
                    }
                } else if (first == open.char && run >= open.length && rest.isBlank()) {
                    fence = null
                    continue
                }
            }
            if (fence != null) continue
            val match = HEADING.find(line) ?: continue
            val level = match.groupValues[1].length
            val name = match.groupValues[2].ifEmpty { match.groupValues[1] }
            val lineStart = document.lineStart(i)
            closeTo(level, lineStart - 2)
            val hashAt = line.indexOf('#')
            val nameAt = if (match.groupValues[2].isEmpty()) hashAt else line.indexOf(name, hashAt + level).coerceAtLeast(hashAt)
            val nameStart = lineStart + nameAt
            stack += Open(level, name, lineStart + hashAt, nameStart until nameStart + name.length)
        }
        closeTo(1, document.length - 1)
        return top
    }
}
