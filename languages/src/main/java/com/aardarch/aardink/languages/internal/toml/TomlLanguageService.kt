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
package com.aardarch.aardink.languages.internal.toml

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.languages.internal.BaseLanguageService

/**
 * In-process language service for TOML documents.
 * Provides diagnostics (duplicate key detection, syntax validation), completions,
 * auto-close, smart indentation, and formatting.
 */
object TomlLanguageService : BaseLanguageService() {

    override val triggerCharacters: Set<Char> = setOf('[', '=', '"', '\'', '{', '.')

    private const val MULTILINE_BASIC = "\"\"\""
    private const val MULTILINE_LITERAL = "'''"

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        val lineCount = document.lineCount
        if (lineCount == 0 || document.text.isBlank()) return emptyList()

        val diags = mutableListOf<Diagnostic>()
        var currentSection = ""
        val sectionKeys = mutableSetOf<String>()
        val lineStates = lineStates(document)

        for (i in 0 until lineCount) {
            val lineText = document.lineText(i)
            val trimmed = lineText.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            // Part of a value opened on an earlier line (a multiline string or array), not a
            // declaration of its own.
            if (lineStates[i].isContinuation) continue

            val lineStartOffset = document.lineStart(i)

            // Table header [section] or [[array]]
            if (trimmed.startsWith("[")) {
                if (!trimmed.endsWith("]")) {
                    val start = lineStartOffset + lineText.indexOf('[')
                    val end = lineStartOffset + lineText.length
                    diags.add(
                        Diagnostic(
                            range = start..end,
                            lineNumber = i,
                            message = "Unclosed table header",
                            severity = DiagnosticSeverity.Error,
                            source = "toml",
                        ),
                    )
                } else {
                    currentSection = trimmed
                    sectionKeys.clear()
                }
                continue
            }

            // Key = Value
            val eqIdx = lineText.indexOf('=')
            if (eqIdx < 0) {
                // Not a table header, comment, blank, or key=value assignment
                val start = lineStartOffset
                val end = lineStartOffset + lineText.length
                diags.add(
                    Diagnostic(
                        range = start..end,
                        lineNumber = i,
                        message = "Expected '=' in key-value declaration",
                        severity = DiagnosticSeverity.Error,
                        source = "toml",
                    ),
                )
                continue
            }

            val rawKey = lineText.substring(0, eqIdx).trim()
            if (rawKey.isEmpty()) {
                val start = lineStartOffset
                val end = lineStartOffset + eqIdx
                diags.add(
                    Diagnostic(
                        range = start..end,
                        lineNumber = i,
                        message = "Missing key name before '='",
                        severity = DiagnosticSeverity.Error,
                        source = "toml",
                    ),
                )
            } else if (!sectionKeys.add(rawKey)) {
                val keyOffset = lineStartOffset + lineText.indexOf(rawKey)
                val keyEnd = keyOffset + rawKey.length
                diags.add(
                    Diagnostic(
                        range = keyOffset..keyEnd,
                        lineNumber = i,
                        message = "Duplicate key '$rawKey' in section $currentSection",
                        severity = DiagnosticSeverity.Warning,
                        source = "toml",
                    ),
                )
            }
        }

        return diags
    }

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val (lineIndex, col) = document.offsetToLineCol(cursorOffset)
        val lineText = document.lineText(lineIndex)
        val textBeforeCursor = lineText.take(col).trimStart()

        // 1. Table header completions after typing '[' or '[['
        if (textBeforeCursor == "[" || textBeforeCursor == "[[") {
            val suffix = if (textBeforeCursor == "[") "]" else "]]"
            return listOf(
                CompletionItem(
                    label = "versions",
                    kind = CompletionKind.Element,
                    insertText = "versions$suffix\n",
                    documentation = "Version catalog dependency versions",
                    sortPriority = 0,
                ),
                CompletionItem(
                    label = "libraries",
                    kind = CompletionKind.Element,
                    insertText = "libraries$suffix\n",
                    documentation = "Version catalog library declarations",
                    sortPriority = 1,
                ),
                CompletionItem(
                    label = "plugins",
                    kind = CompletionKind.Element,
                    insertText = "plugins$suffix\n",
                    documentation = "Version catalog plugin declarations",
                    sortPriority = 2,
                ),
                CompletionItem(
                    label = "bundles",
                    kind = CompletionKind.Element,
                    insertText = "bundles$suffix\n",
                    documentation = "Version catalog library bundles",
                    sortPriority = 3,
                ),
            )
        }

        // 2. Value completions after '='
        if (textBeforeCursor.endsWith("=")) {
            return listOf(
                CompletionItem("true", CompletionKind.Value, " true"),
                CompletionItem("false", CompletionKind.Value, " false"),
                CompletionItem(
                    label = "{ module = \"...\", version.ref = \"...\" }",
                    kind = CompletionKind.Snippet,
                    insertText = " { module = \"\", version.ref = \"\" }",
                    documentation = "Inline library specification with version reference",
                ),
                CompletionItem(
                    label = "{ id = \"...\", version.ref = \"...\" }",
                    kind = CompletionKind.Snippet,
                    insertText = " { id = \"\", version.ref = \"\" }",
                    documentation = "Inline plugin specification",
                ),
            )
        }

        // 3. Key completions based on current active section
        val activeSection = findActiveSection(document, lineIndex)
        if (activeSection == "[libraries]") {
            return listOf(
                CompletionItem("module", CompletionKind.Property, "module = \"\"", "Group and artifact ID"),
                CompletionItem("group", CompletionKind.Property, "group = \"\"", "Dependency group ID"),
                CompletionItem("name", CompletionKind.Property, "name = \"\"", "Dependency artifact name"),
                CompletionItem("version.ref", CompletionKind.Property, "version.ref = \"\"", "Reference to [versions] entry"),
            )
        } else if (activeSection == "[plugins]") {
            return listOf(
                CompletionItem("id", CompletionKind.Property, "id = \"\"", "Plugin ID"),
                CompletionItem("version.ref", CompletionKind.Property, "version.ref = \"\"", "Reference to [versions] entry"),
            )
        }

        return emptyList()
    }

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = when (charTyped) {
        '[' -> "]"
        '{' -> "}"
        '"' -> "\""
        '\'' -> "'"
        else -> null
    }

    override fun smartIndent(document: CodeDocument, lineIndex: Int): Int {
        if (lineIndex <= 0) return 0
        val prevLine = document.lineText(lineIndex - 1)
        val prevIndent = prevLine.takeWhile { it.isWhitespace() }.length
        val trimmed = prevLine.trim()
        return if (trimmed.endsWith("{") || trimmed.endsWith("[") || trimmed.endsWith("=")) {
            prevIndent + 4
        } else {
            prevIndent
        }
    }

    override suspend fun format(document: CodeDocument): String {
        val lineCount = document.lineCount
        if (lineCount == 0) return ""

        val formattedLines = mutableListOf<String>()
        val lineStates = lineStates(document)
        for (i in 0 until lineCount) {
            val line = document.lineText(i)
            // Whitespace inside a multiline string is data — reformatting it changes the document.
            if (lineStates[i].touchesMultilineString) {
                formattedLines.add(line)
                continue
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) {
                formattedLines.add(trimmed)
                continue
            }
            // An array element or inline-table member — an '=' here belongs to the value, not to a
            // key/value declaration this line could be split on.
            if (lineStates[i].isContinuation) {
                formattedLines.add(trimmed)
                continue
            }
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val key = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                formattedLines.add("$key = $value")
            } else {
                formattedLines.add(trimmed)
            }
        }
        return formattedLines.joinToString("\n")
    }

    /**
     * How a line relates to the values around it.
     *
     * @param isContinuation The line continues a value opened earlier — a multiline string, array
     *   or inline table — so it is not a declaration in its own right.
     * @param touchesMultilineString Any part of the line lies inside a `"""` / `'''` string, whose
     *   leading and trailing whitespace is data and must survive formatting verbatim.
     */
    private data class TomlLineState(val isContinuation: Boolean, val touchesMultilineString: Boolean)

    /**
     * Classifies every line of [document]. TOML values span lines — `"""` / `'''` strings and
     * bracketed arrays or inline tables — so a line is only its own declaration once the lines
     * above it have closed everything they opened.
     */
    private fun lineStates(document: CodeDocument): List<TomlLineState> {
        val states = ArrayList<TomlLineState>(document.lineCount)
        var openDelimiter: String? = null
        var depth = 0

        for (i in 0 until document.lineCount) {
            val line = document.lineText(i)
            val startedInsideString = openDelimiter != null
            val startedInsideValue = startedInsideString || depth > 0
            val isHeader = !startedInsideValue && line.trimStart().startsWith("[")
            var openedString = false
            var j = 0

            while (j < line.length) {
                val delimiter = openDelimiter
                if (delimiter != null) {
                    val close = line.indexOf(delimiter, j)
                    if (close < 0) {
                        j = line.length
                    } else {
                        openDelimiter = null
                        j = close + delimiter.length
                    }
                    continue
                }
                when {
                    line[j] == '#' -> j = line.length

                    line.startsWith(MULTILINE_BASIC, j) || line.startsWith(MULTILINE_LITERAL, j) -> {
                        openDelimiter = line.substring(j, j + 3)
                        openedString = true
                        j += 3
                    }

                    line[j] == '"' || line[j] == '\'' -> j = endOfBasicString(line, j)

                    line[j] == '[' || line[j] == '{' -> {
                        depth++
                        j++
                    }

                    line[j] == ']' || line[j] == '}' -> {
                        depth = (depth - 1).coerceAtLeast(0)
                        j++
                    }

                    else -> j++
                }
            }

            // An unclosed table header is reported on its own line; don't let it swallow the rest
            // of the document as one long continuation.
            if (isHeader) depth = 0
            states.add(TomlLineState(startedInsideValue, startedInsideString || openedString))
        }
        return states
    }

    /** Index just past the single-line string opening at [start], or the line end if unterminated. */
    private fun endOfBasicString(line: String, start: Int): Int {
        val quote = line[start]
        var i = start + 1
        while (i < line.length) {
            when {
                quote == '"' && line[i] == '\\' -> i += 2
                line[i] == quote -> return i + 1
                else -> i++
            }
        }
        return line.length
    }

    private fun findActiveSection(document: CodeDocument, currentLine: Int): String {
        for (i in currentLine downTo 0) {
            val trimmed = document.lineText(i).trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                return trimmed
            }
        }
        return ""
    }
}
