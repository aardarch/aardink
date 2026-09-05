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

    private val CATALOG_SECTIONS = listOf(
        "versions" to "Version catalog dependency versions",
        "libraries" to "Version catalog library declarations",
        "plugins" to "Version catalog plugin declarations",
        "bundles" to "Version catalog library bundles",
    )

    private val LIBRARY_KEYS = listOf(
        "module" to "Group and artifact ID",
        "group" to "Dependency group ID",
        "name" to "Dependency artifact name",
        "version.ref" to "Reference to [versions] entry",
    )

    private val PLUGIN_KEYS = listOf(
        "id" to "Plugin ID",
        "version.ref" to "Reference to [versions] entry",
    )

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

            // Table header [section] or [[array]] — an inline comment may follow it.
            if (trimmed.startsWith("[")) {
                val header = headerOf(trimmed)
                if (!header.endsWith("]")) {
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
                    currentSection = header
                    sectionKeys.clear()
                }
                continue
            }

            // Key = Value — the '=' inside a quoted key ("a=b" = 1) is part of the key.
            val eqIdx = assignmentIndex(lineText)
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
            val open = textBeforeCursor
            val close = "]".repeat(open.length)
            // The auto-closer has already put a ']' (or ']]') after the cursor. Accepting an item
            // writes the whole header, so the replaced range must swallow those brackets too —
            // otherwise the document ends up as `[versions]]`.
            val lineStart = document.lineStart(lineIndex)
            val headerStart = lineStart + lineText.indexOf('[')
            val trailingBrackets = lineText.drop(col).takeWhile { it == ']' }.length.coerceAtMost(close.length)
            val replaceRange = headerStart until (cursorOffset + trailingBrackets)
            return CATALOG_SECTIONS.mapIndexed { index, (name, doc) ->
                CompletionItem(
                    label = name,
                    kind = CompletionKind.Element,
                    insertText = "$open$name$close",
                    documentation = doc,
                    sortPriority = index,
                    replaceRange = replaceRange,
                )
            }
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
        val keys = when (activeSection) {
            "[libraries]" -> LIBRARY_KEYS
            "[plugins]" -> PLUGIN_KEYS
            else -> return emptyList()
        }
        // These keys are dotted ("version.ref") and '.' is a token boundary to the editor, so a
        // half-typed "version." would survive and be duplicated. Name the range explicitly.
        val keyStart = document.lineStart(lineIndex) + startOfDottedKey(lineText, col)
        return keys.map { (name, doc) ->
            CompletionItem(
                label = name,
                kind = CompletionKind.Property,
                insertText = "$name = \"\"",
                documentation = doc,
                replaceRange = keyStart until cursorOffset,
            )
        }
    }

    /** Start column of the (possibly dotted) bare key being typed at [col] on [lineText]. */
    private fun startOfDottedKey(lineText: String, col: Int): Int {
        var start = col.coerceIn(0, lineText.length)
        while (start > 0 && (lineText[start - 1].isLetterOrDigit() || lineText[start - 1] in "_-.")) start--
        return start
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
            // Split on the assignment '=', never on one inside a quoted key: formatting
            // `"a=b" = 1` must not rewrite the key to `"a = b"`.
            val eqIdx = assignmentIndex(trimmed)
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

    /**
     * The table header at the start of [trimmedLine], with any inline comment dropped —
     * `[versions] # catalog` is a perfectly closed `[versions]`.
     */
    private fun headerOf(trimmedLine: String): String {
        var i = 0
        while (i < trimmedLine.length) {
            val c = trimmedLine[i]
            when {
                c == '#' -> return trimmedLine.substring(0, i).trimEnd()
                c == '"' || c == '\'' -> i = endOfBasicString(trimmedLine, i)
                else -> i++
            }
        }
        return trimmedLine.trimEnd()
    }

    /**
     * Index of the `=` that separates key from value in [line], or -1 when the line has none.
     * An `=` inside a quoted key or after a comment marker is not an assignment.
     */
    private fun assignmentIndex(line: String): Int {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '#' -> return -1
                c == '=' -> return i
                c == '"' || c == '\'' -> i = endOfBasicString(line, i)
                else -> i++
            }
        }
        return -1
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
            if (!trimmed.startsWith("[")) continue
            val header = headerOf(trimmed)
            if (header.endsWith("]")) return header
        }
        return ""
    }
}
