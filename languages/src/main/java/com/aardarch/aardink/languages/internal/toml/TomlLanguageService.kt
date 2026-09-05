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

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        val lineCount = document.lineCount
        if (lineCount == 0 || document.text.isBlank()) return emptyList()

        val diags = mutableListOf<Diagnostic>()
        var currentSection = ""
        val sectionKeys = mutableSetOf<String>()

        for (i in 0 until lineCount) {
            val lineText = document.lineText(i)
            val trimmed = lineText.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

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
        for (i in 0 until lineCount) {
            val line = document.lineText(i)
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) {
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
