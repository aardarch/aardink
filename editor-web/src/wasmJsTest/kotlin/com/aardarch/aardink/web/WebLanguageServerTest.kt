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
import com.aardarch.aardink.core.LanguageService
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
}
