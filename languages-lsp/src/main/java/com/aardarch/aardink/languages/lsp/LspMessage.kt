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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Shared JSON configuration for every LSP payload.
 *
 * Unknown keys are ignored (servers send many optional fields), defaults are encoded (so
 * `"jsonrpc":"2.0"` and required booleans always appear) and `null` properties are omitted
 * rather than written as explicit `null`.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val LspJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// ── Positions & ranges ───────────────────────────────────────────────────────

/**
 * Protocol positions and ranges according to the Language Server Protocol specification.
 * Positions are 0-based line and UTF-16 character offsets.
 */
@Serializable
data class LspPosition(val line: Int, val character: Int)

/** Half-open range: [start] inclusive, [end] exclusive. `start == end` denotes an insertion point. */
@Serializable
data class LspRange(val start: LspPosition, val end: LspPosition)

@Serializable
data class LspTextDocumentIdentifier(val uri: String)

@Serializable
data class LspVersionedTextDocumentIdentifier(val uri: String, val version: Int)

@Serializable
data class LspTextDocumentItem(val uri: String, val languageId: String, val version: Int, val text: String)

@Serializable
data class LspTextDocumentContentChangeEvent(val range: LspRange? = null, val text: String)

// ── Request / notification params ────────────────────────────────────────────

@Serializable
data class LspDidOpenTextDocumentParams(val textDocument: LspTextDocumentItem)

@Serializable
data class LspDidChangeTextDocumentParams(
    val textDocument: LspVersionedTextDocumentIdentifier,
    val contentChanges: List<LspTextDocumentContentChangeEvent>,
)

@Serializable
data class LspDidCloseTextDocumentParams(val textDocument: LspTextDocumentIdentifier)

@Serializable
data class LspTextDocumentPositionParams(val textDocument: LspTextDocumentIdentifier, val position: LspPosition)

@Serializable
data class LspReferenceContext(val includeDeclaration: Boolean)

@Serializable
data class LspReferenceParams(val textDocument: LspTextDocumentIdentifier, val position: LspPosition, val context: LspReferenceContext)

@Serializable
data class LspRenameParams(val textDocument: LspTextDocumentIdentifier, val position: LspPosition, val newName: String)

@Serializable
data class LspFormattingOptions(val tabSize: Int, val insertSpaces: Boolean)

@Serializable
data class LspDocumentFormattingParams(val textDocument: LspTextDocumentIdentifier, val options: LspFormattingOptions)

@Serializable
data class LspDocumentRangeFormattingParams(
    val textDocument: LspTextDocumentIdentifier,
    val range: LspRange,
    val options: LspFormattingOptions,
)

@Serializable
data class LspCodeActionContext(val diagnostics: List<LspDiagnostic>)

@Serializable
data class LspCodeActionParams(val textDocument: LspTextDocumentIdentifier, val range: LspRange, val context: LspCodeActionContext)

// ── Server results ───────────────────────────────────────────────────────────

/**
 * A server-reported diagnostic. [severity] follows the LSP `DiagnosticSeverity` enum:
 * 1 = Error, 2 = Warning, 3 = Information, 4 = Hint.
 */
@Serializable
data class LspDiagnostic(
    val range: LspRange,
    val message: String,
    val severity: Int? = null,
    val source: String? = null,
    val code: JsonElement? = null,
)

@Serializable
data class LspPublishDiagnosticsParams(val uri: String, val version: Int? = null, val diagnostics: List<LspDiagnostic> = emptyList())

@Serializable
data class LspTextEdit(val range: LspRange, val newText: String)

@Serializable
data class LspLocation(val uri: String, val range: LspRange)

@Serializable
data class LspLocationLink(
    val targetUri: String,
    val targetRange: LspRange,
    val targetSelectionRange: LspRange,
    val originSelectionRange: LspRange? = null,
)

@Serializable
data class LspMarkupContent(val kind: String, val value: String)

/**
 * LSP `CompletionItem`. [documentation] is `string | MarkupContent` and [textEdit] is
 * `TextEdit | InsertReplaceEdit`, so both are kept as raw JSON and interpreted by the adapter.
 * [insertTextFormat] 2 means [insertText] is a snippet with `$1` / `${1:placeholder}` tab stops.
 */
@Serializable
data class LspCompletionItem(
    val label: String,
    val kind: Int? = null,
    val detail: String? = null,
    val documentation: JsonElement? = null,
    val insertText: String? = null,
    val insertTextFormat: Int? = null,
    val textEdit: JsonElement? = null,
    val filterText: String? = null,
    val sortText: String? = null,
)

@Serializable
data class LspCompletionList(val isIncomplete: Boolean = false, val items: List<LspCompletionItem> = emptyList())

/**
 * `CompletionItem.textEdit`, which is `TextEdit | InsertReplaceEdit`: [range] carries a plain
 * `TextEdit`, [insert] and [replace] an `InsertReplaceEdit`.
 */
@Serializable
data class LspCompletionEdit(
    val newText: String,
    val range: LspRange? = null,
    val insert: LspRange? = null,
    val replace: LspRange? = null,
) {
    /**
     * The range the edit applies to. For an `InsertReplaceEdit` the shorter [insert] range wins, so
     * accepting a completion never overwrites text the cursor has already moved past.
     */
    val effectiveRange: LspRange? get() = range ?: insert ?: replace
}

/** LSP `Hover`. [contents] is `MarkedString | MarkedString[] | MarkupContent`. */
@Serializable
data class LspHover(val contents: JsonElement, val range: LspRange? = null)

/** LSP `ParameterInformation`. [label] is `string | [start, end]` offsets into the signature label. */
@Serializable
data class LspParameterInformation(val label: JsonElement, val documentation: JsonElement? = null)

@Serializable
data class LspSignatureInformation(
    val label: String,
    val documentation: JsonElement? = null,
    val parameters: List<LspParameterInformation>? = null,
    val activeParameter: Int? = null,
)

@Serializable
data class LspSignatureHelp(
    val signatures: List<LspSignatureInformation> = emptyList(),
    val activeSignature: Int? = null,
    val activeParameter: Int? = null,
)

/**
 * LSP `WorkspaceEdit`. [documentChanges] is `(TextDocumentEdit | CreateFile | RenameFile | DeleteFile)[]`
 * and is kept raw; the adapter extracts `TextDocumentEdit` entries for its own document.
 */
@Serializable
data class LspWorkspaceEdit(val changes: Map<String, List<LspTextEdit>>? = null, val documentChanges: JsonElement? = null)

/**
 * LSP `CodeAction`. Also decodes a bare `Command` (`title` + `command` + `arguments`), in which
 * case [edit] is null and the entry carries no applicable text edits.
 *
 * @param disabled Present when the server offers the action only to explain why it cannot be run
 *   (`{ reason }`). Unknown members decode to nothing, so without this field a disabled action
 *   carrying an edit would look ordinary and be applied.
 */
@Serializable
data class LspCodeAction(
    val title: String,
    val kind: String? = null,
    val isPreferred: Boolean? = null,
    val edit: LspWorkspaceEdit? = null,
    val command: JsonElement? = null,
    val disabled: JsonElement? = null,
    val diagnostics: List<LspDiagnostic>? = null,
)

// ── JSON-RPC envelope ────────────────────────────────────────────────────────

/**
 * Basic JSON-RPC 2.0 message envelope for LSP communication.
 */
@Serializable
sealed class LspMessage {
    @Serializable
    data class Request(val jsonrpc: String = "2.0", val id: Long, val method: String, val params: JsonElement? = null) : LspMessage()

    @Serializable
    data class Notification(val jsonrpc: String = "2.0", val method: String, val params: JsonElement? = null) : LspMessage()

    /**
     * A response. [id] is kept as raw JSON because JSON-RPC allows numeric or string ids, and
     * server→client requests (which we answer) may use either.
     */
    @Serializable
    data class Response(val jsonrpc: String = "2.0", val id: JsonElement, val result: JsonElement? = null, val error: LspError? = null) :
        LspMessage()
}

@Serializable
data class LspError(val code: Int, val message: String, val data: JsonElement? = null)
