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

import com.aardarch.aardink.core.CodeDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal const val URI = "file:///src/Main.kt"
internal const val OTHER_URI = "file:///src/Other.kt"

/** LSP range JSON literal. */
internal fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int): String =
    """{"start":{"line":$startLine,"character":$startChar},"end":{"line":$endLine,"character":$endChar}}"""

/**
 * A fake language server on the other end of a [ChannelLspTransport].
 *
 * [clientScope] backs [client]'s receive loop and must be a [TestScope]'s `backgroundScope` (see
 * [withServer]) rather than a real dispatcher like `Dispatchers.Default`: both this harness's own
 * coroutines (via [test], the enclosing `runTest`'s [TestScope]) and the client's background work
 * then share one `TestCoroutineScheduler`, so `withTimeout` correctly waits for the client instead
 * of racing an unrelated real thread pool the virtual clock knows nothing about.
 */
internal class Harness(val doc: CodeDocument, private val test: TestScope, clientScope: CoroutineScope, host: LspClientHost) :
    CoroutineScope by test {
    val transport = ChannelLspTransport()
    val client = LspClient(transport, clientScope, host)
    val service = LspLanguageService(client, URI, "kotlin")

    /** A second adapter over the same connection, configured with what the server declared about rename. */
    fun serviceWithRenameSupport(support: LspRenameSupport?) = LspLanguageService(client, URI, "kotlin", serverRenameSupport = support)

    /**
     * Next message the client put on the wire. The timeout turns a client that has stopped
     * answering into a failure as soon as everything is idle, since virtual time then jumps.
     */
    suspend fun nextSent(): JsonObject = withTimeout(5_000) { LspJson.parseToJsonElement(transport.sendChannel.receive()).jsonObject }

    /** Runs everything already scheduled, so what the client was going to do has been done. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun settle() = test.runCurrent()

    /** Asserts the client has written nothing. */
    fun assertNothingSent(message: String) {
        settle()
        assertTrue(transport.sendChannel.tryReceive().isFailure, message)
    }

    /** Delivers raw [json] to the client, as the server would. */
    suspend fun serverSends(json: String) = transport.receiveChannel.send(json)

    /** Sends the client a request from the server. [id] is raw JSON, so a string id keeps its quotes. */
    suspend fun serverAsks(id: String, method: String, paramsJson: String) {
        client.start()
        serverSends("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$paramsJson}""")
    }

    /** Answers the next request with raw [resultJson] after asserting its method; returns the request. */
    suspend fun respond(expectedMethod: String, resultJson: String): JsonObject {
        val sent = nextSent()
        assertEquals(expectedMethod, sent["method"]!!.jsonPrimitive.content)
        val id = sent["id"]!!.jsonPrimitive.content
        serverSends("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""")
        return sent
    }

    /** Answers the next request with a JSON-RPC error. */
    suspend fun respondWithError(expectedMethod: String) {
        val sent = nextSent()
        assertEquals(expectedMethod, sent["method"]!!.jsonPrimitive.content)
        val id = sent["id"]!!.jsonPrimitive.content
        serverSends("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":"boom"}}""")
    }

    /** Answers the next request the way a server that doesn't implement the method would. */
    suspend fun respondWithMethodNotFound(expectedMethod: String) {
        val sent = nextSent()
        assertEquals(expectedMethod, sent["method"]!!.jsonPrimitive.content)
        val id = sent["id"]!!.jsonPrimitive.content
        val code = LspRequestException.METHOD_NOT_FOUND
        serverSends("""{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"unsupported"}}""")
    }

    /** Pushes `publishDiagnostics` for [uri] and returns once every registered listener has run. */
    suspend fun publish(uri: String, diagnosticsJson: String, version: Int? = null) {
        val done = CompletableDeferred<Unit>()
        val probe: DiagnosticsListener = { _, _, _ -> done.complete(Unit) }
        client.addDiagnosticsListener(probe)
        client.start()
        val versionMember = version?.let { """"version":$it,""" }.orEmpty()
        serverSends(
            """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"$uri",$versionMember"diagnostics":$diagnosticsJson}}""",
        )
        withTimeout(5_000) { done.await() }
        client.removeDiagnosticsListener(probe)
    }
}

internal fun withServer(text: String, host: LspClientHost = NoLspClientHost, block: suspend Harness.() -> Unit) = runTest {
    val harness = Harness(CodeDocument(text), this, backgroundScope, host)
    try {
        harness.block()
    } finally {
        harness.client.stop()
    }
}

internal val JsonObject.method: String get() = this["method"]!!.jsonPrimitive.content

internal val JsonObject.params: JsonObject get() = this["params"]!!.jsonObject
