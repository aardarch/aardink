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
package com.aardarch.aardink.web

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.NoOpFoldingProvider
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.languages.lsp.ChannelLspTransport
import com.aardarch.aardink.languages.lsp.LspLanguageService
import com.aardarch.aardink.languages.lsp.LspTransport
import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val EVERYTHING = """{
  "textDocumentSync": 2,
  "completionProvider": { "triggerCharacters": ["."], "resolveProvider": true },
  "hoverProvider": true,
  "definitionProvider": true,
  "documentFormattingProvider": true,
  "documentSymbolProvider": true,
  "foldingRangeProvider": true,
  "renameProvider": true
}"""

private const val SEMANTIC_TOKENS_METHOD = "textDocument/semanticTokens/full"

private const val SEMANTIC_TOKENS_SERVER = """{
  "textDocumentSync": 2,
  "semanticTokensProvider": { "legend": { "tokenTypes": ["variable"], "tokenModifiers": [] }, "full": true }
}"""

private const val INLAY_HINT_METHOD = "textDocument/inlayHint"

private const val INLAY_HINT_SERVER = """{ "textDocumentSync": 2, "inlayHintProvider": true }"""

private const val CODE_LENS_METHOD = "textDocument/codeLens"

private const val CODE_LENS_RESOLVE = "codeLens/resolve"

private const val EXECUTE_COMMAND = "workspace/executeCommand"

private const val CODE_LENS_SERVER =
    """{ "textDocumentSync": 2, "codeLensProvider": { "resolveProvider": true }, "executeCommandProvider": { "commands": ["server.run"] } }"""

private fun range(startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int): String =
    """{"start":{"line":$startLine,"character":$startCharacter},"end":{"line":$endLine,"character":$endCharacter}}"""

/**
 * A language server on the far end of a real [ChannelLspTransport]: everything these tests ask goes
 * through a real `LspClient` and `LspLanguageService` as far as that transport, so nothing about what
 * the adapter sends or answers is assumed.
 */
private class FakeServer(private val capabilities: String = EVERYTHING, private val answer: (String) -> String? = { null }) {
    val transport = ChannelLspTransport()
    val heard = mutableListOf<JsonObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            for (payload in transport.sendChannel) {
                val message = Json.parseToJsonElement(payload).jsonObject
                heard += message
                val id = message["id"] ?: continue
                val result = when (val method = message.method) {
                    "initialize" -> """{"capabilities":$capabilities}"""
                    else -> answer(method) ?: "null"
                }
                transport.receiveChannel.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            }
        }
    }

    val methods: List<String> get() = heard.map { it.method }

    fun params(method: String): List<JsonObject> = heard.filter { it.method == method }.map { it["params"]!!.jsonObject }

    fun pushDiagnostics(uri: String, message: String) {
        val params = """{"uri":"$uri","diagnostics":[{"range":${range(0, 0, 0, 1)},"message":"$message","severity":1}]}"""
        transport.receiveChannel.trySend("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":$params}""")
    }

    /** The socket going away under the client. */
    fun drop() {
        transport.receiveChannel.close()
    }

    fun stop() = scope.cancel()

    private val JsonObject.method: String get() = this["method"]?.jsonPrimitive?.content.orEmpty()
}

private val JsonObject.documentUri: String get() = this["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content

/** `connectLanguageServer` for real, on a transport a test holds: every path but the WebSocket's own. */
class WebLanguageServerTest {

    private val mounted = mutableListOf<AardinkEditorHandle>()
    private val containers = mutableListOf<HTMLElement>()
    private val servers = mutableListOf<FakeServer>()
    private val connections = mutableListOf<DisposableHandle>()

    @AfterTest
    fun tearDown() {
        connections.forEach { it.dispose() }
        mounted.forEach(AardinkWeb::dispose)
        containers.forEach { it.remove() }
        servers.forEach { it.stop() }
    }

    private fun mount(text: String, language: String): AardinkEditorHandle {
        val div = document.createElement("div") as HTMLElement
        div.id = "aardink-server-test-${containers.size}"
        div.style.width = "600px"
        div.style.height = "400px"
        document.body!!.appendChild(div)
        containers += div
        return AardinkWeb.mount(div.id, text, WebEditorOptions(language = language)).also { mounted += it }
    }

    private fun connect(languageId: String, server: FakeServer): DisposableHandle {
        servers += server
        return connectTo(languageId) { server.transport }
    }

    private fun connectTo(languageId: String, transport: suspend (String) -> LspTransport): DisposableHandle =
        AardinkWeb.connectLanguageServer("ws://test", languageId, transport).also { connections += it }

    private suspend fun awaitUntil(condition: () -> Boolean) = withContext(Dispatchers.Main) {
        withTimeout(5_000) {
            while (!condition()) delay(16)
        }
    }

    /** Real time, which `delay` in a test's own body is not. */
    private suspend fun pause(milliseconds: Long) = withContext(Dispatchers.Main) { delay(milliseconds) }

    private suspend fun served(handle: AardinkEditorHandle): LspLanguageService {
        awaitUntil { handle.languageServers.isNotEmpty() }
        return handle.languageServers.single().service
    }

    private fun language(id: String, providers: WebLanguageProviders = WebLanguageProviders(), extends: String? = null) {
        val base = if (extends != null) """"extends": "$extends", """ else ""
        AardinkWeb.registerLanguage("""{ "id": "$id", $base"grammar": { "tokenizer": { "root": [ ["\\w+", "text"] ] } } }""", providers)
    }

    private fun edit(handle: AardinkEditorHandle, at: Int, text: String) {
        handle.state.value.applyTextEdits(listOf(TextEdit(at until at, text)))
    }

    @Test
    fun `editors open already and created later are each a document of their own, in their language only`() = runTest {
        language("toy-open")
        val before = mount("before", "toy-open")
        val elsewhere = mount("{}", "json")
        val server = FakeServer()
        connect("toy-open", server)
        awaitUntil { "textDocument/didOpen" in server.methods }
        val after = mount("after", "toy-open")
        awaitUntil { server.methods.count { it == "textDocument/didOpen" } == 2 }

        val opened = server.params("textDocument/didOpen").map { it["textDocument"]!!.jsonObject }
        assertEquals(listOf("before", "after"), opened.map { it["text"]!!.jsonPrimitive.content })
        assertEquals(listOf("toy-open", "toy-open"), opened.map { it["languageId"]!!.jsonPrimitive.content })
        val uris = opened.map { it["uri"]!!.jsonPrimitive.content }
        assertNotEquals(uris[0], uris[1])
        assertEquals("initialize", server.methods.first())
        assertEquals("initialized", server.methods[1])
        assertEquals(1, before.languageServers.size)
        assertEquals(1, after.languageServers.size)
        assertTrue(elsewhere.languageServers.isEmpty())
    }

    @Test
    fun `an edit reaches the server ahead of the question asked after it`() = runTest {
        language("toy-order")
        val handle = mount("one", "toy-order")
        val server = FakeServer { method -> if (method == "textDocument/hover") """{"contents":"from the server"}""" else null }
        connect("toy-order", server)
        served(handle)

        edit(handle, 3, "x")
        val hover = withContext(Dispatchers.Main) { handle.languageService!!.hoverDoc(handle.state.value.document.snapshot(), 3) }

        assertEquals("from the server", hover?.content)
        assertTrue(
            server.methods.indexOf("textDocument/didChange") in 0 until server.methods.indexOf("textDocument/hover"),
            "${server.methods}",
        )
        val change = server.params("textDocument/didChange").single()["contentChanges"]!!.jsonArray.single().jsonObject
        assertEquals("x", change["text"]!!.jsonPrimitive.content)
        assertEquals(3, change["range"]!!.jsonObject["start"]!!.jsonObject["character"]!!.jsonPrimitive.int)
    }

    @Test
    fun `what the server answers reaches the editor's service, resolving and renaming included`() = runTest {
        language("toy-features")
        val handle = mount("name", "toy-features")
        var uri = ""
        val server = FakeServer { method ->
            when (method) {
                "textDocument/completion" -> """{"isIncomplete":false,"items":[{"label":"served","data":7}]}"""

                "completionItem/resolve" -> """{"label":"served","documentation":"resolved","data":7}"""

                "textDocument/definition" -> """{"uri":"file:///x","range":${range(0, 0, 0, 1)}}"""

                "textDocument/formatting" -> """[{"range":${range(0, 0, 0, 4)},"newText":"NAME"}]"""

                "textDocument/documentSymbol" -> """[{"name":"s","kind":12,"range":${range(
                    0,
                    0,
                    0,
                    4,
                )},"selectionRange":${range(0, 0, 0, 4)}}]"""

                "textDocument/rename" -> """{"changes":{"$uri":[{"range":${range(0, 0, 0, 4)},"newText":"other"}]}}"""

                else -> null
            }
        }
        connect("toy-features", server)
        val adapter = served(handle)
        uri = adapter.documentUri
        val service = assertNotNull(handle.languageService)
        val snapshot = handle.state.value.document.snapshot()

        withContext(Dispatchers.Main) {
            assertEquals(
                setOf('.'),
                service.triggerCharacters - (AardinkWeb.registry.byId("toy-features")!!.languageService!!.triggerCharacters),
            )
            val item = service.completions(snapshot, 0).single()
            assertEquals("served", item.label)
            assertEquals("resolved", service.resolveCompletion(item).documentation)
            assertEquals("file:///x", service.definition(snapshot, 1)?.uri)
            assertEquals("NAME", service.format(snapshot, FormattingOptions()))
            assertEquals(listOf("s"), service.documentSymbols(snapshot).map { it.name })
            assertTrue(service.supportsRename)
            assertEquals(0 until 4, service.prepareRename(snapshot, 1))
            assertEquals(listOf(TextEdit(0 until 4, "other")), service.rename(snapshot, 1, "other"))
        }
        assertEquals(1, server.params("textDocument/completion").count { it.documentUri == adapter.documentUri })
    }

    @Test
    fun `format goes to the server through the editor`() = runTest {
        language("toy-format")
        val handle = mount("name", "toy-format")
        val server = FakeServer { method ->
            if (method ==
                "textDocument/formatting"
            ) {
                """[{"range":${range(0, 0, 0, 4)},"newText":"NAME"}]"""
            } else {
                null
            }
        }
        connect("toy-format", server)
        served(handle)
        val changed = CompletableDeferred<Boolean>()

        AardinkWeb.format(handle) { changed.complete(it) }

        assertTrue(withContext(Dispatchers.Main) { withTimeout(5_000) { changed.await() } })
        assertEquals("NAME", AardinkWeb.getValue(handle))
    }

    @Test
    fun `a registered language's providers answer first, and the server fills in what they leave`() = runTest {
        language(
            "toy-composed",
            WebLanguageProviders(
                completions = { _, _, _ -> """[{ "label": "hosted", "insertText": "hosted" }]""" },
                hover = { _, _, column -> if (column == 1) """{ "contents": "from the host" }""" else null },
                diagnostics = { _ -> """[{ "line": 1, "startColumn": 1, "endColumn": 2, "message": "host's", "severity": "error" }]""" },
            ),
        )
        val handle = mount("name", "toy-composed")
        val server = FakeServer { method ->
            when (method) {
                "textDocument/completion" -> """[{"label":"served"}]"""
                "textDocument/hover" -> """{"contents":"from the server"}"""
                else -> null
            }
        }
        connect("toy-composed", server)
        val adapter = served(handle)
        server.pushDiagnostics(adapter.documentUri, "server's")
        val service = assertNotNull(handle.languageService)
        val snapshot = handle.state.value.document.snapshot()

        withContext(Dispatchers.Main) {
            assertEquals(listOf("hosted", "served"), service.completions(snapshot, 0).map { it.label })
            assertEquals("from the host", service.hoverDoc(snapshot, 0)?.content)
            assertEquals("from the server", service.hoverDoc(snapshot, 2)?.content)
        }
        awaitDiagnostics(handle, "host's", "server's")
    }

    @Test
    fun `a server answers after the host's providers and before the language they extend`() = runTest {
        language("toy-order-base", WebLanguageProviders(hover = { _, _, _ -> """{ "contents": "from the base" }""" }))
        language(
            "toy-order",
            WebLanguageProviders(hover = { _, _, column -> if (column == 1) """{ "contents": "from the host" }""" else null }),
            extends = "toy-order-base",
        )
        val handle = mount("name", "toy-order")
        val server = FakeServer { method -> if (method == "textDocument/hover") """{"contents":"from the server"}""" else null }
        connect("toy-order", server)
        served(handle)
        val service = assertNotNull(handle.languageService)
        val snapshot = handle.state.value.document.snapshot()

        withContext(Dispatchers.Main) {
            assertEquals("from the host", service.hoverDoc(snapshot, 0)?.content)
            assertEquals("from the server", service.hoverDoc(snapshot, 2)?.content, "ahead of the base")
        }
    }

    /** Waits for the service to have exactly [messages]; pushed ones come in their own time. */
    private suspend fun awaitDiagnostics(handle: AardinkEditorHandle, vararg messages: String) {
        var last = emptyList<String>()
        try {
            withContext(Dispatchers.Main) {
                withTimeout(5_000) {
                    while (true) {
                        last = handle.languageService!!.diagnostics(handle.state.value.document.snapshot()).map { it.message }
                        if (last == messages.toList()) break
                        delay(16)
                    }
                }
            }
        } catch (e: Exception) {
            throw AssertionError("expected ${messages.toList()}, had $last", e)
        }
    }

    @Test
    fun `a built-in language's answers come after the server's, but it keeps what it does as text is typed`() = runTest {
        val handle = mount("{\n}", "json")
        val own = handle.language.value.languageService!!
        val server = FakeServer { method ->
            when (method) {
                "textDocument/documentSymbol" -> """[{"name":"served","kind":12,"range":${range(
                    0,
                    0,
                    0,
                    1,
                )},"selectionRange":${range(0, 0, 0, 1)}}]"""

                "textDocument/formatting" -> """[{"range":${range(0, 0, 1, 1)},"newText":"{}"}]"""

                else -> null
            }
        }
        connect("json", server)
        served(handle)
        val service = assertNotNull(handle.languageService)
        val snapshot = CodeDocument("{\n}")

        withContext(Dispatchers.Main) {
            assertEquals(listOf("served"), service.documentSymbols(snapshot).map { it.name })
            assertEquals("{}", service.format(snapshot, FormattingOptions()))
        }
        assertEquals(own.autoClose(snapshot, 0, '"'), service.autoClose(snapshot, 0, '"'))
        assertEquals(own.smartIndent(snapshot, 1, FormattingOptions()), service.smartIndent(snapshot, 1, FormattingOptions()))
    }

    @Test
    fun `a built-in language's own rules for typing come before a server's generic ones`() {
        class Typing(val name: String) : LanguageService {
            override val triggerCharacters: Set<Char> get() = emptySet()

            override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

            override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

            override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = name

            override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String = name

            override fun surroundingClose(typed: Char): String = name

            override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc = HoverDoc(title = name, content = name)

            override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text
        }
        val document = CodeDocument("")

        val served = assertNotNull(servedLanguage(Typing("language"), listOf(Typing("server"))))

        assertEquals("language", served.smartIndent(document, 0, FormattingOptions()))
        assertEquals("language", served.autoClose(document, 0, '('))
        assertEquals("language", served.surroundingClose('('))
    }

    @Test
    fun `what a built-in language leaves alone as text is typed stays alone, with a real server behind it`() = runTest {
        for (id in listOf("json", "kotlin", "markdown")) {
            val handle = mount("it's", id)
            connect(id, FakeServer())
            val adapter = served(handle)
            val service = assertNotNull(handle.languageService)
            val text = CodeDocument("it's")

            assertEquals("'", adapter.autoClose(text, 2, '\''), "the generic rule this must not fall through to")
            assertNull(service.autoClose(text, 2, '\''), id)
        }
    }

    @Test
    fun `a language's empty indentation on Enter stands, with a real server behind it`() = runTest {
        val kotlin = mount("  foo\n}", "kotlin")
        val markdown = mount("    code\n", "markdown")
        connect("kotlin", FakeServer())
        connect("markdown", FakeServer())
        val kotlinAdapter = served(kotlin)
        val markdownAdapter = served(markdown)
        val closing = CodeDocument("  foo\n}")
        val code = CodeDocument("    code\n")

        assertEquals("  ", kotlinAdapter.smartIndent(closing, 1, FormattingOptions()))
        assertEquals("    ", markdownAdapter.smartIndent(code, 1, FormattingOptions()))
        assertEquals("", kotlin.languageService!!.smartIndent(closing, 1, FormattingOptions()))
        assertEquals("", markdown.languageService!!.smartIndent(code, 1, FormattingOptions()))
    }

    @Test
    fun `a registered language decides what is closed as text is typed, whatever it is built on`() = runTest {
        language("toy-bare")
        language("toy-kotlin", extends = "kotlin")
        AardinkWeb.registerLanguage(
            """{ "id": "toy-configured", "configuration": { "autoClosingPairs": [["'", "'"], ["(", ")"]] },
                 "grammar": { "tokenizer": { "root": [ ["\\w+", "text"] ] } } }""",
            WebLanguageProviders(),
        )
        val bare = mount("x", "toy-bare")
        val extending = mount("x", "toy-kotlin")
        val configured = mount("x", "toy-configured")
        connect("toy-bare", FakeServer())
        connect("toy-kotlin", FakeServer())
        connect("toy-configured", FakeServer())
        served(bare)
        served(extending)
        served(configured)
        val text = CodeDocument("don't")
        val paren = CodeDocument("(")

        assertNull(bare.languageService!!.autoClose(paren, 0, '('), "a language with no pairs closes nothing")
        assertEquals("", bare.languageService!!.smartIndent(CodeDocument("  a\n"), 1, FormattingOptions()))
        assertNull(extending.languageService!!.autoClose(text, 3, '\''), "kotlin has no pair for it")
        assertEquals(")", extending.languageService!!.autoClose(paren, 0, '('))
        assertNull(configured.languageService!!.autoClose(text, 3, '\''), "refused in the middle of a word")
        assertEquals(")", configured.languageService!!.autoClose(paren, 0, '('))
    }

    @Test
    fun `an editor with no language service types as it does without one, with a real server behind it`() = runTest {
        val handle = mount("  a\n", "toy-no-rules")
        assertNull(handle.languageService)
        connect("toy-no-rules", FakeServer())
        val adapter = served(handle)
        val service = assertNotNull(handle.languageService)

        val quote = CodeDocument("don't")
        assertEquals("'", adapter.autoClose(quote, 3, '\''), "the generic rule this must not fall through to")
        assertEquals("  ", adapter.smartIndent(CodeDocument("  a\n"), 1, FormattingOptions()))
        assertNull(service.autoClose(quote, 3, '\''))
        for (typed in "([{\"'") {
            assertNull(service.autoClose(CodeDocument(typed.toString()), 0, typed), "nothing closes $typed")
            assertNull(service.surroundingClose(typed), "nothing surrounds with $typed")
        }
        assertEquals("", service.smartIndent(CodeDocument("  a\n"), 1, FormattingOptions()))
    }

    @Test
    fun `an editor with no language service still has what a server knows`() = runTest {
        val handle = mount("a", "toy-no-rules-answers")
        val server = FakeServer { method -> """{"items":[{"label":"served"}]}""".takeIf { method == "textDocument/completion" } }
        connect("toy-no-rules-answers", server)
        served(handle)
        val service = assertNotNull(handle.languageService)

        assertEquals(listOf("served"), service.completions(handle.state.value.document.snapshot(), 1).map { it.label })
        assertEquals(setOf('.'), service.triggerCharacters)
    }

    @Test
    fun `two servers on an editor with no language service change no more than one`() = runTest {
        val handle = mount("a", "toy-no-rules-two")
        connect("toy-no-rules-two", FakeServer())
        connect("toy-no-rules-two", FakeServer())
        awaitUntil { handle.languageServers.size == 2 }
        val service = assertNotNull(handle.languageService)

        assertNull(service.autoClose(CodeDocument("("), 0, '('))
        assertEquals("", service.smartIndent(CodeDocument("  a\n"), 1, FormattingOptions()))
    }

    @Test
    fun `a server with no trigger characters adds none to the language's`() = runTest {
        language("toy-triggers")
        val handle = mount("name", "toy-triggers")
        val own = handle.language.value.languageService!!
        connect("toy-triggers", FakeServer(capabilities = """{ "textDocumentSync": 2, "hoverProvider": true }"""))
        served(handle)

        assertEquals(own.triggerCharacters, handle.languageService!!.triggerCharacters)
    }

    @Test
    fun `what a server pushes reaches the host without an edit`() = runTest {
        language("toy-pushed")
        val handle = mount("name", "toy-pushed")
        val reported = mutableListOf<String>()
        AardinkWeb.onDiagnosticsChange(handle) { reported += it }
        val server = FakeServer()
        connect("toy-pushed", server)
        val adapter = served(handle)
        // After the editor's first pass, which is the one the push must not wait for an edit after.
        awaitUntil { reported.isNotEmpty() }

        server.pushDiagnostics(adapter.documentUri, "pushed later")

        awaitUntil { reported.any { "pushed later" in it } }
    }

    @Test
    fun `what a server pushes joins an owner's markers, which the layout does not collect beside`() = runTest {
        language("toy-owned")
        val handle = mount("name", "toy-owned")
        AardinkWeb.setDiagnosticsForOwner(handle, "host", listOf(WebDiagnostic(1, 1, 2, "owned", "error")))
        val reported = mutableListOf<String>()
        AardinkWeb.onDiagnosticsChange(handle) { reported += it }
        val server = FakeServer()
        connect("toy-owned", server)
        val adapter = served(handle)
        awaitUntil { reported.isNotEmpty() }

        server.pushDiagnostics(adapter.documentUri, "pushed later")

        awaitUntil { reported.any { "pushed later" in it } }
        assertTrue(handle.languageDiagnostics.value.any { it.message == "pushed later" })
    }

    /** A language whose provider notes each time it is asked, on an editor with a server settled behind it. */
    private suspend fun settledWithProvider(id: String, asked: MutableList<TimeMark>): Pair<AardinkEditorHandle, FakeServer> {
        language(
            id,
            WebLanguageProviders(
                diagnostics = { _ ->
                    asked += TimeSource.Monotonic.markNow()
                    "[]"
                },
            ),
        )
        val handle = mount("name", id)
        val server = FakeServer()
        connect(id, server)
        served(handle)
        // Past the pass that a new service sets off.
        awaitUntil { asked.isNotEmpty() }
        pause(700)
        return handle to server
    }

    @Test
    fun `a push during the pause in typing does not end the pause, and the pass that follows reads it`() = runTest {
        val asked = mutableListOf<TimeMark>()
        val (handle, server) = settledWithProvider("toy-paused", asked)
        val uri = handle.languageServers.single().service.documentUri
        val reported = mutableListOf<String>()
        AardinkWeb.onDiagnosticsChange(handle) { reported += it }
        val before = asked.size

        val typed = TimeSource.Monotonic.markNow()
        edit(handle, 0, "x")
        // Apart from the edit, or the layout would see the two as one change.
        pause(100)
        server.pushDiagnostics(uri, "pushed")
        awaitUntil { asked.size > before }

        assertTrue(typed.elapsedNow() >= 450.milliseconds, "asked ${typed.elapsedNow()} after the edit")
        awaitUntil { reported.any { "pushed" in it } }
        // That pass read the push, so nothing is left to ask about.
        pause(700)
        assertEquals(before + 1, asked.size)
    }

    @Test
    fun `a push is left to the pass of an edit made while it waits`() = runTest {
        val asked = mutableListOf<TimeMark>()
        val (handle, server) = settledWithProvider("toy-still-typing", asked)
        val uri = handle.languageServers.single().service.documentUri

        edit(handle, 0, "x")
        pause(100)
        server.pushDiagnostics(uri, "pushed")
        pause(300)
        val before = asked.size
        val typed = TimeSource.Monotonic.markNow()
        edit(handle, 0, "y")
        awaitUntil { asked.size > before }

        assertTrue(typed.elapsedNow() >= 450.milliseconds, "asked ${typed.elapsedNow()} after the last edit")
    }

    @Test
    fun `pushes in a burst are shown by one pass`() = runTest {
        val asked = mutableListOf<TimeMark>()
        val (handle, server) = settledWithProvider("toy-burst", asked)
        val uri = handle.languageServers.single().service.documentUri
        val before = asked.size

        repeat(5) { server.pushDiagnostics(uri, "pushed $it") }
        awaitUntil { asked.size > before }
        pause(700)

        assertEquals(before + 1, asked.size)
    }

    @Test
    fun `a server gone takes its diagnostics with it from a language with no service, beside an owner's markers`() = runTest {
        val handle = mount("name", "toy-no-service")
        assertNull(handle.languageService)
        AardinkWeb.setDiagnosticsForOwner(handle, "host", listOf(WebDiagnostic(1, 1, 2, "owned", "error")))
        val reported = mutableListOf<String>()
        AardinkWeb.onDiagnosticsChange(handle) { reported += it }
        val server = FakeServer()
        connect("toy-no-service", server)
        val adapter = served(handle)
        server.pushDiagnostics(adapter.documentUri, "from the server")
        awaitUntil { handle.languageDiagnostics.value.any { it.message == "from the server" } }

        server.drop()

        awaitUntil { handle.languageDiagnostics.value.isEmpty() }
        assertEquals("[]", reported.last())
    }

    @Test
    fun `a server that folds gets a plain editor a fold lane, and one that does not leaves it without`() = runTest {
        val unfolded = mount("name", "toy-plain-unfolded")
        val folded = mount("name", "toy-plain-folded")
        connect("toy-plain-unfolded", FakeServer(capabilities = """{ "textDocumentSync": 2 }"""))
        connect("toy-plain-folded", FakeServer())
        served(unfolded)
        served(folded)

        assertSame(NoOpFoldingProvider, unfolded.foldingProvider)
        assertNotSame(NoOpFoldingProvider, folded.foldingProvider)
    }

    @Test
    fun `folding comes from a server that folds, and from the language otherwise`() = runTest {
        language("toy-folded")
        language("toy-unfolded")
        val folded = mount("a\nb\nc", "toy-folded")
        val unfolded = mount("  a\n    b\n  c", "toy-unfolded")
        val ownRanges = unfolded.foldingProvider.foldableRanges(CodeDocument("  a\n    b\n  c"))
        connect(
            "toy-folded",
            FakeServer { method ->
                if (method ==
                    "textDocument/foldingRange"
                ) {
                    """[{"startLine":0,"endLine":2}]"""
                } else {
                    null
                }
            },
        )
        connect("toy-unfolded", FakeServer(capabilities = """{ "textDocumentSync": 2 }"""))
        served(folded)
        served(unfolded)

        withContext(Dispatchers.Main) {
            val snapshot = folded.state.value.document.snapshot()
            assertEquals(listOf(0 to 2), folded.foldingProvider.requestFoldableRanges(snapshot).map { it.startLine to it.endLine })
            val other = unfolded.state.value.document.snapshot()
            assertEquals(ownRanges, unfolded.foldingProvider.requestFoldableRanges(other))
        }
    }

    @Test
    fun `dispose closes each document, shuts the server down and leaves the editor as it was`() = runTest {
        language("toy-dispose")
        val handle = mount("name", "toy-dispose")
        val own = handle.language.value.languageService
        val server = FakeServer()
        val connection = connect("toy-dispose", server)
        val adapter = served(handle)

        connection.dispose()
        connection.dispose()

        assertSame(own, handle.languageService)
        awaitUntil { "exit" in server.methods }
        assertEquals(listOf("textDocument/didClose", "shutdown", "exit"), server.methods.takeLast(3))
        assertEquals(adapter.documentUri, server.params("textDocument/didClose").single().documentUri)
        assertTrue(AardinkWeb.languageServers.isEmpty())

        // No longer followed: this edit goes nowhere, and an editor created now gets no document.
        edit(handle, 0, "x")
        mount("later", "toy-dispose")
        delay(50)
        assertFalse("textDocument/didChange" in server.methods)
        assertEquals(1, server.methods.count { it == "textDocument/didOpen" })
    }

    @Test
    fun `an editor disposed closes its document and leaves the connection up for the others`() = runTest {
        language("toy-editor-gone")
        val gone = mount("gone", "toy-editor-gone")
        val staying = mount("staying", "toy-editor-gone")
        val server = FakeServer { method -> if (method == "textDocument/hover") """{"contents":"still here"}""" else null }
        connect("toy-editor-gone", server)
        val goneUri = served(gone).documentUri
        served(staying)

        AardinkWeb.dispose(gone)

        awaitUntil { "textDocument/didClose" in server.methods }
        assertEquals(goneUri, server.params("textDocument/didClose").single().documentUri)
        val hover = withContext(Dispatchers.Main) { staying.languageService!!.hoverDoc(staying.state.value.document.snapshot(), 0) }
        assertEquals("still here", hover?.content)
    }

    @Test
    fun `changing an editor's language moves it on and off the server, and its text with it`() = runTest {
        language("toy-switch")
        language("toy-other")
        val handle = mount("name", "toy-other")
        val server = FakeServer()
        connect("toy-switch", server)
        awaitUntil { "initialized" in server.methods }
        assertTrue(handle.languageServers.isEmpty())

        AardinkWeb.patchOptions(handle, """{ "language": "toy-switch" }""")
        val first = served(handle).documentUri
        AardinkWeb.patchOptions(handle, """{ "language": "toy-other" }""")
        assertTrue(handle.languageServers.isEmpty())
        awaitUntil { "textDocument/didClose" in server.methods }
        edit(handle, 0, "x")
        AardinkWeb.patchOptions(handle, """{ "language": "toy-switch" }""")
        val second = served(handle).documentUri

        assertNotEquals(first, second)
        awaitUntil { server.methods.count { it == "textDocument/didOpen" } == 2 }
        val text = server.params("textDocument/didOpen").last()["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("xname", text)
    }

    @Test
    fun `a language this page does not know still reaches the server, under the name it was asked for`() = runTest {
        val handle = mount("name", "toy-unknown")
        assertEquals("plaintext", handle.language.value.id)
        val server = FakeServer()
        connect("toy-unknown", server)
        val adapter = served(handle)

        assertTrue(adapter.documentUri.endsWith(".toy-unknown"), adapter.documentUri)
        awaitUntil { "textDocument/didOpen" in server.methods }
        val opened = server.params("textDocument/didOpen").single()["textDocument"]!!.jsonObject
        assertEquals("toy-unknown", opened["languageId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a server that cannot be reached leaves the editor working and its handle harmless`() = runTest {
        language("toy-refused")
        val handle = mount("name", "toy-refused")
        val own = handle.language.value.languageService
        val connection = connectTo("toy-refused") { error("connection refused") }

        awaitUntil { AardinkWeb.languageServers.isEmpty() }
        assertSame(own, handle.languageService)
        edit(handle, 0, "x")
        assertEquals("xname", AardinkWeb.getValue(handle))
        connection.dispose()
        assertTrue(mount("later", "toy-refused").languageServers.isEmpty())
    }

    @Test
    fun `a server that closes during the handshake leaves the editor working`() = runTest {
        language("toy-handshake")
        val handle = mount("name", "toy-handshake")
        val server = FakeServer(capabilities = "{}")
        server.drop()
        connect("toy-handshake", server)

        awaitUntil { AardinkWeb.languageServers.isEmpty() }
        assertTrue(handle.languageServers.isEmpty())
    }

    @Test
    fun `a socket gone just as the handshake finishes follows no editor`() = runTest {
        language("toy-gone-at-once")
        val handle = mount("name", "toy-gone-at-once")
        val server = FakeServer().also { servers += it }
        val transport = object : LspTransport by server.transport {
            override suspend fun sendPayload(jsonPayload: String) {
                // What the real WebSocket transport does once the socket is closing.
                check("\"initialized\"" !in jsonPayload) { "WebSocket is not open" }
                server.transport.sendPayload(jsonPayload)
            }
        }
        connectTo("toy-gone-at-once") { transport }

        awaitUntil { AardinkWeb.languageServers.isEmpty() }
        pause(50)

        assertTrue(handle.languageServers.isEmpty())
    }

    @Test
    fun `disposed while connecting, the connection is abandoned`() = runTest {
        language("toy-early")
        val handle = mount("name", "toy-early")
        var abandoned = false
        val connection = connectTo("toy-early") {
            try {
                awaitCancellation()
            } finally {
                abandoned = true
            }
        }
        awaitUntil { AardinkWeb.languageServers.isNotEmpty() }
        delay(50)

        connection.dispose()

        awaitUntil { abandoned }
        assertTrue(AardinkWeb.languageServers.isEmpty())
        assertTrue(handle.languageServers.isEmpty())
    }

    @Test
    fun `disposed before the server has answered, the transport is closed and nothing waits on it`() = runTest {
        language("toy-silent")
        val transport = ChannelLspTransport()
        val connection = connectTo("toy-silent") { transport }
        withContext(Dispatchers.Main) { withTimeout(5_000) { transport.sendChannel.receive() } } // initialize, which nobody answers

        connection.dispose()

        awaitUntil { transport.sendChannel.isClosedForSend }
    }

    @Test
    fun `a connection that drops hands the editor back to its language, which goes on working`() = runTest {
        language("toy-dropped", WebLanguageProviders(completions = { _, _, _ -> """[{ "label": "hosted" }]""" }))
        val handle = mount("name", "toy-dropped")
        val reported = mutableListOf<String>()
        AardinkWeb.onDiagnosticsChange(handle) { reported += it }
        val server = FakeServer { method -> if (method == "textDocument/completion") """[{"label":"served"}]""" else null }
        val connection = connect("toy-dropped", server)
        val adapter = served(handle)
        server.pushDiagnostics(adapter.documentUri, "from the server")
        awaitUntil { reported.lastOrNull()?.contains("from the server") == true }

        server.drop()

        awaitUntil { handle.languageServers.isEmpty() }
        assertTrue(AardinkWeb.languageServers.isEmpty())
        assertEquals(handle.language.value.languageService, handle.languageService)
        // Its squiggles go with it, and the language still answers.
        awaitUntil { reported.last() == "[]" }
        val items = withContext(Dispatchers.Main) { handle.languageService!!.completions(handle.state.value.document.snapshot(), 0) }
        assertEquals(listOf("hosted"), items.map { it.label })
        edit(handle, 0, "x")
        assertEquals("xname", AardinkWeb.getValue(handle))
        connection.dispose()
    }

    @Test
    fun `a question in flight when the connection drops comes back empty, not never`() = runTest {
        language("toy-in-flight")
        val handle = mount("name", "toy-in-flight")
        val asked = CompletableDeferred<Unit>()
        val transport = ChannelLspTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            for (payload in transport.sendChannel) {
                val message = Json.parseToJsonElement(payload).jsonObject
                when (message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> transport.receiveChannel.send(
                        """{"jsonrpc":"2.0","id":${message["id"]},"result":{"capabilities":$EVERYTHING}}""",
                    )

                    "textDocument/hover" -> asked.complete(Unit)
                }
            }
        }
        connectTo("toy-in-flight") { transport }
        val service = served(handle)
        val snapshot = handle.state.value.document.snapshot()

        val hover = withContext(Dispatchers.Main) {
            launch {
                asked.await()
                transport.receiveChannel.close()
            }
            withTimeout(5_000) { service.hoverDoc(snapshot, 0) }
        }

        assertNull(hover)
        scope.cancel()
    }

    @Test
    fun `two servers on one language are both asked, the first to connect first`() = runTest {
        language("toy-two")
        val handle = mount("name", "toy-two")
        connect("toy-two", FakeServer { method -> if (method == "textDocument/completion") """[{"label":"first"}]""" else null })
        awaitUntil { handle.languageServers.size == 1 }
        connect("toy-two", FakeServer { method -> if (method == "textDocument/completion") """[{"label":"second"}]""" else null })
        awaitUntil { handle.languageServers.size == 2 }

        val items = withContext(Dispatchers.Main) { handle.languageService!!.completions(handle.state.value.document.snapshot(), 0) }

        assertEquals(listOf("first", "second"), items.map { it.label })
    }

    @Test
    fun `a server's completion the host also offers shows once, as the host's`() = runTest {
        language("toy-same", WebLanguageProviders(completions = { _, _, _ -> """[{ "label": "same", "kind": "value" }]""" }))
        val handle = mount("name", "toy-same")
        connect("toy-same", FakeServer { method -> if (method == "textDocument/completion") """[{"label":"same","kind":12}]""" else null })
        served(handle)

        val items = withContext(Dispatchers.Main) { handle.languageService!!.completions(handle.state.value.document.snapshot(), 0) }

        assertEquals(listOf("same"), items.map { it.label })
    }

    /** A server that calls the first four characters a variable. */
    private fun servingSemanticTokens() =
        FakeServer(SEMANTIC_TOKENS_SERVER) { method -> if (method == SEMANTIC_TOKENS_METHOD) """{"data":[0,0,4,0,0]}""" else null }

    /** The layout asks by itself, once the server has the document, and the server's answer comes back. */
    private suspend fun assertSemanticTokensServed(handle: AardinkEditorHandle, server: FakeServer) {
        awaitUntil { SEMANTIC_TOKENS_METHOD in server.methods }
        assertTrue(server.methods.indexOf("textDocument/didOpen") in 0 until server.methods.indexOf(SEMANTIC_TOKENS_METHOD))

        val tokens = withContext(Dispatchers.Main) { handle.languageService!!.semanticTokens(handle.state.value.document.snapshot()) }

        assertEquals(listOf(0 to 4), tokens.map { it.start to it.end })
    }

    @Test
    fun `semantic highlighting reaches a server through a registered language, which has none of its own`() = runTest {
        language("toy-semantic")
        val handle = mount("name", "toy-semantic")
        AardinkWeb.patchOptions(handle, """{"semanticHighlighting": true}""")
        val server = servingSemanticTokens()

        connect("toy-semantic", server)

        assertSemanticTokensServed(handle, server)
    }

    @Test
    fun `semantic highlighting reaches a server ahead of a built-in language`() = runTest {
        val handle = mount("true", "json")
        AardinkWeb.patchOptions(handle, """{"semanticHighlighting": true}""")
        val server = servingSemanticTokens()

        connect("json", server)

        assertSemanticTokensServed(handle, server)
    }

    @Test
    fun `with semantic highlighting off, a server is not asked`() = runTest {
        language("toy-semantic-off")
        val handle = mount("name", "toy-semantic-off")
        val server = servingSemanticTokens()
        connect("toy-semantic-off", server)
        served(handle)

        pause(600)

        assertFalse(SEMANTIC_TOKENS_METHOD in server.methods)
    }

    /** A server with one hint, at the third character. */
    private fun servingInlayHints() = FakeServer(INLAY_HINT_SERVER) { method ->
        if (method == INLAY_HINT_METHOD) """[{"position":{"line":0,"character":2},"label":"hint"}]""" else null
    }

    /** The layout asks by itself, once the server has the document, and the server's answer comes back. */
    private suspend fun assertInlayHintsServed(handle: AardinkEditorHandle, server: FakeServer) {
        awaitUntil { INLAY_HINT_METHOD in server.methods }
        assertTrue(server.methods.indexOf("textDocument/didOpen") in 0 until server.methods.indexOf(INLAY_HINT_METHOD))

        val document = handle.state.value.document.snapshot()
        val hints = withContext(Dispatchers.Main) { handle.languageService!!.inlayHints(document, 0 until document.length) }

        assertEquals(listOf(InlayHint(2, "hint")), hints)
    }

    @Test
    fun `inlay hints reach a server through a registered language, which has none of its own`() = runTest {
        language("toy-hinted")
        val handle = mount("name", "toy-hinted")
        AardinkWeb.patchOptions(handle, """{"inlayHints": true}""")
        val server = servingInlayHints()

        connect("toy-hinted", server)

        assertInlayHintsServed(handle, server)
    }

    @Test
    fun `inlay hints reach a server ahead of a built-in language`() = runTest {
        val handle = mount("true", "json")
        AardinkWeb.patchOptions(handle, """{"inlayHints": true}""")
        val server = servingInlayHints()

        connect("json", server)

        assertInlayHintsServed(handle, server)
    }

    @Test
    fun `a host's hint and a server's, at different places, are both shown, the host's first`() = runTest {
        language(
            "toy-both-hints",
            WebLanguageProviders(inlayHints = { _, _ -> """[{ "position": { "lineNumber": 1, "column": 2 }, "label": "host" }]""" }),
        )
        val handle = mount("name", "toy-both-hints")
        connect("toy-both-hints", servingInlayHints())
        served(handle)

        val document = handle.state.value.document.snapshot()
        val hints = withContext(Dispatchers.Main) { handle.languageService!!.inlayHints(document, 0 until document.length) }

        assertEquals(listOf(InlayHint(1, "host"), InlayHint(2, "hint")), hints)
    }

    @Test
    fun `with inlay hints off, a server is not asked`() = runTest {
        language("toy-hints-off")
        val handle = mount("name", "toy-hints-off")
        val server = servingInlayHints()
        connect("toy-hints-off", server)
        served(handle)

        pause(600)

        assertFalse(INLAY_HINT_METHOD in server.methods)
    }

    /** A server with one lens, at the third character, that it fills in when asked. */
    private fun servingCodeLenses() = FakeServer(CODE_LENS_SERVER) { method ->
        when (method) {
            CODE_LENS_METHOD -> """[{"range":${range(0, 2, 0, 3)},"data":1}]"""

            CODE_LENS_RESOLVE ->
                """{"range":${range(0, 2, 0, 3)},"command":{"title":"Run","command":"server.run","arguments":["a"]}}"""

            else -> null
        }
    }

    /** The layout asks by itself, once the server has the document, and shows what it resolved. */
    private suspend fun assertCodeLensesServed(handle: AardinkEditorHandle, server: FakeServer) {
        awaitUntil { CODE_LENS_RESOLVE in server.methods }
        assertTrue(server.methods.indexOf("textDocument/didOpen") in 0 until server.methods.indexOf(CODE_LENS_METHOD))

        val document = handle.state.value.document.snapshot()
        val shown = withContext(Dispatchers.Main) { handle.languageService!!.codeLenses(document) }

        assertEquals(listOf(2 to "Run"), shown.map { it.offset to it.title })
        assertEquals(1, server.methods.count { it == CODE_LENS_METHOD }, "what the layout asked is the answer this is")
        withContext(Dispatchers.Main) { shown.single().onClick() }
        awaitUntil { EXECUTE_COMMAND in server.methods }
        val sent = server.params(EXECUTE_COMMAND).single()
        assertEquals("server.run", sent["command"]!!.jsonPrimitive.content)
        assertEquals("""["a"]""", sent["arguments"].toString())
    }

    @Test
    fun `code lenses reach a server through a registered language, which has none of its own`() = runTest {
        language("toy-lensed")
        val handle = mount("name", "toy-lensed")
        AardinkWeb.patchOptions(handle, """{"codeLens": true}""")
        val server = servingCodeLenses()

        connect("toy-lensed", server)

        assertCodeLensesServed(handle, server)
    }

    @Test
    fun `code lenses reach a server ahead of a built-in language`() = runTest {
        val handle = mount("true", "json")
        AardinkWeb.patchOptions(handle, """{"codeLens": true}""")
        val server = servingCodeLenses()

        connect("json", server)

        assertCodeLensesServed(handle, server)
    }

    @Test
    fun `a host's lens and a server's are both shown, the host's first, each with its own click`() = runTest {
        val ran = mutableListOf<String>()
        val range = """{ "startLineNumber": 1, "startColumn": 2, "endLineNumber": 1, "endColumn": 2 }"""
        language(
            "toy-both-lenses",
            WebLanguageProviders(
                codeLenses = { """[{ "range": $range, "command": { "id": "host.run", "title": "Host" } }]""" },
                runCodeLensCommand = { id, _ -> ran += id },
            ),
        )
        val handle = mount("name", "toy-both-lenses")
        AardinkWeb.patchOptions(handle, """{"codeLens": true}""")
        val server = servingCodeLenses()
        connect("toy-both-lenses", server)
        served(handle)

        awaitUntil { CODE_LENS_RESOLVE in server.methods }
        val document = handle.state.value.document.snapshot()
        val shown = withContext(Dispatchers.Main) { handle.languageService!!.codeLenses(document) }

        assertEquals(listOf(1 to "Host", 2 to "Run"), shown.map { it.offset to it.title })
        withContext(Dispatchers.Main) { shown.forEach { it.onClick() } }
        awaitUntil { EXECUTE_COMMAND in server.methods && ran.isNotEmpty() }
        assertEquals(listOf("host.run"), ran)
    }

    @Test
    fun `with code lenses off, a server is not asked`() = runTest {
        language("toy-lenses-off")
        val handle = mount("name", "toy-lenses-off")
        val server = servingCodeLenses()
        connect("toy-lenses-off", server)
        served(handle)

        pause(600)

        assertFalse(CODE_LENS_METHOD in server.methods)
    }
}
