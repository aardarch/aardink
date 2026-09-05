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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Callback invoked when the server pushes `textDocument/publishDiagnostics` for [uri].
 *
 * [version] is the document version the diagnostics were computed against, or null when the server
 * did not report one; listeners should drop a notification older than the last version they sent.
 */
typealias DiagnosticsListener = (uri: String, version: Int?, diagnostics: List<LspDiagnostic>) -> Unit

/**
 * Thrown by [LspClient.sendRequest] when the server answers with a JSON-RPC error, or when the
 * connection is closed while a request is still pending.
 */
class LspRequestException(val code: Int, message: String) : RuntimeException(message) {
    companion object {
        /** JSON-RPC: the requested method is not supported by the receiver. */
        const val METHOD_NOT_FOUND: Int = -32601

        /** Implementation-defined: the transport closed before a response arrived. */
        const val CONNECTION_CLOSED: Int = -32001
    }
}

/**
 * Asynchronous Coroutine JSON-RPC 2.0 client managing communication with a Language Server.
 *
 * One client normally serves many documents: register one [DiagnosticsListener] per open
 * document with [addDiagnosticsListener]; every listener receives every `publishDiagnostics`
 * notification and filters by URI itself.
 */
class LspClient(val transport: LspTransport, private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
    private val nextRequestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<LspMessage.Response>>()
    private val diagnosticsListeners = CopyOnWriteArrayList<DiagnosticsListener>()
    private var listeningJob: Job? = null

    /**
     * Set once the connection is gone — the server closed the stream, or [stop] was called. A
     * closed client never starts another receive loop, because nothing could complete the requests
     * it would accept.
     */
    @Volatile
    private var closed = false

    /** Registers [listener]; it stays registered until [removeDiagnosticsListener] is called. */
    fun addDiagnosticsListener(listener: DiagnosticsListener) {
        diagnosticsListeners.addIfAbsent(listener)
    }

    fun removeDiagnosticsListener(listener: DiagnosticsListener) {
        diagnosticsListeners.remove(listener)
    }

    /**
     * Starts the receive loop. Idempotent; also called implicitly by the send functions.
     *
     * Does nothing once the client is closed: without a loop to complete them, any request accepted
     * afterwards would wait forever.
     */
    @Synchronized
    fun start() {
        if (closed || listeningJob != null) return
        listeningJob = scope.launch {
            try {
                while (true) {
                    val payload = transport.receivePayload() ?: break
                    handleIncomingPayload(payload)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // A broken pipe or a killed server process is how a connection ordinarily ends.
                // Letting it out of this root coroutine would reach the scope's uncaught handler
                // and take the host process down over a language server going away.
            } finally {
                onReceiveLoopEnded()
            }
        }
    }

    /**
     * The receive loop is over — because the server closed the stream, or because [stop] cancelled
     * it. Either way the connection is finished: mark the client closed so no later send can
     * register a request nothing will answer, release the transport, and fail what is in flight.
     */
    @Synchronized
    private fun onReceiveLoopEnded() {
        closed = true
        listeningJob = null
        failPendingRequests("Language server connection closed")
        transport.close()
    }

    /**
     * Performs the mandatory LSP handshake: sends `initialize` with [rootUri] and [capabilities],
     * waits for the server's response, then sends the `initialized` notification.
     *
     * Must be the first request/notification exchanged with the server — servers are entitled to
     * reject anything sent beforehand with a `ServerNotInitialized` (-32002) error.
     *
     * @param rootUri Workspace root, or null for a server with no workspace context.
     * @param capabilities Client capabilities object; an empty object is valid, since every LSP
     *   capability is optional for the client to support.
     * @return The server's reported `ServerCapabilities` (the `capabilities` member of the
     *   `InitializeResult`), or null if the response carried none.
     */
    suspend fun initialize(rootUri: String?, capabilities: JsonElement = buildJsonObject { }): JsonElement? {
        val params = buildJsonObject {
            put("processId", JsonNull)
            put("rootUri", rootUri?.let { JsonPrimitive(it) } ?: JsonNull)
            put("capabilities", capabilities)
        }
        val result = sendRequest("initialize", params)
        sendNotification("initialized", buildJsonObject { })
        return (result as? JsonObject)?.get("capabilities")
    }

    /**
     * Sends an LSP request and suspends until the matching response arrives.
     *
     * @return The `result` member of the response, or null when the server returned `null`.
     * @throws LspRequestException with [LspRequestException.CONNECTION_CLOSED] when the connection
     *   is already closed or closes before a response is received, or with the server's code when
     *   it answers with an `error`.
     */
    suspend fun sendRequest(method: String, params: JsonElement? = null): JsonElement? {
        start()
        if (closed) {
            throw LspRequestException(LspRequestException.CONNECTION_CLOSED, "Language server connection is closed")
        }
        val id = nextRequestId.getAndIncrement()
        val deferred = CompletableDeferred<LspMessage.Response>()
        pendingRequests[id] = deferred
        try {
            val request = LspMessage.Request(id = id, method = method, params = params)
            transport.sendPayload(LspJson.encodeToString(LspMessage.Request.serializer(), request))
            val response = deferred.await()
            response.error?.let { throw LspRequestException(it.code, it.message) }
            return response.result?.takeUnless { it is JsonNull }
        } finally {
            pendingRequests.remove(id)
        }
    }

    /**
     * Sends a one-way notification without expecting a response.
     *
     * Notifications are fire-and-forget, so one sent after the connection closed is dropped rather
     * than reported — unlike [sendRequest], there is no caller waiting on an answer.
     */
    suspend fun sendNotification(method: String, params: JsonElement? = null) {
        start()
        if (closed) return
        val notification = LspMessage.Notification(method = method, params = params)
        transport.sendPayload(LspJson.encodeToString(LspMessage.Notification.serializer(), notification))
    }

    /**
     * Stops the receive loop, fails any pending requests and closes the transport. The client
     * cannot be restarted afterwards; construct a new one for a new connection.
     */
    @Synchronized
    fun stop() {
        // Set before cancelling: the loop's own teardown runs later, on the transport's thread, and
        // a send in between must not see an idle-but-open client and relaunch the loop.
        closed = true
        listeningJob?.cancel()
        listeningJob = null
        failPendingRequests("Language server client stopped")
        transport.close()
    }

    private fun failPendingRequests(reason: String) {
        val iterator = pendingRequests.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            entry.value.completeExceptionally(LspRequestException(LspRequestException.CONNECTION_CLOSED, reason))
        }
    }

    private suspend fun handleIncomingPayload(payload: String) {
        val message = parseObjectOrNull(payload) ?: return
        val id = message["id"]?.takeUnless { it is JsonNull }
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        when {
            method != null && id != null -> handleServerRequest(id, method, message["params"])
            method != null -> handleNotification(method, message["params"])
            id != null -> handleResponse(payload)
        }
    }

    private fun handleResponse(payload: String) {
        val response = try {
            LspJson.decodeFromString(LspMessage.Response.serializer(), payload)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        val id = (response.id as? JsonPrimitive)?.longOrNull ?: return
        pendingRequests.remove(id)?.complete(response)
    }

    private fun handleNotification(method: String, params: JsonElement?) {
        if (method != "textDocument/publishDiagnostics" || params == null) return
        val published = try {
            LspJson.decodeFromJsonElement(LspPublishDiagnosticsParams.serializer(), params)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        for (listener in diagnosticsListeners) {
            try {
                listener(published.uri, published.version, published.diagnostics)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A misbehaving listener must not tear down the connection for everyone else.
            }
        }
    }

    /**
     * Answers server→client requests this bridge has a safe generic reply for; anything else gets
     * a `MethodNotFound` error so the server does not block waiting on us.
     *
     * - `client/registerCapability` / `client/unregisterCapability`: acknowledged with a `null`
     *   result. This bridge always sends its full capabilities up front and does not track dynamic
     *   registrations, so there is nothing further to do.
     * - `window/showMessageRequest` / `window/workDoneProgress/create`: acknowledged with a `null`
     *   result (no action selected). Surfacing these to a human is a host UI concern, not this
     *   bridge's.
     * - `workspace/configuration`: answered with one `null` per requested item, per spec, meaning
     *   "no configuration value for this scope".
     */
    private suspend fun handleServerRequest(id: JsonElement, method: String, params: JsonElement?) {
        val result: JsonElement = when (method) {
            "client/registerCapability",
            "client/unregisterCapability",
            "window/showMessageRequest",
            "window/workDoneProgress/create",
            -> JsonNull

            "workspace/configuration" -> workspaceConfigurationResult(params)

            else -> return replyMethodNotFound(id, method)
        }
        transport.sendPayload(LspJson.encodeToString(LspMessage.Response.serializer(), LspMessage.Response(id = id, result = result)))
    }

    private fun workspaceConfigurationResult(params: JsonElement?): JsonElement {
        val itemCount = ((params as? JsonObject)?.get("items") as? JsonArray)?.size ?: 0
        return JsonArray(List(itemCount) { JsonNull })
    }

    private suspend fun replyMethodNotFound(id: JsonElement, method: String) {
        val response = LspMessage.Response(
            id = id,
            error = LspError(LspRequestException.METHOD_NOT_FOUND, "Method not found: $method"),
        )
        transport.sendPayload(LspJson.encodeToString(LspMessage.Response.serializer(), response))
    }

    private fun parseObjectOrNull(payload: String): JsonObject? = try {
        LspJson.parseToJsonElement(payload) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
