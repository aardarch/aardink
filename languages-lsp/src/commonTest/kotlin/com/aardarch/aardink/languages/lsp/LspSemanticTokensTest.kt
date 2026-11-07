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
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.Token
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

private const val FULL = "textDocument/semanticTokens/full"
private const val CONTENT_MODIFIED = -32801

private const val LEGEND =
    """{"tokenTypes":["class","function","variable","comment","lifetime"],"tokenModifiers":["declaration","static","readonly"]}"""

private const val TOKENS_SERVER = """{"semanticTokensProvider":{"legend":$LEGEND,"full":true}}"""

/** The server knows the document, by the host's word. */
private suspend fun Harness.open(capabilities: String = TOKENS_SERVER) {
    serverDeclares(capabilities)
    service.didOpen(doc)
    assertEquals("textDocument/didOpen", nextSent().method)
}

/** Has the server answer [dataJson], and returns what the service made of it. */
private suspend fun Harness.answered(dataJson: String, document: CodeDocument = doc): List<Token> {
    val server = async { respond(FULL, """{"data":$dataJson}""") }
    val tokens = service.semanticTokens(document)
    server.await()
    return tokens
}

private fun type(name: String) = NamedTokenType(name)

private val List<Token>.spans: List<Pair<IntRange, String>>
    get() = map { it.start until it.end to (it.type as NamedTokenType).name }

class LspSemanticTokensTest {

    // ── The answer ───────────────────────────────────────────────────────────

    @Test
    fun `delta-encoded integers become tokens at their offsets`() = withServer("class A {\n    fun b() {}\n}\nval c") {
        open()

        val tokens = answered(
            """
            [0, 6, 1, 0, 0,
             1, 8, 1, 1, 0,
             0, 4, 1, 2, 0,
             2, 4, 1, 2, 0]
            """,
        )

        assertEquals(
            listOf(
                Token(6, 7, type("type.class")),
                Token(18, 19, type("function")),
                Token(22, 23, type("variable")),
                Token(31, 32, type("variable")),
            ),
            tokens,
        )
        assertEquals(listOf("A", "b", "{", "c"), tokens.map { doc.text.substring(it.start, it.end) })
    }

    @Test
    fun `the request names the document, and asks for the whole of it`() = withServer("x") {
        open()

        val server = async { respond(FULL, """{"data":[]}""") }
        service.semanticTokens(doc)
        val sent = server.await()

        assertEquals(URI, sent.params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        assertEquals(setOf("textDocument"), sent.params.keys)
    }

    @Test
    fun `modifiers follow the type in the legend's order`() = withServer("abcd") {
        open()

        val tokens = answered("[0, 0, 1, 1, 6, 0, 1, 1, 1, 1, 0, 1, 1, 1, 4]")

        assertEquals(
            listOf("function.static.readonly", "function.declaration", "function.readonly"),
            tokens.map {
                (it.type as NamedTokenType).name
            },
        )
    }

    @Test
    fun `a modifier outside the legend is left out`() = withServer("ab") {
        open()

        assertEquals(listOf(0..0 to "function"), answered("[0, 0, 1, 1, 64]").spans)
    }

    @Test
    fun `a token type the client did not offer keeps the server's name`() = withServer("ab") {
        open()

        assertEquals(listOf(0..1 to "lifetime"), answered("[0, 0, 2, 4, 0]").spans)
    }

    @Test
    fun `columns count UTF-16 units`() = withServer("😀 name") {
        open()

        val token = answered("[0, 3, 4, 2, 0]").single()

        assertEquals("name", doc.text.substring(token.start, token.end))
    }

    @Test
    fun `a token running past its line goes on into the next`() = withServer("/* one\n   two */ x") {
        open()

        val tokens = answered("[0, 0, 16, 3, 0, 1, 10, 1, 2, 0]")

        assertEquals(listOf(0..15 to "comment", 17..17 to "variable"), tokens.spans)
    }

    @Test
    fun `tokens on lines past the end are left out`() = withServer("one\ntwo") {
        open()

        assertEquals(listOf(4..6 to "variable"), answered("[1, 0, 3, 2, 0, 1, 0, 3, 2, 0, 5, 0, 3, 2, 0]").spans)
    }

    @Test
    fun `a line delta too large to add up ends the answer`() = withServer("one\ntwo") {
        open()

        assertEquals(listOf(0..2 to "variable"), answered("[0, 0, 3, 2, 0, 2147483647, 0, 3, 2, 0, 2147483647, 0, 3, 2, 0]").spans)
    }

    @Test
    fun `a token starting past the end of its line is left out, and the next counts from it`() = withServer("one two\nx") {
        open()

        // Column 20 of a line of seven; the next line starts over.
        assertEquals(listOf(8..8 to "variable"), answered("[0, 20, 3, 2, 0, 1, 0, 1, 2, 0]").spans)
        // At the end of the line itself there is no character to colour.
        assertEquals(emptyList(), answered("[0, 7, 1, 2, 0]"))
        // Past the end, a later token on the line counts from the one left out.
        assertEquals(emptyList(), answered("[0, 9, 1, 2, 0, 0, 3, 1, 2, 0]"))
    }

    @Test
    fun `a token running past the end of the document stops there`() = withServer("one") {
        open()

        assertEquals(listOf(1..2 to "variable"), answered("[0, 1, 100, 2, 0]").spans)
    }

    @Test
    fun `an empty token is left out`() = withServer("one") {
        open()

        assertEquals(emptyList(), answered("[0, 1, 0, 2, 0]"))
    }

    @Test
    fun `a token of a type outside the legend is left out, and the next counts from it`() = withServer("one two") {
        open()

        assertEquals(listOf(4..6 to "variable"), answered("[0, 0, 3, 99, 0, 0, 4, 3, 2, 0]").spans)
    }

    @Test
    fun `integers that do not make up a whole token are ignored`() = withServer("one two") {
        open()

        assertEquals(listOf(0..2 to "variable"), answered("[0, 0, 3, 2, 0, 0, 4]").spans)
    }

    @Test
    fun `anything but unsigned integers is no answer`() = withServer("one two") {
        open()

        assertEquals(emptyList(), answered("""[0, 0, 3, 2, 0, 0, "x", 3, 2, 0]"""))
        assertEquals(emptyList(), answered("[0, 0, 3, 2, 0, 0, -4, 3, 2, 0]"))
        assertEquals(emptyList(), answered("[0, 0, 3, 2, 0, null, 4, 3, 2, 0]"))
    }

    @Test
    fun `an answer without data, or none at all, is no tokens`() = withServer("one") {
        open()

        val first = async { respond(FULL, "null") }
        assertEquals(emptyList(), service.semanticTokens(doc))
        first.await()
        val second = async { respond(FULL, """{"resultId":"1"}""") }
        assertEquals(emptyList(), service.semanticTokens(doc))
        second.await()
        val third = async { respondWithError(FULL) }
        assertEquals(emptyList(), service.semanticTokens(doc))
        third.await()
    }

    @Test
    fun `a long decode pauses on a single-threaded host`() = runTest {
        val lines = 10_000
        val document = CodeDocument(List(lines) { "x" }.joinToString("\n"))
        val data = JsonArray(
            List(lines * 5) {
                JsonPrimitive(
                    if (it % 5 == 2) {
                        1
                    } else if (it % 5 == 0 && it > 0) {
                        1
                    } else {
                        0
                    },
                )
            },
        )
        val legend = LspSemanticLegend(listOf("variable"), emptyList())

        val tokens = decodeSemanticTokens(data, legend, document, LspDecodePacer(Duration.ZERO))

        assertEquals(lines, tokens.size)
        assertEquals(40L, testScheduler.currentTime, "a millisecond's pause every 256 tokens")
    }

    // ── What the server declared ─────────────────────────────────────────────

    @Test
    fun `a server without the provider is sent nothing`() = withServer("x") {
        open("{}")

        assertEquals(emptyList(), service.semanticTokens(doc))
        assertNothingSent("no provider, no request")
    }

    @Test
    fun `a server that answers only for a range is sent nothing`() = withServer("x") {
        open("""{"semanticTokensProvider":{"legend":$LEGEND,"range":true}}""")

        assertEquals(emptyList(), service.semanticTokens(doc))
        assertNothingSent("range is not in 0.9")
    }

    @Test
    fun `a provider with no usable legend is sent nothing`() = withServer("x") {
        open("""{"semanticTokensProvider":{"full":true}}""")

        assertEquals(emptyList(), service.semanticTokens(doc))
        assertNothingSent("its numbers could not be read")
    }

    @Test
    fun `a server offering deltas is still asked for the whole answer`() = withServer("ab") {
        open("""{"semanticTokensProvider":{"legend":$LEGEND,"full":{"delta":true}}}""")

        assertEquals(listOf(0..1 to "variable"), answered("[0, 0, 2, 2, 0]").spans)
    }

    @Test
    fun `a server whose positions are not UTF-16 is sent nothing`() = withServer("x") {
        open("""{"positionEncoding":"utf-8","semanticTokensProvider":{"legend":$LEGEND,"full":true}}""")

        assertEquals(emptyList(), service.semanticTokens(doc))
        assertNothingSent("its columns could not be read")
    }

    @Test
    fun `a host that never ran the handshake gets nothing, as there is no legend`() = withServer("x") {
        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)

        assertEquals(emptyList(), service.semanticTokens(doc))
        assertNothingSent("its numbers could not be read")
    }

    @Test
    fun `the legend can come with a registration, and goes with it`() = withServer("ab") {
        open("{}")
        val method = "textDocument/semanticTokens"
        serverRegisters(registration(method, """{"legend":$LEGEND,"full":true,"documentSelector":null}"""))

        assertEquals(listOf(0..1 to "variable"), answered("[0, 0, 2, 2, 0]").spans)

        serverAsks("2", "client/unregisterCapability", """{"unregisterations":[{"id":"$method","method":"$method"}]}""")
        nextSent()
        assertEquals(emptyList(), service.semanticTokens(doc))
        assertNothingSent("taken back")
    }

    @Test
    fun `the default capabilities say what the decoder can take`() {
        val semanticTokens = DefaultClientCapabilities["textDocument"]!!.jsonObject["semanticTokens"]!!.jsonObject

        assertEquals(setOf("full"), semanticTokens["requests"]!!.jsonObject.keys, "delta and range are not in 0.9")
        assertTrue(semanticTokens["requests"]!!.jsonObject["full"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("relative"), semanticTokens["formats"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(semanticTokens["multilineTokenSupport"]!!.jsonPrimitive.boolean, "the store splits a token per line")
        assertFalse(semanticTokens["overlappingTokenSupport"]!!.jsonPrimitive.boolean)
        assertTrue(semanticTokens["augmentsSyntaxTokens"]!!.jsonPrimitive.boolean)
        assertFalse(semanticTokens["dynamicRegistration"]!!.jsonPrimitive.boolean)
    }

    // ── The order on the wire ────────────────────────────────────────────────

    private val incremental = """{"textDocumentSync":2,"semanticTokensProvider":{"legend":$LEGEND,"full":true}}"""

    private val wholeText = """{"textDocumentSync":1,"semanticTokensProvider":{"legend":$LEGEND,"full":true}}"""

    @Test
    fun `the request is written after the edit it is about`() = withServer("") {
        serverDeclares(incremental)
        val state = editorState("val x = 1")
        service.attach(state)
        assertEquals("textDocument/didOpen", nextSent().method)

        // What the editor does once typing pauses.
        state.applyTextEdits(listOf(TextEdit(4..3, "abc")))
        val snapshot = state.document.snapshot()
        val asked = async { service.semanticTokens(snapshot) }

        assertEquals("textDocument/didChange", nextSent().method)
        respond(FULL, """{"data":[0, 4, 4, 2, 0]}""")
        assertEquals(listOf(4..7 to "variable"), asked.await().spans)
        assertEquals("abcx", snapshot.text.substring(4, 8))
    }

    @Test
    fun `a stalled edit holds the request back`() = withServer("") {
        serverDeclares(incremental)
        val state = editorState("x")
        service.attach(state)
        assertEquals("textDocument/didOpen", nextSent().method)

        wire.hold("didChange")
        state.applyTextEdits(listOf(TextEdit(1..0, "y")))
        val asked = async { service.semanticTokens(state.document.snapshot()) }
        assertNothingSent("the edit is still on its way")

        wire.release()
        assertEquals("textDocument/didChange", nextSent().method)
        respond(FULL, """{"data":[]}""")
        asked.await()
    }

    @Test
    fun `a server sent whole texts hears the edit first too, without waiting for the pause`() = withServer("") {
        serverDeclares(wholeText)
        val state = editorState("x")
        service.attach(state)
        assertEquals("textDocument/didOpen", nextSent().method)

        state.applyTextEdits(listOf(TextEdit(1..0, "y")))
        val asked = async { service.semanticTokens(state.document.snapshot()) }

        val change = nextSent()
        assertEquals("textDocument/didChange", change.method)
        assertEquals("xy", change.params["contentChanges"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        respond(FULL, """{"data":[0, 0, 2, 2, 0]}""")
        assertEquals(listOf(0..1 to "variable"), asked.await().spans)
    }

    @Test
    fun `asked before the handshake, it waits for the document and goes once it is open`() = withServer("") {
        val state = editorState("name")
        // The editor's first request, which an unedited document does not get again.
        val asked = async { service.semanticTokens(state.document.snapshot()) }
        assertNothingSent("no document to ask about yet")

        serverDeclares(incremental)
        assertNothingSent("still not open")
        service.attach(state)

        assertEquals("textDocument/didOpen", nextSent().method)
        respond(FULL, """{"data":[0, 0, 4, 2, 0]}""")
        assertEquals(listOf(0..3 to "variable"), asked.await().spans)
    }

    @Test
    fun `asked before the handshake of a server without the provider, nothing is sent`() = withServer("") {
        val state = editorState("name")
        val asked = async { service.semanticTokens(state.document.snapshot()) }
        assertNothingSent("no document to ask about yet")

        serverDeclares("""{"textDocumentSync":2}""")
        service.attach(state)

        assertEquals("textDocument/didOpen", nextSent().method)
        assertEquals(emptyList(), asked.await())
        assertNothingSent("what it declared came in while the request waited")
    }

    @Test
    fun `a server still starting up is asked again`() = withServer("ab") {
        open()

        val asked = async { service.semanticTokens(doc) }
        respondWithError(FULL, CONTENT_MODIFIED)
        assertNothingSent("not at once")
        advanceBy(1_000)
        respond(FULL, """{"data":[0, 0, 2, 2, 0]}""")

        assertEquals(listOf(0..1 to "variable"), asked.await().spans)
    }

    @Test
    fun `a server that turns out not to have the method is not asked again`() = withServer("ab") {
        open()

        val asked = async { service.semanticTokens(doc) }
        respondWithMethodNotFound(FULL)

        assertEquals(emptyList(), asked.await())
        advanceBy(10_000)
        assertNothingSent("it said it has no such method")
    }

    @Test
    fun `a request called off is cancelled on the server`() = withServer("ab") {
        open()

        val asked = async { service.semanticTokens(doc) }
        val request = nextSent()
        assertEquals(FULL, request.method)
        asked.cancel()

        val cancel = nextSent()
        assertEquals("\$/cancelRequest", cancel.method)
        assertEquals(request["id"], cancel.params["id"])
    }

    @Test
    fun `the answer comes through a composite service`() = withServer("ab") {
        open()
        val composite = CompositeLanguageService(service, service)

        val server = async { respond(FULL, """{"data":[0, 0, 2, 2, 0]}""") }
        val tokens = composite.semanticTokens(doc)
        server.await()

        assertEquals(listOf(0..1 to "variable"), tokens.spans)
    }
}
