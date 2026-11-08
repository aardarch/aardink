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
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.TextEdit
import kotlinx.coroutines.async
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private const val METHOD = "textDocument/inlayHint"
private const val CONTENT_MODIFIED = -32801

private const val HINTS_SERVER = """{"textDocumentSync":2,"inlayHintProvider":true}"""

private fun hint(line: Int, character: Int, label: String = """"h"""", members: String = "") =
    """{"position":{"line":$line,"character":$character},"label":$label$members}"""

/** The server knows the document, by the host's word. */
private suspend fun Harness.open(capabilities: String = HINTS_SERVER) {
    serverDeclares(capabilities)
    service.didOpen(doc)
    assertEquals("textDocument/didOpen", nextSent().method)
}

private val Harness.whole: IntRange get() = 0 until doc.length

/** Has the server answer [hintsJson], and returns what the service made of it. */
private suspend fun Harness.answered(hintsJson: String, document: CodeDocument = doc): List<InlayHint> {
    val server = async { respond(METHOD, hintsJson) }
    val hints = service.inlayHints(document, 0 until document.length)
    server.await()
    return hints
}

class LspInlayHintsTest {

    // ── The answer ───────────────────────────────────────────────────────────

    @Test
    fun `a hint's position becomes an offset, and its string its label`() = withServer("val a = 1\nval b = 2") {
        open()

        assertEquals(
            listOf(InlayHint(5, ": Int"), InlayHint(15, ": Long")),
            answered("""[${hint(0, 5, "\": Int\"")},${hint(1, 5, "\": Long\"")}]"""),
        )
    }

    @Test
    fun `positions are UTF-16, as the document's offsets are`() = withServer("\uD83D\uDE00 = 1\n\uD83D\uDE00 = 2") {
        open()

        // The emoji is two code units: a server counting code points would say 1.
        assertEquals(listOf(InlayHint(2, "h")), answered("[${hint(0, 2)}]"))
    }

    @Test
    fun `the request asks about the range, end exclusive, in UTF-16`() = withServer("ab\n\uD83D\uDE00cd\nef") {
        open()

        val server = async { respond(METHOD, "[]") }
        service.inlayHints(doc, 3 until 7)
        val sent = server.await()

        assertEquals(URI, sent.params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        val range = sent.params["range"]!!.jsonObject
        val start = range["start"]!!.jsonObject
        val end = range["end"]!!.jsonObject
        assertEquals(1 to 0, start["line"]!!.jsonPrimitive.int to start["character"]!!.jsonPrimitive.int)
        assertEquals(1 to 4, end["line"]!!.jsonPrimitive.int to end["character"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the range of an empty line goes as an empty range`() = withServer("") {
        open()

        val server = async { respond(METHOD, "[]") }
        service.inlayHints(doc, whole)
        val range = server.await().params["range"]!!.jsonObject

        assertEquals(range["start"], range["end"])
    }

    @Test
    fun `label parts are joined, and what else they hold is not read`() = withServer("abc") {
        open()

        val parts = """[{"value":"x: "},{"value":"Int","tooltip":"a type","location":{"uri":"file:///a","range":${range(0, 0, 0, 0)}}}]"""

        assertEquals(listOf(InlayHint(1, "x: Int")), answered("[${hint(0, 1, parts)}]"))
    }

    @Test
    fun `padding becomes a space on its side`() = withServer("abc") {
        open()

        val padded = hint(0, 1, "\"x:\"", ""","paddingRight":true""")
        val both = hint(0, 2, "\"y\"", ""","paddingLeft":true,"paddingRight":true""")

        assertEquals(listOf(InlayHint(1, "x: "), InlayHint(2, " y ")), answered("[$padded,$both]"))
    }

    @Test
    fun `what cannot be read is left out, and the rest kept`() = withServer("abc") {
        open()

        val noPosition = """{"label":"lost"}"""
        val noLabel = """{"position":{"line":0,"character":0}}"""
        val emptyLabel = hint(0, 0, "\"\"")
        val noParts = hint(0, 0, "[]")

        assertEquals(listOf(InlayHint(2, "h")), answered("""[$noPosition,$noLabel,$emptyLabel,$noParts,"nonsense",${hint(0, 2)}]"""))
    }

    @Test
    fun `a position past the line is its end, and past the document the document's`() = withServer("ab\ncd") {
        open()

        assertEquals(listOf(InlayHint(2, "h"), InlayHint(5, "h")), answered("[${hint(0, 99)},${hint(9, 0)}]"))
    }

    @Test
    fun `a null answer is no hints`() = withServer("ab") {
        open()

        assertEquals(emptyList(), answered("null"))
    }

    // ── What the server has ──────────────────────────────────────────────────

    @Test
    fun `a server that declared options has the provider too`() = withServer("ab") {
        open("""{"textDocumentSync":2,"inlayHintProvider":{"resolveProvider":true}}""")

        assertEquals(listOf(InlayHint(1, "h")), answered("[${hint(0, 1)}]"))
    }

    @Test
    fun `a server without the provider is not asked`() = withServer("ab") {
        open("""{"textDocumentSync":2}""")

        assertEquals(emptyList(), service.inlayHints(doc, whole))
        assertNothingSent("it has no such thing")
    }

    @Test
    fun `the provider can come with a registration, and goes with it`() = withServer("ab") {
        open("""{"textDocumentSync":2}""")
        serverRegisters(registration(METHOD))

        assertEquals(listOf(InlayHint(1, "h")), answered("[${hint(0, 1)}]"))

        serverAsks("2", "client/unregisterCapability", """{"unregisterations":[{"id":"$METHOD","method":"$METHOD"}]}""")
        nextSent()
        assertEquals(emptyList(), service.inlayHints(doc, whole))
        assertNothingSent("taken back")
    }

    @Test
    fun `the default capabilities ask for hints, and make no promise of refreshing them`() {
        val inlayHint = DefaultClientCapabilities["textDocument"]!!.jsonObject["inlayHint"]!!.jsonObject

        assertFalse(inlayHint["dynamicRegistration"]!!.jsonPrimitive.boolean)
        assertFalse("resolveSupport" in inlayHint, "a hint comes whole: nothing resolves one")
        assertFalse("inlayHint" in DefaultClientCapabilities["workspace"]!!.jsonObject, "no refreshSupport: nothing could act on a refresh")
    }

    @Test
    fun `a refresh the client never offered is refused, as any unknown request`() = withServer("ab") {
        open()

        serverAsks("9", "workspace/inlayHint/refresh", "null")

        assertEquals(LspRequestException.METHOD_NOT_FOUND, nextSent()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    // ── The order on the wire ────────────────────────────────────────────────

    @Test
    fun `the request is written after the edit it is about`() = withServer("") {
        serverDeclares(HINTS_SERVER)
        val state = editorState("val x = 1")
        service.attach(state)
        assertEquals("textDocument/didOpen", nextSent().method)

        // What the editor does once typing pauses.
        state.applyTextEdits(listOf(TextEdit(4..3, "abc")))
        val snapshot = state.document.snapshot()
        val asked = async { service.inlayHints(snapshot, 0 until snapshot.length) }

        assertEquals("textDocument/didChange", nextSent().method)
        respond(METHOD, "[${hint(0, 8)}]")
        assertEquals(listOf(InlayHint(8, "h")), asked.await())
    }

    @Test
    fun `a stalled edit holds the request back`() = withServer("") {
        serverDeclares(HINTS_SERVER)
        val state = editorState("x")
        service.attach(state)
        assertEquals("textDocument/didOpen", nextSent().method)

        wire.hold("didChange")
        state.applyTextEdits(listOf(TextEdit(1..0, "y")))
        val snapshot = state.document.snapshot()
        val asked = async { service.inlayHints(snapshot, 0 until snapshot.length) }
        assertNothingSent("the edit is still on its way")

        wire.release()
        assertEquals("textDocument/didChange", nextSent().method)
        respond(METHOD, "[]")
        asked.await()
    }

    @Test
    fun `asked before the handshake, it waits for the document and goes once it is open`() = withServer("") {
        val state = editorState("name")
        val snapshot = state.document.snapshot()
        // The editor's first question, which an unedited document does not get again.
        val asked = async { service.inlayHints(snapshot, 0 until snapshot.length) }
        assertNothingSent("no document to ask about yet")

        serverDeclares(HINTS_SERVER)
        assertNothingSent("still not open")
        service.attach(state)

        assertEquals("textDocument/didOpen", nextSent().method)
        respond(METHOD, "[${hint(0, 4)}]")
        assertEquals(listOf(InlayHint(4, "h")), asked.await())
    }

    @Test
    fun `asked before the handshake of a server without the provider, nothing is sent`() = withServer("") {
        val state = editorState("name")
        val snapshot = state.document.snapshot()
        val asked = async { service.inlayHints(snapshot, 0 until snapshot.length) }
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

        val asked = async { service.inlayHints(doc, whole) }
        respondWithError(METHOD, CONTENT_MODIFIED)
        assertNothingSent("not at once")
        advanceBy(1_000)
        respond(METHOD, "[${hint(0, 1)}]")

        assertEquals(listOf(InlayHint(1, "h")), asked.await())
    }

    @Test
    fun `a request called off is cancelled on the server`() = withServer("ab") {
        open()

        val asked = async { service.inlayHints(doc, whole) }
        val request = nextSent()
        assertEquals(METHOD, request.method)
        asked.cancel()

        val cancel = nextSent()
        assertEquals("\$/cancelRequest", cancel.method)
        assertEquals(request["id"], cancel.params["id"])
    }

    // ── Over another service ─────────────────────────────────────────────────

    private val dialect = listOf(InlayHint(0, "dialect"))

    private fun Harness.overDialect() = CompositeLanguageService(
        service,
        object : LanguageService by service {
            override suspend fun inlayHints(document: CodeDocument, range: IntRange): List<InlayHint> = dialect
        },
    )

    @Test
    fun `a composite adds a fallback's hints to the server's`() = withServer("ab") {
        open()
        val composite = overDialect()

        val server = async { respond(METHOD, "[${hint(0, 1)}]") }
        val hints = composite.inlayHints(doc, whole)
        server.await()

        assertEquals(listOf(InlayHint(1, "h"), InlayHint(0, "dialect")), hints)
    }

    @Test
    fun `a failed handshake ends the wait, and the fallback answers`() = withServer("ab") {
        val composite = overDialect()
        val asked = async { composite.inlayHints(doc, whole) }
        assertNothingSent("no document to ask about yet")
        assertFalse(asked.isCompleted, "the handshake may yet go well")

        val handshake = async { assertFailsWith<LspRequestException> { client.initialize(rootUri = null) } }
        respondWithError("initialize")
        handshake.await()

        assertEquals(dialect, asked.await())
        assertNothingSent("a server that refused the handshake has no document")
    }
}
