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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenStyleResolver
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.BuiltInLanguages
import com.aardarch.aardink.languages.lsp.ChannelLspTransport
import com.aardarch.aardink.languages.lsp.DefaultClientCapabilities
import com.aardarch.aardink.languages.lsp.LspClient
import com.aardarch.aardink.languages.lsp.LspLanguageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Semantic highlighting through the whole path: the real [CodeEditorLayout] and
 * [LspLanguageService] with a server on the other end of the wire, because a fake service here says
 * nothing about what the adapter does in the order the editor really calls it.
 */
@OptIn(ExperimentalTestApi::class)
class LspSemanticHighlightingUiTest {

    private val method = "textDocument/semanticTokens/full"

    private val options = EditorOptions(semanticHighlighting = true)

    /** A server over [transport] that declares [capabilities] and answers each request for tokens with [data]. */
    private class Server(private val transport: ChannelLspTransport, private val capabilities: String, private val data: (Int) -> String) {
        val methods = CopyOnWriteArrayList<String>()

        suspend fun serve() {
            for (payload in transport.sendChannel) {
                val message = Json.parseToJsonElement(payload).jsonObject
                val method = message["method"]?.jsonPrimitive?.content ?: continue
                methods += method
                val id = message["id"] ?: continue
                val result = when (method) {
                    "initialize" -> """{"capabilities":$capabilities}"""
                    "textDocument/semanticTokens/full" -> """{"data":${data(methods.count { it == method })}}"""
                    else -> "null"
                }
                transport.receiveChannel.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            }
        }
    }

    private class Connection(capabilities: String, data: (Int) -> String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = ChannelLspTransport()
        val client = LspClient(transport, scope)
        val service = LspLanguageService(client, "file:///Main.kt", "kotlin")
        val server = Server(transport, capabilities, data).also { server -> scope.launch { server.serve() } }

        fun close() = scope.cancel()
    }

    private fun capabilities(types: String, sync: Int = 2) =
        """{"textDocumentSync":$sync,"semanticTokensProvider":{"legend":{"tokenTypes":$types,"tokenModifiers":[]},"full":true}}"""

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

    private fun ComposeUiTest.awaitLayer(line: Int, state: CodeEditorState): List<Token> {
        waitUntil { state.semanticStore?.tokensForLine(line).orEmpty().isNotEmpty() }
        return state.semanticStore!!.tokensForLine(line)
    }

    @Test
    fun `a server's tokens are coloured from the theme, over the syntax's`() = runComposeUiTest {
        val connection = Connection(capabilities("""["class"]""")) { "[0, 4, 1, 0, 0]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("val X = 1")
            connection.service.attach(state)
            show(state, connection.service)

            pause()

            val token = awaitLayer(0, state).single()
            assertEquals(Token(4, 5, NamedTokenType("type.class")), token)
            val theme = EditorThemes.VsCodeDark
            assertEquals(theme.tokenColors[TokenType.TypeName], TokenStyleResolver(theme).color(token.type))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `every token type the client offers finds a colour in the built-in themes`() = runComposeUiTest {
        val offered = DefaultClientCapabilities["textDocument"]!!.jsonObject["semanticTokens"]!!.jsonObject["tokenTypes"]!!.jsonArray
        val data = offered.indices.joinToString(prefix = "[", postfix = "]") { "0, 1, 1, $it, 0" }
        val connection = Connection(capabilities(offered.toString())) { data }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("x".repeat(offered.size + 1))
            connection.service.attach(state)
            show(state, connection.service)

            pause()

            val tokens = awaitLayer(0, state)
            assertEquals(offered.size, tokens.size)
            val themes = listOf(EditorThemes.VsCodeDark, EditorThemes.VsCodeLight, EditorThemes.MaterialDark, EditorThemes.SolarizedDark)
            for (theme in themes) {
                val resolver = TokenStyleResolver(theme)
                assertEquals(emptyList(), tokens.map { it.type }.filter { resolver.color(it) == null }, "without a colour")
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `typing is sent before the next request, and the answer replaces the last`() = runComposeUiTest {
        val answers = listOf("[0, 0, 1, 0, 0]", "[0, 1, 1, 0, 0]")
        val connection = Connection(capabilities("""["variable"]""")) { answers[it - 1] }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("ab")
            connection.service.attach(state)
            show(state, connection.service)
            pause()
            assertEquals(0, awaitLayer(0, state).single().start)

            state.selection = TextRange(2)
            onNodeWithTag(EditorTestTags.EDITOR).performTextInput("c")
            waitForIdle()
            pause()

            waitUntil { state.semanticStore!!.tokensForLine(0).single().start == 1 }
            val methods = connection.server.methods.filter { it == method || it.startsWith("textDocument/did") }
            assertEquals(listOf("textDocument/didOpen", method, "textDocument/didChange", method), methods)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `an editor up before the server gets its tokens without an edit`() = runComposeUiTest {
        val connection = Connection(capabilities("""["variable"]""")) { "[0, 0, 4, 0, 0]" }
        try {
            val state = CodeEditorState("name")
            show(state, connection.service)
            pause()
            assertTrue(method !in connection.server.methods, "there is no document on the server to ask about")

            connection.client.initialize(rootUri = null)
            connection.service.attach(state)

            assertEquals(Token(0, 4, NamedTokenType("variable")), awaitLayer(0, state).single())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a server without the provider is never asked, and leaves no layer`() = runComposeUiTest {
        val connection = Connection("""{"textDocumentSync":2}""") { "[]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("name")
            connection.service.attach(state)
            show(state, connection.service)

            pause()

            assertTrue(method !in connection.server.methods)
            assertEquals(emptyList(), state.semanticStore?.tokensForLine(0).orEmpty())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `through a composite over a built-in language, as a dialect's service would be`() = runComposeUiTest {
        val connection = Connection(capabilities("""["variable"]""")) { "[0, 0, 4, 0, 0]" }
        try {
            connection.client.initialize(rootUri = null)
            val state = CodeEditorState("name")
            connection.service.attach(state)
            show(state, CompositeLanguageService(connection.service, BuiltInLanguages.Kotlin.languageService!!))

            pause()

            assertEquals(Token(0, 4, NamedTokenType("variable")), awaitLayer(0, state).single())
        } finally {
            connection.close()
        }
    }
}
