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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CANCEL_REQUEST = "\$/cancelRequest"
private const val INTERNAL_ERROR = -32603

private const val MESSAGE_REQUEST = "window/showMessageRequest"
private const val MESSAGE_PARAMS = """{"type":3,"message":"Reload?","actions":[{"title":"Yes"},{"title":"No"}]}"""

/** A host whose `showMessageRequest` the test decides, once the harness's client exists. */
private class ScriptedHost : LspClientHost {
    var onMessageRequest: suspend (type: Int, message: String, actions: List<String>) -> String? = { _, _, _ -> null }

    override suspend fun showMessageRequest(type: Int, message: String, actions: List<String>): String? =
        onMessageRequest(type, message, actions)
}

/**
 * A server that stopped reading. Every write after [writesAccepted] blocks and, like a write to a
 * stream, takes no notice of cancellation: only closing the connection ends it.
 */
private class HungTransport(private val writesAccepted: Int) : LspTransport {
    val written = mutableListOf<String>()
    private val closed = CompletableDeferred<Unit>()
    val isClosed: Boolean get() = closed.isCompleted

    /** Completes when every accepted write has happened, so the next one is stuck. */
    val full = CompletableDeferred<Unit>()

    override suspend fun sendPayload(jsonPayload: String) {
        if (written.size >= writesAccepted) {
            withContext(NonCancellable) { closed.await() }
        } else {
            written += jsonPayload
            if (written.size == writesAccepted) full.complete(Unit)
        }
    }

    override suspend fun receivePayload(): String? {
        closed.await()
        return null
    }

    override fun close() {
        closed.complete(Unit)
    }
}

private fun withHost(block: suspend Harness.(ScriptedHost) -> Unit) = ScriptedHost().let { host -> withServer("", host) { block(host) } }

class LspClientLifecycleTest {

    // ── $/cancelRequest ──────────────────────────────────────────────────────

    @Test
    fun `cancelling a pending request tells the server which one`() = withServer("") {
        val pending = async { client.sendRequest("textDocument/hover") }
        val request = nextSent()

        pending.cancel()

        val cancel = nextSent()
        assertEquals(CANCEL_REQUEST, cancel.method)
        assertEquals(request["id"]!!.jsonPrimitive.long, cancel.params["id"]!!.jsonPrimitive.long)
        assertFalse("id" in cancel, "it is a notification")
    }

    @Test
    fun `a timeout around a request cancels it on the server too`() = withServer("") {
        val pending = async { runCatching { withTimeout(100) { client.sendRequest("textDocument/hover") } } }
        nextSent()

        assertTrue(pending.await().exceptionOrNull() is TimeoutCancellationException)

        assertEquals(CANCEL_REQUEST, nextSent().method)
    }

    @Test
    fun `the server's late answer to a cancelled request is ignored`() = withServer("") {
        val pending = async { client.sendRequest("textDocument/hover") }
        val id = nextSent()["id"]!!.jsonPrimitive.long
        pending.cancel()
        assertEquals(CANCEL_REQUEST, nextSent().method)

        serverSends("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32800,"message":"cancelled"}}""")

        val next = async { client.sendRequest("textDocument/definition") }
        respond("textDocument/definition", "\"fresh\"")
        assertEquals("fresh", next.await()!!.jsonPrimitive.content)
    }

    @Test
    fun `an answered request is not cancelled`() = withServer("") {
        val pending = async { client.sendRequest("textDocument/hover") }
        respond("textDocument/hover", "null")
        pending.await()

        assertNothingSent("nothing is left to cancel")
    }

    @Test
    fun `a request the server failed is not cancelled`() = withServer("") {
        val pending = async { runCatching { client.sendRequest("textDocument/hover") } }
        respondWithError("textDocument/hover")
        assertTrue(pending.await().exceptionOrNull() is LspRequestException)

        assertNothingSent("the server already answered")
    }

    @Test
    fun `a cancelled caller does not wait for a write stuck behind a hung server`() = runTest {
        val transport = HungTransport(writesAccepted = 1)
        val client = LspClient(transport, backgroundScope)
        val pending = async { client.sendRequest("textDocument/hover") }
        withTimeout(5_000) { transport.full.await() }

        try {
            // Typing must not wait on the server just because a stale completion was dropped.
            withTimeout(5_000) { pending.cancelAndJoin() }
        } finally {
            // Frees a caller that did wait, so that regression fails here instead of hanging runTest.
            client.stop()
        }
    }

    // ── shutdown and exit ────────────────────────────────────────────────────

    @Test
    fun `shutdown waits for the server's answer, sends exit and closes`() = withServer("") {
        val closing = async { client.shutdown() }

        val request = nextSent()
        assertEquals("shutdown", request.method)
        assertFalse("params" in request, "shutdown takes no params")
        assertNothingSent("exit must wait for the answer to shutdown")

        serverSends("""{"jsonrpc":"2.0","id":${request["id"]},"result":null}""")
        closing.await()

        val exit = nextSent()
        assertEquals("exit", exit.method)
        assertFalse("id" in exit, "exit is a notification")
        assertTrue(transport.sendChannel.isClosedForSend, "the transport is released")
    }

    @Test
    fun `shutdown sends exit after a server error`() = withServer("") {
        val closing = async { client.shutdown() }
        respondWithError("shutdown")
        closing.await()

        assertEquals("exit", nextSent().method)
        assertTrue(transport.sendChannel.isClosedForSend)
    }

    @Test
    fun `shutdown gives up on a server that never answers`() = withServer("") {
        val closing = async { client.shutdown() }
        assertEquals("shutdown", nextSent().method)

        closing.await()

        assertEquals("exit", nextSent().method)
        assertNothingSent("the protocol allows nothing but exit after shutdown, so no cancel either")
        assertTrue(transport.sendChannel.isClosedForSend)
    }

    @Test
    fun `shutdown fails what is still pending`() = withServer("") {
        val pending = async { runCatching { client.sendRequest("textDocument/hover") } }
        nextSent()

        val closing = async { client.shutdown() }
        respond("shutdown", "null")
        closing.await()

        val failure = pending.await().exceptionOrNull()
        assertTrue(failure is LspRequestException, "expected LspRequestException, got $failure")
        assertEquals(LspRequestException.CONNECTION_CLOSED, failure.code)
    }

    @Test
    fun `notifications are dropped once shutdown has begun`() = withServer("") {
        val closing = async { client.shutdown() }
        val request = nextSent()

        client.sendNotification("textDocument/didChange")
        assertNothingSent("only exit may follow shutdown")

        serverSends("""{"jsonrpc":"2.0","id":${request["id"]},"result":null}""")
        closing.await()
        assertEquals("exit", nextSent().method)
    }

    @Test
    fun `requests are refused once shutdown has begun`() = withServer("") {
        val closing = async { client.shutdown() }
        val request = nextSent()

        val failure = assertFailsWith<LspRequestException> { client.sendRequest("textDocument/hover") }
        assertEquals(LspRequestException.CONNECTION_CLOSED, failure.code)
        assertNothingSent("only exit may follow shutdown")

        serverSends("""{"jsonrpc":"2.0","id":${request["id"]},"result":null}""")
        closing.await()
        assertEquals("exit", nextSent().method)
    }

    @Test
    fun `shutdown closes a server that stopped reading before the request`() = runTest {
        val transport = HungTransport(writesAccepted = 0)
        val client = LspClient(transport, backgroundScope)

        client.shutdown()

        assertTrue(transport.isClosed)
    }

    @Test
    fun `shutdown closes a server that stopped reading before exit`() = runTest {
        val transport = HungTransport(writesAccepted = 1)
        val client = LspClient(transport, backgroundScope)

        client.shutdown()

        assertEquals("shutdown", LspJson.parseToJsonElement(transport.written.single()).jsonObject.method)
        assertTrue(transport.isClosed)
    }

    @Test
    fun `cancelling shutdown still closes the client`() = withServer("") {
        val closing = async { client.shutdown() }
        nextSent()

        closing.cancelAndJoin()

        assertTrue(transport.sendChannel.isClosedForSend)
        val failure = assertFailsWith<LspRequestException> { client.sendRequest("textDocument/hover") }
        assertEquals(LspRequestException.CONNECTION_CLOSED, failure.code)
    }

    @Test
    fun `shutdown after the client stopped does nothing`() = withServer("") {
        client.stop()

        client.shutdown()

        assertNothingSent("the connection is gone")
    }

    @Test
    fun `a shutdown after the session ended sends nothing more`() = withServer("") {
        val closing = async { client.shutdown() }
        respond("shutdown", "null")
        closing.await()
        assertEquals("exit", nextSent().method)

        client.shutdown()

        assertNothingSent("the session is already over")
    }

    @Test
    fun `a shutdown while one is running returns and sends nothing more`() = withServer("") {
        val first = async { client.shutdown() }
        val request = nextSent()
        assertEquals("shutdown", request.method)

        // The connection is still open here, so only the claim keeps this one off the wire.
        withTimeout(5_000) { client.shutdown() }
        assertNothingSent("one shutdown request is all the protocol allows")

        serverSends("""{"jsonrpc":"2.0","id":${request["id"]},"result":null}""")
        first.await()
        assertEquals("exit", nextSent().method)
        assertNothingSent("and one exit")
    }

    @Test
    fun `shutdown still closes the transport when the scope is already cancelled`() = runTest {
        val transport = ChannelLspTransport()
        val scope = CoroutineScope(Job())
        val client = LspClient(transport, scope)
        scope.cancel()

        client.shutdown()

        assertTrue(transport.sendChannel.isClosedForSend, "nothing but the caller is left to close it")
    }

    @Test
    fun `stop stays a forced close that says nothing to the server`() = withServer("") {
        client.start()

        client.stop()

        assertNothingSent("stop is not shutdown")
        assertTrue(transport.sendChannel.isClosedForSend)
    }

    // ── Server-to-client requests ────────────────────────────────────────────

    @Test
    fun `a host callback that sends a request gets its response`() = withHost { host ->
        // On the receive loop this callback waits for a response only the receive loop can read.
        host.onMessageRequest = { _, _, _ -> client.sendRequest("custom/whichOne")!!.jsonPrimitive.content }

        serverAsks("\"ask\"", MESSAGE_REQUEST, MESSAGE_PARAMS)
        respond("custom/whichOne", "\"Yes\"")

        val reply = nextSent()
        assertEquals("ask", reply["id"]!!.jsonPrimitive.content)
        assertEquals("Yes", reply["result"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a host callback that suspends does not hold up responses or notifications`() = withHost { host ->
        val answer = CompletableDeferred<String?>()
        host.onMessageRequest = { _, _, _ -> answer.await() }
        serverAsks("1", MESSAGE_REQUEST, MESSAGE_PARAMS)

        val pending = async { client.sendRequest("textDocument/hover") }
        respond("textDocument/hover", "\"fresh\"")
        assertEquals("fresh", withTimeout(5_000) { pending.await() }!!.jsonPrimitive.content)
        publish(URI, """[{"range":${range(0, 0, 0, 0)},"message":"seen"}]""")
        assertEquals("seen", service.diagnostics(doc).single().message)

        assertNothingSent("the callback has not answered yet")
        answer.complete("No")
        assertEquals("No", nextSent()["result"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a request from the server is answered while a callback waits on the server`() = withHost { host ->
        // A real server asks for configuration while working on a request. Requests queued behind
        // the running callback would deadlock here just as the receive loop did.
        host.onMessageRequest = { _, _, _ -> client.sendRequest("custom/needsConfiguration")!!.jsonPrimitive.content }
        serverAsks("1", MESSAGE_REQUEST, MESSAGE_PARAMS)
        val inner = nextSent()
        assertEquals("custom/needsConfiguration", inner.method)

        serverAsks("2", "workspace/configuration", """{"items":[{"section":"a"}]}""")
        val configuration = nextSent()
        assertEquals(2, configuration["id"]!!.jsonPrimitive.int)
        assertEquals(1, configuration["result"]!!.jsonArray.size)

        serverSends("""{"jsonrpc":"2.0","id":${inner["id"]},"result":"Yes"}""")
        assertEquals(1, nextSent()["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a host callback that throws is reported and the connection survives`() = withHost { host ->
        host.onMessageRequest = { _, _, _ -> throw IllegalStateException("no window") }

        serverAsks("7", MESSAGE_REQUEST, MESSAGE_PARAMS)

        val reply = nextSent()
        assertEquals(7, reply["id"]!!.jsonPrimitive.int)
        assertEquals(INTERNAL_ERROR, reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertTrue("no window" in reply["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertFalse("result" in reply)

        val next = async { client.sendRequest("textDocument/hover") }
        respond("textDocument/hover", "\"still here\"")
        assertEquals("still here", next.await()!!.jsonPrimitive.content)
    }

    @Test
    fun `a timeout that escapes a host callback still answers the server`() = withHost { host ->
        // A cancellation, but not the client's: the server would wait for this reply forever.
        host.onMessageRequest = { _, _, _ -> withTimeout(10) { awaitCancellation() } }

        serverAsks("8", MESSAGE_REQUEST, MESSAGE_PARAMS)

        assertEquals(INTERNAL_ERROR, nextSent()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `stop cancels a running callback`() = withHost { host ->
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        host.onMessageRequest = { _, _, _ ->
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        serverAsks("1", MESSAGE_REQUEST, MESSAGE_PARAMS)
        withTimeout(5_000) { started.await() }

        client.stop()

        withTimeout(5_000) { cancelled.await() }
        assertNothingSent("nobody is left to answer")
    }

    @Test
    fun `the server going away cancels a running callback`() = withHost { host ->
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        host.onMessageRequest = { _, _, _ ->
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        serverAsks("1", MESSAGE_REQUEST, MESSAGE_PARAMS)
        withTimeout(5_000) { started.await() }

        transport.receiveChannel.close()

        withTimeout(5_000) { cancelled.await() }
    }

    @Test
    fun `showMessageRequest passes the host what the server sent and returns its choice`() = withHost { host ->
        var asked: Triple<Int, String, List<String>>? = null
        host.onMessageRequest = { type, message, actions ->
            asked = Triple(type, message, actions)
            "No"
        }

        serverAsks("1", MESSAGE_REQUEST, MESSAGE_PARAMS)

        val result = nextSent()["result"]!!.jsonObject
        assertEquals(Triple(3, "Reload?", listOf("Yes", "No")), asked)
        assertEquals(setOf("title"), result.keys)
    }

    @Test
    fun `a showMessageRequest without actions reaches the host with none`() = withHost { host ->
        var actions: List<String>? = null
        host.onMessageRequest = { _, _, offered ->
            actions = offered
            null
        }

        serverAsks("1", MESSAGE_REQUEST, """{"type":1,"message":"Heads up"}""")

        assertEquals(JsonNull, nextSent()["result"])
        assertEquals(emptyList(), actions)
    }

    @Test
    fun `a showMessageRequest too malformed to show is answered without the host`() = withHost { host ->
        var asked = false
        host.onMessageRequest = { _, _, _ ->
            asked = true
            "Yes"
        }

        serverAsks("1", MESSAGE_REQUEST, """{"type":"loud","message":"Reload?"}""")

        assertEquals(JsonNull, nextSent()["result"])
        assertFalse(asked)
    }

    @Test
    fun `an action without a title is left out of what the host is offered`() = withHost { host ->
        var offered: List<String>? = null
        host.onMessageRequest = { _, _, actions ->
            offered = actions
            null
        }

        serverAsks("1", MESSAGE_REQUEST, """{"type":3,"message":"Reload?","actions":[{"title":"Yes"},{},7]}""")

        nextSent()
        assertEquals(listOf("Yes"), offered)
    }

    @Test
    fun `a default host leaves every member a harmless answer`() = runTest {
        val host = object : LspClientHost {}

        assertFalse(host.applyWorkspaceEdit(LspWorkspaceEdit(), label = null), "nothing was applied, so it must not say so")
        assertNull(host.showMessageRequest(1, "m", listOf("a")))
        host.showMessage(1, "m")
        host.logMessage(1, "m")
        host.progress(JsonPrimitive("token"), buildJsonObject { })
    }

    // ── Capabilities ─────────────────────────────────────────────────────────

    @Test
    fun `serverCapabilities holds what initialize returned`() = withServer("") {
        assertNull(client.serverCapabilities)

        val initializing = async { client.initialize(rootUri = null) }
        respond("initialize", """{"capabilities":{"hoverProvider":true}}""")

        assertEquals("initialized", nextSent().method)
        val returned = initializing.await()
        assertEquals(returned, client.serverCapabilities)
        assertTrue(client.serverCapabilities!!.jsonObject["hoverProvider"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `serverCapabilities is set by the time initialized reaches the server`() = runTest {
        // Read from inside the write: a server may ask the client something the moment it sees
        // `initialized`, and nothing observed after initialize returns could tell the difference.
        var atInitialized: JsonElement? = null
        lateinit var client: LspClient
        val transport = object : LspTransport {
            val incoming = Channel<String>(Channel.UNLIMITED)

            override suspend fun sendPayload(jsonPayload: String) {
                val message = LspJson.parseToJsonElement(jsonPayload).jsonObject
                when (message.method) {
                    "initialize" -> incoming.send(
                        """{"jsonrpc":"2.0","id":${message["id"]},"result":{"capabilities":{"hoverProvider":true}}}""",
                    )

                    "initialized" -> atInitialized = client.serverCapabilities
                }
            }

            override suspend fun receivePayload(): String? = incoming.receiveCatching().getOrNull()

            override fun close() {
                incoming.close()
            }
        }
        client = LspClient(transport, backgroundScope)

        val returned = client.initialize(rootUri = null)

        assertEquals(returned, atInitialized)
        assertTrue(atInitialized!!.jsonObject["hoverProvider"]!!.jsonPrimitive.boolean)
        client.stop()
    }

    @Test
    fun `serverCapabilities stays null when the answer carried none`() = withServer("") {
        val initializing = async { client.initialize(rootUri = null) }
        respond("initialize", "{}")

        assertNull(initializing.await())
        assertNull(client.serverCapabilities)
    }

    @Test
    fun `initialize declares the default capabilities unless given others`() = withServer("") {
        val defaults = async { client.initialize(rootUri = null) }
        val sent = respond("initialize", "{}")
        assertEquals(DefaultClientCapabilities, sent.params["capabilities"])
        nextSent()
        defaults.await()

        val custom = buildJsonObject { }
        val explicit = async { client.initialize(rootUri = null, capabilities = custom) }
        assertEquals(custom, respond("initialize", "{}").params["capabilities"])
        explicit.await()
    }

    @Test
    fun `the default capabilities never offer dynamic registration`() {
        fun visit(element: JsonElement, found: MutableList<JsonElement>) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "dynamicRegistration") found += value
                    visit(value, found)
                }

                is JsonArray -> element.forEach { visit(it, found) }

                else -> Unit
            }
        }
        val declared = mutableListOf<JsonElement>().also { visit(DefaultClientCapabilities, it) }

        // Of what client/registerCapability covers only text sync is acted on, so inviting it for
        // anything else would lose a server relying on it.
        assertTrue(declared.size > 10, "every feature says so explicitly, found ${declared.size}")
        assertTrue(declared.all { !it.jsonPrimitive.boolean })
    }

    @Test
    fun `the default capabilities declare what servers gate their answers on`() {
        val textDocument = DefaultClientCapabilities["textDocument"]!!.jsonObject
        val workspace = DefaultClientCapabilities["workspace"]!!.jsonObject
        val window = DefaultClientCapabilities["window"]!!.jsonObject
        val general = DefaultClientCapabilities["general"]!!.jsonObject

        assertEquals(listOf("utf-16"), general["positionEncodings"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(window["workDoneProgress"]!!.jsonPrimitive.boolean)
        assertTrue(workspace["applyEdit"]!!.jsonPrimitive.boolean)
        assertFalse(textDocument["diagnostic"]!!.jsonObject["relatedDocumentSupport"]!!.jsonPrimitive.boolean)
        assertTrue(textDocument["documentSymbol"]!!.jsonObject["hierarchicalDocumentSymbolSupport"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("markdown", "plaintext"),
            textDocument["hover"]!!.jsonObject["contentFormat"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val completionItem = textDocument["completion"]!!.jsonObject["completionItem"]!!.jsonObject
        assertTrue(completionItem["snippetSupport"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("markdown", "plaintext"),
            completionItem["documentationFormat"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `the default capabilities promise nothing that would change a server for the worse`() {
        val textDocument = DefaultClientCapabilities["textDocument"]!!.jsonObject
        val completionItem = textDocument["completion"]!!.jsonObject["completionItem"]!!.jsonObject
        val resolved = completionItem["resolveSupport"]!!.jsonObject["properties"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertFalse("additionalTextEdits" in resolved, "accepting an item does not wait for resolve, so the import would be lost")
        assertFalse("willSaveWaitUntil" in textDocument["synchronization"]!!.jsonObject, "not in 0.9")
        assertFalse(
            "resourceOperations" in DefaultClientCapabilities["workspace"]!!.jsonObject["workspaceEdit"]!!.jsonObject,
            "the adapter refuses file operations",
        )
    }
}
