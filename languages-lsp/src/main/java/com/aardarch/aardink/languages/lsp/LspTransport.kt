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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Bi-directional transport interface for raw LSP JSON-RPC payloads.
 */
interface LspTransport {

    /**
     * Sends a raw JSON payload framed with HTTP Content-Length headers.
     */
    suspend fun sendPayload(jsonPayload: String)

    /**
     * Receives the next raw JSON payload unframed from headers.
     * Returns null when the stream/channel is closed.
     */
    suspend fun receivePayload(): String?

    /**
     * Closes the transport.
     */
    fun close()
}

/**
 * Standard stream-based transport for `InputStream` / `OutputStream` (e.g. process Stdio or Socket streams).
 */
class StreamLspTransport(private val inputStream: InputStream, private val outputStream: OutputStream) : LspTransport {

    override suspend fun sendPayload(jsonPayload: String): Unit = withContext(Dispatchers.IO) {
        val bytes = jsonPayload.toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        outputStream.write(header.toByteArray(StandardCharsets.US_ASCII))
        outputStream.write(bytes)
        outputStream.flush()
    }

    override suspend fun receivePayload(): String? = withContext(Dispatchers.IO) {
        var contentLength = -1
        val headerLine = StringBuilder()

        while (true) {
            val c = inputStream.read()
            if (c == -1) return@withContext null
            val ch = c.toChar()
            if (ch == '\n') {
                val line = headerLine.toString().trim()
                if (line.isEmpty()) {
                    // Blank line signifies end of headers
                    break
                }
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substring(15).trim().toIntOrNull() ?: -1
                }
                headerLine.clear()
            } else if (ch != '\r') {
                headerLine.append(ch)
            }
        }

        if (contentLength <= 0) return@withContext null

        val buffer = ByteArray(contentLength)
        var totalRead = 0
        while (totalRead < contentLength) {
            val read = inputStream.read(buffer, totalRead, contentLength - totalRead)
            if (read == -1) break
            totalRead += read
        }

        String(buffer, 0, totalRead, StandardCharsets.UTF_8)
    }

    override fun close() {
        try {
            inputStream.close()
            outputStream.close()
        } catch (_: Exception) {
            // Ignore close failures
        }
    }
}

/**
 * In-memory channel transport for testing or in-process coroutine servers.
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
