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

/**
 * Protocol positions and ranges according to the Language Server Protocol specification.
 * Positions are 0-based line and character offsets.
 */
data class LspPosition(val line: Int, val character: Int)

data class LspRange(val start: LspPosition, val end: LspPosition)

data class LspTextDocumentIdentifier(val uri: String)

data class LspVersionedTextDocumentIdentifier(val uri: String, val version: Int)

data class LspTextDocumentItem(val uri: String, val languageId: String, val version: Int, val text: String)

data class LspTextDocumentContentChangeEvent(val range: LspRange? = null, val text: String)

/**
 * Basic JSON-RPC 2.0 message envelope for LSP communication.
 */
sealed class LspMessage {
    data class Request(val jsonrpc: String = "2.0", val id: Long, val method: String, val params: Any? = null) : LspMessage()

    data class Notification(val jsonrpc: String = "2.0", val method: String, val params: Any? = null) : LspMessage()

    data class Response(val jsonrpc: String = "2.0", val id: Long, val result: Any? = null, val error: LspError? = null) : LspMessage()
}

data class LspError(val code: Int, val message: String, val data: Any? = null)
