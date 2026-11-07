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
package com.aardarch.aardink.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CodeLens
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.languages.BuiltInLanguages
import com.aardarch.aardink.languages.lsp.ChannelLspTransport
import com.aardarch.aardink.languages.lsp.LspClient
import com.aardarch.aardink.languages.lsp.LspLanguageService
import com.aardarch.aardink.languages.lsp.LspRequestException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Code lenses through the whole path: the real [CodeEditorLayout] and [LspLanguageService] with a
 * server on the other end of the wire, because a fake service here says nothing about what the
 * adapter does in the order the editor really calls it.
 */
@OptIn(ExperimentalTestApi::class)
class LspCodeLensUiTest {

    private val method = "textDocument/codeLens"
    private val resolve = "codeLens/resolve"
    private val execute = "workspace/executeCommand"

    private val options = EditorOptions(codeLens = true)

    private class Server(
        private val transport: ChannelLspTransport,
        private val capabilities: String,
        private val refusesHandshake: Boolean,
        private val holding: String?,
        private val lenses: (asked: Int) -> String,
    ) {
        /** Completed to let the [holding] method have its answer. */
        val released = CompletableDeferred<Unit>()

        val methods = CopyOnWriteArrayList<String>()

        /** Every request as it came, for what it held. */
        val requests = CopyOnWriteArrayList<JsonObject>()

        /** The lines asked about with `codeLens/resolve`, in order. */
        val resolved = CopyOnWriteArrayList<Int>()

        suspend fun serve() {
            for (payload in transport.sendChannel) {
                val message = Json.parseToJsonElement(payload).jsonObject
                val method = message["method"]?.jsonPrimitive?.content ?: continue
                methods += method
                requests += message
                val id = message["id"] ?: continue
                if (method == holding) released.await()
                if (method == "initialize" && refusesHandshake) {
                    transport.receiveChannel.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":"no"}}""")
                    continue
                }
                val result = when (method) {
                    "initialize" -> """{"capabilities":$capabilities}"""
                    "textDocument/codeLens" -> lenses(methods.count { it == method })
                    "codeLens/resolve" -> resolved(message["params"]!!.jsonObject)
                    else -> "null"
                }
                transport.receiveChannel.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            }
        }

        private fun resolved(lens: JsonObject): String {
            val line = lens["range"]!!.jsonObject["start"]!!.jsonObject["line"]!!.jsonPrimitive.int
            resolved += line
            return """{"range":${lens["range"]},"command":{"title":"${lens["data"]!!.jsonPrimitive.content} references","command":"run"}}"""
        }
    }

    private class Connection(
        capabilities: String = RESOLVING,
        refusesHandshake: Boolean = false,
        holding: String? = null,
        lenses: (asked: Int) -> String,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = ChannelLspTransport()
        val client = LspClient(transport, scope)
        val service = LspLanguageService(client, "file:///Main.kt", "kotlin")
        val server = Server(transport, capabilities, refusesHandshake, holding, lenses).also { server -> scope.launch { server.serve() } }

        fun close() = scope.cancel()
    }

    private fun lens(line: Int, title: String, command: String = "run") =
        """{"range":${range(line)},"command":{"title":"$title","command":"$command","arguments":[$line]}}"""

    private fun unresolved(line: Int) = """{"range":${range(line)},"data":$line}"""

    private fun ComposeUiTest.show(state: CodeEditorState, service: LanguageService) {
        state.computeDispatcher = Dispatchers.Unconfined
        setContent {
            CodeEditorLayout(state = state, languageService = service, options = options, modifier = Modifier.size(600.dp, 400.dp))
        }
        waitForIdle()
    }

    private fun ComposeUiTest.pause() {
        mainClock.advanceTimeBy(400)
        waitForIdle()
    }

    private fun onLens(density: Density) = with(density) { Offset(8.dp.toPx() + 6f, 8.dp.toPx() + 10.dp.toPx()) }

    @Test
    fun `a server's lenses are on show, and a click runs one on the server`() = runComposeUiTest {
        val connection = Connection(ALL_RESOLVED) { "[${lens(0, "Run test")}]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("fun main() {}")
            connection.service.attach(state)
            show(state, connection.service)
            pause()
            waitUntil { state.codeLenses.isNotEmpty() }
            assertEquals(listOf("Run test"), state.codeLenses.map { it.title })

            onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { click(onLens(this)) }
            waitForIdle()

            waitUntil { execute in connection.server.methods }
            val sent = connection.server.requests.last { it["method"]!!.jsonPrimitive.content == execute }["params"]!!.jsonObject
            assertEquals("run", sent["command"]!!.jsonPrimitive.content)
            assertEquals("[0]", sent["arguments"].toString())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a tap runs one too`() = runComposeUiTest {
        val connection = Connection(ALL_RESOLVED) { "[${lens(0, "Run test")}]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("fun main() {}")
            connection.service.attach(state)
            show(state, connection.service)
            pause()
            waitUntil { state.codeLenses.isNotEmpty() }

            onNodeWithTag(EditorTestTags.EDITOR).performTouchInput { click(onLens(this)) }
            waitForIdle()

            waitUntil { execute in connection.server.methods }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `typing is sent before the next request, and the answer replaces the last`() = runComposeUiTest {
        val connection = Connection(ALL_RESOLVED) { asked -> if (asked == 1) "[${lens(0, "first")}]" else "[${lens(0, "second")}]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("ab")
            connection.service.attach(state)
            show(state, connection.service)
            pause()
            waitUntil { state.codeLenses.isNotEmpty() }

            state.selection = TextRange(2)
            onNodeWithTag(EditorTestTags.EDITOR).performTextInput("c")
            waitForIdle()
            pause()

            waitUntil { state.codeLenses.map { it.title } == listOf("second") }
            val methods = connection.server.methods.filter { it == method || it.startsWith("textDocument/did") }
            assertEquals(listOf("textDocument/didOpen", method, "textDocument/didChange", method), methods)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `an editor up before the server gets its lenses without an edit`() = runComposeUiTest {
        val connection = Connection(ALL_RESOLVED) { "[${lens(0, "lens")}]" }
        try {
            val state = CodeEditorState("name")
            show(state, connection.service)
            pause()
            assertTrue(method !in connection.server.methods, "there is no document on the server to ask about")

            connection.client.initialize(rootUri = null)
            connection.service.attach(state)

            waitUntil { state.codeLenses.isNotEmpty() }
            assertEquals(listOf("lens"), state.codeLenses.map { it.title })
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a server without the provider is never asked, and shows nothing`() = runComposeUiTest {
        val connection = Connection("""{"textDocumentSync":2}""") { "[]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("name")
            connection.service.attach(state)
            show(state, connection.service)

            pause()

            assertTrue(method !in connection.server.methods)
            assertTrue(state.codeLenses.isEmpty())
        } finally {
            connection.close()
        }
    }

    // ── Resolving what is on screen ──────────────────────────────────────────

    private val lines = 2_000
    private val everyTwenty = (0 until lines step 20).toList()

    private suspend fun ComposeUiTest.showMany(connection: Connection): CodeEditorState {
        connection.client.initialize(rootUri = null)
        val state = CodeEditorState((0 until lines).joinToString("\n") { "line $it" })
        connection.service.attach(state)
        show(state, connection.service)
        pause()
        waitUntil { state.codeLenses.isNotEmpty() && state.codeLenses.first().title.isNotEmpty() }
        return state
    }

    private fun CodeEditorState.lineOf(lens: CodeLens) = document.offsetToLineCol(lens.offset).first

    @Test
    fun `only what is around the view is resolved, and every lens has its row from the start`() = runComposeUiTest {
        val connection = Connection { "[${everyTwenty.joinToString(",") { unresolved(it) }}]" }
        try {
            val state = showMany(connection)

            assertEquals(everyTwenty.size, state.codeLenses.size, "lenses not yet resolved are still there")
            val resolved = connection.server.resolved.toList()
            assertTrue(0 in resolved, "the top of the document is on screen")
            assertTrue(resolved.size in 1..6, "a screen and a screen below of lenses, of ${everyTwenty.size}: $resolved")
            assertEquals(resolved.sorted(), state.codeLenses.filter { it.title.isNotEmpty() }.map { state.lineOf(it) })
        } finally {
            connection.close()
        }
    }

    @Test
    fun `scrolling to a lens not yet resolved resolves it, without asking for the lenses again or moving anything`() = runComposeUiTest {
        val connection = Connection { "[${everyTwenty.joinToString(",") { unresolved(it) }}]" }
        try {
            val state = showMany(connection)
            val height = state.viewport.contentHeight
            val first = connection.server.resolved.size

            state.execute(EditorCommand.ScrollTo(height / 2f))
            waitForIdle()
            pause()
            waitUntil { connection.server.resolved.size > first }
            waitUntil {
                state.codeLenses.any {
                    it.title.isNotEmpty() &&
                        state.lineOf(it) in state.viewport.firstVisibleLine..state.viewport.lastVisibleLine
                }
            }

            assertEquals(1, connection.server.methods.count { it == method }, "the answer stands: the text is the same")
            assertEquals(height, state.viewport.contentHeight, "the rows were all there")
            val later = connection.server.resolved.drop(first)
            assertEquals(later.distinct(), later, "nothing resolved twice")
            assertTrue(later.all { it > lines / 4 }, "around the view: $later")
        } finally {
            connection.close()
        }
    }

    @Test
    fun `an edit asks for the lenses again, and resolves what is on screen`() = runComposeUiTest {
        val connection = Connection { "[${everyTwenty.joinToString(",") { unresolved(it) }}]" }
        try {
            val state = showMany(connection)
            val first = connection.server.resolved.size

            state.selection = TextRange(0)
            onNodeWithTag(EditorTestTags.EDITOR).performTextInput("x")
            waitForIdle()
            pause()

            waitUntil { connection.server.methods.count { it == method } == 2 }
            waitUntil { connection.server.resolved.size == first * 2 }
            waitUntil { state.codeLenses.first().title.isNotEmpty() }
        } finally {
            connection.close()
        }
    }

    // ── With a host's own ────────────────────────────────────────────────────

    /** A host's service for its dialect, with a lens at [offset] whatever it is asked. */
    private fun dialect(offset: Int, title: String) = object : LanguageService by BuiltInLanguages.Kotlin.languageService!! {
        override suspend fun codeLenses(document: CodeDocument): List<CodeLens> = listOf(CodeLens(offset, title))
    }

    /** Long enough for the editor to ask, and to stop waiting for the rest of the answer. */
    private fun ComposeUiTest.outwait() {
        pause()
        mainClock.advanceTimeBy(1_100)
        waitForIdle()
    }

    @Test
    fun `a host's lenses do not wait on a server that has not answered initialize`() = runComposeUiTest {
        val connection = Connection(ALL_RESOLVED, holding = "initialize") { "[${lens(0, "server")}]" }
        try {
            val state = CodeEditorState("name")
            show(state, CompositeLanguageService(dialect(1, "own"), connection.service))
            connection.scope.launch {
                connection.client.initialize(rootUri = null)
                connection.service.attach(state)
            }

            outwait()

            waitUntil { state.codeLenses.isNotEmpty() }
            assertEquals(listOf("own"), state.codeLenses.map { it.title })
            assertTrue(method !in connection.server.methods)

            connection.server.released.complete(Unit)

            waitUntil { state.codeLenses.size == 2 }
            assertEquals(listOf("own", "server"), state.codeLenses.map { it.title }, "with no edit since")
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a host's lenses do not wait on a server stuck on the request`() = runComposeUiTest {
        val connection = Connection(ALL_RESOLVED, holding = method) { "[]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("name")
            connection.service.attach(state)
            show(state, CompositeLanguageService(dialect(1, "own"), connection.service))

            outwait()

            waitUntil { state.codeLenses.isNotEmpty() }
            assertEquals(listOf("own"), state.codeLenses.map { it.title })
        } finally {
            connection.server.released.complete(Unit)
            connection.close()
        }
    }

    @Test
    fun `a handshake that fails after the editor asked gives a composite's fallback its lenses`() = runComposeUiTest {
        val connection = Connection(refusesHandshake = true) { "[]" }
        try {
            val state = CodeEditorState("name")
            show(state, CompositeLanguageService(connection.service, dialect(1, "dialect")))
            pause()
            assertTrue(state.codeLenses.isEmpty(), "the server may yet be coming up")

            assertFailsWith<LspRequestException> { connection.client.initialize(rootUri = null) }

            waitUntil { state.codeLenses.isNotEmpty() }
            assertEquals(listOf("dialect"), state.codeLenses.map { it.title })
            assertTrue(method !in connection.server.methods)
        } finally {
            connection.close()
        }
    }

    private companion object {
        private const val COMMANDS = """"executeCommandProvider":{"commands":["run"]}"""
        const val RESOLVING = """{"textDocumentSync":2,"codeLensProvider":{"resolveProvider":true},$COMMANDS}"""
        const val ALL_RESOLVED = """{"textDocumentSync":2,"codeLensProvider":{},$COMMANDS}"""

        fun range(line: Int) = """{"start":{"line":$line,"character":0},"end":{"line":$line,"character":1}}"""
    }
}
