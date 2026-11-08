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

import com.aardarch.aardink.platform.EditorDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

/**
 * The two ends of a process pipe to a server that has hung, as they behave on Windows: a read waits
 * for the server, closing the input waits for that read, and a write waits for the server to drain
 * it, with `BufferedOutputStream` holding its lock throughout. Nothing frees them but [serverExits].
 */
private class HungPipe {
    private val serverGone = CountDownLatch(1)
    private val reading = CountDownLatch(1)
    private val writing = CountDownLatch(1)

    @Volatile
    var outputClosed = false
        private set

    val input = object : InputStream() {
        override fun read(): Int {
            reading.countDown()
            serverGone.await()
            return -1
        }

        override fun close() {
            if (reading.count == 0L) serverGone.await()
        }
    }

    val output = BufferedOutputStream(
        object : OutputStream() {
            override fun write(b: Int) = hold()

            override fun write(b: ByteArray, off: Int, len: Int) = hold()

            private fun hold() {
                writing.countDown()
                serverGone.await()
                throw IOException("The pipe has been ended")
            }

            override fun close() {
                outputClosed = true
            }
        },
    )

    fun serverExits() = serverGone.countDown()

    /** Waits, off the test's thread so the client's coroutines can run, for a read to be in progress. */
    suspend fun awaitRead() = withContext(EditorDispatchers.io) { assertTrue(reading.await(10, TimeUnit.SECONDS)) }

    /** The same for a write. */
    suspend fun awaitWrite() = withContext(EditorDispatchers.io) { assertTrue(writing.await(10, TimeUnit.SECONDS)) }
}

/** Runs [free] if the test is still going in ten seconds, so a regression fails instead of hanging the build. */
private class Rescue(free: () -> Unit) {
    @Volatile
    var needed = false
        private set

    init {
        thread(isDaemon = true) {
            Thread.sleep(10_000)
            needed = true
            free()
        }
    }
}

/** A transport whose close blocks until [free]; it answers `shutdown` and nothing else. */
private class BlockingCloseTransport : LspTransport {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val released = CountDownLatch(1)
    val closing = CountDownLatch(1)

    override suspend fun sendPayload(jsonPayload: String) {
        val message = LspJson.parseToJsonElement(jsonPayload).jsonObject
        if (message["method"]?.jsonPrimitive?.content == "shutdown") {
            incoming.send("""{"jsonrpc":"2.0","id":${message["id"]},"result":null}""")
        }
    }

    override suspend fun receivePayload(): String? = incoming.receiveCatching().getOrNull()

    override fun close() {
        closing.countDown()
        released.await()
        incoming.close()
    }

    fun free() = released.countDown()
}

@OptIn(ExperimentalCoroutinesApi::class)
class LspTransportTest {

    @Test
    fun `close does not wait for a read and a write stuck on a process pipe`() = runTest {
        val pipe = HungPipe()
        val transport = StreamLspTransport(pipe.input, pipe.output)
        backgroundScope.launch { transport.receivePayload() }
        backgroundScope.launch { runCatching { transport.sendPayload("{}") } }
        pipe.awaitRead()
        pipe.awaitWrite()
        val rescue = Rescue(pipe::serverExits)

        try {
            transport.close()

            assertFalse(rescue.needed, "close waited for the server to go away")
        } finally {
            pipe.serverExits()
        }
    }

    @Test
    fun `close reaches the output while a read is in progress, so the server sees its stdin end`() = runTest {
        // A read is what the receive loop is nearly always doing. A healthy server exits when its
        // stdin ends, and that is what frees the read, so the output must not queue behind it.
        val pipe = HungPipe()
        val transport = StreamLspTransport(pipe.input, pipe.output)
        backgroundScope.launch { transport.receivePayload() }
        pipe.awaitRead()
        val rescue = Rescue(pipe::serverExits)

        try {
            transport.close()

            assertFalse(rescue.needed, "close waited for the server to go away")
            assertTrue(pipe.outputClosed)
        } finally {
            pipe.serverExits()
        }
    }

    @Test
    fun `shutdown returns while a read and a write are stuck on a process pipe`() = runTest {
        val pipe = HungPipe()
        val client = LspClient(StreamLspTransport(pipe.input, pipe.output), backgroundScope)
        backgroundScope.launch { client.sendNotification("textDocument/didChange") }
        pipe.awaitRead()
        pipe.awaitWrite()
        val rescue = Rescue(pipe::serverExits)

        try {
            client.shutdown()

            assertFalse(rescue.needed, "shutdown waited for the server to go away")
            val failure = assertFailsWith<LspRequestException> { client.sendRequest("textDocument/hover") }
            assertEquals(LspRequestException.CONNECTION_CLOSED, failure.code)
        } finally {
            pipe.serverExits()
        }
    }

    @Test
    fun `a transport whose close blocks holds up neither shutdown nor the client`() = runTest {
        val transport = BlockingCloseTransport()
        val scope = CoroutineScope(EditorDispatchers.io + SupervisorJob())
        val client = LspClient(transport, scope)
        val pending = async { runCatching { client.sendRequest("textDocument/hover") } }
        runCurrent()
        val rescue = Rescue(transport::free)

        try {
            client.shutdown()
            withContext(EditorDispatchers.io) { assertTrue(transport.closing.await(10, TimeUnit.SECONDS)) }

            // Refused rather than stalled: nothing may need the client's lock behind that close.
            val refused = assertFailsWith<LspRequestException> { client.sendRequest("textDocument/hover") }
            assertEquals(LspRequestException.CONNECTION_CLOSED, refused.code)
            val failure = pending.await().exceptionOrNull()
            assertTrue(failure is LspRequestException, "expected LspRequestException, got $failure")
            assertEquals(LspRequestException.CONNECTION_CLOSED, failure.code)
            assertFalse(rescue.needed, "something waited for the close")
        } finally {
            transport.free()
            scope.cancel()
        }
    }

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
        val failure = assertFailsWith<IOException> {
            runBlocking { framesOf(stream.toByteArray()) }
        }
        assertTrue(failure.message!!.contains("2147483647"), failure.message)
    }

    @Test
    fun `an endless header line is refused before it exhausts memory`() = runBlocking {
        // The body limit is only consulted once a header block has been read, so a peer that never
        // sends a newline declares nothing and would grow the header buffer without bound.
        val stream = "Content-Length: 2".toByteArray() + ByteArray(64 * 1024) { 'x'.code.toByte() }
        val failure = assertFailsWith<IOException> { runBlocking { framesOf(stream) } }
        assertTrue(failure.message!!.contains("header"), failure.message)
    }

    @Test
    fun `a throwing input close still closes the output`() {
        var outputClosed = false
        val badInput = object : ByteArrayInputStream(ByteArray(0)) {
            override fun close() = throw IOException("cannot close")
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                outputClosed = true
            }
        }

        StreamLspTransport(badInput, output).close()

        assertTrue(outputClosed, "a failing read-side close must not leak the write side")
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
