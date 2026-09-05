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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Asynchronous Coroutine JSON-RPC 2.0 client managing communication with a Language Server.
 */
class LspClient(val transport: LspTransport, private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
    private val nextRequestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private var listeningJob: Job? = null

    /**
     * Listener callback invoked when the server pushes a `textDocument/publishDiagnostics` notification.
     */
    var onDiagnosticsPublished: ((uri: String, diagnostics: List<LspDiagnosticPayload>) -> Unit)? = null

    data class LspDiagnosticPayload(
        val range: LspRange,
        val message: String,
        val severity: Int = 1, // 1 = Error, 2 = Warning, 3 = Info
        val source: String? = null,
    )

    fun start() {
        if (listeningJob != null) return
        listeningJob = scope.launch {
            while (true) {
                val payload = transport.receivePayload() ?: break
                handleIncomingPayload(payload)
            }
        }
    }

    /**
     * Sends an LSP request and waits for the matching JSON response payload.
     */
    suspend fun sendRequest(method: String, paramsJson: String): String {
        start()
        val id = nextRequestId.getAndIncrement()
        val deferred = CompletableDeferred<String>()
        pendingRequests[id] = deferred

        val json = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$paramsJson}"""
        transport.sendPayload(json)

        return deferred.await()
    }

    /**
     * Sends a one-way notification without expecting a response.
     */
    suspend fun sendNotification(method: String, paramsJson: String) {
        start()
        val json = """{"jsonrpc":"2.0","method":"$method","params":$paramsJson}"""
        transport.sendPayload(json)
    }

    fun stop() {
        listeningJob?.cancel()
        listeningJob = null
        transport.close()
    }

    private fun handleIncomingPayload(payload: String) {
        if (payload.contains("\"id\":")) {
            // Response or Request
            val idStr = parseJsonNumberField(payload, "id")
            if (idStr != null) {
                val id = idStr.toLongOrNull()
                if (id != null) {
                    val deferred = pendingRequests.remove(id)
                    deferred?.complete(payload)
                }
            }
        } else if (payload.contains("\"method\":")) {
            // Server Notification
            if (payload.contains("textDocument/publishDiagnostics")) {
                val uri = parseJsonStringField(payload, "uri") ?: ""
                onDiagnosticsPublished?.invoke(uri, emptyList())
            }
        }
    }

    private fun parseJsonNumberField(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun parseJsonStringField(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }
}
