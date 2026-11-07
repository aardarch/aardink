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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.InlayHint
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
 * Inlay hints through the whole path: the real [CodeEditorLayout] and [LspLanguageService] with a
 * server on the other end of the wire, because a fake service here says nothing about what the
 * adapter does in the order the editor really calls it.
 */
@OptIn(ExperimentalTestApi::class)
class LspInlayHintsUiTest {

    private val method = "textDocument/inlayHint"

    private val options = EditorOptions(inlayHints = true)

    /** A server over [transport] that declares [capabilities] and answers each request with what [hints] makes of its range. */
    private class Server(
        private val transport: ChannelLspTransport,
        private val capabilities: String,
        private val refusesHandshake: Boolean,
        private val holding: String?,
        private val hints: (asked: Int, startLine: Int, endLine: Int) -> String,
    ) {
        /** Completed to let the [holding] method have its answer. */
        val released = CompletableDeferred<Unit>()

        val methods = CopyOnWriteArrayList<String>()

        suspend fun serve() {
            for (payload in transport.sendChannel) {
                val message = Json.parseToJsonElement(payload).jsonObject
                val method = message["method"]?.jsonPrimitive?.content ?: continue
                methods += method
                val id = message["id"] ?: continue
                if (method == holding) released.await()
                if (method == "initialize" && refusesHandshake) {
                    transport.receiveChannel.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":"no"}}""")
                    continue
                }
                val result = when (method) {
                    "initialize" -> """{"capabilities":$capabilities}"""
                    "textDocument/inlayHint" -> answer(message["params"]!!.jsonObject)
                    else -> "null"
                }
                transport.receiveChannel.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            }
        }

        private fun answer(params: JsonObject): String {
            val range = params["range"]!!.jsonObject
            val start = range["start"]!!.jsonObject["line"]!!.jsonPrimitive.int
            val end = range["end"]!!.jsonObject["line"]!!.jsonPrimitive.int
            return hints(methods.count { it == "textDocument/inlayHint" }, start, end)
        }
    }

    private class Connection(
        capabilities: String = """{"textDocumentSync":2,"inlayHintProvider":true}""",
        refusesHandshake: Boolean = false,
        holding: String? = null,
        hints: (asked: Int, startLine: Int, endLine: Int) -> String,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = ChannelLspTransport()
        val client = LspClient(transport, scope)
        val service = LspLanguageService(client, "file:///Main.kt", "kotlin")
        val server = Server(transport, capabilities, refusesHandshake, holding, hints).also { server -> scope.launch { server.serve() } }

        fun close() = scope.cancel()
    }

    private fun hint(line: Int, character: Int, label: String) = """{"position":{"line":$line,"character":$character},"label":"$label"}"""

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

    @Test
    fun `a server's hints are on show`() = runComposeUiTest {
        val connection = Connection { _, _, _ -> "[${hint(0, 5, ": Int")}]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("val x = 1")
            connection.service.attach(state)
            show(state, connection.service)

            pause()

            waitUntil { state.inlayHints.isNotEmpty() }
            assertEquals(listOf(InlayHint(5, ": Int")), state.inlayHints)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `typing is sent before the next request, and the answer replaces the last`() = runComposeUiTest {
        val connection = Connection { asked, _, _ -> if (asked == 1) "[${hint(0, 1, "first")}]" else "[${hint(0, 2, "second")}]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("ab")
            connection.service.attach(state)
            show(state, connection.service)
            pause()
            waitUntil { state.inlayHints.isNotEmpty() }

            state.selection = TextRange(2)
            onNodeWithTag(EditorTestTags.EDITOR).performTextInput("c")
            waitForIdle()
            pause()

            waitUntil { state.inlayHints == listOf(InlayHint(2, "second")) }
            val methods = connection.server.methods.filter { it == method || it.startsWith("textDocument/did") }
            assertEquals(listOf("textDocument/didOpen", method, "textDocument/didChange", method), methods)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `an editor up before the server gets its hints without an edit`() = runComposeUiTest {
        val connection = Connection { _, _, _ -> "[${hint(0, 2, "h")}]" }
        try {
            val state = CodeEditorState("name")
            show(state, connection.service)
            pause()
            assertTrue(method !in connection.server.methods, "there is no document on the server to ask about")

            connection.client.initialize(rootUri = null)
            connection.service.attach(state)

            waitUntil { state.inlayHints.isNotEmpty() }
            assertEquals(listOf(InlayHint(2, "h")), state.inlayHints)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `scrolling past what was asked about asks the server about the new lines`() = runComposeUiTest {
        val connection = Connection { _, start, end -> "[${hint(start, 1, "from $start")},${hint(end, 1, "to $end")}]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState((0 until 2_000).joinToString("\n") { "line $it" })
            connection.service.attach(state)
            show(state, connection.service)
            pause()
            waitUntil { state.inlayHints.isNotEmpty() }
            assertEquals(1, connection.server.methods.count { it == method })

            onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { scroll(400f) }
            waitForIdle()
            pause()

            waitUntil { connection.server.methods.count { it == method } == 2 }
            waitUntil { state.inlayHints.first().label != "from 0" }
            val shown = state.visibleLines!!
            val first = state.document.offsetToLineCol(state.inlayHints[0].offset).first
            val last = state.document.offsetToLineCol(state.inlayHints[1].offset).first
            assertTrue(first <= shown.first && last >= shown.last, "$first to $last covers $shown")
            assertEquals("from $first", state.inlayHints[0].label)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a server without the provider is never asked, and shows nothing`() = runComposeUiTest {
        val connection = Connection("""{"textDocumentSync":2}""") { _, _, _ -> "[]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("name")
            connection.service.attach(state)
            show(state, connection.service)

            pause()

            assertTrue(method !in connection.server.methods)
            assertTrue(state.inlayHints.isEmpty())
        } finally {
            connection.close()
        }
    }

    /** A host's service for its dialect, saying [label] at [offset] whatever it is asked. */
    private fun dialect(offset: Int, label: String) = object : LanguageService by BuiltInLanguages.Kotlin.languageService!! {
        override suspend fun inlayHints(document: CodeDocument, range: IntRange): List<InlayHint> = listOf(InlayHint(offset, label))
    }

    /** Long enough for the editor to ask, and to stop waiting for the rest of the answer. */
    private fun ComposeUiTest.outwait() {
        pause()
        mainClock.advanceTimeBy(1_100)
        waitForIdle()
    }

    @Test
    fun `a host's hints do not wait on a server that has not answered initialize`() = runComposeUiTest {
        val connection = Connection(holding = "initialize") { _, _, _ -> "[${hint(0, 3, "server")}]" }
        try {
            val state = CodeEditorState("name")
            show(state, CompositeLanguageService(dialect(1, "own"), connection.service))
            connection.scope.launch {
                connection.client.initialize(rootUri = null)
                connection.service.attach(state)
            }

            outwait()

            waitUntil { state.inlayHints.isNotEmpty() }
            assertEquals(listOf(InlayHint(1, "own")), state.inlayHints)
            assertTrue(method !in connection.server.methods)

            connection.server.released.complete(Unit)

            waitUntil { state.inlayHints.size == 2 }
            assertEquals(listOf(InlayHint(1, "own"), InlayHint(3, "server")), state.inlayHints, "with no edit since")
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a host's hints do not wait on a server stuck on the request, and go on being asked for`() = runComposeUiTest {
        val connection = Connection(holding = method) { _, _, _ -> "[]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("name")
            connection.service.attach(state)
            var asked = 0
            val own = object : LanguageService by BuiltInLanguages.Kotlin.languageService!! {
                override suspend fun inlayHints(document: CodeDocument, range: IntRange): List<InlayHint> =
                    listOf(InlayHint(1, "asked ${++asked}"))
            }
            show(state, CompositeLanguageService(own, connection.service))

            outwait()
            waitUntil { state.inlayHints == listOf(InlayHint(1, "asked 1")) }

            state.selection = TextRange(4)
            onNodeWithTag(EditorTestTags.EDITOR).performTextInput("s")
            waitForIdle()
            outwait()

            waitUntil { state.inlayHints == listOf(InlayHint(1, "asked 2")) }
        } finally {
            connection.server.released.complete(Unit)
            connection.close()
        }
    }

    @Test
    fun `a handshake that fails after the editor asked gives a composite's fallback its hints`() = runComposeUiTest {
        val connection = Connection(refusesHandshake = true) { _, _, _ -> "[]" }
        try {
            val dialect = InlayHint(1, "dialect")
            val fallback = object : LanguageService by BuiltInLanguages.Kotlin.languageService!! {
                override suspend fun inlayHints(document: CodeDocument, range: IntRange): List<InlayHint> = listOf(dialect)
            }
            val state = CodeEditorState("name")
            show(state, CompositeLanguageService(connection.service, fallback))
            pause()
            assertTrue(state.inlayHints.isEmpty(), "the server may yet be coming up")

            assertFailsWith<LspRequestException> { connection.client.initialize(rootUri = null) }

            waitUntil { state.inlayHints.isNotEmpty() }
            assertEquals(listOf(dialect), state.inlayHints)
            assertTrue(method !in connection.server.methods)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `through a composite over a built-in language, as a dialect's service would be`() = runComposeUiTest {
        val connection = Connection { _, _, _ -> "[${hint(0, 2, "h")}]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("name")
            connection.service.attach(state)
            show(state, CompositeLanguageService(connection.service, BuiltInLanguages.Kotlin.languageService!!))

            pause()

            waitUntil { state.inlayHints.isNotEmpty() }
            assertEquals(listOf(InlayHint(2, "h")), state.inlayHints)
        } finally {
            connection.close()
        }
    }
}
