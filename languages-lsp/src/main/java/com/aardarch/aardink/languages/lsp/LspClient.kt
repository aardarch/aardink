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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Callback invoked when the server pushes `textDocument/publishDiagnostics` for [uri]. */
typealias DiagnosticsListener = (uri: String, diagnostics: List<LspDiagnostic>) -> Unit

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

    /** Registers [listener]; it stays registered until [removeDiagnosticsListener] is called. */
    fun addDiagnosticsListener(listener: DiagnosticsListener) {
        diagnosticsListeners.addIfAbsent(listener)
    }

    fun removeDiagnosticsListener(listener: DiagnosticsListener) {
        diagnosticsListeners.remove(listener)
    }

    /** Starts the receive loop. Idempotent; also called implicitly by the send functions. */
    @Synchronized
    fun start() {
        if (listeningJob != null) return
        listeningJob = scope.launch {
            try {
                while (true) {
                    val payload = transport.receivePayload() ?: break
                    handleIncomingPayload(payload)
                }
            } finally {
                failPendingRequests("Language server connection closed")
            }
        }
    }

    /**
     * Sends an LSP request and suspends until the matching response arrives.
     *
     * @return The `result` member of the response, or null when the server returned `null`.
     * @throws LspRequestException when the server answers with an `error`, or the connection
     *   closes before a response is received.
     */
    suspend fun sendRequest(method: String, params: JsonElement? = null): JsonElement? {
        start()
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
     */
    suspend fun sendNotification(method: String, params: JsonElement? = null) {
        start()
        val notification = LspMessage.Notification(method = method, params = params)
        transport.sendPayload(LspJson.encodeToString(LspMessage.Notification.serializer(), notification))
    }

    /** Stops the receive loop, fails any pending requests and closes the transport. */
    @Synchronized
    fun stop() {
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
            method != null && id != null -> replyMethodNotFound(id, method)
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
                listener(published.uri, published.diagnostics)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A misbehaving listener must not tear down the connection for everyone else.
            }
        }
    }

    /**
     * Server→client requests (e.g. `client/registerCapability`, `workspace/configuration`) are not
     * supported yet. Answer with `MethodNotFound` so the server does not block waiting on us.
     */
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
