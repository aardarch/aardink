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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        val first = CompletableDeferred<Pair<String, List<LspDiagnostic>>>()
        val second = CompletableDeferred<Pair<String, List<LspDiagnostic>>>()
        client.addDiagnosticsListener { uri, diagnostics -> first.complete(uri to diagnostics) }
        client.addDiagnosticsListener { uri, diagnostics -> second.complete(uri to diagnostics) }
        client.start()

        // "data":{"id":42} guards against treating any payload containing "id" as a response.
        serverSends(
            """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///Main.kt","version":3,
               |"diagnostics":[{"range":{"start":{"line":2,"character":4},"end":{"line":2,"character":9}},
               |"message":"Unresolved reference","severity":1,"source":"kotlin","data":{"id":42}}]}}
            """.trimMargin().replace("\n", ""),
        )

        val (uri, diagnostics) = awaitSoon(first)
        assertEquals("file:///Main.kt", uri)
        assertEquals(1, diagnostics.size)
        assertEquals(LspRange(LspPosition(2, 4), LspPosition(2, 9)), diagnostics[0].range)
        assertEquals("Unresolved reference", diagnostics[0].message)
        assertEquals(1, diagnostics[0].severity)
        assertEquals("kotlin", diagnostics[0].source)
        assertEquals(uri to diagnostics, awaitSoon(second))
        client.stop()
    }

    @Test
    fun `removed listener no longer receives diagnostics`() = runBlocking {
        val kept = CompletableDeferred<String>()
        var removedWasCalled = false
        val removed: DiagnosticsListener = { _, _ -> removedWasCalled = true }
        client.addDiagnosticsListener(removed)
        client.addDiagnosticsListener { uri, _ -> kept.complete(uri) }
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
    fun `server-to-client request is answered with MethodNotFound`() = runBlocking {
        client.start()
        serverSends("""{"jsonrpc":"2.0","id":"abc","method":"client/registerCapability","params":{"registrations":[]}}""")

        val reply = nextSent()
        assertEquals("abc", reply["id"]!!.jsonPrimitive.content)
        assertTrue(reply["id"]!!.jsonPrimitive.isString)
        assertEquals(LspRequestException.METHOD_NOT_FOUND, reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertFalse("result" in reply)
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
}
