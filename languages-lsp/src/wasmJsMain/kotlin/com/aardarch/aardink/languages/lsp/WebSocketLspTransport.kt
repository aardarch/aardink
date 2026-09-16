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
import kotlinx.coroutines.channels.Channel
import org.w3c.dom.WebSocket

/**
 * Browser [LspTransport] over a WebSocket — the wasmJs counterpart to `StreamLspTransport`.
 *
 * One JSON-RPC payload per WebSocket frame: the framing `Content-Length` headers a stream
 * transport needs exist to find message boundaries in a byte stream, and a WebSocket already
 * has them. Nothing here parses or emits headers.
 *
 * Uses `org.w3c.dom.WebSocket` directly rather than pulling in Ktor, so `:languages-lsp`'s
 * dependency graph is unchanged apart from this wasmJs-only file.
 *
 * Obtain one with [connect]; the constructor is private because a socket that is not open yet
 * cannot send.
 */
class WebSocketLspTransport private constructor(private val socket: WebSocket) : LspTransport {

    private val incoming = Channel<String>(Channel.UNLIMITED)

    /** Completes when the socket opens, or fails if it errors before opening. */
    private val opened = CompletableDeferred<Unit>()

    init {
        // Installed once, in the constructor, and never replaced. The obvious alternative --
        // having connect() install temporary handlers and the instance swap in its own
        // afterwards -- drops any frame that arrives between the open event and the swap.
        socket.onopen = {
            opened.complete(Unit)
        }
        socket.onmessage = { event ->
            // Text frames only; a server sending binary is a protocol error we surface as a
            // closed channel rather than by guessing at a decoding.
            val data = event.data
            if (data == null) {
                incoming.close(LspTransportException("WebSocket delivered a frame with no data"))
            } else {
                incoming.trySend(data.toString())
            }
        }
        socket.onclose = {
            // A close after open is an ordinary end of stream: receivePayload returns null and
            // LspClient tears the connection down. A close *before* open means the connection
            // was refused, and connect() must fail rather than hang.
            opened.completeExceptionally(LspTransportException("WebSocket closed before it opened"))
            incoming.close()
        }
        socket.onerror = {
            val failure = LspTransportException("WebSocket error")
            // Idempotent: if the socket already opened, this is a no-op and the error only
            // reaches the receive side. This is what keeps a socket that opens and then fails
            // from resuming connect()'s continuation twice.
            opened.completeExceptionally(failure)
            incoming.close(failure)
        }
    }

    override suspend fun sendPayload(jsonPayload: String) {
        if (socket.readyState != OPEN) {
            throw LspTransportException("WebSocket is not open (readyState=${socket.readyState})")
        }
        socket.send(jsonPayload)
    }

    override suspend fun receivePayload(): String? = incoming.receiveCatching().getOrNull()

    override fun close() {
        socket.close()
        incoming.close()
    }

    companion object {
        /** `WebSocket.OPEN`, the only readyState in which a frame can be sent. */
        private const val OPEN: Short = 1

        /**
         * Opens a WebSocket to [url] and suspends until it is ready to carry traffic.
         *
         * Cancelling the caller closes the socket rather than leaking it.
         *
         * @throws LspTransportException if the socket errors or closes before opening.
         */
        suspend fun connect(url: String): WebSocketLspTransport {
            val transport = WebSocketLspTransport(WebSocket(url))
            try {
                transport.opened.await()
            } catch (e: Throwable) {
                // Covers ordinary failure and cancellation alike: either way the half-open
                // socket must not outlive this call.
                transport.close()
                throw e
            }
            return transport
        }
    }
}

/** Raised by [WebSocketLspTransport] for any transport-level failure. */
class LspTransportException(message: String) : RuntimeException(message)
