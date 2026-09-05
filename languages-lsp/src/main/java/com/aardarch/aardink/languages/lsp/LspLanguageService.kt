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
import com.aardarch.aardink.core.CodeActionKind
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.Location
import com.aardarch.aardink.core.ParameterInformation
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.SignatureInformation
import com.aardarch.aardink.core.TextEdit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * [LanguageService] adapter that bridges Aardink editor documents to an external Language Server
 * via [LspClient].
 *
 * Converts editor character offsets to/from LSP 0-based line / UTF-16 column positions, and maps
 * LSP result shapes onto the editor's `core` models. Server errors and malformed responses degrade
 * to the empty result (`emptyList()`, `null`, or the unformatted text) rather than throwing.
 *
 * Many services can share one [LspClient]; each registers its own diagnostics listener and keeps
 * only the diagnostics published for its [documentUri].
 *
 * The `ServerCapabilities` returned by [LspClient.initialize] are otherwise not consulted — every
 * request is sent and an unsupported one degrades to the empty result. Two places are exceptions:
 * [prepareRename], which reads the `MethodNotFound` code so a server offering `rename` without
 * `prepareRename` still gets a rename range, and [triggerCharacters], which a host fills from the
 * capabilities with [triggerCharactersFrom].
 *
 * @param client Connected [LspClient] instance.
 * @param documentUri The file or document URI (e.g. `"file:///src/Main.kt"`).
 * @param languageId LSP language identifier (e.g. `"kotlin"`, `"xml"`, `"json"`, `"toml"`).
 * @param serverTriggerCharacters The server's own `completionProvider.triggerCharacters`, read from
 *   its capabilities with [triggerCharactersFrom]. Null falls back to the editor's generic set,
 *   which is a poor fit for a language server — it omits `.`, so member completion after `list.`
 *   would never open — but it is all an unconfigured adapter has to go on.
 */
class LspLanguageService(
    val client: LspClient,
    val documentUri: String,
    val languageId: String,
    serverTriggerCharacters: Set<Char>? = null,
) : LanguageService {

    /**
     * The server's completion trigger characters when it declared any, else the editor's defaults.
     *
     * `CodeEditorLayout` only opens a fresh completion list for a character in this set, so a
     * server that triggers on `.` or `::` is unreachable until it says so here.
     */
    override val triggerCharacters: Set<Char> =
        serverTriggerCharacters?.takeIf { it.isNotEmpty() } ?: super.triggerCharacters

    /**
     * True: this adapter implements rename by asking the server.
     *
     * Whether a *particular* symbol can be renamed is still [prepareRename]'s answer — and a server
     * with no rename support at all answers `MethodNotFound` there.
     */
    override val supportsRename: Boolean = true

    @Volatile
    private var lspDiagnostics: List<LspDiagnostic> = emptyList()

    /** Version last sent to the server; diagnostics computed against an older one are stale. */
    @Volatile
    private var sentVersion: Int = 0

    @Volatile
    private var diagnosticsVersion: Int? = null

    private val diagnosticsListener: DiagnosticsListener = { uri, version, diagnostics ->
        if (uri == documentUri && !isStale(version)) {
            lspDiagnostics = diagnostics
            diagnosticsVersion = version
        }
    }

    /**
     * A versioned notification is stale when it predates the last version sent, or when a newer
     * versioned batch has already been accepted. Unversioned notifications are always applied —
     * `version` is optional in the protocol, so its absence says nothing about ordering.
     */
    private fun isStale(version: Int?): Boolean {
        if (version == null) return false
        return version < sentVersion || version < (diagnosticsVersion ?: Int.MIN_VALUE)
    }

    private val textDocument: LspTextDocumentIdentifier
        get() = LspTextDocumentIdentifier(documentUri)

    init {
        client.addDiagnosticsListener(diagnosticsListener)
    }

    // ── Document lifecycle ───────────────────────────────────────────────────

    /**
     * Sends `textDocument/didOpen` notification to the server and (re-)registers this service's
     * diagnostics listener.
     */
    suspend fun didOpen(document: CodeDocument) {
        client.addDiagnosticsListener(diagnosticsListener)
        sentVersion = 1
        diagnosticsVersion = null
        val params = LspDidOpenTextDocumentParams(
            LspTextDocumentItem(uri = documentUri, languageId = languageId, version = 1, text = document.text),
        )
        client.sendNotification("textDocument/didOpen", encode(LspDidOpenTextDocumentParams.serializer(), params))
    }

    /**
     * Sends a full-content `textDocument/didChange` notification to the server.
     */
    suspend fun didChange(document: CodeDocument, version: Int) {
        sentVersion = version
        val params = LspDidChangeTextDocumentParams(
            textDocument = LspVersionedTextDocumentIdentifier(documentUri, version),
            contentChanges = listOf(LspTextDocumentContentChangeEvent(text = document.text)),
        )
        client.sendNotification("textDocument/didChange", encode(LspDidChangeTextDocumentParams.serializer(), params))
    }

    /**
     * Sends `textDocument/didClose` notification to the server, unregisters the diagnostics
     * listener and clears cached diagnostics.
     */
    suspend fun didClose() {
        client.removeDiagnosticsListener(diagnosticsListener)
        lspDiagnostics = emptyList()
        sentVersion = 0
        diagnosticsVersion = null
        val params = LspDidCloseTextDocumentParams(textDocument)
        client.sendNotification("textDocument/didClose", encode(LspDidCloseTextDocumentParams.serializer(), params))
    }

    // ── LanguageService ──────────────────────────────────────────────────────

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val result = request("textDocument/completion", positionParams(document, cursorOffset)) ?: return emptyList()
        val items = when (result) {
            is JsonArray -> decodeOrNull(ListSerializer(LspCompletionItem.serializer()), result)
            is JsonObject -> decodeOrNull(LspCompletionList.serializer(), result)?.items
            else -> null
        } ?: return emptyList()
        return items.map { it.toCompletionItem(document) }
    }

    /**
     * Diagnostics most recently pushed by the server for [documentUri], converted to document
     * offsets against the current [document].
     */
    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = lspDiagnostics.map { d ->
        val range = offsetRangeOf(document, d.range)
        Diagnostic(
            // Squiggles need at least one character to attach to.
            range = range.first..range.last.coerceAtLeast(range.first),
            lineNumber = document.offsetToLineCol(range.first).first,
            message = d.message,
            severity = when (d.severity) {
                1 -> DiagnosticSeverity.Error
                2 -> DiagnosticSeverity.Warning
                else -> DiagnosticSeverity.Info
            },
            source = d.source ?: languageId,
        )
    }

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
        val result = request("textDocument/hover", positionParams(document, offset)) ?: return null
        val hover = decodeOrNull(LspHover.serializer(), result) ?: return null
        val content = markupText(hover.contents)?.takeIf { it.isNotBlank() } ?: return null
        return HoverDoc(
            title = languageId,
            content = content,
            range = hover.range?.let { offsetRangeOf(document, it) },
        )
    }

    override suspend fun format(document: CodeDocument): String {
        val params = LspDocumentFormattingParams(textDocument, DEFAULT_FORMATTING_OPTIONS)
        val result = request("textDocument/formatting", encode(LspDocumentFormattingParams.serializer(), params))
            ?: return document.text
        val edits = decodeOrNull(ListSerializer(LspTextEdit.serializer()), result) ?: return document.text
        return applyLspEdits(document, edits)
    }

    override suspend fun formatRange(document: CodeDocument, range: IntRange): List<TextEdit> {
        val params = LspDocumentRangeFormattingParams(textDocument, lspRangeOf(document, range), DEFAULT_FORMATTING_OPTIONS)
        val result = request("textDocument/rangeFormatting", encode(LspDocumentRangeFormattingParams.serializer(), params))
            ?: return emptyList()
        val edits = decodeOrNull(ListSerializer(LspTextEdit.serializer()), result) ?: return emptyList()
        return toTextEdits(document, edits)
    }

    /**
     * Code actions whose workspace edit touches this document, and whose whole effect is that edit.
     *
     * Four shapes are omitted, all for the same reason — the editor would perform only part of what
     * the action promises. Bare `Command`s have nothing this adapter can apply. An action carrying
     * *both* an edit and a `command` has the command run after the edit, and `core` has no way to
     * represent that half. An action whose edit reaches another file, or creates, renames or
     * deletes one, cannot be applied whole through a single-document API. And an action the server
     * marked `disabled` is one it has told us not to run at all.
     */
    override suspend fun codeActions(document: CodeDocument, range: IntRange): List<CodeAction> {
        val context = LspCodeActionContext(
            diagnostics = lspDiagnostics.filter { overlaps(offsetRangeOf(document, it.range), range) },
        )
        val params = LspCodeActionParams(textDocument, lspRangeOf(document, range), context)
        val result = request("textDocument/codeAction", encode(LspCodeActionParams.serializer(), params))
            ?: return emptyList()
        val actions = decodeOrNull(ListSerializer(LspCodeAction.serializer()), result) ?: return emptyList()
        return actions.mapNotNull { action ->
            if (action.command != null) return@mapNotNull null
            if (action.disabled != null) return@mapNotNull null
            if (reachesBeyondThisDocument(action.edit)) return@mapNotNull null
            val edits = editsForThisDocument(action.edit)
            if (edits.isEmpty()) return@mapNotNull null
            CodeAction(
                title = action.title,
                kind = mapCodeActionKind(action.kind),
                edits = toTextEdits(document, edits),
                isPreferred = action.isPreferred ?: false,
            )
        }
    }

    /**
     * First definition target reported by the server. When the target lives in another file the
     * returned [Location.range] is [IntRange.EMPTY], because offsets cannot be computed without that
     * file's text; consumers should open the file by [Location.uri].
     */
    override suspend fun definition(document: CodeDocument, offset: Int): Location? {
        val result = request("textDocument/definition", positionParams(document, offset)) ?: return null
        return parseLocations(result).firstOrNull()?.toLocation(document)
    }

    /** All references reported by the server; see [definition] for the cross-file range caveat. */
    override suspend fun references(document: CodeDocument, offset: Int): List<Location> {
        val params = LspReferenceParams(textDocument, positionOf(document, offset), LspReferenceContext(includeDeclaration = true))
        val result = request("textDocument/references", encode(LspReferenceParams.serializer(), params))
            ?: return emptyList()
        return parseLocations(result).map { it.toLocation(document) }
    }

    override suspend fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? {
        val result = request("textDocument/signatureHelp", positionParams(document, offset)) ?: return null
        val help = decodeOrNull(LspSignatureHelp.serializer(), result) ?: return null
        if (help.signatures.isEmpty()) return null
        val activeSignature = (help.activeSignature ?: 0).coerceIn(0, help.signatures.lastIndex)
        val activeParameter = help.signatures[activeSignature].activeParameter ?: help.activeParameter ?: 0
        return SignatureHelp(
            signatures = help.signatures.map { sig ->
                SignatureInformation(
                    label = sig.label,
                    documentation = markupText(sig.documentation),
                    parameters = sig.parameters.orEmpty().map { param ->
                        val labelRange = parameterLabelRange(sig.label, param.label)
                        ParameterInformation(
                            // The server's [start, end] form says which occurrence it means, which
                            // matters for a signature that repeats a parameter type.
                            label = labelRange?.let { sig.label.substring(it.first, it.last + 1) }
                                ?: (param.label as? JsonPrimitive)?.contentOrNull.orEmpty(),
                            documentation = markupText(param.documentation),
                            labelRange = labelRange,
                        )
                    },
                )
            },
            activeSignature = activeSignature,
            activeParameter = activeParameter,
        )
    }

    /**
     * Range of the renameable symbol at [offset], or null when the symbol cannot be renamed.
     *
     * The editor treats null as "rename is unavailable here" and does not open its dialog, so the
     * two cases where the protocol says rename *is* available but names no range — an answer of
     * `{ defaultBehavior: true }`, and a server that supports `textDocument/rename` without
     * `textDocument/prepareRename` (answered `MethodNotFound`) — resolve to the identifier around
     * [offset] here rather than being reported as "cannot rename".
     */
    override suspend fun prepareRename(document: CodeDocument, offset: Int): IntRange? {
        val result = when (val outcome = requestOrFailure("textDocument/prepareRename", positionParams(document, offset))) {
            // No prepareRename support says nothing about rename support — fall back to the word.
            is LspResult.Unsupported -> return identifierRangeAt(document.text, offset).takeUnless { it.isEmpty() }

            is LspResult.Failed -> return null

            is LspResult.Value -> outcome.element as? JsonObject ?: return null
        }
        // Either a bare Range, or { range, placeholder }, or { defaultBehavior }.
        val rangeElement = result["range"] ?: result.takeIf { "start" in it }
        if (rangeElement == null) {
            val defaultBehavior = (result["defaultBehavior"] as? JsonPrimitive)?.booleanOrNull ?: false
            return if (defaultBehavior) identifierRangeAt(document.text, offset).takeUnless { it.isEmpty() } else null
        }
        val range = decodeOrNull(LspRange.serializer(), rangeElement) ?: return null
        return offsetRangeOf(document, range)
    }

    /**
     * Text edits renaming the symbol at [offset], when the whole rename fits in this document.
     *
     * A rename that also touches another file, or creates, renames or deletes one, returns empty:
     * the editor can only edit the open document, and applying the local half of a cross-file
     * rename leaves every reference elsewhere pointing at a name that no longer exists. Refusing
     * is the honest answer until the editor gains a workspace-level edit API.
     */
    override suspend fun rename(document: CodeDocument, offset: Int, newName: String): List<TextEdit> {
        val params = LspRenameParams(textDocument, positionOf(document, offset), newName)
        val result = request("textDocument/rename", encode(LspRenameParams.serializer(), params)) ?: return emptyList()
        val edit = decodeOrNull(LspWorkspaceEdit.serializer(), result) ?: return emptyList()
        if (reachesBeyondThisDocument(edit)) return emptyList()
        return toTextEdits(document, editsForThisDocument(edit))
    }

    // ── Transport helpers ────────────────────────────────────────────────────

    private suspend fun request(method: String, params: JsonElement): JsonElement? =
        (requestOrFailure(method, params) as? LspResult.Value)?.element

    /**
     * [request] keeping the reason a call produced nothing, for the one caller that must tell
     * "the server does not implement this method" apart from "the server had no answer".
     */
    private suspend fun requestOrFailure(method: String, params: JsonElement): LspResult = try {
        LspResult.Value(client.sendRequest(method, params))
    } catch (e: LspRequestException) {
        if (e.code == LspRequestException.METHOD_NOT_FOUND) LspResult.Unsupported else LspResult.Failed
    } catch (_: SerializationException) {
        LspResult.Failed
    }

    private sealed interface LspResult {
        /** The server answered; [element] is null when that answer was JSON `null`. */
        data class Value(val element: JsonElement?) : LspResult

        /** The server does not implement the method. */
        data object Unsupported : LspResult

        /** The server errored, or the connection is gone. */
        data object Failed : LspResult
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = LspJson.encodeToJsonElement(serializer, value)

    private fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonElement): T? = try {
        LspJson.decodeFromJsonElement(serializer, element)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun positionParams(document: CodeDocument, offset: Int): JsonElement = encode(
        LspTextDocumentPositionParams.serializer(),
        LspTextDocumentPositionParams(textDocument, positionOf(document, offset)),
    )

    // ── Coordinate conversion ────────────────────────────────────────────────

    private fun positionOf(document: CodeDocument, offset: Int): LspPosition {
        val (line, column) = document.offsetToLineCol(offset)
        return LspPosition(line, column)
    }

    /** Inclusive editor [range] → half-open LSP range. An empty range (`n..n-1`) becomes an insertion point. */
    private fun lspRangeOf(document: CodeDocument, range: IntRange): LspRange = LspRange(
        positionOf(document, range.first),
        positionOf(
            document,
            range.last + 1,
        ),
    )

    /** Half-open LSP [range] → inclusive editor range. An insertion point becomes the empty range `n..n-1`. */
    private fun offsetRangeOf(document: CodeDocument, range: LspRange): IntRange {
        val start = document.lineColToOffset(range.start.line, range.start.character)
        val end = document.lineColToOffset(range.end.line, range.end.character).coerceAtLeast(start)
        return start until end
    }

    private fun overlaps(a: IntRange, b: IntRange): Boolean = a.first <= b.last + 1 && b.first <= a.last + 1

    private fun toTextEdits(document: CodeDocument, edits: List<LspTextEdit>): List<TextEdit> = edits.map {
        TextEdit(offsetRangeOf(document, it.range), it.newText)
    }

    /** Applies [edits] (positions relative to the unmodified [document]) to a copy of its text. */
    private fun applyLspEdits(document: CodeDocument, edits: List<LspTextEdit>): String {
        val builder = StringBuilder(document.text)
        edits
            .withIndex()
            .map { (index, edit) -> Triple(index, offsetRangeOf(document, edit.range), edit.newText) }
            // High-to-low, and last-to-first among edits sharing an offset, so several inserts at
            // one position come out in the order the server listed them, as the protocol requires.
            .sortedWith(
                compareByDescending<Triple<Int, IntRange, String>> { it.second.first }
                    .thenByDescending { it.second.last }
                    .thenByDescending { it.first },
            )
            .forEach { (_, range, newText) -> builder.replace(range.first, range.last + 1, newText) }
        return builder.toString()
    }

    private fun LspLocation.toLocation(document: CodeDocument): Location {
        val line = range.start.line
        val column = range.start.character
        return if (uri == documentUri) {
            Location(uri, offsetRangeOf(document, range), line, column)
        } else {
            Location(uri, IntRange.EMPTY, line, column)
        }
    }

    // ── Result-shape helpers ─────────────────────────────────────────────────

    /** `Location | Location[] | LocationLink[]` → list of locations (links use their selection range). */
    private fun parseLocations(result: JsonElement): List<LspLocation> = when (result) {
        is JsonObject -> listOfNotNull(decodeOrNull(LspLocation.serializer(), result))

        is JsonArray -> result.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if ("targetUri" in obj) {
                decodeOrNull(LspLocationLink.serializer(), obj)?.let { LspLocation(it.targetUri, it.targetSelectionRange) }
            } else {
                decodeOrNull(LspLocation.serializer(), obj)
            }
        }

        else -> emptyList()
    }

    /** Collects the edits in [edit] that target [documentUri], from both `changes` and `documentChanges`. */
    private fun editsForThisDocument(edit: LspWorkspaceEdit?): List<LspTextEdit> {
        if (edit == null) return emptyList()
        val result = mutableListOf<LspTextEdit>()
        edit.changes?.get(documentUri)?.let { result.addAll(it) }
        (edit.documentChanges as? JsonArray)?.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val uri = ((obj["textDocument"] as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull
            if (uri != documentUri) return@forEach
            obj["edits"]?.let { edits ->
                decodeOrNull(ListSerializer(LspTextEdit.serializer()), edits)?.let { result.addAll(it) }
            }
        }
        return result
    }

    /**
     * Whether [edit] does anything this adapter cannot carry out: touch another file, or create,
     * rename or delete one.
     *
     * The editor applies text edits to one open document, so there is no honest way to perform
     * half of a workspace edit. A rename that updates the declaration here and leaves every
     * reference elsewhere untouched does not leave the project better off than not renaming at
     * all — it leaves it broken — so callers reject the whole result instead of applying a part.
     */
    private fun reachesBeyondThisDocument(edit: LspWorkspaceEdit?): Boolean {
        if (edit == null) return false
        if (edit.changes?.keys?.any { it != documentUri } == true) return true
        val changes = edit.documentChanges as? JsonArray ?: return false
        return changes.any { entry ->
            val obj = entry as? JsonObject ?: return@any true
            val textDocument = obj["textDocument"] as? JsonObject
                // No `textDocument` member means a CreateFile / RenameFile / DeleteFile operation.
                ?: return@any true
            (textDocument["uri"] as? JsonPrimitive)?.contentOrNull != documentUri
        }
    }

    /** `string | MarkedString | MarkupContent | (string | MarkedString)[]` → plain text, or null. */
    private fun markupText(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> (element["value"] as? JsonPrimitive)?.contentOrNull
        is JsonArray -> element.mapNotNull { markupText(it) }.filter { it.isNotBlank() }.joinToString("\n\n").takeIf { it.isNotEmpty() }
    }

    /**
     * The `[start, end]` form of an LSP parameter label as an inclusive offset range into
     * [signatureLabel], or null when the server gave the parameter as a bare string instead.
     *
     * A null answer leaves the popup matching on text, which is all a string label supports.
     */
    private fun parameterLabelRange(signatureLabel: String, label: JsonElement): IntRange? {
        if (label !is JsonArray || label.size != 2) return null
        val start = (label[0] as? JsonPrimitive)?.intOrNull ?: return null
        val end = (label[1] as? JsonPrimitive)?.intOrNull ?: return null
        val s = start.coerceIn(0, signatureLabel.length)
        val e = end.coerceIn(s, signatureLabel.length)
        // LSP's end is exclusive; IntRange is inclusive. An empty span names no parameter text.
        return if (e > s) s..(e - 1) else null
    }

    /**
     * `textEdit` wins over `insertText` per the protocol, and carries the exact range to replace so
     * the editor does not have to guess a token boundary.
     */
    private fun LspCompletionItem.toCompletionItem(document: CodeDocument): CompletionItem {
        val edit = (textEdit as? JsonObject)?.let { decodeOrNull(LspCompletionEdit.serializer(), it) }
        val rawInsert = edit?.newText ?: insertText ?: label
        val insert = if (insertTextFormat == INSERT_TEXT_FORMAT_SNIPPET) stripSnippetSyntax(rawInsert) else rawInsert
        return CompletionItem(
            label = label,
            kind = mapCompletionKind(kind),
            insertText = insert,
            documentation = markupText(documentation) ?: detail,
            filterText = filterText ?: label,
            replaceRange = edit?.effectiveRange?.let { offsetRangeOf(document, it) },
            // The import half of an auto-import completion; applied in the same undo batch.
            additionalEdits = additionalTextEdits.orEmpty().map { TextEdit(offsetRangeOf(document, it.range), it.newText) },
        )
    }

    companion object {
        /**
         * The `completionProvider.triggerCharacters` a server declared, for
         * [LspLanguageService]'s `serverTriggerCharacters`, or null when it declared none.
         *
         * [capabilities] is the `ServerCapabilities` object [LspClient.initialize] returns. Only
         * single-character triggers are usable: the editor asks for completions per typed
         * character, so a multi-character trigger like `::` is represented by its last character.
         */
        fun triggerCharactersFrom(capabilities: JsonElement?): Set<Char>? {
            val provider = (capabilities as? JsonObject)?.get("completionProvider") as? JsonObject ?: return null
            val declared = provider["triggerCharacters"] as? JsonArray ?: return null
            val chars = declared.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lastOrNull() }
            return chars.toSet().takeIf { it.isNotEmpty() }
        }

        private val DEFAULT_FORMATTING_OPTIONS = LspFormattingOptions(tabSize = 4, insertSpaces = true)
        private const val INSERT_TEXT_FORMAT_SNIPPET = 2

        private val SNIPPET_CHOICE = Regex("""\$\{\d+\|([^,|}]*)[^}]*\}""")
        private val SNIPPET_PLACEHOLDER = Regex("""\$\{\d+:([^}]*)\}""")
        private val SNIPPET_TABSTOP = Regex("""\$\{\d+\}|\$\d+""")
        private val SNIPPET_ESCAPE = Regex("""\\([$}\\])""")

        /** Reduces LSP snippet syntax to plain text: `${1|a,b|}` → `a`, `${1:x}` → `x`, `$0` → ``. */
        private fun stripSnippetSyntax(snippet: String): String = snippet
            .replace(SNIPPET_CHOICE) { it.groupValues[1] }
            .replace(SNIPPET_PLACEHOLDER) { it.groupValues[1] }
            .replace(SNIPPET_TABSTOP, "")
            .replace(SNIPPET_ESCAPE) { it.groupValues[1] }

        /** LSP `CompletionItemKind` → editor [CompletionKind]. */
        private fun mapCompletionKind(kind: Int?): CompletionKind = when (kind) {
            5, 10 -> CompletionKind.Property

            // Field, Property
            7, 8, 9, 13, 22 -> CompletionKind.Module

            // Class, Interface, Module, Enum, Struct
            12, 20, 21 -> CompletionKind.Value

            // Value, EnumMember, Constant
            15 -> CompletionKind.Snippet

            16 -> CompletionKind.ColorRef

            // Color
            else -> CompletionKind.Element
        }

        /**
         * Identifier around [offset], used as the rename target when the server says rename is
         * available but leaves the range to the client. Empty when [offset] touches no identifier.
         */
        private fun identifierRangeAt(text: String, offset: Int): IntRange {
            fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
            val cursor = offset.coerceIn(0, text.length)
            var start = cursor
            while (start > 0 && isWordChar(text[start - 1])) start--
            var end = cursor
            while (end < text.length && isWordChar(text[end])) end++
            return start until end
        }

        /** LSP `CodeActionKind` string → editor [CodeActionKind]. */
        private fun mapCodeActionKind(kind: String?): CodeActionKind = when {
            kind == null -> CodeActionKind.QuickFix
            kind.startsWith("source.organizeImports") -> CodeActionKind.SourceOrganizeImports
            kind.startsWith("refactor") -> CodeActionKind.Refactor
            else -> CodeActionKind.QuickFix
        }
    }
}
