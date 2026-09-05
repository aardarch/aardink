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
        val headerLines = tableHeaderLines(document)
        var currentSectionStart = -1
        for (i in 0 until lineCount) {
            if (i in headerLines) {
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

    /**
     * Lines that really open a table, found in one pass that tracks multiline strings.
     *
     * A line-by-line test cannot tell a header from its own text: `[example]` sitting inside a
     * `"""` value looks exactly like one, and folding on it produces sections that overlap the
     * string's own fold. The scan also lets a header carry an inline comment, which
     * `endsWith("]")` rejected.
     */
    private fun tableHeaderLines(document: CodeDocument): Set<Int> {
        val headers = mutableSetOf<Int>()
        var inMultiline: String? = null

        for (i in 0 until document.lineCount) {
            val line = document.lineText(i)
            val startedInsideString = inMultiline != null
            var j = 0
            while (j < line.length) {
                val open = inMultiline
                if (open != null) {
                    val close = line.indexOf(open, j)
                    if (close < 0) {
                        j = line.length
                    } else {
                        inMultiline = null
                        j = close + open.length
                    }
                    continue
                }
                when {
                    line.startsWith(MULTILINE_BASIC, j) -> {
                        inMultiline = MULTILINE_BASIC
                        j += MULTILINE_BASIC.length
                    }

                    line.startsWith(MULTILINE_LITERAL, j) -> {
                        inMultiline = MULTILINE_LITERAL
                        j += MULTILINE_LITERAL.length
                    }

                    line[j] == '#' -> j = line.length

                    line[j] == '"' || line[j] == '\'' -> j = endOfSingleLineString(line, j)

                    else -> j++
                }
            }

            if (startedInsideString) continue
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || !trimmed.startsWith("[")) continue
            // Everything up to an unquoted '#' is the header; the rest is a comment.
            val header = headerOf(trimmed)
            if (header.endsWith("]")) headers.add(i)
        }
        return headers
    }

    /** [trimmedLine] up to an unquoted `#`, so `[versions] # catalog` reads as `[versions]`. */
    private fun headerOf(trimmedLine: String): String {
        var i = 0
        while (i < trimmedLine.length) {
            val c = trimmedLine[i]
            when {
                c == '#' -> return trimmedLine.substring(0, i).trimEnd()
                c == '"' || c == '\'' -> i = endOfSingleLineString(trimmedLine, i)
                else -> i++
            }
        }
        return trimmedLine.trimEnd()
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
            // An ordinary quoted value is data, not syntax: `x = "["` opens no bracket and `"#"`
            // starts no comment. Skipped after the triple-quote branches, which claim theirs first.
            if (c == '"' || c == '\'') {
                i = endOfSingleLineString(text, i)
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

    /**
     * Index just past the single-line string opening at [start] in [text].
     *
     * A basic string (`"`) honours backslash escapes; a literal string (`'`) has none, so a
     * backslash in it is an ordinary character. Neither may span a line, so an unterminated one
     * ends at the newline and the scan carries on from there rather than swallowing the file.
     */
    private fun endOfSingleLineString(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            when {
                text[i] == '\n' -> return i
                quote == '"' && text[i] == '\\' -> i += 2
                text[i] == quote -> return i + 1
                else -> i++
            }
        }
        return text.length
    }

    private const val MULTILINE_BASIC = "\"\"\""
    private const val MULTILINE_LITERAL = "'''"
}
