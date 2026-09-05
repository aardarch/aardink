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
package com.aardarch.aardink.languages.internal.folding

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldingProvider

/**
 * Produces fold regions for TOML documents:
 *   - Sections starting at `[table]` or `[[array]]` headers up to the next header or EOF.
 *   - Multiline strings (`"""..."""` or `'''...'''`).
 *   - Multiline inline tables `{...}` and inline arrays `[...]`.
 */
object TomlFoldingProvider : FoldingProvider {

    override fun foldableRanges(document: CodeDocument): List<FoldRange> {
        val lineCount = document.lineCount
        if (lineCount <= 1) return emptyList()

        val ranges = mutableListOf<FoldRange>()

        // 1. Fold table sections [header] ...
        var currentSectionStart = -1
        for (i in 0 until lineCount) {
            val lineText = document.lineText(i).trim()
            if (isTableHeader(lineText)) {
                if (currentSectionStart >= 0 && i - 1 > currentSectionStart) {
                    ranges.add(FoldRange(currentSectionStart, i - 1))
                }
                currentSectionStart = i
            }
        }
        if (currentSectionStart >= 0 && lineCount - 1 > currentSectionStart) {
            ranges.add(FoldRange(currentSectionStart, lineCount - 1))
        }

        // 2. Fold multiline strings & inline brackets
        ranges.addAll(findMultilineConstructs(document))

        return ranges.sortedBy { it.startLine }
    }

    private fun isTableHeader(trimmedLine: String): Boolean {
        if (trimmedLine.startsWith("#")) return false
        return (trimmedLine.startsWith("[[") && trimmedLine.endsWith("]]")) ||
            (trimmedLine.startsWith("[") && trimmedLine.endsWith("]"))
    }

    private fun findMultilineConstructs(document: CodeDocument): List<FoldRange> {
        val text = document.text
        if (text.isEmpty()) return emptyList()

        val ranges = mutableListOf<FoldRange>()
        var i = 0
        val n = text.length
        val stack = ArrayDeque<Pair<Char, Int>>()

        while (i < n) {
            val c = text[i]
            if (c == '#') {
                val nl = text.indexOf('\n', i)
                i = if (nl < 0) n else nl
                continue
            }
            if (c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                val start = i
                val end = text.indexOf("\"\"\"", i + 3)
                if (end > i) {
                    val (sLine, _) = document.offsetToLineCol(start)
                    val (eLine, _) = document.offsetToLineCol(end + 2)
                    if (eLine > sLine) ranges.add(FoldRange(sLine, eLine))
                    i = end + 3
                } else {
                    i = n
                }
                continue
            }
            if (c == '\'' && i + 2 < n && text[i + 1] == '\'' && text[i + 2] == '\'') {
                val start = i
                val end = text.indexOf("'''", i + 3)
                if (end > i) {
                    val (sLine, _) = document.offsetToLineCol(start)
                    val (eLine, _) = document.offsetToLineCol(end + 2)
                    if (eLine > sLine) ranges.add(FoldRange(sLine, eLine))
                    i = end + 3
                } else {
                    i = n
                }
                continue
            }
            if (c == '{' || c == '[') {
                stack.addLast(c to i)
            } else if (c == '}' || c == ']') {
                val matchingOpen = if (c == '}') '{' else '['
                if (stack.isNotEmpty() && stack.last().first == matchingOpen) {
                    val openOffset = stack.removeLast().second
                    val (sLine, _) = document.offsetToLineCol(openOffset)
                    val (eLine, _) = document.offsetToLineCol(i)
                    if (eLine > sLine) {
                        ranges.add(FoldRange(sLine, eLine))
                    }
                }
            }
            i++
        }
        return ranges
    }
}
