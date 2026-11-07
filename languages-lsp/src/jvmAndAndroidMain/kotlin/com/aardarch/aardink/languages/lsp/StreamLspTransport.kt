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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Standard stream-based transport for `InputStream` / `OutputStream` (e.g. process Stdio or Socket streams).
 *
 * JVM/Android only — `java.io` streams have no common-Kotlin equivalent, so this class lives in
 * `jvmAndAndroidMain` rather than `commonMain` alongside [LspTransport] and [ChannelLspTransport].
 * A wasmJs host uses `WebSocketLspTransport` instead.
 *
 * A read or write on a process pipe cannot be interrupted, and on a server that has hung, only that
 * process ending frees it. [close] does not wait for that, so a host that started a process should
 * destroy it after stopping the client, or those threads stay blocked until the process exits.
 */
class StreamLspTransport(private val inputStream: InputStream, private val outputStream: OutputStream) : LspTransport {

    // One client serves many coroutines (requests, notifications and replies from the receive loop).
    // Frames must reach the server whole: a half-written header interleaved with another payload
    // desynchronises JSON-RPC framing for the rest of the connection.
    private val writeMutex = Mutex()

    override suspend fun sendPayload(jsonPayload: String): Unit = withContext(Dispatchers.IO) {
        val bytes = jsonPayload.toByteArray(StandardCharsets.UTF_8)
        val header = "$CONTENT_LENGTH_HEADER ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val frame = ByteArrayOutputStream(header.size + bytes.size).apply {
            write(header)
            write(bytes)
        }.toByteArray()
        writeMutex.withLock {
            outputStream.write(frame)
            outputStream.flush()
        }
    }

    override suspend fun receivePayload(): String? = withContext(Dispatchers.IO) { readFrame() }

    /**
     * Reads the next complete frame, or null at end of stream.
     *
     * Only a real end-of-stream returns null: the client reads null as "the server is gone" and
     * shuts down for good, so a header block we could not make sense of must not look like one —
     * it skips ahead to the next frame instead.
     */
    private fun readFrame(): String? {
        while (true) {
            val contentLength = readContentLength() ?: return null
            if (contentLength < 0) continue
            if (contentLength > MAX_FRAME_BYTES) {
                // Allocating what this header asks for would take the host down before a single
                // byte of body is read, so treat it as a broken connection rather than obeying it.
                throw IOException("Language server declared a $contentLength byte frame, over the $MAX_FRAME_BYTES limit")
            }
            if (contentLength == 0) return ""

            val buffer = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val read = inputStream.read(buffer, totalRead, contentLength - totalRead)
                if (read == -1) return null
                totalRead += read
            }
            return String(buffer, 0, totalRead, StandardCharsets.UTF_8)
        }
    }

    /**
     * Reads one header block. Returns the payload length, -1 when the block carried no usable
     * `Content-Length`, or null at end of stream.
     */
    private fun readContentLength(): Int? {
        var contentLength = -1
        var headerBlockBytes = 0
        val headerLine = StringBuilder()
        while (true) {
            val c = inputStream.read()
            if (c == -1) return null
            // The body limit is checked only once a header block has been read, so the block needs
            // a bound of its own: a peer that never sends a newline would otherwise grow this
            // builder until the process runs out of memory, having declared nothing at all.
            if (++headerBlockBytes > MAX_HEADER_BYTES) {
                throw IOException("Language server sent over $MAX_HEADER_BYTES bytes of header without ending the block")
            }
            val ch = c.toChar()
            if (ch == '\n') {
                val line = headerLine.toString().trim()
                if (line.isEmpty()) return contentLength // Blank line signifies end of headers
                if (line.startsWith(CONTENT_LENGTH_HEADER, ignoreCase = true)) {
                    contentLength = line.substring(CONTENT_LENGTH_HEADER.length).trim().toIntOrNull() ?: -1
                }
                headerLine.clear()
            } else if (ch != '\r') {
                headerLine.append(ch)
            }
        }
    }

    /**
     * Closes both streams, waiting [CLOSE_WAIT_MILLIS] at most.
     *
     * Each half gets its own thread, because either can block. On a process pipe, closing the input
     * waits for a read in progress, and the receive loop is nearly always in one. Closing the output
     * waits for a write the server has stopped draining, through the lock `BufferedOutputStream`
     * holds around it. Done in turn, the first would also keep the second from running: the server
     * would never see its stdin end, which is what lets a healthy one exit and free that read. A
     * throwing close must not skip the other half either.
     */
    override fun close() {
        val closed = CountDownLatch(2)
        for (stream in listOf(inputStream, outputStream)) {
            thread(isDaemon = true, name = "StreamLspTransport close") {
                try {
                    stream.close()
                } catch (_: Exception) {
                    // Ignore close failures
                } finally {
                    closed.countDown()
                }
            }
        }
        try {
            closed.await(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val CONTENT_LENGTH_HEADER = "Content-Length:"

        /** Far longer than a close that is not stuck takes, and short enough not to freeze a UI thread. */
        const val CLOSE_WAIT_MILLIS = 500L

        /**
         * Largest payload this transport will allocate for, in bytes.
         *
         * `Content-Length` arrives from the far end and is believed before any body is read, so an
         * unbounded one is an allocation a malformed or hostile server chooses for us. 32 MiB is
         * far above any real LSP message — the largest are whole-document syncs and completion
         * lists — and far below what would trouble an Android heap.
         */
        const val MAX_FRAME_BYTES = 32 * 1024 * 1024

        /**
         * Largest header block, in bytes, before the blank line that ends it.
         *
         * LSP defines two headers and real ones run to tens of bytes; 8 KiB leaves room for
         * anything reasonable while keeping a peer that never sends a newline from growing the
         * buffer without limit.
         */
        const val MAX_HEADER_BYTES = 8 * 1024
    }
}
