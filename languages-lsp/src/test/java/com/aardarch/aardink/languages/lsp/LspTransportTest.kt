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
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    /** Reads [bytes] back as a list of `Content-Length`-framed payloads. */
    private suspend fun framesOf(bytes: ByteArray): List<String> {
        val reader = StreamLspTransport(ByteArrayInputStream(bytes), ByteArrayOutputStream())
        val frames = mutableListOf<String>()
        while (true) {
            frames += reader.receivePayload() ?: return frames
        }
    }
}
