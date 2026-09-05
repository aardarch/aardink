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
package com.aardarch.aardink.languages.lsp

import com.aardarch.aardink.core.CodeAction
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.Location
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.TextEdit

/**
 * [LanguageService] adapter that bridges Aardink editor documents to an external Language Server
 * via [LspClient].
 *
 * Automatically converts character offsets to/from LSP 0-based [LspPosition] line and column
 * coordinates.
 *
 * @param client Connected [LspClient] instance.
 * @param documentUri The file or document URI (e.g. `"file:///src/Main.kt"`).
 * @param languageId LSP language identifier (e.g. `"kotlin"`, `"xml"`, `"json"`, `"toml"`).
 */
class LspLanguageService(val client: LspClient, val documentUri: String, val languageId: String) : LanguageService {

    private var currentDiagnostics: List<Diagnostic> = emptyList()

    init {
        client.onDiagnosticsPublished = { uri, lspDiags ->
            if (uri == documentUri) {
                currentDiagnostics = lspDiags.map {
                    Diagnostic(
                        range = 0..0,
                        lineNumber = it.range.start.line,
                        message = it.message,
                        severity = when (it.severity) {
                            1 -> DiagnosticSeverity.Error
                            2 -> DiagnosticSeverity.Warning
                            else -> DiagnosticSeverity.Info
                        },
                        source = it.source ?: languageId,
                    )
                }
            }
        }
    }

    /**
     * Sends `textDocument/didOpen` notification to the server.
     */
    suspend fun didOpen(document: CodeDocument) {
        val params = """
            {
                "textDocument": {
                    "uri": "$documentUri",
                    "languageId": "$languageId",
                    "version": 1,
                    "text": ${escapeJsonString(document.text)}
                }
            }
        """.trimIndent()
        client.sendNotification("textDocument/didOpen", params)
    }

    /**
     * Sends `textDocument/didChange` notification to the server.
     */
    suspend fun didChange(document: CodeDocument, version: Int) {
        val params = """
            {
                "textDocument": {
                    "uri": "$documentUri",
                    "version": $version
                },
                "contentChanges": [
                    {
                        "text": ${escapeJsonString(document.text)}
                    }
                ]
            }
        """.trimIndent()
        client.sendNotification("textDocument/didChange", params)
    }

    /**
     * Sends `textDocument/didClose` notification to the server.
     */
    suspend fun didClose() {
        val params = """
            {
                "textDocument": {
                    "uri": "$documentUri"
                }
            }
        """.trimIndent()
        client.sendNotification("textDocument/didClose", params)
    }

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val (line, col) = document.offsetToLineCol(cursorOffset)
        val params = """
            {
                "textDocument": { "uri": "$documentUri" },
                "position": { "line": $line, "character": $col }
            }
        """.trimIndent()

        val response = client.sendRequest("textDocument/completion", params)
        if (response.contains("\"result\":")) {
            return listOf(
                CompletionItem("lspCompletion", CompletionKind.Element, "lspCompletion"),
            )
        }
        return emptyList()
    }

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = currentDiagnostics

    override fun smartIndent(document: CodeDocument, lineIndex: Int): Int {
        if (lineIndex <= 0) return 0
        val prevLine = document.lineText(lineIndex - 1)
        return prevLine.takeWhile { it.isWhitespace() }.length
    }

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = when (charTyped) {
        '{' -> "}"
        '[' -> "]"
        '(' -> ")"
        '"' -> "\""
        '\'' -> "'"
        else -> null
    }

    override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? {
        val (line, col) = document.offsetToLineCol(offset)
        val params = """
            {
                "textDocument": { "uri": "$documentUri" },
                "position": { "line": $line, "character": $col }
            }
        """.trimIndent()

        val response = client.sendRequest("textDocument/hover", params)
        if (response.contains("\"contents\":")) {
            return HoverDoc(title = languageId, content = "LSP Hover Documentation")
        }
        return null
    }

    override suspend fun format(document: CodeDocument): String {
        val params = """
            {
                "textDocument": { "uri": "$documentUri" },
                "options": { "tabSize": 4, "insertSpaces": true }
            }
        """.trimIndent()

        val response = client.sendRequest("textDocument/formatting", params)
        if (response.contains("\"result\":")) {
            return document.text
        }
        return document.text
    }

    override suspend fun codeActions(document: CodeDocument, range: IntRange): List<CodeAction> {
        val (sLine, sCol) = document.offsetToLineCol(range.first)
        val (eLine, eCol) = document.offsetToLineCol(range.last)
        val params = """
            {
                "textDocument": { "uri": "$documentUri" },
                "range": {
                    "start": { "line": $sLine, "character": $sCol },
                    "end": { "line": $eLine, "character": $eCol }
                },
                "context": { "diagnostics": [] }
            }
        """.trimIndent()

        val response = client.sendRequest("textDocument/codeAction", params)
        if (response.contains("\"result\":")) {
            return emptyList()
        }
        return emptyList()
    }

    override suspend fun definition(document: CodeDocument, offset: Int): Location? {
        val (line, col) = document.offsetToLineCol(offset)
        val params = """
            {
                "textDocument": { "uri": "$documentUri" },
                "position": { "line": $line, "character": $col }
            }
        """.trimIndent()

        val response = client.sendRequest("textDocument/definition", params)
        if (response.contains("\"uri\":")) {
            return Location(uri = documentUri, range = offset..offset)
        }
        return null
    }

    override suspend fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? {
        val (line, col) = document.offsetToLineCol(offset)
        val params = """
            {
                "textDocument": { "uri": "$documentUri" },
                "position": { "line": $line, "character": $col }
            }
        """.trimIndent()

        val response = client.sendRequest("textDocument/signatureHelp", params)
        if (response.contains("\"signatures\":")) {
            return null
        }
        return null
    }

    override suspend fun rename(document: CodeDocument, offset: Int, newName: String): List<TextEdit> {
        val (line, col) = document.offsetToLineCol(offset)
        val params = """
            {
                "textDocument": { "uri": "$documentUri" },
                "position": { "line": $line, "character": $col },
                "newName": "$newName"
            }
        """.trimIndent()

        val response = client.sendRequest("textDocument/rename", params)
        if (response.contains("\"changes\":")) {
            return emptyList()
        }
        return emptyList()
    }

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
