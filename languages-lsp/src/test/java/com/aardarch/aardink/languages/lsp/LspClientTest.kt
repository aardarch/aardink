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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class LspClientTest {

    private val transport = ChannelLspTransport()
    private val client = LspClient(transport, CoroutineScope(Dispatchers.Default))

    private suspend fun nextSent(): JsonObject = LspJson.parseToJsonElement(transport.sendChannel.receive()).jsonObject

    private suspend fun serverSends(json: String) = transport.receiveChannel.send(json)

    private suspend fun <T> awaitSoon(deferred: CompletableDeferred<T>): T = withTimeout(5_000) { deferred.await() }

    @Test
    fun `sendRequest frames a JSON-RPC request and returns the result element`() = runBlocking {
        val server = async {
            val sent = nextSent()
            assertEquals("2.0", sent["jsonrpc"]!!.jsonPrimitive.content)
            assertEquals("textDocument/completion", sent["method"]!!.jsonPrimitive.content)
            assertEquals(1L, sent["id"]!!.jsonPrimitive.long)
            assertEquals(7, sent["params"]!!.jsonObject["x"]!!.jsonPrimitive.int)
            serverSends("""{"jsonrpc":"2.0","id":1,"result":[1,2]}""")
        }

        val result = client.sendRequest("textDocument/completion", buildJsonObject { put("x", 7) })
        server.await()

        assertEquals(2, result!!.jsonArray.size)
        client.stop()
    }

    @Test
    fun `sendRequest returns null for a null result`() = runBlocking {
        val server = async {
            nextSent()
            serverSends("""{"jsonrpc":"2.0","id":1,"result":null}""")
        }

        val result = client.sendRequest("textDocument/hover")
        server.await()

        assertNull(result)
        client.stop()
    }

    @Test
    fun `sendRequest throws LspRequestException on an error response`() = runBlocking {
        val server = async {
            nextSent()
            serverSends("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"not supported"}}""")
        }

        val failure = runCatching { client.sendRequest("textDocument/definition") }.exceptionOrNull()
        server.await()

        assertTrue(failure is LspRequestException, "expected LspRequestException, got $failure")
        assertEquals(-32601, (failure as LspRequestException).code)
        assertEquals("not supported", failure.message)
        client.stop()
    }

    @Test
    fun `sendNotification omits id and null params`() = runBlocking {
        client.sendNotification("initialized")

        val sent = nextSent()
        assertEquals("initialized", sent["method"]!!.jsonPrimitive.content)
        assertFalse("id" in sent)
        assertFalse("params" in sent)
        client.stop()
    }

    @Test
    fun `publishDiagnostics decodes diagnostics and reaches every listener`() = runBlocking {
        val first = CompletableDeferred<Triple<String, Int?, List<LspDiagnostic>>>()
        val second = CompletableDeferred<Triple<String, Int?, List<LspDiagnostic>>>()
        client.addDiagnosticsListener { uri, version, diagnostics -> first.complete(Triple(uri, version, diagnostics)) }
        client.addDiagnosticsListener { uri, version, diagnostics -> second.complete(Triple(uri, version, diagnostics)) }
        client.start()

        // "data":{"id":42} guards against treating any payload containing "id" as a response.
        serverSends(
            """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///Main.kt","version":3,
               |"diagnostics":[{"range":{"start":{"line":2,"character":4},"end":{"line":2,"character":9}},
               |"message":"Unresolved reference","severity":1,"source":"kotlin","data":{"id":42}}]}}
            """.trimMargin().replace("\n", ""),
        )

        val (uri, version, diagnostics) = awaitSoon(first)
        assertEquals("file:///Main.kt", uri)
        assertEquals(3, version)
        assertEquals(1, diagnostics.size)
        assertEquals(LspRange(LspPosition(2, 4), LspPosition(2, 9)), diagnostics[0].range)
        assertEquals("Unresolved reference", diagnostics[0].message)
        assertEquals(1, diagnostics[0].severity)
        assertEquals("kotlin", diagnostics[0].source)
        assertEquals(Triple(uri, version, diagnostics), awaitSoon(second))
        client.stop()
    }

    @Test
    fun `removed listener no longer receives diagnostics`() = runBlocking {
        val kept = CompletableDeferred<String>()
        var removedWasCalled = false
        val removed: DiagnosticsListener = { _, _, _ -> removedWasCalled = true }
        client.addDiagnosticsListener(removed)
        client.addDiagnosticsListener { uri, _, _ -> kept.complete(uri) }
        client.removeDiagnosticsListener(removed)
        client.start()

        serverSends("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///A.kt","diagnostics":[]}}""")

        assertEquals("file:///A.kt", awaitSoon(kept))
        assertFalse(removedWasCalled)
        client.stop()
    }

    @Test
    fun `malformed payloads and unknown response ids are ignored`() = runBlocking {
        client.start()
        serverSends("this is not json")
        serverSends("""{"jsonrpc":"2.0","id":999,"result":"stale"}""")
        serverSends("""{"jsonrpc":"2.0","method":"window/logMessage","params":{"type":3,"message":"hi"}}""")

        val server = async {
            nextSent()
            serverSends("""{"jsonrpc":"2.0","id":1,"result":"fresh"}""")
        }
        val result = client.sendRequest("shutdown")
        server.await()

        assertEquals("fresh", result!!.jsonPrimitive.content)
        client.stop()
    }

    @Test
    fun `unsupported server-to-client request is answered with MethodNotFound`() = runBlocking {
        client.start()
        serverSends("""{"jsonrpc":"2.0","id":"abc","method":"workspace/applyEdit","params":{"edit":{}}}""")

        val reply = nextSent()
        assertEquals("abc", reply["id"]!!.jsonPrimitive.content)
        assertTrue(reply["id"]!!.jsonPrimitive.isString)
        assertEquals(LspRequestException.METHOD_NOT_FOUND, reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertFalse("result" in reply)
        client.stop()
    }

    @Test
    fun `registerCapability and showMessageRequest are acknowledged with a null result`() = runBlocking {
        client.start()
        serverSends("""{"jsonrpc":"2.0","id":1,"method":"client/registerCapability","params":{"registrations":[]}}""")

        val registerReply = nextSent()
        assertEquals(1L, registerReply["id"]!!.jsonPrimitive.long)
        assertTrue(registerReply["result"]!!.jsonNull === JsonNull)
        assertFalse("error" in registerReply)

        serverSends("""{"jsonrpc":"2.0","id":2,"method":"window/showMessageRequest","params":{"type":1,"message":"hi","actions":[]}}""")
        val messageReply = nextSent()
        assertEquals(2L, messageReply["id"]!!.jsonPrimitive.long)
        assertFalse("error" in messageReply)

        client.stop()
    }

    @Test
    fun `workspace configuration is answered with one null per requested item`() = runBlocking {
        client.start()
        serverSends(
            """{"jsonrpc":"2.0","id":3,"method":"workspace/configuration",
               |"params":{"items":[{"section":"aardink.lsp"},{"section":"aardink.format"}]}}
            """.trimMargin().replace("\n", ""),
        )

        val reply = nextSent()
        val result = reply["result"]!!.jsonArray
        assertEquals(2, result.size)
        assertTrue(result.all { it == JsonNull })

        client.stop()
    }

    @Test
    fun `initialize sends processId, rootUri and capabilities then initialized`() = runBlocking {
        val server = async {
            val init = nextSent()
            assertEquals("initialize", init["method"]!!.jsonPrimitive.content)
            val params = init["params"]!!.jsonObject
            assertTrue(params["processId"]!!.jsonNull === JsonNull)
            assertEquals("file:///workspace", params["rootUri"]!!.jsonPrimitive.content)
            assertEquals(emptyMap<String, Nothing>(), params["capabilities"]!!.jsonObject.toMap())
            val id = init["id"]!!.jsonPrimitive.long
            serverSends("""{"jsonrpc":"2.0","id":$id,"result":{"capabilities":{"hoverProvider":true}}}""")

            val initialized = nextSent()
            assertEquals("initialized", initialized["method"]!!.jsonPrimitive.content)
            assertFalse("id" in initialized)
        }

        val capabilities = client.initialize("file:///workspace")
        server.await()

        assertTrue(capabilities!!.jsonObject["hoverProvider"]!!.jsonPrimitive.content == "true")
        client.stop()
    }

    @Test
    fun `stop fails pending requests instead of hanging them`() = runBlocking {
        val outcome = CompletableDeferred<Result<JsonElement?>>()
        launch { outcome.complete(runCatching { client.sendRequest("textDocument/hover") }) }
        nextSent() // the request is on the wire, nobody will answer it

        client.stop()

        val failure = awaitSoon(outcome).exceptionOrNull()
        assertTrue(failure is LspRequestException, "expected LspRequestException, got $failure")
        assertEquals(LspRequestException.CONNECTION_CLOSED, (failure as LspRequestException).code)
    }

    @Test
    fun `a request after the server closed the stream fails instead of hanging`() = runBlocking {
        client.start()
        // The server exits: the receive loop ends on its own, without anyone calling stop().
        transport.receiveChannel.close()

        val outcome = CompletableDeferred<Result<JsonElement?>>()
        launch { outcome.complete(runCatching { client.sendRequest("textDocument/hover") }) }

        val failure = awaitSoon(outcome).exceptionOrNull()
        assertTrue(failure is LspRequestException, "expected LspRequestException, got $failure")
        assertEquals(LspRequestException.CONNECTION_CLOSED, (failure as LspRequestException).code)
    }

    @Test
    fun `a transport failure closes the client instead of escaping the coroutine`() = runBlocking {
        // A broken pipe is how a language server ordinarily goes away. Letting the IOException out
        // of the receive loop's root coroutine reaches the scope's uncaught handler, which on
        // Android takes the host process down.
        val uncaught = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(
            Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.complete(e) },
        )
        val failing = object : LspTransport {
            override suspend fun sendPayload(jsonPayload: String) = Unit
            override suspend fun receivePayload(): String = throw IOException("broken pipe")
            override fun close() = Unit
        }
        val failingClient = LspClient(failing, scope)

        val outcome = CompletableDeferred<Result<JsonElement?>>()
        launch { outcome.complete(runCatching { failingClient.sendRequest("textDocument/hover") }) }

        val failure = awaitSoon(outcome).exceptionOrNull()
        assertTrue(failure is LspRequestException, "expected LspRequestException, got $failure")
        assertEquals(LspRequestException.CONNECTION_CLOSED, (failure as LspRequestException).code)
        assertFalse(uncaught.isCompleted, "the IOException must not reach the scope's handler")
    }

    @Test
    fun `a receive failure that is not an IOException still closes the client`() = runBlocking {
        // The transport contract promises no failure type on the read side either.
        val uncaught = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(
            Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.complete(e) },
        )
        val failing = object : LspTransport {
            override suspend fun sendPayload(jsonPayload: String) = Unit
            override suspend fun receivePayload(): String = throw IllegalStateException("closed underneath")
            override fun close() = Unit
        }
        val failingClient = LspClient(failing, scope)

        val outcome = CompletableDeferred<Result<JsonElement?>>()
        launch { outcome.complete(runCatching { failingClient.sendRequest("textDocument/hover") }) }

        val failure = awaitSoon(outcome).exceptionOrNull()
        assertTrue(failure is LspRequestException, "expected LspRequestException, got $failure")
        assertEquals(LspRequestException.CONNECTION_CLOSED, (failure as LspRequestException).code)
        assertFalse(uncaught.isCompleted, "the failure must not reach the scope's handler")
    }

    @Test
    fun `a client whose scope is cancelled fails requests instead of hanging`() = runBlocking {
        // A host that ties the client to a ViewModel scope and outlives it: launching the receive
        // loop into the cancelled scope does nothing, so without this a request would wait forever.
        val scope = CoroutineScope(Dispatchers.Default)
        scope.cancel()
        val orphan = LspClient(transport, scope)

        val failure = runCatching { orphan.sendRequest("textDocument/hover") }.exceptionOrNull()
        assertTrue(failure is LspRequestException, "expected LspRequestException, got $failure")
        assertEquals(LspRequestException.CONNECTION_CLOSED, (failure as LspRequestException).code)
        assertTrue(transport.sendChannel.isClosedForSend, "the transport is released")
    }

    @Test
    fun `the transport is closed once however many teardown paths run`() = runBlocking {
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val counting = object : LspTransport {
            override suspend fun sendPayload(jsonPayload: String) = Unit
            override suspend fun receivePayload(): String? = CompletableDeferred<String>().await()
            override fun close() {
                closes.incrementAndGet()
            }
        }
        val client = LspClient(counting, CoroutineScope(Dispatchers.Default))
        client.start()

        // stop() closes, then the cancelled receive loop's own teardown runs.
        client.stop()
        withTimeout(5_000) { while (closes.get() == 0) kotlinx.coroutines.yield() }
        kotlinx.coroutines.delay(50)

        assertEquals(1, closes.get())
    }

    @Test
    fun `a failed send closes the connection and frees other pending requests`() = runBlocking {
        // The write half breaks while the read half is still blocked on a server that will never
        // answer. Reporting a raw IOException to this caller and leaving the rest waiting on a
        // dead connection is the failure mode; every pending request must be released.
        val writesFail = object : LspTransport {
            /** Completes once the first request is on the wire; every later write fails. */
            val firstSent = CompletableDeferred<Unit>()

            override suspend fun sendPayload(jsonPayload: String) {
                if (firstSent.complete(Unit)) return
                throw IOException("broken pipe")
            }

            // Never returns: the receive loop is stuck, as it would be on a hung server.
            override suspend fun receivePayload(): String? = CompletableDeferred<String>().await()
            override fun close() = Unit
        }
        val client = LspClient(writesFail, CoroutineScope(Dispatchers.Default))

        val stranded = CompletableDeferred<Result<JsonElement?>>()
        launch { stranded.complete(runCatching { client.sendRequest("textDocument/hover") }) }
        awaitSoon(writesFail.firstSent)

        val failed = runCatching { client.sendRequest("textDocument/definition") }.exceptionOrNull()
        assertTrue(failed is LspRequestException, "expected LspRequestException, got $failed")
        assertEquals(LspRequestException.CONNECTION_CLOSED, (failed as LspRequestException).code)

        val strandedFailure = awaitSoon(stranded).exceptionOrNull()
        assertTrue(strandedFailure is LspRequestException, "the earlier request must not hang: got $strandedFailure")
        assertEquals(LspRequestException.CONNECTION_CLOSED, (strandedFailure as LspRequestException).code)
    }

    @Test
    fun `a notification after the connection closed is dropped rather than thrown`() = runBlocking {
        client.stop()
        // Fire-and-forget: there is no caller to report the closed connection to.
        client.sendNotification("textDocument/didChange")
    }

    @Test
    fun `a send failing with something other than an IOException still closes the connection`() = runBlocking {
        // The transport contract does not restrict failures to IOException: a channel transport
        // closed by stop() between registration and the write throws ClosedSendChannelException.
        // That must be reported as CONNECTION_CLOSED and tear the connection down like any other
        // failed write, not leak out as an undocumented exception.
        val writesFail = object : LspTransport {
            val firstSent = CompletableDeferred<Unit>()

            override suspend fun sendPayload(jsonPayload: String) {
                if (firstSent.complete(Unit)) return
                throw ClosedSendChannelException("Channel was closed")
            }

            override suspend fun receivePayload(): String? = CompletableDeferred<String>().await()
            override fun close() = Unit
        }
        val client = LspClient(writesFail, CoroutineScope(Dispatchers.Default))

        val stranded = CompletableDeferred<Result<JsonElement?>>()
        launch { stranded.complete(runCatching { client.sendRequest("textDocument/hover") }) }
        awaitSoon(writesFail.firstSent)

        val failed = runCatching { client.sendRequest("textDocument/definition") }.exceptionOrNull()
        assertTrue(failed is LspRequestException, "expected LspRequestException, got $failed")
        assertEquals(LspRequestException.CONNECTION_CLOSED, (failed as LspRequestException).code)

        val strandedFailure = awaitSoon(stranded).exceptionOrNull()
        assertEquals(LspRequestException.CONNECTION_CLOSED, (strandedFailure as LspRequestException).code)
    }

    @Test
    fun `a notification whose send fails tears the connection down without escaping`() = runBlocking {
        val uncaught = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(
            Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.complete(e) },
        )
        val writesFail = object : LspTransport {
            val firstSent = CompletableDeferred<Unit>()

            override suspend fun sendPayload(jsonPayload: String) {
                if (firstSent.complete(Unit)) return
                throw IllegalStateException("closed underneath")
            }

            override suspend fun receivePayload(): String? = CompletableDeferred<String>().await()
            override fun close() = Unit
        }
        val client = LspClient(writesFail, scope)

        val stranded = CompletableDeferred<Result<JsonElement?>>()
        scope.launch { stranded.complete(runCatching { client.sendRequest("textDocument/hover") }) }
        awaitSoon(writesFail.firstSent)

        // Fire-and-forget: the failure is swallowed, but the connection it revealed as dead must
        // still be closed so the request above does not wait forever.
        val notified = scope.launch { client.sendNotification("textDocument/didChange") }
        withTimeout(5_000) { notified.join() }

        val strandedFailure = awaitSoon(stranded).exceptionOrNull()
        assertTrue(strandedFailure is LspRequestException, "the pending request must be released: got $strandedFailure")
        assertEquals(LspRequestException.CONNECTION_CLOSED, (strandedFailure as LspRequestException).code)
        assertFalse(uncaught.isCompleted, "the failure must not reach the scope's handler")
    }

    @Test
    fun `stop is final - a later request does not restart the receive loop`() = runBlocking {
        client.stop()

        val outcome = CompletableDeferred<Result<JsonElement?>>()
        launch { outcome.complete(runCatching { client.sendRequest("textDocument/hover") }) }

        val failure = awaitSoon(outcome).exceptionOrNull()
        assertEquals(LspRequestException.CONNECTION_CLOSED, (failure as LspRequestException).code)
        assertTrue(transport.sendChannel.isClosedForSend, "nothing may be written to a stopped transport")
    }
}
