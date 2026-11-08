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

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeLens
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.LanguageService
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val METHOD = "textDocument/codeLens"
private const val RESOLVE = "codeLens/resolve"
private const val EXECUTE = "workspace/executeCommand"
private const val CANCEL = "\$/cancelRequest"
private const val CONTENT_MODIFIED = -32801

private const val LENS_SERVER = """{"textDocumentSync":2,"codeLensProvider":{},"executeCommandProvider":{"commands":["run"]}}"""
private const val RESOLVING_SERVER = """{"textDocumentSync":2,"codeLensProvider":{"resolveProvider":true}}"""

private fun command(title: String, id: String = "run", members: String = "") = """{"title":"$title","command":"$id"$members}"""

private fun lens(line: Int, character: Int, command: String? = null, members: String = "") =
    """{"range":${range(line, character, line, character + 1)}${command?.let { ""","command":$it""" }.orEmpty()}$members}"""

/** The server knows the document, by the host's word. */
private suspend fun Harness.open(capabilities: String = LENS_SERVER) {
    serverDeclares(capabilities)
    service.didOpen(doc)
    assertEquals("textDocument/didOpen", nextSent().method)
}

/** Has the server answer [lensesJson], and returns what the service made of it. */
private suspend fun Harness.answered(lensesJson: String, document: CodeDocument = doc): List<CodeLens> {
    val server = async { respond(METHOD, lensesJson) }
    val lenses = service.codeLenses(document)
    server.await()
    return lenses
}

private val List<CodeLens>.seen: List<Pair<Int, String>> get() = map { it.offset to it.title }

class LspCodeLensTest {

    // ── The answer ───────────────────────────────────────────────────────────

    @Test
    fun `a lens's start becomes its offset, and its command's title its title`() = withServer("val a = 1\nval b = 2") {
        open()

        val lenses = answered("[${lens(0, 4, command("3 references"))},${lens(1, 4, command("Run"))}]")

        assertEquals(listOf(4 to "3 references", 14 to "Run"), lenses.seen)
    }

    @Test
    fun `the request names the document`() = withServer("abc") {
        open()

        val server = async { respond(METHOD, "[]") }
        service.codeLenses(doc)

        assertEquals(URI, server.await().params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `positions are UTF-16, as the document's offsets are`() = withServer("😀 = 1") {
        open()

        assertEquals(listOf(2 to "x"), answered("[${lens(0, 2, command("x"))}]").seen)
    }

    @Test
    fun `no lenses, or a null answer, is none`() = withServer("abc") {
        open()

        assertEquals(emptyList(), answered("null"))
        assertEquals(emptyList(), answered("[]"))
    }

    @Test
    fun `what cannot be read is left out, and the rest kept`() = withServer("abc") {
        open()

        val noRange = """{"command":${command("lost")}}"""

        assertEquals(listOf(1 to "kept"), answered("""[$noRange,"text",${lens(0, 1, command("kept"))}]""").seen)
    }

    @Test
    fun `a server that did not declare lenses is not asked`() = withServer("abc") {
        serverDeclares("""{"textDocumentSync":2}""")
        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)

        assertEquals(emptyList(), service.codeLenses(doc))

        assertNothingSent("no request for what the server does not do")
    }

    @Test
    fun `a request waits for the document to open, behind a server still starting up`() = withServer("abc") {
        val asked = async { service.codeLenses(doc) }
        assertNothingSent("the client is not even initialized")

        serverDeclares(LENS_SERVER)
        assertNothingSent("the server has not heard of the document")

        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)
        respond(METHOD, "[${lens(0, 1, command("x"))}]")

        assertEquals(listOf(1 to "x"), asked.await().seen)
    }

    @Test
    fun `a request refused while the server starts up is asked again`() = withServer("abc") {
        open()

        val asked = async { service.codeLenses(doc) }
        respondWithError(METHOD, CONTENT_MODIFIED)
        advanceBy(1_000)
        respond(METHOD, "[${lens(0, 1, command("x"))}]")

        assertEquals(listOf(1 to "x"), asked.await().seen)
    }

    @Test
    fun `cancelling a request tells the server`() = withServer("abc") {
        open()

        val asked = async { service.codeLenses(doc) }
        val sent = nextSent()
        assertEquals(METHOD, sent.method)
        asked.cancel()
        settle()

        val cancelled = nextSent()
        assertEquals("\$/cancelRequest", cancelled.method)
        assertEquals(sent["id"], cancelled.params["id"])
    }

    // ── Resolving ────────────────────────────────────────────────────────────

    @Test
    fun `a lens with no command is resolved, sent back as it came, data and all`() = withServer("abc") {
        open(RESOLVING_SERVER)

        val raw = lens(0, 1, members = ""","data":{"id":7}""")
        val asked = async { service.codeLenses(doc) }
        respond(METHOD, "[$raw]")
        val sent = respond(RESOLVE, lens(0, 1, command("7 references")))

        assertEquals(listOf(1 to "7 references"), asked.await().seen)
        assertEquals(LspJson.parseToJsonElement(raw), sent.params)
    }

    @Test
    fun `one that came with its command is not resolved`() = withServer("abc") {
        open(RESOLVING_SERVER)

        assertEquals(listOf(1 to "x"), answered("[${lens(0, 1, command("x"))}]").seen)

        assertNothingSent("nothing to resolve")
    }

    @Test
    fun `a server that does not resolve has its lenses without a command left out, and is not asked`() = withServer("abc") {
        open()

        assertEquals(listOf(2 to "kept"), answered("[${lens(0, 1)},${lens(0, 2, command("kept"))}]").seen)

        assertNothingSent("no resolve from a server that did not declare it")
    }

    @Test
    fun `a server that registered lenses as resolving them is asked to`() = withServer("abc") {
        serverDeclares("""{"textDocumentSync":2}""")
        serverRegisters(registration(METHOD, """{"resolveProvider":true}"""))
        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)

        val asked = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 1)}]")
        respond(RESOLVE, lens(0, 1, command("x")))

        assertEquals(listOf(1 to "x"), asked.await().seen)
    }

    @Test
    fun `a server that registered lenses without resolving them is not asked to, by LSP's default`() {
        for (options in listOf("{}", """{"resolveProvider":false}""")) {
            withServer("abc") {
                serverDeclares("""{"textDocumentSync":2}""")
                serverRegisters(registration(METHOD, options))
                service.didOpen(doc)
                assertEquals("textDocument/didOpen", nextSent().method)

                assertEquals(listOf(2 to "kept"), answered("[${lens(0, 1)},${lens(0, 2, command("kept"))}]").seen)

                assertNothingSent("no resolve from a server that registered $options")
            }
        }
    }

    @Test
    fun `a lens that resolves to no command, or to a method not found, or to an error is left out`() = withServer("abc") {
        open(RESOLVING_SERVER)

        val asked = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 0)},${lens(0, 1)},${lens(0, 2)},${lens(0, 3, command("kept"))}]")
        respond(RESOLVE, lens(0, 0))
        respondWithMethodNotFound(RESOLVE)
        respondWithError(RESOLVE)

        assertEquals(listOf(3 to "kept"), asked.await().seen)
    }

    @Test
    fun `a resolve that may pass is asked again, and past that keeps the lens's row without a title`() = withServer("abc") {
        open(RESOLVING_SERVER)

        val first = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 1)}]")
        respondWithError(RESOLVE, CONTENT_MODIFIED)
        assertEquals(listOf(1 to ""), first.await().seen, "the row does not wait for the retries")

        val again = async { service.codeLenses(doc) }
        respondWithError(RESOLVE, CONTENT_MODIFIED)
        for (wait in listOf(1_000L, 2_000L, 4_000L)) {
            advanceBy(wait)
            respondWithError(RESOLVE, CONTENT_MODIFIED)
        }

        assertEquals(listOf(1 to ""), again.await().seen)
    }

    @Test
    fun `asked again about the same text, only what is left is resolved, and the lenses are not asked for`() = withServer("ab") {
        open(RESOLVING_SERVER)

        val first = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 0)},${lens(0, 1)}]")
        respond(RESOLVE, lens(0, 0, command("resolved")))
        respondWithError(RESOLVE, CONTENT_MODIFIED)
        assertEquals(listOf(0 to "resolved", 1 to ""), first.await().seen)

        val second = async { service.codeLenses(doc) }
        respond(RESOLVE, lens(0, 1, command("now")))

        assertEquals(listOf(0 to "resolved", 1 to "now"), second.await().seen)
    }

    @Test
    fun `other text is asked about from the start`() = withServer("abc") {
        open(RESOLVING_SERVER)
        assertEquals(listOf(1 to "x"), answered("[${lens(0, 1, command("x"))}]").seen)

        assertEquals(listOf(2 to "y"), answered("[${lens(0, 2, command("y"))}]", CodeDocument("abcd")).seen)
    }

    @Test
    fun `a closed document's lenses are not kept`() = withServer("abc") {
        open(RESOLVING_SERVER)
        answered("[${lens(0, 1, command("x"))}]")

        val closing = async { service.didClose() }
        assertEquals("textDocument/didClose", nextSent().method)
        closing.await()
        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)

        assertEquals(listOf(2 to "y"), answered("[${lens(0, 2, command("y"))}]").seen)
    }

    @Test
    fun `with no view to go by every lens is resolved, a few at a time`() = withServer("x".repeat(100)) {
        open(RESOLVING_SERVER)

        val count = 40
        val asked = async { service.codeLenses(doc) }
        respond(METHOD, (0 until count).joinToString(",", "[", "]") { lens(0, it) })
        settle()

        val out = ArrayList<JsonObject>()
        while (true) out += transport.sendChannel.tryReceive().getOrNull()?.let { LspJson.parseToJsonElement(it).jsonObject } ?: break
        assertEquals(16, out.size, "no more than this on the wire at once")

        var answered = 0
        while (answered < count) {
            val sent = out.removeFirstOrNull() ?: nextSent()
            assertEquals(RESOLVE, sent.method)
            val at = sent.params["range"]!!.jsonObject["start"]!!.jsonObject["character"]!!.jsonPrimitive.int
            serverSends("""{"jsonrpc":"2.0","id":${sent["id"]},"result":${lens(0, at, command("t$at"))}}""")
            answered++
        }

        assertEquals((0 until count).map { it to "t$it" }, asked.await().seen)
    }

    @Test
    fun `what resolved before a question is called off is kept for the next`() = withServer("abc") {
        open(RESOLVING_SERVER)

        val first = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 0)},${lens(0, 1)}]")
        respond(RESOLVE, lens(0, 0, command("kept")))
        val stuck = nextSent()
        assertEquals(RESOLVE, stuck.method)
        settle()
        first.cancel()
        settle()
        assertEquals("\$/cancelRequest", nextSent().method)

        val second = async { service.codeLenses(doc) }
        respond(RESOLVE, lens(0, 1, command("next")))

        assertEquals(listOf(0 to "kept", 1 to "next"), second.await().seen)
    }

    // ── What a resolve can hold back ─────────────────────────────────────────

    @Test
    fun `a resolve never answered holds back neither the rows nor the lenses with a command`() = withServer("abc") {
        open(RESOLVING_SERVER)

        val asked = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 0, command("came with it"))},${lens(0, 1)}]")
        val stuck = nextSent()
        assertEquals(RESOLVE, stuck.method)

        advanceBy(499)
        assertFalse(asked.isCompleted, "an answer in time keeps a row from going blank")
        advanceBy(1)

        assertEquals(listOf(0 to "came with it", 1 to ""), asked.await().seen)
        val cancelled = nextSent()
        assertEquals(CANCEL, cancelled.method)
        assertEquals(stuck["id"], cancelled.params["id"])
    }

    @Test
    fun `what did resolve is not held back by what did not`() = withServer("ab") {
        open(RESOLVING_SERVER)

        val asked = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 0)},${lens(0, 1)}]")
        respond(RESOLVE, lens(0, 0, command("in time")))
        assertEquals(RESOLVE, nextSent().method)
        advanceBy(500)

        assertEquals(listOf(0 to "in time", 1 to ""), asked.await().seen)
    }

    @Test
    fun `with the rows given, asking again waits for a resolve past the patience, as they cost nothing now`() = withServer("abc") {
        open(RESOLVING_SERVER)
        val first = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 1)}]")
        assertEquals(RESOLVE, nextSent().method)
        advanceBy(500)
        assertEquals(listOf(1 to ""), first.await().seen)
        assertEquals(CANCEL, nextSent().method)

        val again = async { service.codeLenses(doc) }
        val slow = nextSent()
        assertEquals(RESOLVE, slow.method)
        advanceBy(60_000)
        assertFalse(again.isCompleted, "it would only be cancelled again: this one takes the server a while")
        serverSends("""{"jsonrpc":"2.0","id":${slow["id"]},"result":${lens(0, 1, command("slow"))}}""")

        assertEquals(listOf(1 to "slow"), again.await().seen)
    }

    @Test
    fun `with the rows given, the first to answer starts the patience for the rest`() = withServer("ab") {
        open(RESOLVING_SERVER)
        val first = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 0)},${lens(0, 1)}]")
        assertEquals(RESOLVE, nextSent().method)
        assertEquals(RESOLVE, nextSent().method)
        advanceBy(500)
        first.await()
        assertEquals(CANCEL, nextSent().method)
        assertEquals(CANCEL, nextSent().method)

        val again = async { service.codeLenses(doc) }
        respond(RESOLVE, lens(0, 0, command("first")))
        assertEquals(RESOLVE, nextSent().method)
        advanceBy(499)
        assertFalse(again.isCompleted)
        advanceBy(1)

        assertEquals(listOf(0 to "first", 1 to ""), again.await().seen)
    }

    @Test
    fun `other text starts over, and its rows are not held by a resolve`() = withServer("abc") {
        open(RESOLVING_SERVER)
        val first = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 1)}]")
        assertEquals(RESOLVE, nextSent().method)
        advanceBy(500)
        first.await()
        assertEquals(CANCEL, nextSent().method)

        val other = async { service.codeLenses(CodeDocument("abcd")) }
        respond(METHOD, "[${lens(0, 2)}]")
        assertEquals(RESOLVE, nextSent().method)
        advanceBy(500)

        assertEquals(listOf(2 to ""), other.await().seen)
    }

    // ── Clicks ───────────────────────────────────────────────────────────────

    private suspend fun Harness.clicked(lens: CodeLens): JsonObject {
        lens.onClick()
        return nextSent()
    }

    @Test
    fun `a click sends the command with its arguments, as they came`() = withServer("abc") {
        open()
        val arguments = """["a",1,{"b":[true]}]"""
        val lens = answered("""[${lens(0, 1, command("Run", members = ""","arguments":$arguments"""))}]""").single()

        val sent = clicked(lens)

        assertEquals(EXECUTE, sent.method)
        assertEquals("run", sent.params["command"]!!.jsonPrimitive.content)
        assertEquals(LspJson.parseToJsonElement(arguments), sent.params["arguments"])
    }

    @Test
    fun `a command with no arguments sends none`() = withServer("abc") {
        open()
        val lens = answered("[${lens(0, 1, command("Run"))}]").single()

        assertNull(clicked(lens).params["arguments"])
    }

    @Test
    fun `a resolved lens's click sends what it resolved to`() = withServer("abc") {
        open("""{"textDocumentSync":2,"codeLensProvider":{"resolveProvider":true},"executeCommandProvider":{"commands":["late"]}}""")
        val asked = async { service.codeLenses(doc) }
        respond(METHOD, "[${lens(0, 1)}]")
        respond(RESOLVE, lens(0, 1, command("Run", "late", ""","arguments":[1]""")))

        val sent = clicked(asked.await().single())

        assertEquals("late", sent.params["command"]!!.jsonPrimitive.content)
        assertEquals(1, sent.params["arguments"]!!.jsonArray.size)
    }

    @Test
    fun `a click goes after the edits made before it`() = withServer("abc") {
        serverDeclares(LENS_SERVER)
        val state = editorState("abc")
        service.attach(state)
        assertEquals("textDocument/didOpen", nextSent().method)
        val document = state.document.snapshot()
        val lens = answered("[${lens(0, 1, command("Run"))}]", document).single()

        state.applyEdit(0, 0, "x", TextRange(1))
        lens.onClick()
        advanceBy(1_000)

        assertEquals("textDocument/didChange", nextSent().method)
        assertEquals(EXECUTE, nextSent().method)
    }

    @Test
    fun `a server that declared no commands hears none`() = withServer("abc") {
        open("""{"textDocumentSync":2,"codeLensProvider":{}}""")
        val lens = answered("[${lens(0, 1, command("Run"))}]").single()

        lens.onClick()

        assertNothingSent("the server said it runs nothing")
    }

    @Test
    fun `a command the server did not list is its client's, and is not sent`() = withServer("abc") {
        open()
        val lens = answered("[${lens(0, 1, command("Show references", "client.showReferences"))}]").single()

        assertEquals("Show references", lens.title, "it is still something to read")
        lens.onClick()

        assertNothingSent("not one of the commands it declared")
    }

    @Test
    fun `a server that registered commands is sent them`() = withServer("abc") {
        serverDeclares("""{"textDocumentSync":2,"codeLensProvider":{}}""")
        serverRegisters(registration(EXECUTE, """{"commands":["other","run"]}"""))
        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)
        val lens = answered("[${lens(0, 1, command("Run"))}]").single()

        assertEquals(EXECUTE, clicked(lens).method)
    }

    @Test
    fun `a command a registration does not list is its client's, and is not sent`() = withServer("abc") {
        serverDeclares("""{"textDocumentSync":2,"codeLensProvider":{}}""")
        serverRegisters(registration(EXECUTE, """{"commands":["other"]}"""))
        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)
        val lens = answered("[${lens(0, 1, command("Show references", "client.showReferences"))}]").single()

        lens.onClick()

        assertNothingSent("not one of the commands it registered")
    }

    @Test
    fun `a command is sent if a registration lists it and what was declared does not`() = withServer("abc") {
        serverDeclares("""{"textDocumentSync":2,"codeLensProvider":{},"executeCommandProvider":{"commands":["other"]}}""")
        serverRegisters(registration(EXECUTE, """{"commands":["run"]}"""))
        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)
        val lens = answered("[${lens(0, 1, command("Run"))}]").single()

        assertEquals(EXECUTE, clicked(lens).method)
    }

    @Test
    fun `capabilities not known, a command is sent as a lens is asked for`() = withServer("abc") {
        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)
        val lens = answered("[${lens(0, 1, command("Run"))}]").single()

        assertEquals(EXECUTE, clicked(lens).method)
    }

    @Test
    fun `a command that fails, or a connection gone, costs nothing`() = withServer("abc") {
        open()
        val lens = answered("[${lens(0, 1, command("Run"))}]").single()

        lens.onClick()
        respondWithError(EXECUTE)
        settle()

        client.stop()
        settle()
        lens.onClick()
        settle()
    }

    @Test
    fun `a command's edit comes back through the host while the command is out`() {
        val applied = ArrayList<LspWorkspaceEdit>()
        val host = object : LspClientHost {
            override suspend fun applyWorkspaceEdit(edit: LspWorkspaceEdit, label: String?): Boolean {
                applied += edit
                return true
            }
        }
        withServer("abc", host) {
            open()
            val lens = answered("[${lens(0, 1, command("Run"))}]").single()
            lens.onClick()
            val sent = nextSent()
            assertEquals(EXECUTE, sent.method)

            val edit = """{"changes":{"$URI":[{"range":${range(0, 0, 0, 0)},"newText":"x"}]}}"""
            serverAsks("\"edit\"", "workspace/applyEdit", """{"edit":$edit}""")
            val reply = nextSent()
            assertEquals("true", reply["result"]!!.jsonObject["applied"]!!.jsonPrimitive.content)
            serverSends("""{"jsonrpc":"2.0","id":${sent["id"]},"result":null}""")
            settle()

            assertEquals(1, applied.size)
        }
    }

    // ── With the other services ──────────────────────────────────────────────

    @Test
    fun `behind a composite a server's lenses follow the primary's`() = withServer("abc") {
        open()
        val own = object : LanguageService by service {
            override suspend fun codeLenses(document: CodeDocument): List<CodeLens> = listOf(CodeLens(0, "own"))
        }
        val composite = CompositeLanguageService(own, service)

        val server = async { respond(METHOD, "[${lens(0, 1, command("server"))}]") }
        val lenses = composite.codeLenses(doc)
        server.await()

        assertEquals(listOf(0 to "own", 1 to "server"), lenses.seen)
    }

    // ── What the client declares ─────────────────────────────────────────────

    @Test
    fun `the client says it takes lenses and runs commands, and not that it can be told to refresh them`() {
        val capabilities = DefaultClientCapabilities
        assertTrue("codeLens" in capabilities["textDocument"]!!.jsonObject)
        val workspace = capabilities["workspace"]!!.jsonObject
        assertTrue("executeCommand" in workspace)
        assertFalse("codeLens" in workspace)
    }
}
