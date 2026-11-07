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

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CodeAction
import com.aardarch.aardink.core.CodeActionKind
import com.aardarch.aardink.core.CodeDescription
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.ColorInfo
import com.aardarch.aardink.core.ColorPresentation
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticRelatedInformation
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.DiagnosticTag
import com.aardarch.aardink.core.DocumentHighlight
import com.aardarch.aardink.core.DocumentHighlightKind
import com.aardarch.aardink.core.DocumentLink
import com.aardarch.aardink.core.DocumentSymbol
import com.aardarch.aardink.core.FoldKind
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.LinkedEditingRanges
import com.aardarch.aardink.core.Location
import com.aardarch.aardink.core.ParameterInformation
import com.aardarch.aardink.core.SelectionRange
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.SignatureInformation
import com.aardarch.aardink.core.SymbolKind
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.Token
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile

/**
 * A server's declared rename support, as read from its `renameProvider` capability by
 * [LspLanguageService.renameSupportFrom].
 */
enum class LspRenameSupport {
    /** `renameProvider` absent or `false`: the server cannot rename, so no rename command is offered. */
    Unsupported,

    /** `renameProvider: true`: the server renames but has no `prepareRename`; the client picks the range. */
    Rename,

    /** `renameProvider: { prepareProvider: true }`: the server also answers `prepareRename`. */
    RenameWithPrepare,
}

/** Why a document is being saved, for [LspLanguageService.willSave]: LSP's `TextDocumentSaveReason`. */
enum class LspSaveReason(internal val code: Int) {
    /** The user asked for it. */
    Manual(1),

    /** A timer saved it, after a delay. */
    AfterDelay(2),

    /** The editor lost the focus. */
    FocusOut(3),
}

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
 * The usual way to keep the server's copy of the document current is [attach], which opens it and
 * sends each edit as it happens. Whatever goes to the server for this document, requests included,
 * goes in the order it happened: a completion asked for after a keystroke is written after the
 * `didChange` for that keystroke. A host that manages the text itself calls [didOpen] and
 * [didChange] instead.
 *
 * The server's `textDocumentSync` capability, read from [LspClient.serverCapabilities], and what it
 * registered through `client/registerCapability` decide what [attach], [willSave] and [didSave] send.
 * A server that declared no sync hears none of them, and no [didClose] for a document only [attach]
 * opened; capabilities not known, because [LspClient.initialize] was not called, count as whole-text
 * sync with everything else supported. [didOpen] and [didChange] are a host's own word and always go.
 *
 * The rest of `ServerCapabilities` decides what is asked, through [supports]: a request the server
 * did not declare returns the empty result without being sent. A host passes the completion trigger
 * characters in ([triggerCharactersFrom]), since they cannot wait for the handshake, and may do the
 * same for rename support ([renameSupportFrom]).
 *
 * Diagnostics come the same way whichever way the server gives them. One that declared
 * `diagnosticProvider`, or whose capabilities are not known, is asked with `textDocument/diagnostic`
 * once the document opens and each time typing pauses, and its answer reaches every
 * [DiagnosticsListener] as if it had been pushed, for the version the question was about. A
 * `MethodNotFound` ends the asking for this document. A server that declared nothing of the kind is
 * left to push.
 *
 * A server that answered a `positionEncoding` other than `utf-16` has no feature here that needs a
 * position, whatever else it declared or the host said: [supports] is false, and [supportsRename]
 * too. Edits go as whole text, and pushed diagnostics stay, on the right lines but perhaps not the
 * right columns. [LspClient] refuses its `workspace/applyEdit`.
 *
 * @param client Connected [LspClient] instance.
 * @param documentUri The file or document URI (e.g. `"file:///src/Main.kt"`).
 * @param languageId LSP language identifier (e.g. `"kotlin"`, `"xml"`, `"json"`, `"toml"`).
 * @param serverTriggerCharacters The server's own `completionProvider.triggerCharacters`, read from
 *   its capabilities with [triggerCharactersFrom]. Null falls back to the editor's generic set,
 *   which is a poor fit for a language server — it omits `.`, so member completion after `list.`
 *   would never open — but it is all an unconfigured adapter has to go on.
 * @param serverRenameSupport What the server's `renameProvider` capability declared, read with
 *   [renameSupportFrom]. Null means the host did not say, and the adapter follows
 *   [LspClient.serverCapabilities]. Until those are known it assumes rename is available and probes
 *   `prepareRename` for each symbol, as it does for rename registered through
 *   `client/registerCapability`, whose options it does not read.
 */
class LspLanguageService(
    val client: LspClient,
    val documentUri: String,
    val languageId: String,
    serverTriggerCharacters: Set<Char>? = null,
    private val serverRenameSupport: LspRenameSupport? = null,
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
     * True unless the server's capabilities said it cannot rename: those the host passed as
     * `serverRenameSupport`, or else [LspClient.serverCapabilities].
     *
     * Whether a *particular* symbol can be renamed is still [prepareRename]'s answer; this only
     * tells a host whether a rename command is worth showing at all.
     */
    override val supportsRename: Boolean
        get() = renameSupport != LspRenameSupport.Unsupported

    /** What is known about rename; null when nothing is, or the server registered it later. */
    private val renameSupport: LspRenameSupport?
        get() = when {
            !positionsAreUtf16(client.serverCapabilities) -> LspRenameSupport.Unsupported
            serverRenameSupport != null -> serverRenameSupport
            isRegistered("renameProvider") -> null
            else -> renameSupportFrom(client.serverCapabilities)
        }

    /**
     * Whether the server has [provider], a `ServerCapabilities` member name such as `renameProvider`.
     * A request it does not have returns empty without being sent.
     *
     * Absent knowledge counts as yes: capabilities not known, because [LspClient.initialize] was not
     * called or has not been answered, and a feature registered through `client/registerCapability`
     * and not taken back, whatever its `documentSelector` says.
     *
     * `textDocumentSync`, the one member that can be a number, counts when it asks to hear of the
     * document at all, by that number or through the registrations of `didOpen` and the like. It is
     * also the only member that counts for a server whose positions cannot be read.
     */
    fun supports(provider: String): Boolean {
        val capabilities = client.serverCapabilities as? JsonObject ?: return true
        if (provider == "textDocumentSync") return LspTextDocumentSync.from(capabilities, client.registrations) != LspTextDocumentSync()
        if (!positionsAreUtf16(capabilities)) return false
        val declared = capabilities[provider]
        return declared is JsonObject || (declared as? JsonPrimitive)?.booleanOrNull == true || isRegistered(provider)
    }

    private fun isRegistered(provider: String): Boolean {
        val method = methodOf(provider)
        return client.registrations.any { it.method == method }
    }

    @Volatile
    private var lspDiagnostics: List<LspDiagnostic> = emptyList()

    /** The last completion answer's `isIncomplete`; a plain array is complete. */
    private var lastCompletionsIncomplete = true

    /**
     * The last answer's `isIncomplete`, as the protocol means it: a complete list is asked for
     * once per word and filtered by the editor, an incomplete one again on each keystroke.
     */
    override val completionsAreIncomplete: Boolean
        get() = lastCompletionsIncomplete

    /** Holds the version last sent to the server; diagnostics computed against an older one are stale. */
    private val sync = LspDocumentSync(client, documentUri, languageId, onTextSent = ::pullDiagnosticsSoon, onClosed = ::callOffPull)

    @Volatile
    private var diagnosticsVersion: Int? = null

    /** The last completion answer, with each item as the server sent it, `data` and all. */
    @Volatile
    private var offeredCompletions: List<OfferedCompletion> = emptyList()

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
        return version < sync.sentVersion || version < (diagnosticsVersion ?: Int.MIN_VALUE)
    }

    /** The pull waiting for typing to pause, or waiting for its answer. A newer text replaces it. */
    @Volatile
    private var diagnosticsPull: Job? = null

    /** The server answered a pull with `MethodNotFound`, as one that only pushes may when its capabilities were not known. */
    @Volatile
    private var pullRefused = false

    /** Asks for diagnostics once the text has stood still for a moment, unless the server pushes them. */
    private fun pullDiagnosticsSoon() {
        if (pullRefused || !supports("diagnosticProvider")) return
        diagnosticsPull?.cancel()
        diagnosticsPull = client.launchUntilClosed {
            delay(DIAGNOSTICS_PULL_DEBOUNCE_MILLIS)
            pullDiagnostics()
        }
    }

    /** Called on the queue, as the sends that start a pull are, so that none can follow it. */
    private fun callOffPull() {
        diagnosticsPull?.cancel()
    }

    /**
     * The answer goes out as a push would, under the version asked about, so that [isStale] sees to
     * it the same way. `previousResultId` is not sent: a report saying `unchanged` would save the
     * server some work, but one dropped as stale would then leave nothing to compare with. A server
     * that says it anyway changes nothing here.
     *
     * What the server supports is read again, as capabilities not known a moment ago may be by now.
     */
    private suspend fun pullDiagnostics() {
        if (!supports("diagnosticProvider")) return
        val params = buildJsonObject {
            put("textDocument", textDocumentJson)
            val provider = (client.serverCapabilities as? JsonObject)?.get("diagnosticProvider") as? JsonObject
            provider?.string("identifier")?.let { put("identifier", it) }
        }
        var version = 0
        val answer = requestPatiently("textDocument/diagnostic", params) { version = sync.sentVersion }
        if (answer is LspResult.Unsupported) pullRefused = true
        val report = (answer as? LspResult.Value)?.element as? JsonObject ?: return
        if (report.string("kind") != "full") return
        val items = report["items"]?.let { decodeOrNull(ListSerializer(LspDiagnostic.serializer()), it) } ?: return
        client.deliverDiagnostics(documentUri, version, items)
    }

    private val textDocument: LspTextDocumentIdentifier
        get() = LspTextDocumentIdentifier(documentUri)

    init {
        client.addDiagnosticsListener(diagnosticsListener)
    }

    // ── Document lifecycle ───────────────────────────────────────────────────

    /**
     * Keeps the server's copy of the document in step with [state] from now until [detach]: sends
     * `textDocument/didOpen` unless the document is already open, and then each edit as it happens.
     *
     * Each edit is one `didChange` with its own version, sent as ranges when the server takes them
     * and otherwise as the whole text once typing pauses. Any request made through this service
     * waits for what was typed before it, so it never overtakes the edit it is about.
     *
     * Call this after [LspClient.initialize], since what the server declared decides how edits are
     * sent, and on the thread that edits [state], as the listeners of [CodeEditorState] are. A
     * document opened with [didOpen] and edited since is brought up to date. Attaching the state
     * already attached does nothing; another one needs [detach] first.
     *
     * @throws IllegalStateException if a different state is attached.
     */
    fun attach(state: CodeEditorState) {
        client.addDiagnosticsListener(diagnosticsListener)
        sync.attach(state)
    }

    /**
     * Stops following the state given to [attach], on the same thread, and does nothing if there is
     * none. The document stays open on the server, and edits already made still reach it; [didClose]
     * closes it, and [attach] catches up on anything missed meanwhile.
     */
    fun detach() = sync.detach()

    /**
     * Sends `textDocument/didOpen` notification to the server and (re-)registers this service's
     * diagnostics listener. A document still open, as [detach] leaves it, is not opened a second time:
     * if the text is not what the server has, it goes as a whole-text `didChange` instead.
     *
     * @throws IllegalStateException while attached: [attach] has opened the document already.
     */
    suspend fun didOpen(document: CodeDocument) {
        check(!sync.isAttached) { "$documentUri is attached to an editor state, which opened it" }
        client.addDiagnosticsListener(diagnosticsListener)
        diagnosticsVersion = null
        sync.open(document.text)
    }

    /**
     * Sends a full-content `textDocument/didChange` notification to the server.
     *
     * @throws IllegalStateException while attached: the attached state owns the version, and a second
     *   sender would put the server's copy out of step.
     */
    suspend fun didChange(document: CodeDocument, version: Int) {
        check(!sync.isAttached) { "$documentUri is attached to an editor state, which sends its changes" }
        sync.sendWholeText(document.text, version)
    }

    /**
     * Sends `textDocument/didClose` notification to the server, after any edits still on their way,
     * unregisters the diagnostics listener and clears cached diagnostics. Detaches first if attached,
     * so while attached this must be called on the thread that edits the state, as [detach] must.
     *
     * The notification goes even if the caller is cancelled while waiting for it, so this is safe in
     * a `finally`, and no diagnostics are asked for after it either way.
     */
    suspend fun didClose() {
        client.removeDiagnosticsListener(diagnosticsListener)
        lspDiagnostics = emptyList()
        diagnosticsVersion = null
        offeredCompletions = emptyList()
        sync.close()
    }

    /**
     * Sends `textDocument/willSave`, if the server asked for it with `textDocumentSync.willSave`.
     * It does not wait for the server: `willSaveWaitUntil` is not supported.
     */
    suspend fun willSave(reason: LspSaveReason) = sync.willSave(reason)

    /**
     * Sends `textDocument/didSave`, if the server's `textDocumentSync.save` asked for it, with the
     * text of [document] when it set `includeText`.
     */
    suspend fun didSave(document: CodeDocument) = sync.didSave(document)

    // ── LanguageService ──────────────────────────────────────────────────────

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val result = request("completionProvider", "textDocument/completion", positionParams(document, cursorOffset))
            ?: return emptyList()
        val list = when (result) {
            is JsonArray -> decodeOrNull(ListSerializer(LspCompletionItem.serializer()), result)?.let { LspCompletionList(false, it) }
            is JsonObject -> decodeOrNull(LspCompletionList.serializer(), result)
            else -> null
        } ?: return emptyList()
        lastCompletionsIncomplete = list.isIncomplete
        // The server orders by sortText (the label when it has none); the editor by sortPriority.
        val ranks = list.items.map { it.sortText ?: it.label }.distinct().sorted().withIndex().associate { (i, key) -> key to i }
        val items = list.items.map { it.toCompletionItem(document).copy(sortPriority = ranks.getValue(it.sortText ?: it.label)) }
        val rawItems = result as? JsonArray ?: (result as? JsonObject)?.get("items") as? JsonArray
        offeredCompletions = items.zip(rawItems.orEmpty()) { item, raw -> OfferedCompletion(item, raw) }
        return items
    }

    /**
     * [item] with the `documentation`, `detail` and `additionalTextEdits` the server's
     * `completionItem/resolve` adds. The server is sent its own item back, so [item] has to come
     * from the last [completions] answer; another one comes back as it is, as it does from a server
     * that does not resolve, or fails to.
     *
     * The resolved edits are placed in the text the server had when it was asked, through [attach],
     * or [didOpen] and [didChange], even if that text has changed by the time it answers: accepting
     * an item changes it, and the editor moves them past its own edit. With neither there is no
     * telling where they go, and [item] keeps its own.
     */
    override suspend fun resolveCompletion(item: CompletionItem): CompletionItem {
        if (!resolvesCompletions) return item
        val raw = rawCompletionOf(item) ?: return item
        var asked: String? = null
        val answer = requestOrFailure("completionItem/resolve", raw) { asked = sync.textAtTurn }
        val result = (answer as? LspResult.Value)?.element ?: return item
        val resolved = decodeOrNull(LspCompletionItem.serializer(), result) ?: return item
        return item.copy(
            documentation = documentationMarkdown(resolved.documentation) ?: item.documentation,
            detail = resolved.detail ?: item.detail,
            additionalEdits = asked?.let { text ->
                resolved.additionalTextEdits.orEmpty().takeIf { it.isNotEmpty() }?.let { toTextEdits(CodeDocument(text), it) }
            } ?: item.additionalEdits,
        )
    }

    /**
     * What the server sent for [item]. Two items can be equal, and only their `data` tell them apart,
     * so it goes by instance. The editor keeps a list open through typing by re-addressing its items,
     * which copies those with a range: such a copy is still [item], if only one fits it.
     */
    private fun rawCompletionOf(item: CompletionItem): JsonElement? {
        val offered = offeredCompletions
        offered.firstOrNull { it.item === item }?.let { return it.raw }
        return offered
            .filter { it.item.copy(replaceRange = item.replaceRange, additionalEdits = item.additionalEdits) == item }
            .singleOrNull()
            ?.raw
    }

    /** `completionProvider.resolveProvider`, or the same in a registration's options. */
    private val resolvesCompletions: Boolean
        get() {
            val capabilities = client.serverCapabilities as? JsonObject ?: return true
            val declared = capabilities["completionProvider"] as? JsonObject
            return declared?.boolean("resolveProvider") == true ||
                client.registrations.any { it.method == methodOf("completionProvider") && it.options?.boolean("resolveProvider") != false }
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
            message = d.message,
            severity = when (d.severity) {
                1 -> DiagnosticSeverity.Error
                2 -> DiagnosticSeverity.Warning
                4 -> DiagnosticSeverity.Hint
                else -> DiagnosticSeverity.Info
            },
            source = d.source ?: languageId,
            code = (d.code as? JsonPrimitive)?.content,
            codeDescription = d.codeDescription?.let { CodeDescription(it.href) },
            tags = d.tags.orEmpty().mapNotNullTo(mutableSetOf()) {
                when (it) {
                    1 -> DiagnosticTag.Unnecessary
                    2 -> DiagnosticTag.Deprecated
                    else -> null
                }
            },
            relatedInformation = d.relatedInformation.orEmpty().map { related ->
                DiagnosticRelatedInformation(related.location.toLocation(document), related.message)
            },
        )
    }

    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String {
        if (lineIndex <= 0) return ""
        return options.indentAfter(document.lineText(lineIndex - 1))
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
        val result = request("hoverProvider", "textDocument/hover", positionParams(document, offset)) ?: return null
        val hover = decodeOrNull(LspHover.serializer(), result) ?: return null
        val content = hoverMarkdown(hover.contents)?.takeIf { it.isNotBlank() } ?: return null
        return HoverDoc(
            title = languageId,
            content = content,
            range = hover.range?.let { offsetRangeOf(document, it) },
        )
    }

    override suspend fun format(document: CodeDocument, options: FormattingOptions): String {
        val params = LspDocumentFormattingParams(textDocument, options.toLsp())
        val result =
            request("documentFormattingProvider", "textDocument/formatting", encode(LspDocumentFormattingParams.serializer(), params))
                ?: return document.text
        val edits = decodeOrNull(ListSerializer(LspTextEdit.serializer()), result) ?: return document.text
        return applyLspEdits(document, edits)
    }

    override suspend fun formatRange(document: CodeDocument, range: IntRange, options: FormattingOptions): List<TextEdit> {
        val params = LspDocumentRangeFormattingParams(textDocument, lspRangeOf(document, range), options.toLsp())
        val result = request(
            "documentRangeFormattingProvider",
            "textDocument/rangeFormatting",
            encode(LspDocumentRangeFormattingParams.serializer(), params),
        ) ?: return emptyList()
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
     * deletes one, cannot be applied whole through a single-document API: unlike a rename, nothing
     * here hears when one is chosen, so its other files cannot go to [LspClientHost] at that moment.
     * And an action the server marked `disabled` is one it has told us not to run at all.
     */
    override suspend fun codeActions(document: CodeDocument, range: IntRange): List<CodeAction> {
        val context = LspCodeActionContext(
            diagnostics = lspDiagnostics.filter { overlaps(offsetRangeOf(document, it.range), range) },
        )
        val params = LspCodeActionParams(textDocument, lspRangeOf(document, range), context)
        val result = request("codeActionProvider", "textDocument/codeAction", encode(LspCodeActionParams.serializer(), params))
            ?: return emptyList()
        val actions = decodeOrNull(ListSerializer(LspCodeAction.serializer()), result) ?: return emptyList()
        return actions.mapNotNull { action ->
            if (action.command != null) return@mapNotNull null
            if (action.disabled != null) return@mapNotNull null
            val parts = action.edit?.let(::partsOf) ?: return@mapNotNull null
            if (parts.elsewhere != null || parts.here.isEmpty()) return@mapNotNull null
            CodeAction(
                title = action.title,
                kind = mapCodeActionKind(action.kind),
                edits = toTextEdits(document, parts.here),
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
        val result = request("definitionProvider", "textDocument/definition", positionParams(document, offset)) ?: return null
        return parseLocations(result).firstOrNull()?.toLocation(document)
    }

    /**
     * Where the type of the symbol at [offset] is defined, for a host's own menu item; the editor has
     * no command for it. See [definition] for the cross-file range caveat, as for [implementation]
     * and [declaration].
     */
    suspend fun typeDefinition(document: CodeDocument, offset: Int): List<Location> =
        locations("typeDefinitionProvider", "textDocument/typeDefinition", document, offset)

    /** Where the symbol at [offset] is implemented, for a host's own menu item. */
    suspend fun implementation(document: CodeDocument, offset: Int): List<Location> =
        locations("implementationProvider", "textDocument/implementation", document, offset)

    /** Where the symbol at [offset] is declared, for a host's own menu item. */
    suspend fun declaration(document: CodeDocument, offset: Int): List<Location> =
        locations("declarationProvider", "textDocument/declaration", document, offset)

    private suspend fun locations(provider: String, method: String, document: CodeDocument, offset: Int): List<Location> {
        val result = request(provider, method, positionParams(document, offset)) ?: return emptyList()
        return parseLocations(result).map { it.toLocation(document) }
    }

    /** All references reported by the server; see [definition] for the cross-file range caveat. */
    override suspend fun references(document: CodeDocument, offset: Int): List<Location> {
        val params = LspReferenceParams(textDocument, positionOf(document, offset), LspReferenceContext(includeDeclaration = true))
        val result = request("referencesProvider", "textDocument/references", encode(LspReferenceParams.serializer(), params))
            ?: return emptyList()
        return parseLocations(result).map { it.toLocation(document) }
    }

    override suspend fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? {
        val result = request("signatureHelpProvider", "textDocument/signatureHelp", positionParams(document, offset)) ?: return null
        val help = decodeOrNull(LspSignatureHelp.serializer(), result) ?: return null
        if (help.signatures.isEmpty()) return null
        val activeSignature = (help.activeSignature ?: 0).coerceIn(0, help.signatures.lastIndex)
        val activeParameter = help.signatures[activeSignature].activeParameter ?: help.activeParameter ?: 0
        return SignatureHelp(
            signatures = help.signatures.map { sig ->
                SignatureInformation(
                    label = sig.label,
                    documentation = documentationMarkdown(sig.documentation),
                    parameters = sig.parameters.orEmpty().map { param ->
                        val labelRange = parameterLabelRange(sig.label, param.label)
                        ParameterInformation(
                            // The server's [start, end] form says which occurrence it means, which
                            // matters for a signature that repeats a parameter type.
                            label = labelRange?.let { sig.label.substring(it.first, it.last + 1) }
                                ?: (param.label as? JsonPrimitive)?.contentOrNull.orEmpty(),
                            documentation = documentationMarkdown(param.documentation),
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
     * `textDocument/prepareRename` — resolve to the identifier around [offset] here rather than
     * being reported as "cannot rename". The second case is known up front from the capabilities
     * (no request is sent), and otherwise recognised by a `MethodNotFound` answer.
     * A server whose capabilities declare no rename support at all gets null without a request:
     * a `MethodNotFound` from it would mean "no rename", not "no prepareRename".
     */
    override suspend fun prepareRename(document: CodeDocument, offset: Int): IntRange? {
        when (renameSupport) {
            LspRenameSupport.Unsupported -> return null
            LspRenameSupport.Rename -> return identifierRangeAt(document.text, offset).takeUnless { it.isEmpty() }
            LspRenameSupport.RenameWithPrepare, null -> Unit
        }
        val result = when (val outcome = requestOrFailure("textDocument/prepareRename", positionParams(document, offset))) {
            // Rename is declared or assumed supported, so a missing prepareRename says nothing
            // about it — fall back to the word.
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
     * Text edits renaming the symbol at [offset] in this document.
     *
     * What the rename does to other files, or creating, renaming or deleting one, goes to
     * [LspClientHost.applyWorkspaceEdit] first, without this document's edits, and they are returned
     * only if it says all of that was applied: the local half of a cross-file rename alone leaves
     * every reference elsewhere pointing at a name that no longer exists. So a host that applies
     * nothing, which is the default, gets empty here, and so does a rename that creates, renames or
     * deletes this very document, whose edits cannot be put in order with that from here. The
     * host is not asked if the text has changed since the server was: the editor would drop the
     * edits for this document, for being about text that has moved on.
     */
    override suspend fun rename(document: CodeDocument, offset: Int, newName: String): List<TextEdit> {
        if (renameSupport == LspRenameSupport.Unsupported) return emptyList()
        val params = LspRenameParams(textDocument, positionOf(document, offset), newName)
        var revision = 0L
        val answer = requestOrFailure("textDocument/rename", encode(LspRenameParams.serializer(), params)) { revision = sync.textRevision }
        val result = (answer as? LspResult.Value)?.element ?: return emptyList()
        val edit = decodeOrNull(LspWorkspaceEdit.serializer(), result) ?: return emptyList()
        val parts = partsOf(edit) ?: return emptyList()
        if (parts.elsewhere != null && (sync.textRevision != revision || !client.applyThroughHost(parts.elsewhere))) return emptyList()
        return toTextEdits(document, parts.here)
    }

    /**
     * The document's structure. A server's flat `SymbolInformation` list is nested by range, as the
     * editor's breadcrumbs and sticky scroll need a tree, and symbols it places in other files, whose
     * offsets cannot be known, are left out.
     */
    override suspend fun documentSymbols(document: CodeDocument): List<DocumentSymbol> {
        val result = request("documentSymbolProvider", "textDocument/documentSymbol", documentParams()) as? JsonArray ?: return emptyList()
        val entries = result.filterIsInstance<JsonObject>()
        if (entries.none { "location" in it }) return inDocumentOrder(entries.mapNotNull { it.toDocumentSymbol(document) })
        return nestedByRange(entries.mapNotNull { it.toFlatSymbol(document) })
    }

    override suspend fun documentHighlights(document: CodeDocument, offset: Int): List<DocumentHighlight> {
        val params = positionParams(document, offset)
        val result = request("documentHighlightProvider", "textDocument/documentHighlight", params) as? JsonArray ?: return emptyList()
        return result.filterIsInstance<JsonObject>().mapNotNull { highlight ->
            val range = highlight.range("range") ?: return@mapNotNull null
            val kind = when (highlight.int("kind")) {
                2 -> DocumentHighlightKind.Read
                3 -> DocumentHighlightKind.Write
                else -> DocumentHighlightKind.Text
            }
            DocumentHighlight(offsetRangeOf(document, range), kind)
        }
    }

    /** One chain per offset, or none at all if the server answered for some and not for others. */
    override suspend fun selectionRanges(document: CodeDocument, offsets: List<Int>): List<SelectionRange> {
        val params = buildJsonObject {
            put("textDocument", textDocumentJson)
            put("positions", JsonArray(offsets.map { encode(LspPosition.serializer(), positionOf(document, it)) }))
        }
        val result = request("selectionRangeProvider", "textDocument/selectionRange", params) as? JsonArray ?: return emptyList()
        val ranges = result.map { (it as? JsonObject)?.toSelectionRange(document) ?: return emptyList() }
        return if (ranges.size == offsets.size) ranges else emptyList()
    }

    private fun JsonObject.toSelectionRange(document: CodeDocument): SelectionRange? {
        val range = range("range") ?: return null
        return SelectionRange(offsetRangeOf(document, range), (this["parent"] as? JsonObject)?.toSelectionRange(document))
    }

    override suspend fun linkedEditingRanges(document: CodeDocument, offset: Int): LinkedEditingRanges? {
        val params = positionParams(document, offset)
        val result = request("linkedEditingRangeProvider", "textDocument/linkedEditingRange", params) as? JsonObject ?: return null
        val ranges = (result["ranges"] as? JsonArray).orEmpty().mapNotNull { decodeOrNull(LspRange.serializer(), it) }
        return if (ranges.isEmpty()) null else LinkedEditingRanges(ranges.map { offsetRangeOf(document, it) })
    }

    /** The links with a target. One the server would fill in through `documentLink/resolve` is left out. */
    override suspend fun documentLinks(document: CodeDocument): List<DocumentLink> {
        val result = request("documentLinkProvider", "textDocument/documentLink", documentParams()) as? JsonArray ?: return emptyList()
        return result.filterIsInstance<JsonObject>().mapNotNull { link ->
            val range = link.range("range") ?: return@mapNotNull null
            val target = link.string("target") ?: return@mapNotNull null
            DocumentLink(offsetRangeOf(document, range), target)
        }
    }

    override suspend fun documentColors(document: CodeDocument): List<ColorInfo> {
        val result = request("colorProvider", "textDocument/documentColor", documentParams()) as? JsonArray ?: return emptyList()
        return result.filterIsInstance<JsonObject>().mapNotNull { info ->
            val range = info.range("range") ?: return@mapNotNull null
            val color = (info["color"] as? JsonObject)?.toColor() ?: return@mapNotNull null
            ColorInfo(offsetRangeOf(document, range), color)
        }
    }

    /**
     * Ways to write [color] in place of [info]. One with `additionalTextEdits` is left out, like a
     * code action that cannot be applied whole: `core` can only carry its main edit.
     */
    override suspend fun colorPresentations(document: CodeDocument, info: ColorInfo, color: Color): List<ColorPresentation> {
        val params = buildJsonObject {
            put("textDocument", textDocumentJson)
            put(
                "color",
                buildJsonObject {
                    put("red", color.red)
                    put("green", color.green)
                    put("blue", color.blue)
                    put("alpha", color.alpha)
                },
            )
            put("range", encode(LspRange.serializer(), lspRangeOf(document, info.range)))
        }
        val result = request("colorProvider", "textDocument/colorPresentation", params) as? JsonArray ?: return emptyList()
        return result.filterIsInstance<JsonObject>().mapNotNull { presentation ->
            val label = presentation.string("label") ?: return@mapNotNull null
            if ((presentation["additionalTextEdits"] as? JsonArray).orEmpty().isNotEmpty()) return@mapNotNull null
            val edit = presentation["textEdit"]?.let { decodeOrNull(LspTextEdit.serializer(), it) }
            ColorPresentation(label, edit?.let { TextEdit(offsetRangeOf(document, it.range), it.newText) })
        }
    }

    /**
     * The server's `textDocument/semanticTokens/full` answer for [document], which should be what it
     * was last sent, read through the legend it declared in `semanticTokensProvider` or registered.
     * A server that declared neither, or none that answers a `full` request, is not asked, and neither
     * is one whose capabilities are not known, since its numbers could not be read.
     *
     * The editor asks once per edit, so a request that comes too soon may be the only one an unedited
     * document gets, as with [LspFoldingProvider]: this waits for the document to open, and asks a few
     * more times if the server says to. The editor's cancellation ends either wait.
     *
     * A token's type is a [NamedTokenType][com.aardarch.aardink.core.NamedTokenType] under the part of
     * a name a theme colours: `type.class`, `variable.parameter`, `function.method`, then its
     * modifiers, `function.method.static`. A theme can colour any of those by name.
     */
    override suspend fun semanticTokens(document: CodeDocument): List<Token> {
        if (supports("semanticTokensProvider")) sync.awaitOpen()
        val legend = semanticLegend ?: return emptyList()
        val answer = requestPatiently("textDocument/semanticTokens/full", documentParams())
        val data = ((answer as? LspResult.Value)?.element as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return decodeSemanticTokens(data, legend, document)
    }

    /** What the server's numbers mean, from what it declared or else registered; null when it does not say. */
    private val semanticLegend: LspSemanticLegend?
        get() {
            if (!supports("semanticTokensProvider")) return null
            val declared = (client.serverCapabilities as? JsonObject)?.get("semanticTokensProvider") as? JsonObject
            val method = methodOf("semanticTokensProvider")
            val registered = client.registrations.filter { it.method == method }.mapNotNull { it.options }
            return (listOfNotNull(declared) + registered).firstNotNullOfOrNull(LspSemanticLegend::from)
        }

    /**
     * The server's folds in order, one per first line, or null when it gave no answer: an empty list
     * is one it means. See [LspFoldingProvider].
     *
     * The editor asks once per edit, so a request that comes too soon may be the only one an
     * unedited document gets. Hence the wait for the document, which opens after the handshake: a
     * server asked before that can only fail, or say a document it has not heard of folds nothing.
     */
    internal suspend fun foldingRanges(document: CodeDocument): List<FoldRange>? {
        if (supports("foldingRangeProvider")) sync.awaitOpen()
        if (!supports("foldingRangeProvider")) return emptyList()
        val result = when (val outcome = requestPatiently("textDocument/foldingRange", documentParams())) {
            is LspResult.Unsupported -> return emptyList()
            is LspResult.Failed -> return null
            is LspResult.Value -> outcome.element as? JsonArray ?: return null
        }
        return result
            .filterIsInstance<JsonObject>()
            .mapNotNull { it.toFoldRange(lastLine = document.lineCount - 1) }
            .sortedWith(compareBy<FoldRange> { it.startLine }.thenByDescending { it.endLine })
            .distinctBy { it.startLine }
    }

    /**
     * [requestOrFailure], asking a few more times if the server says to, as it may while it starts
     * up: for what is asked once per edit, where an unedited document gets no second chance.
     */
    private suspend fun requestPatiently(method: String, params: JsonElement, onTurn: () -> Unit = {}): LspResult {
        var outcome = requestOrFailure(method, params, onTurn)
        for (wait in RETRY_MILLIS) {
            if ((outcome as? LspResult.Failed)?.tryAgain != true) break
            delay(wait)
            outcome = requestOrFailure(method, params, onTurn)
        }
        return outcome
    }

    // ── Transport helpers ────────────────────────────────────────────────────

    /** [request], when the server has [provider]; see [supports]. */
    private suspend fun request(provider: String, method: String, params: JsonElement): JsonElement? =
        if (supports(provider)) request(method, params) else null

    private suspend fun request(method: String, params: JsonElement): JsonElement? =
        (requestOrFailure(method, params) as? LspResult.Value)?.element

    /**
     * [request] keeping the reason a call produced nothing, for the one caller that must tell
     * "the server does not implement this method" apart from "the server had no answer". [onTurn]
     * runs in the document's order, just before the request is written.
     */
    private suspend fun requestOrFailure(method: String, params: JsonElement, onTurn: () -> Unit = {}): LspResult = try {
        LspResult.Value(
            sync.inOrder { written ->
                onTurn()
                client.sendRequest(method, params, written)
            },
        )
    } catch (e: LspRequestException) {
        if (e.code == LspRequestException.METHOD_NOT_FOUND) LspResult.Unsupported else LspResult.Failed(e.code in TRY_AGAIN_ERRORS)
    } catch (_: SerializationException) {
        LspResult.Failed(tryAgain = false)
    }

    private sealed interface LspResult {
        /** The server answered; [element] is null when that answer was JSON `null`. */
        data class Value(val element: JsonElement?) : LspResult

        /** The server does not implement the method. */
        data object Unsupported : LspResult

        /** The server errored, or the connection is gone. [tryAgain]: it may answer later. */
        data class Failed(val tryAgain: Boolean) : LspResult
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = LspJson.encodeToJsonElement(serializer, value)

    private fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonElement): T? = try {
        LspJson.decodeFromJsonElement(serializer, element)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private val textDocumentJson: JsonElement
        get() = encode(LspTextDocumentIdentifier.serializer(), textDocument)

    private fun documentParams(): JsonElement = buildJsonObject { put("textDocument", textDocumentJson) }

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
        val start = offsetOf(document, range.start)
        val end = offsetOf(document, range.end).coerceAtLeast(start)
        return start until end
    }

    /**
     * Editor offset of [position]. A line past the last one is the end of the document — the
     * `{ line: lineCount, character: 0 }` idiom servers use for "end of file" in a whole-document
     * edit — where [CodeDocument.lineColToOffset] would clamp it to the *start* of the last line.
     */
    private fun offsetOf(document: CodeDocument, position: LspPosition): Int =
        if (position.line >= document.lineCount) document.length else document.lineColToOffset(position.line, position.character)

    /**
     * Whether two inclusive editor ranges share a character. An empty range is an insertion point
     * (`n..n-1`, see [offsetRangeOf]) and counts as overlapping when it sits inside the other range
     * or at either of its edges; two non-empty ranges that merely abut do not overlap, so a
     * quick-fix request for one diagnostic does not carry its neighbour's diagnostic along.
     */
    private fun overlaps(a: IntRange, b: IntRange): Boolean = when {
        a.isEmpty() -> a.first in b.first..b.last + 1
        b.isEmpty() -> b.first in a.first..a.last + 1
        else -> a.first <= b.last && b.first <= a.last
    }

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
            .forEach { (_, range, newText) ->
                // StringBuilder.replace(start, end, str) is JVM-only; deleteRange + insert is the
                // common-Kotlin equivalent.
                builder.deleteRange(range.first, range.last + 1)
                builder.insert(range.first, newText)
            }
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

    /** A workspace edit by who can carry it out: the editor [here], and the host [elsewhere], if anything is left for it. */
    private class EditParts(val here: List<LspTextEdit>, val elsewhere: LspWorkspaceEdit?)

    /**
     * [edit] divided between this document and everything else, from both `changes` and
     * `documentChanges`. The editor applies text edits to one open document, and there is no honest
     * way to perform half of a workspace edit, so a caller goes ahead only if every part is taken
     * care of.
     *
     * Null when part of it cannot be: `documentChanges` that is not a list, an entry of it that is
     * not a `TextDocumentEdit` or a file operation this can read through, which a host might skip and
     * still report as applied, or a file operation on this document itself, which has to happen in
     * order with its text edits.
     */
    private fun partsOf(edit: LspWorkspaceEdit): EditParts? {
        val here = edit.changes?.get(documentUri).orEmpty().toMutableList()
        val otherChanges = edit.changes.orEmpty() - documentUri
        val otherEntries = mutableListOf<JsonElement>()
        val entries = when (val changes = edit.documentChanges) {
            null, JsonNull -> emptyList()
            is JsonArray -> changes
            else -> return null
        }
        for (entry in entries) {
            val change = entry as? JsonObject ?: return null
            if ("textDocument" in change) {
                val uri = (change["textDocument"] as? JsonObject)?.uri("uri") ?: return null
                val edits = change["edits"]?.let { decodeOrNull(ListSerializer(LspTextEdit.serializer()), it) } ?: return null
                if (uri == documentUri) here += edits else otherEntries += entry
            } else {
                val uris = change.fileOperationUris() ?: return null
                if (documentUri in uris) return null
                otherEntries += entry
            }
        }
        if (otherChanges.isEmpty() && otherEntries.isEmpty()) return EditParts(here, elsewhere = null)
        val elsewhere = LspWorkspaceEdit(otherChanges.ifEmpty { null }, JsonArray(otherEntries).takeIf { it.isNotEmpty() })
        return EditParts(here, elsewhere)
    }

    /** Member by member, for the reason [LspClient] gives: these wire types would be public ABI as `@Serializable`. */
    private fun JsonObject.range(name: String): LspRange? = this[name]?.let { decodeOrNull(LspRange.serializer(), it) }

    private fun JsonObject.toDocumentSymbol(document: CodeDocument): DocumentSymbol? {
        val name = string("name") ?: return null
        val range = offsetRangeOf(document, range("range") ?: return null)
        val children = (this["children"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        return DocumentSymbol(
            name = name,
            kind = symbolKind(int("kind")),
            range = range,
            selectionRange = range("selectionRange")?.let { offsetRangeOf(document, it) } ?: range,
            detail = string("detail")?.takeIf { it.isNotEmpty() },
            children = inDocumentOrder(children.mapNotNull { it.toDocumentSymbol(document) }),
        )
    }

    /** A `SymbolInformation`, unless it is in another file. */
    private fun JsonObject.toFlatSymbol(document: CodeDocument): DocumentSymbol? {
        val name = string("name") ?: return null
        val location = this["location"]?.let { decodeOrNull(LspLocation.serializer(), it) } ?: return null
        if (location.uri != documentUri) return null
        return DocumentSymbol(name, symbolKind(int("kind")), offsetRangeOf(document, location.range))
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
        return CompletionItem(
            label = label,
            kind = mapCompletionKind(kind),
            insertText = edit?.newText ?: insertText ?: label,
            documentation = documentationMarkdown(documentation),
            detail = detail,
            commitCharacters = commitCharacters.orEmpty().mapNotNullTo(mutableSetOf()) { it.singleOrNull() },
            filterText = filterText ?: label,
            replaceRange = edit?.effectiveRange?.let { offsetRangeOf(document, it) },
            // The import half of an auto-import completion; applied in the same undo batch.
            additionalEdits = additionalTextEdits.orEmpty().map { TextEdit(offsetRangeOf(document, it.range), it.newText) },
            // The editor fills in LSP snippets itself: tab stops, placeholders and choices.
            isSnippet = insertTextFormat == INSERT_TEXT_FORMAT_SNIPPET,
        )
    }

    /** An item of the last completion answer, and that item as the server sent it. */
    private class OfferedCompletion(val item: CompletionItem, val raw: JsonElement)

    companion object {
        /** LSP's `ServerNotInitialized`, `ContentModified` and `ServerCancelled`: nothing wrong with the request. */
        private val TRY_AGAIN_ERRORS = setOf(-32002, -32801, -32802)

        /** The pauses before each further request of [requestPatiently]. An edit asks anew after those. */
        private val RETRY_MILLIS = listOf(1_000L, 2_000L, 4_000L)

        /** How long the text has to stand still for its diagnostics to be pulled. */
        private const val DIAGNOSTICS_PULL_DEBOUNCE_MILLIS = 400L

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

        /**
         * What a server's `renameProvider` capability declared, for [LspLanguageService]'s
         * `serverRenameSupport`, or null when [capabilities] is not a capabilities object at all.
         *
         * [capabilities] is the `ServerCapabilities` object [LspClient.initialize] returns. Per the
         * protocol, `renameProvider` is absent or `false` for no support, `true` for rename alone,
         * and an object — `{ "prepareProvider": true }` — when the server also answers
         * `textDocument/prepareRename`.
         */
        fun renameSupportFrom(capabilities: JsonElement?): LspRenameSupport? {
            val declaredCapabilities = capabilities as? JsonObject ?: return null
            return when (val provider = declaredCapabilities["renameProvider"]) {
                is JsonObject ->
                    if ((provider["prepareProvider"] as? JsonPrimitive)?.booleanOrNull == true) {
                        LspRenameSupport.RenameWithPrepare
                    } else {
                        LspRenameSupport.Rename
                    }

                is JsonPrimitive -> if (provider.booleanOrNull == true) LspRenameSupport.Rename else LspRenameSupport.Unsupported

                // Absent, or a shape the protocol does not define.
                else -> LspRenameSupport.Unsupported
            }
        }

        /** The method a `ServerCapabilities` member stands for, in `client/registerCapability`. */
        private fun methodOf(provider: String): String = when (provider) {
            "colorProvider" -> "textDocument/documentColor"
            "documentFormattingProvider" -> "textDocument/formatting"
            "documentRangeFormattingProvider" -> "textDocument/rangeFormatting"
            "documentOnTypeFormattingProvider" -> "textDocument/onTypeFormatting"
            "callHierarchyProvider" -> "textDocument/prepareCallHierarchy"
            "typeHierarchyProvider" -> "textDocument/prepareTypeHierarchy"
            "executeCommandProvider" -> "workspace/executeCommand"
            "workspaceSymbolProvider" -> "workspace/symbol"
            else -> "textDocument/${provider.removeSuffix("Provider")}"
        }

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.uri(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** The files a `CreateFile`, `RenameFile` or `DeleteFile` is about, or null if this is not all of one. */
        private fun JsonObject.fileOperationUris(): List<String>? {
            val members = when (string("kind")) {
                "create", "delete" -> listOf("uri")
                "rename" -> listOf("oldUri", "newUri")
                else -> return null
            }
            return members.map { uri(it) ?: return null }
        }

        private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

        private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.float(name: String): Float? = (this[name] as? JsonPrimitive)?.floatOrNull

        /** LSP's `Color`, each channel from 0 to 1. [Color] refuses anything outside that. */
        private fun JsonObject.toColor(): Color? {
            fun channel(name: String) = float(name)?.coerceIn(0f, 1f)
            return Color(
                red = channel("red") ?: return null,
                green = channel("green") ?: return null,
                blue = channel("blue") ?: return null,
                alpha = channel("alpha") ?: return null,
            )
        }

        /** A `FoldingRange` that folds something, within the document's [lastLine]. */
        private fun JsonObject.toFoldRange(lastLine: Int): FoldRange? {
            val start = int("startLine") ?: return null
            val end = (int("endLine") ?: return null).coerceAtMost(lastLine)
            if (start < 0 || end <= start) return null
            val kind = when (string("kind")) {
                "comment" -> FoldKind.Comment
                "imports" -> FoldKind.Imports
                "region" -> FoldKind.Region
                else -> null
            }
            val range = FoldRange(start, end, kind = kind)
            return string("collapsedText")?.takeIf { it.isNotEmpty() }?.let { range.copy(placeholder = it) } ?: range
        }

        /** [SymbolKind] is in LSP's order, from 1. A kind newer than this one reads as a property. */
        private fun symbolKind(kind: Int?): SymbolKind = SymbolKind.entries.getOrNull((kind ?: 0) - 1) ?: SymbolKind.Property

        private fun inDocumentOrder(symbols: List<DocumentSymbol>) = symbols.sortedBy { it.range.first }

        /** [symbols], each one the child of the smallest other whose range holds it, as VS Code nests them. */
        private fun nestedByRange(symbols: List<DocumentSymbol>): List<DocumentSymbol> {
            val sorted = symbols.sortedWith(compareBy<DocumentSymbol> { it.range.first }.thenByDescending { it.range.last })
            var next = 0
            fun inside(last: Int): List<DocumentSymbol> {
                val level = ArrayList<DocumentSymbol>()
                while (next < sorted.size && sorted[next].range.last <= last) {
                    val symbol = sorted[next++]
                    level += symbol.copy(children = inside(symbol.range.last))
                }
                return level
            }
            return inside(Int.MAX_VALUE)
        }

        private fun FormattingOptions.toLsp() = LspFormattingOptions(tabSize = tabSize, insertSpaces = insertSpaces)
        private const val INSERT_TEXT_FORMAT_SNIPPET = 2

        /** LSP's `CompletionItemKind` numbers. */
        private fun mapCompletionKind(kind: Int?): CompletionKind = when (kind) {
            1 -> CompletionKind.Text
            2 -> CompletionKind.Method
            3 -> CompletionKind.Function
            4 -> CompletionKind.Constructor
            5 -> CompletionKind.Field
            6 -> CompletionKind.Variable
            7 -> CompletionKind.Class
            8 -> CompletionKind.Interface
            9 -> CompletionKind.Module
            10 -> CompletionKind.Property
            11 -> CompletionKind.Unit
            12 -> CompletionKind.Value
            13 -> CompletionKind.Enum
            14 -> CompletionKind.Keyword
            15 -> CompletionKind.Snippet
            16 -> CompletionKind.Color
            17 -> CompletionKind.File
            18 -> CompletionKind.Reference
            19 -> CompletionKind.Folder
            20 -> CompletionKind.EnumMember
            21 -> CompletionKind.Constant
            22 -> CompletionKind.Struct
            23 -> CompletionKind.Event
            24 -> CompletionKind.Operator
            25 -> CompletionKind.TypeParameter
            else -> CompletionKind.Text
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
