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

import kotlinx.coroutines.channels.Channel

/**
 * Bi-directional transport interface for raw LSP JSON-RPC payloads.
 */
interface LspTransport {

    /**
     * Sends a raw JSON payload framed with HTTP Content-Length headers.
     *
     * A write that can no longer reach the server may fail with any exception — an `IOException`
     * from a stream, a `ClosedSendChannelException` from a channel closed underneath it, or
     * whatever a custom transport throws. [LspClient] treats every non-cancellation failure as the
     * connection ending, so implementations need not normalise their errors.
     */
    suspend fun sendPayload(jsonPayload: String)

    /**
     * Receives the next raw JSON payload unframed from headers.
     * Returns null when the stream/channel is closed.
     */
    suspend fun receivePayload(): String?

    /**
     * Closes the transport. [LspClient] calls this exactly once per connection.
     */
    fun close()
}

/**
 * In-memory channel transport for testing or in-process coroutine servers. Fully portable —
 * unlike [StreamLspTransport] (JVM/Android only, in `jvmAndAndroidMain`), this is also the escape
 * hatch a wasmJs host can build its own transport around (see `WebSocketLspTransport`, added in a
 * later migration step).
 */
class ChannelLspTransport : LspTransport {
    val sendChannel = Channel<String>(Channel.UNLIMITED)
    val receiveChannel = Channel<String>(Channel.UNLIMITED)

    override suspend fun sendPayload(jsonPayload: String) {
        sendChannel.send(jsonPayload)
    }

    override suspend fun receivePayload(): String? = try {
        receiveChannel.receive()
    } catch (_: Exception) {
        null
    }

    override fun close() {
        sendChannel.close()
        receiveChannel.close()
    }
}
