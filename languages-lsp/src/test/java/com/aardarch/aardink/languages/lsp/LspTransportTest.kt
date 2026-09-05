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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * An `OutputStream` that yields the thread inside every write, so any gap between the header
 * write and the body write is very likely to be interleaved by a competing coroutine.
 */
private class InterleavingOutputStream(private val sink: ByteArrayOutputStream) : OutputStream() {
    override fun write(b: Int) {
        Thread.yield()
        synchronized(sink) { sink.write(b) }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        Thread.yield()
        synchronized(sink) { sink.write(b, off, len) }
    }
}

class LspTransportTest {

    @Test
    fun `concurrent sends never interleave frames`() = runBlocking {
        val sink = ByteArrayOutputStream()
        val transport = StreamLspTransport(ByteArrayInputStream(ByteArray(0)), InterleavingOutputStream(sink))
        // Varying lengths: an interleaved frame would be read back with the wrong Content-Length.
        val payloads = (1..64).map { """{"jsonrpc":"2.0","id":$it,"method":"m","params":"${"x".repeat(it * 3)}"}""" }

        payloads.map { payload ->
            async(Dispatchers.Default) { transport.sendPayload(payload) }
        }.awaitAll()

        val received = framesOf(sink.toByteArray())
        assertEquals(payloads.size, received.size, "every frame must be readable back")
        assertEquals(payloads.toSet(), received.toSet())
    }

    @Test
    fun `a header block without a usable Content-Length is skipped, not read as end of stream`() = runBlocking {
        // Null means "the server is gone" and shuts the client down for good, so only a real EOF
        // may produce it.
        val stream = "X-Note: hello\r\n\r\nContent-Length: 2\r\n\r\n{}"
        assertEquals(listOf("{}"), framesOf(stream.toByteArray()))
    }

    @Test
    fun `a zero-length frame is an empty payload, not end of stream`() = runBlocking {
        val stream = "Content-Length: 0\r\n\r\nContent-Length: 2\r\n\r\n{}"
        assertEquals(listOf("", "{}"), framesOf(stream.toByteArray()))
    }

    @Test
    fun `a truncated body ends the stream`() = runBlocking {
        assertEquals(emptyList<String>(), framesOf("Content-Length: 99\r\n\r\n{}".toByteArray()))
    }

    @Test
    fun `an absurd Content-Length is refused rather than allocated`() = runBlocking {
        // The header is believed before a byte of body is read, so obeying this one would try for
        // ~2 GiB on an Android heap. The read fails instead, and LspClient treats that as the
        // connection ending.
        val stream = "Content-Length: 2147483647\r\n\r\n"
        val failure = assertThrows(IOException::class.java) {
            runBlocking { framesOf(stream.toByteArray()) }
        }
        assertTrue(failure.message!!.contains("2147483647"), failure.message)
    }

    @Test
    fun `an endless header line is refused before it exhausts memory`() = runBlocking {
        // The body limit is only consulted once a header block has been read, so a peer that never
        // sends a newline declares nothing and would grow the header buffer without bound.
        val stream = "Content-Length: 2".toByteArray() + ByteArray(64 * 1024) { 'x'.code.toByte() }
        val failure = assertThrows(IOException::class.java) { runBlocking { framesOf(stream) } }
        assertTrue(failure.message!!.contains("header"), failure.message)
    }

    @Test
    fun `an ordinary header block is well within the limit`() = runBlocking {
        val stream = "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\nContent-Length: 2\r\n\r\n{}"
        assertEquals(listOf("{}"), framesOf(stream.toByteArray()))
    }

    @Test
    fun `a frame at the size limit is still read`() = runBlocking {
        // The bound rejects only what is over it; ordinary large payloads must still arrive.
        val payload = "{\"x\":\"${"y".repeat(200_000)}\"}"
        val framed = "Content-Length: ${payload.toByteArray().size}\r\n\r\n$payload"
        assertEquals(listOf(payload), framesOf(framed.toByteArray()))
    }

    /** Reads [bytes] back as a list of `Content-Length`-framed payloads. */
    private suspend fun framesOf(bytes: ByteArray): List<String> {
        val reader = StreamLspTransport(ByteArrayInputStream(bytes), ByteArrayOutputStream())
        val frames = mutableListOf<String>()
        while (true) {
            frames += reader.receivePayload() ?: return frames
        }
    }
}
