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
package com.aardarch.aardink.sample.lsp

import com.aardarch.aardink.languages.lsp.LspClient
import com.aardarch.aardink.languages.lsp.LspCompletionItem
import com.aardarch.aardink.languages.lsp.LspCompletionList
import com.aardarch.aardink.languages.lsp.LspDiagnostic
import com.aardarch.aardink.languages.lsp.LspDidChangeTextDocumentParams
import com.aardarch.aardink.languages.lsp.LspDidOpenTextDocumentParams
import com.aardarch.aardink.languages.lsp.LspHover
import com.aardarch.aardink.languages.lsp.LspMessage
import com.aardarch.aardink.languages.lsp.LspPosition
import com.aardarch.aardink.languages.lsp.LspPublishDiagnosticsParams
import com.aardarch.aardink.languages.lsp.LspRange
import com.aardarch.aardink.languages.lsp.LspTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.putJsonArray

/** The document URI the sample's CSS editor and the demo server agree to talk about. */
const val DEMO_CSS_DOCUMENT_URI: String = "inmemory://demo/style.css"

/**
 * Wires up an in-process demo "language server" for the CSS sample and returns the [LspClient]
 * connected to it.
 *
 * Real `:languages-lsp` consumers connect [LspClient] to an external process or socket; this demo
 * has neither, so it pairs two in-memory channels instead. That is the only thing specific to the
 * sample app — everything past the transport (JSON-RPC framing, request/response matching,
 * `LanguageService` semantics) is the real `:languages-lsp` code the same as it would be talking
 * to any other server.
 */
fun createDemoCssLspConnection(scope: CoroutineScope): LspClient {
    val clientToServer = Channel<String>(Channel.UNLIMITED)
    val serverToClient = Channel<String>(Channel.UNLIMITED)
    val serverTransport = ChannelLspTransport(outgoing = serverToClient, incoming = clientToServer)
    val clientTransport = ChannelLspTransport(outgoing = clientToServer, incoming = serverToClient)
    DemoCssLanguageServer(serverTransport).start(scope)
    return LspClient(clientTransport, scope)
}

/** [LspTransport] backed by a pair of channels rather than a socket or process pipe. */
private class ChannelLspTransport(private val outgoing: Channel<String>, private val incoming: Channel<String>) : LspTransport {
    override suspend fun sendPayload(jsonPayload: String) {
        outgoing.send(jsonPayload)
    }

    override suspend fun receivePayload(): String? = incoming.receiveCatching().getOrNull()

    override fun close() {
        outgoing.close()
    }
}

/**
 * A tiny CSS "language server" that lives entirely in this process.
 *
 * It answers just enough of the protocol to demonstrate [com.aardarch.aardink.languages.lsp.LspLanguageService]
 * end to end: `initialize`, document sync, a couple of static completions, a static hover, and a
 * `!important` lint pushed as `textDocument/publishDiagnostics` whenever the document changes.
 */
private class DemoCssLanguageServer(private val transport: LspTransport) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private var documentText = ""

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                val payload = transport.receivePayload() ?: break
                handlePayload(payload)
            }
        }
    }

    private suspend fun handlePayload(payload: String) {
        val message = parseObjectOrNull(payload) ?: return
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val id = message["id"]
        val params = message["params"]
        when (method) {
            "initialize" -> id?.let { respond(it, initializeResult()) }

            "textDocument/didOpen" -> onDidOpen(params)

            "textDocument/didChange" -> onDidChange(params)

            "textDocument/completion" -> id?.let { respond(it, completionResult()) }

            "textDocument/hover" -> id?.let { respond(it, hoverResult()) }

            // Everything else (didClose, formatting, rename, ...) this demo server doesn't
            // implement; answer requests with null rather than leave the client waiting forever.
            else -> id?.let { respond(it, JsonNull) }
        }
    }

    private suspend fun onDidOpen(params: JsonElement?) {
        val parsed = params?.let { decodeOrNull(LspDidOpenTextDocumentParams.serializer(), it) } ?: return
        documentText = parsed.textDocument.text
        publishDiagnostics(uri = parsed.textDocument.uri, version = parsed.textDocument.version)
    }

    private suspend fun onDidChange(params: JsonElement?) {
        val parsed = params?.let { decodeOrNull(LspDidChangeTextDocumentParams.serializer(), it) } ?: return
        // LspLanguageService.didChange always sends one full-document content change.
        documentText = parsed.contentChanges.lastOrNull()?.text ?: documentText
        publishDiagnostics(uri = parsed.textDocument.uri, version = parsed.textDocument.version)
    }

    private suspend fun publishDiagnostics(uri: String, version: Int) {
        val params = LspPublishDiagnosticsParams(uri = uri, version = version, diagnostics = lintCss(documentText))
        val notification = LspMessage.Notification(
            method = "textDocument/publishDiagnostics",
            params = json.encodeToJsonElement(LspPublishDiagnosticsParams.serializer(), params),
        )
        transport.sendPayload(json.encodeToString(LspMessage.Notification.serializer(), notification))
    }

    /** Flags every `!important` in [text] — a deliberately simple lint that is easy to see work. */
    private fun lintCss(text: String): List<LspDiagnostic> {
        val flagged = "!important"
        val diagnostics = mutableListOf<LspDiagnostic>()
        text.lineSequence().forEachIndexed { lineIndex, line ->
            var fromIndex = 0
            while (true) {
                val at = line.indexOf(flagged, fromIndex)
                if (at < 0) break
                diagnostics += LspDiagnostic(
                    range = LspRange(LspPosition(lineIndex, at), LspPosition(lineIndex, at + flagged.length)),
                    message = "Avoid !important overrides (flagged by the demo language server)",
                    severity = 2,
                    source = "demo-css-lsp",
                )
                fromIndex = at + flagged.length
            }
        }
        return diagnostics
    }

    private fun completionResult(): JsonElement {
        val items = listOf(
            LspCompletionItem(label = "display", detail = "CSS property", insertText = "display: "),
            LspCompletionItem(label = "flex", detail = "display value", insertText = "flex;"),
            LspCompletionItem(label = "color", detail = "CSS property", insertText = "color: "),
            LspCompletionItem(label = "background-color", detail = "CSS property", insertText = "background-color: "),
        )
        return json.encodeToJsonElement(LspCompletionList.serializer(), LspCompletionList(items = items))
    }

    private fun hoverResult(): JsonElement {
        val hover = LspHover(contents = JsonPrimitive("Served by the sample app's in-process demo language server."))
        return json.encodeToJsonElement(LspHover.serializer(), hover)
    }

    private fun initializeResult(): JsonElement = buildJsonObject {
        put(
            "capabilities",
            buildJsonObject {
                put("hoverProvider", JsonPrimitive(true))
                put(
                    "completionProvider",
                    buildJsonObject { putJsonArray("triggerCharacters") { add(JsonPrimitive(":")) } },
                )
            },
        )
    }

    private suspend fun respond(id: JsonElement, result: JsonElement) {
        transport.sendPayload(json.encodeToString(LspMessage.Response.serializer(), LspMessage.Response(id = id, result = result)))
    }

    private fun parseObjectOrNull(payload: String): JsonObject? = try {
        json.parseToJsonElement(payload) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonElement): T? = try {
        json.decodeFromJsonElement(serializer, element)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
