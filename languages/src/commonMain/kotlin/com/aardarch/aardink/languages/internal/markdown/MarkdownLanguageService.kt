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

    private val HEADING = Regex("^ {0,3}(#{1,6})[ \\t]+(.*?)[ \\t#]*$")

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
        var fence: String? = null

        fun closeTo(level: Int, last: Int) {
            while (stack.isNotEmpty() && stack.last().level >= level) {
                val closed = stack.removeAt(stack.size - 1).build(last)
                (stack.lastOrNull()?.children ?: top) += closed
            }
        }

        for (i in 0 until lineCount) {
            pacer?.onProgress(i)
            val line = document.lineText(i)
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (marker != null) {
                fence = if (fence == null) {
                    marker
                } else if (fence == marker) {
                    null
                } else {
                    fence
                }
                continue
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
