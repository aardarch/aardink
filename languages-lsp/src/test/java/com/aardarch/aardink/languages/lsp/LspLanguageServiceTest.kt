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

import com.aardarch.aardink.core.CodeActionKind
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.Location
import com.aardarch.aardink.core.TextEdit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val URI = "file:///src/Main.kt"
private const val OTHER_URI = "file:///src/Other.kt"

/** LSP range JSON literal. */
private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int): String =
    """{"start":{"line":$startLine,"character":$startChar},"end":{"line":$endLine,"character":$endChar}}"""

/** A fake language server on the other end of a [ChannelLspTransport]. */
private class Harness(val doc: CodeDocument, scope: CoroutineScope) : CoroutineScope by scope {
    val transport = ChannelLspTransport()
    val client = LspClient(transport, CoroutineScope(Dispatchers.Default))
    val service = LspLanguageService(client, URI, "kotlin")

    /** Next message the client put on the wire. */
    suspend fun nextSent(): JsonObject = LspJson.parseToJsonElement(transport.sendChannel.receive()).jsonObject

    /** Answers the next request with raw [resultJson] after asserting its method; returns the request. */
    suspend fun respond(expectedMethod: String, resultJson: String): JsonObject {
        val sent = nextSent()
        assertEquals(expectedMethod, sent["method"]!!.jsonPrimitive.content)
        val id = sent["id"]!!.jsonPrimitive.content
        transport.receiveChannel.send("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""")
        return sent
    }

    /** Answers the next request with a JSON-RPC error. */
    suspend fun respondWithError(expectedMethod: String) {
        val sent = nextSent()
        assertEquals(expectedMethod, sent["method"]!!.jsonPrimitive.content)
        val id = sent["id"]!!.jsonPrimitive.content
        transport.receiveChannel.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":"boom"}}""")
    }

    /** Answers the next request the way a server that doesn't implement the method would. */
    suspend fun respondWithMethodNotFound(expectedMethod: String) {
        val sent = nextSent()
        assertEquals(expectedMethod, sent["method"]!!.jsonPrimitive.content)
        val id = sent["id"]!!.jsonPrimitive.content
        val code = LspRequestException.METHOD_NOT_FOUND
        transport.receiveChannel.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"unsupported"}}""")
    }

    /** Pushes `publishDiagnostics` for [uri] and returns once every registered listener has run. */
    suspend fun publish(uri: String, diagnosticsJson: String, version: Int? = null) {
        val done = CompletableDeferred<Unit>()
        val probe: DiagnosticsListener = { _, _, _ -> done.complete(Unit) }
        client.addDiagnosticsListener(probe)
        client.start()
        val versionMember = version?.let { """"version":$it,""" }.orEmpty()
        transport.receiveChannel.send(
            """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"$uri",$versionMember"diagnostics":$diagnosticsJson}}""",
        )
        withTimeout(5_000) { done.await() }
        client.removeDiagnosticsListener(probe)
    }
}

private fun withServer(text: String, block: suspend Harness.() -> Unit) = runBlocking {
    val harness = Harness(CodeDocument(text), this)
    try {
        harness.block()
    } finally {
        harness.client.stop()
    }
}

private val JsonObject.params: JsonObject get() = this["params"]!!.jsonObject

private val JsonObject.position: Pair<Int, Int>
    get() = this["position"]!!.jsonObject.let { it["line"]!!.jsonPrimitive.int to it["character"]!!.jsonPrimitive.int }

class LspLanguageServiceTest {

    // ── Lifecycle notifications ──────────────────────────────────────────────

    @Test
    fun `didOpen sends the document with escaped text`() = withServer("fun main() {\n    println(\"hi\")\n}") {
        service.didOpen(doc)

        val sent = nextSent()
        assertEquals("textDocument/didOpen", sent["method"]!!.jsonPrimitive.content)
        val textDocument = sent.params["textDocument"]!!.jsonObject
        assertEquals(URI, textDocument["uri"]!!.jsonPrimitive.content)
        assertEquals("kotlin", textDocument["languageId"]!!.jsonPrimitive.content)
        assertEquals(1, textDocument["version"]!!.jsonPrimitive.int)
        assertEquals(doc.text, textDocument["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `didChange sends full content with version`() = withServer("val x = 1") {
        service.didChange(doc, 7)

        val sent = nextSent()
        assertEquals("textDocument/didChange", sent["method"]!!.jsonPrimitive.content)
        assertEquals(7, sent.params["textDocument"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        assertEquals("val x = 1", sent.params["contentChanges"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `didClose notifies the server and stops tracking diagnostics`() = withServer("val a = 1") {
        publish(URI, """[{"range":${range(0, 4, 0, 5)},"message":"before"}]""")
        assertEquals(1, service.diagnostics(doc).size)

        service.didClose()
        val sent = nextSent()
        assertEquals("textDocument/didClose", sent["method"]!!.jsonPrimitive.content)
        assertEquals(URI, sent.params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)

        publish(URI, """[{"range":${range(0, 4, 0, 5)},"message":"after"}]""")
        assertTrue(service.diagnostics(doc).isEmpty())
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    @Test
    fun `diagnostics convert LSP ranges to document offsets`() = withServer("val a = 1\nval b = 2") {
        publish(
            URI,
            """[
              {"range":${range(1, 4, 1, 5)},"message":"unused","severity":2},
              {"range":${range(0, 0, 0, 3)},"message":"bad keyword","severity":1,"source":"kotlinc"},
              {"range":${range(0, 9, 0, 9)},"message":"zero width","severity":4}
            ]""",
        )

        val diagnostics = service.diagnostics(doc)
        assertEquals(3, diagnostics.size)

        assertEquals(14..14, diagnostics[0].range)
        assertEquals(1, diagnostics[0].lineNumber)
        assertEquals("unused", diagnostics[0].message)
        assertEquals(DiagnosticSeverity.Warning, diagnostics[0].severity)
        assertEquals("kotlin", diagnostics[0].source)

        assertEquals(0..2, diagnostics[1].range)
        assertEquals(DiagnosticSeverity.Error, diagnostics[1].severity)
        assertEquals("kotlinc", diagnostics[1].source)

        assertEquals(9..9, diagnostics[2].range, "zero-width diagnostics still cover one character")
        assertEquals(DiagnosticSeverity.Info, diagnostics[2].severity)
    }

    @Test
    fun `diagnostics for other documents are ignored`() = withServer("val a = 1") {
        publish(OTHER_URI, """[{"range":${range(0, 0, 0, 1)},"message":"elsewhere"}]""")
        assertTrue(service.diagnostics(doc).isEmpty())
    }

    @Test
    fun `diagnostics for an older version are ignored`() = withServer("val a = 1") {
        service.didChange(doc, 4)
        nextSent()

        publish(URI, """[{"range":${range(0, 0, 0, 1)},"message":"current"}]""", version = 4)
        publish(URI, """[{"range":${range(0, 0, 0, 1)},"message":"stale"}]""", version = 2)

        assertEquals("current", service.diagnostics(doc).single().message)
    }

    @Test
    fun `unversioned diagnostics are always applied`() = withServer("val a = 1") {
        service.didChange(doc, 4)
        nextSent()

        publish(URI, """[{"range":${range(0, 0, 0, 1)},"message":"versioned"}]""", version = 4)
        publish(URI, """[{"range":${range(0, 0, 0, 1)},"message":"unversioned"}]""")

        assertEquals("unversioned", service.diagnostics(doc).single().message)
    }

    @Test
    fun `two services on one client each keep their own diagnostics`() = withServer("val a = 1") {
        val other = LspLanguageService(client, OTHER_URI, "kotlin")
        publish(URI, """[{"range":${range(0, 0, 0, 1)},"message":"mine"}]""")
        publish(OTHER_URI, """[{"range":${range(0, 0, 0, 1)},"message":"theirs"}]""")

        assertEquals("mine", service.diagnostics(doc).single().message)
        assertEquals("theirs", other.diagnostics(doc).single().message)
    }

    // ── Completions ──────────────────────────────────────────────────────────

    @Test
    fun `completions map a CompletionItem array`() = withServer("val x = 1\nx.") {
        val server = async {
            respond(
                "textDocument/completion",
                """[
                  {"label":"println","kind":3,"detail":"fun println()","insertText":"println(${'$'}1)${'$'}0","insertTextFormat":2},
                  {"label":"size","kind":10,"documentation":{"kind":"markdown","value":"Size doc"},"filterText":"sz"}
                ]""",
            )
        }

        val items = service.completions(doc, 12)
        val sent = server.await()

        assertEquals(1 to 2, sent.params.position)
        assertEquals(2, items.size)
        assertEquals("println", items[0].label)
        assertEquals("println()", items[0].insertText, "snippet tab stops are stripped")
        assertEquals(CompletionKind.Element, items[0].kind)
        assertEquals("fun println()", items[0].documentation)
        assertEquals("size", items[1].label)
        assertEquals(CompletionKind.Property, items[1].kind)
        assertEquals("Size doc", items[1].documentation)
        assertEquals("sz", items[1].filterText)
    }

    @Test
    fun `completions map a CompletionList and prefer textEdit newText`() = withServer("x.") {
        val server = async {
            respond(
                "textDocument/completion",
                """{"isIncomplete":true,"items":[{"label":"xyz","kind":15,"textEdit":{"range":${range(0, 2, 0, 2)},"newText":"xyz()"}}]}""",
            )
        }

        val items = service.completions(doc, 2)
        server.await()

        assertEquals(listOf("xyz()"), items.map { it.insertText })
        assertEquals(CompletionKind.Snippet, items[0].kind)
        assertEquals(2 until 2, items[0].replaceRange, "an insertion point stays empty")
    }

    @Test
    fun `completions carry the textEdit range and let it beat insertText`() = withServer("val v = fo") {
        val server = async {
            respond(
                "textDocument/completion",
                """[{"label":"forEach","insertText":"ignored","textEdit":{"range":${range(0, 8, 0, 10)},"newText":"forEach"}}]""",
            )
        }

        val items = service.completions(doc, 10)
        server.await()

        assertEquals("forEach", items[0].insertText, "textEdit.newText wins over insertText")
        assertEquals(8 until 10, items[0].replaceRange)
    }

    @Test
    fun `completions prefer the insert range of an InsertReplaceEdit`() = withServer("val v = foBar") {
        val server = async {
            respond(
                "textDocument/completion",
                """[{"label":"forEach","textEdit":{"newText":"forEach",
                   |"insert":${range(0, 8, 0, 10)},"replace":${range(0, 8, 0, 13)}}}]
                """.trimMargin(),
            )
        }

        val items = service.completions(doc, 10)
        server.await()

        assertEquals(8 until 10, items[0].replaceRange, "accepting must not swallow text after the cursor")
    }

    @Test
    fun `completions without a textEdit leave the range to the editor`() = withServer("x.") {
        val server = async { respond("textDocument/completion", """[{"label":"size","insertText":"size"}]""") }

        val items = service.completions(doc, 2)
        server.await()

        assertNull(items[0].replaceRange)
    }

    @Test
    fun `completions return empty for null result or server error`() = withServer("x.") {
        val nullServer = async { respond("textDocument/completion", "null") }
        assertTrue(service.completions(doc, 2).isEmpty())
        nullServer.await()

        val errorServer = async { respondWithError("textDocument/completion") }
        assertTrue(service.completions(doc, 2).isEmpty())
        errorServer.await()
    }

    // ── Hover ────────────────────────────────────────────────────────────────

    @Test
    fun `hoverDoc uses string contents`() = withServer("val x = 1") {
        val server = async { respond("textDocument/hover", """{"contents":"Doc"}""") }

        val hover = service.hoverDoc(doc, 4)
        server.await()

        assertNotNull(hover)
        assertEquals("kotlin", hover?.title)
        assertEquals("Doc", hover?.content)
        assertNull(hover?.range)
    }

    @Test
    fun `hoverDoc uses MarkupContent and converts range`() = withServer("val x = 1") {
        val server = async {
            respond("textDocument/hover", """{"contents":{"kind":"markdown","value":"**x**: Int"},"range":${range(0, 4, 0, 5)}}""")
        }

        val hover = service.hoverDoc(doc, 4)
        server.await()

        assertEquals("**x**: Int", hover?.content)
        assertEquals(4..4, hover?.range)
    }

    @Test
    fun `hoverDoc joins MarkedString arrays and returns null when empty`() = withServer("val x = 1") {
        val arrayServer = async {
            respond("textDocument/hover", """{"contents":["first",{"language":"kotlin","value":"val x: Int"}]}""")
        }
        assertEquals("first\n\nval x: Int", service.hoverDoc(doc, 4)?.content)
        arrayServer.await()

        val nullServer = async { respond("textDocument/hover", "null") }
        assertNull(service.hoverDoc(doc, 4))
        nullServer.await()
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    @Test
    fun `format applies server edits in one pass`() = withServer("val x=1\nval y=2") {
        val server = async {
            respond(
                "textDocument/formatting",
                """[
                  {"range":${range(0, 0, 0, 0)},"newText":"// header\n"},
                  {"range":${range(0, 5, 0, 6)},"newText":" = "},
                  {"range":${range(1, 5, 1, 6)},"newText":" = "}
                ]""",
            )
        }

        val formatted = service.format(doc)
        val sent = server.await()

        assertEquals(4, sent.params["options"]!!.jsonObject["tabSize"]!!.jsonPrimitive.int)
        assertEquals("// header\nval x = 1\nval y = 2", formatted)
        assertEquals("val x=1\nval y=2", doc.text, "format must not mutate the document")
    }

    @Test
    fun `format returns the original text on null result or error`() = withServer("val x=1") {
        val nullServer = async { respond("textDocument/formatting", "null") }
        assertEquals("val x=1", service.format(doc))
        nullServer.await()

        val errorServer = async { respondWithError("textDocument/formatting") }
        assertEquals("val x=1", service.format(doc))
        errorServer.await()
    }

    @Test
    fun `formatRange returns offset-based edits`() = withServer("val x=1\nval y=2") {
        val server = async {
            respond(
                "textDocument/rangeFormatting",
                """[{"range":${range(1, 5, 1, 6)},"newText":" = "},{"range":${range(1, 0, 1, 0)},"newText":"  "}]""",
            )
        }

        val edits = service.formatRange(doc, 8..14)
        val sent = server.await()

        assertEquals(range(1, 0, 1, 7), sent.params["range"].toString())
        assertEquals(listOf(TextEdit(13..13, " = "), TextEdit(8 until 8, "  ")), edits)
    }

    // ── Code actions ─────────────────────────────────────────────────────────

    @Test
    fun `codeActions omit an action whose command runs after its edit`() = withServer("val x = 1") {
        val server = async {
            respond(
                "textDocument/codeAction",
                """[
                  {"title":"Import and organize","kind":"quickfix",
                   "command":{"title":"Organize","command":"kotlin.organize"},
                   "edit":{"changes":{"$URI":[{"range":${range(0, 0, 0, 0)},"newText":"import foo\n"}]}}},
                  {"title":"Import only","kind":"quickfix",
                   "edit":{"changes":{"$URI":[{"range":${range(0, 0, 0, 0)},"newText":"import bar\n"}]}}}
                ]""",
            )
        }

        val actions = service.codeActions(doc, 1..1)
        server.await()

        // Applying the edit alone would do half of what "Import and organize" promises.
        assertEquals(listOf("Import only"), actions.map { it.title })
    }

    @Test
    fun `triggerCharactersFrom reads the server's completion provider`() {
        val capabilities = LspJson.parseToJsonElement(
            """{"completionProvider":{"triggerCharacters":[".","::","@"]}}""",
        )

        // "::" is multi-character; the editor asks per typed character, so the last one stands in.
        assertEquals(setOf('.', ':', '@'), LspLanguageService.triggerCharactersFrom(capabilities))
    }

    @Test
    fun `triggerCharactersFrom is null when the server declares none`() {
        assertNull(LspLanguageService.triggerCharactersFrom(null))
        assertNull(LspLanguageService.triggerCharactersFrom(LspJson.parseToJsonElement("""{"hoverProvider":true}""")))
        assertNull(
            LspLanguageService.triggerCharactersFrom(LspJson.parseToJsonElement("""{"completionProvider":{"triggerCharacters":[]}}""")),
        )
    }

    @Test
    fun `a service without server trigger characters keeps the editor defaults`() {
        val client = LspClient(ChannelLspTransport(), CoroutineScope(Dispatchers.Default))
        val plain = LspLanguageService(client, URI, "kotlin")
        val configured = LspLanguageService(client, URI, "kotlin", serverTriggerCharacters = setOf('.', ':'))

        assertTrue('.' !in plain.triggerCharacters, "the editor defaults omit '.', which is why servers must say so")
        assertEquals(setOf('.', ':'), configured.triggerCharacters)
    }

    @Test
    fun `format keeps several inserts at one position in server order`() = withServer("foo()") {
        val server = async {
            respond(
                "textDocument/formatting",
                """[
                  {"range":${range(0, 0, 0, 0)},"newText":"a"},
                  {"range":${range(0, 0, 0, 0)},"newText":"b"},
                  {"range":${range(0, 0, 0, 0)},"newText":"c"}
                ]""",
            )
        }

        val formatted = service.format(doc)
        server.await()

        // Applied high-to-low, so edits sharing a position must not come out reversed.
        assertEquals("abcfoo()", formatted)
    }

    @Test
    fun `codeActions keep only actions with edits for this document`() = withServer("val x = 1") {
        publish(URI, """[{"range":${range(0, 0, 0, 3)},"message":"prefer var","severity":3}]""")
        val server = async {
            respond(
                "textDocument/codeAction",
                """[
                  {"title":"Run command","command":"kotlin.doSomething","arguments":[]},
                  {"title":"Import foo","kind":"quickfix","isPreferred":true,
                   "edit":{"changes":{"$URI":[{"range":${range(0, 0, 0, 0)},"newText":"import foo\n"}]}}},
                  {"title":"Extract elsewhere","kind":"refactor.extract",
                   "edit":{"changes":{"$OTHER_URI":[{"range":${range(0, 0, 0, 1)},"newText":"z"}]}}},
                  {"title":"Organize imports","kind":"source.organizeImports",
                   "edit":{"documentChanges":[{"textDocument":{"uri":"$URI","version":1},"edits":[{"range":${range(
                    0,
                    0,
                    0,
                    3,
                )},"newText":"var"}]}]}}
                ]""",
            )
        }

        val actions = service.codeActions(doc, 1..1)
        val sent = server.await()

        assertEquals(range(0, 1, 0, 2), sent.params["range"].toString())
        assertEquals(1, sent.params["context"]!!.jsonObject["diagnostics"]!!.jsonArray.size, "overlapping diagnostics are forwarded")

        assertEquals(listOf("Import foo", "Organize imports"), actions.map { it.title })
        assertEquals(CodeActionKind.QuickFix, actions[0].kind)
        assertTrue(actions[0].isPreferred)
        assertEquals(listOf(TextEdit(0 until 0, "import foo\n")), actions[0].edits)
        assertEquals(CodeActionKind.SourceOrganizeImports, actions[1].kind)
        assertEquals(listOf(TextEdit(0..2, "var")), actions[1].edits)
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    @Test
    fun `definition maps a single Location in this document`() = withServer("val x = 1\nx.foo()") {
        val server = async { respond("textDocument/definition", """{"uri":"$URI","range":${range(0, 4, 0, 5)}}""") }

        val location = service.definition(doc, 10)
        val sent = server.await()

        assertEquals(1 to 0, sent.params.position)
        assertEquals(Location(URI, 4..4, line = 0, column = 4), location)
    }

    @Test
    fun `definition takes the first of a Location array and handles LocationLinks`() = withServer("val x = 1") {
        val arrayServer = async {
            respond(
                "textDocument/definition",
                """[{"uri":"$URI","range":${range(0, 4, 0, 5)}},{"uri":"$URI","range":${range(0, 0, 0, 3)}}]""",
            )
        }
        assertEquals(Location(URI, 4..4, line = 0, column = 4), service.definition(doc, 4))
        arrayServer.await()

        val linkServer = async {
            respond(
                "textDocument/definition",
                """[{"targetUri":"$URI","targetRange":${range(0, 0, 0, 9)},"targetSelectionRange":${range(0, 4, 0, 5)}}]""",
            )
        }
        assertEquals(Location(URI, 4..4, line = 0, column = 4), service.definition(doc, 4))
        linkServer.await()
    }

    @Test
    fun `definition in another file keeps the uri with an empty range`() = withServer("val x = 1") {
        val server = async { respond("textDocument/definition", """{"uri":"$OTHER_URI","range":${range(3, 0, 3, 5)}}""") }

        val location = service.definition(doc, 4)
        server.await()

        assertEquals(OTHER_URI, location?.uri)
        assertTrue(location!!.range.isEmpty())
        assertEquals(3, location.line, "line/column are still reported for a cross-file target")
        assertEquals(0, location.column)
    }

    @Test
    fun `definition returns null for null result`() = withServer("val x = 1") {
        val server = async { respond("textDocument/definition", "null") }
        assertNull(service.definition(doc, 4))
        server.await()
    }

    @Test
    fun `references map every location and include declarations`() = withServer("val x = 1\nx.foo()") {
        val server = async {
            respond(
                "textDocument/references",
                """[{"uri":"$URI","range":${range(0, 4, 0, 5)}},{"uri":"$URI","range":${range(1, 0, 1, 1)}}]""",
            )
        }

        val references = service.references(doc, 4)
        val sent = server.await()

        assertTrue(sent.params["context"]!!.jsonObject["includeDeclaration"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf(Location(URI, 4..4, line = 0, column = 4), Location(URI, 10..10, line = 1, column = 0)),
            references,
        )
    }

    // ── Signature help ───────────────────────────────────────────────────────

    @Test
    fun `signatureHelp maps signatures and offset-based parameter labels`() = withServer("foo(1, ") {
        val server = async {
            respond(
                "textDocument/signatureHelp",
                """{"signatures":[{"label":"fun foo(a: Int, b: String)","documentation":"Foo doc",
                   "parameters":[{"label":"a: Int"},{"label":[16,25],"documentation":{"kind":"plaintext","value":"B doc"}}]}],
                   "activeSignature":0,"activeParameter":1}""",
            )
        }

        val help = service.signatureHelp(doc, 7)
        server.await()

        assertNotNull(help)
        val signature = help!!.signatures.single()
        assertEquals("fun foo(a: Int, b: String)", signature.label)
        assertEquals("Foo doc", signature.documentation)
        assertEquals(listOf("a: Int", "b: String"), signature.parameters.map { it.label })
        assertEquals("B doc", signature.parameters[1].documentation)
        assertEquals(0, help.activeSignature)
        assertEquals(1, help.activeParameter)
    }

    @Test
    fun `signatureHelp returns null when there are no signatures`() = withServer("foo(") {
        val server = async { respond("textDocument/signatureHelp", """{"signatures":[]}""") }
        assertNull(service.signatureHelp(doc, 4))
        server.await()
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    @Test
    fun `prepareRename accepts Range and placeholder results`() = withServer("val x = 1") {
        val rangeServer = async { respond("textDocument/prepareRename", range(0, 4, 0, 5)) }
        assertEquals(4..4, service.prepareRename(doc, 4))
        rangeServer.await()

        val placeholderServer = async { respond("textDocument/prepareRename", """{"range":${range(0, 4, 0, 5)},"placeholder":"x"}""") }
        assertEquals(4..4, service.prepareRename(doc, 4))
        placeholderServer.await()
    }

    @Test
    fun `defaultBehavior resolves to the identifier at the offset`() = withServer("val value = 1") {
        // The editor reads null as "cannot rename" and skips the dialog, but defaultBehavior means
        // rename is available and the client picks the range.
        val server = async { respond("textDocument/prepareRename", """{"defaultBehavior":true}""") }
        assertEquals(4..8, service.prepareRename(doc, 6))
        server.await()
    }

    @Test
    fun `a server without prepareRename support still yields a rename range`() = withServer("val value = 1") {
        val server = async { respondWithMethodNotFound("textDocument/prepareRename") }
        assertEquals(4..8, service.prepareRename(doc, 6))
        server.await()
    }

    @Test
    fun `a declined or failed prepareRename is null`() = withServer("val value = 1") {
        val declined = async { respond("textDocument/prepareRename", "null") }
        assertNull(service.prepareRename(doc, 6), "the server said no")
        declined.await()

        val failed = async { respondWithError("textDocument/prepareRename") }
        assertNull(service.prepareRename(doc, 6), "a server error is not a licence to rename")
        failed.await()

        val unsupportedAwayFromAWord = async { respondWithMethodNotFound("textDocument/prepareRename") }
        assertNull(service.prepareRename(doc, 10), "no identifier at the offset")
        unsupportedAwayFromAWord.await()
    }

    @Test
    fun `rename escapes the new name and collects edits from both workspace edit forms`() = withServer("val x = 1\nx.foo()") {
        val server = async {
            respond(
                "textDocument/rename",
                """{"changes":{"$URI":[{"range":${range(
                    0,
                    4,
                    0,
                    5,
                )},"newText":"y"}]},
                   "documentChanges":[{"textDocument":{"uri":"$URI","version":2},"edits":[{"range":${range(
                    1,
                    0,
                    1,
                    1,
                )},"newText":"y"}]}]}""",
            )
        }

        val edits = service.rename(doc, 4, "new\"Name")
        val sent = server.await()

        assertEquals("new\"Name", sent.params["newName"]!!.jsonPrimitive.content)
        assertEquals(listOf(TextEdit(4..4, "y"), TextEdit(10..10, "y")), edits)
    }

    @Test
    fun `rename refuses a workspace edit that reaches another file`() = withServer("val x = 1\nx.foo()") {
        val server = async {
            respond(
                "textDocument/rename",
                """{"changes":{"$URI":[{"range":${range(
                    0,
                    4,
                    0,
                    5,
                )},"newText":"y"}],"$OTHER_URI":[{"range":${range(0, 0, 0, 1)},"newText":"y"}]}}""",
            )
        }

        val edits = service.rename(doc, 4, "y")
        server.await()

        // Renaming the declaration here and leaving the references in Other.kt alone would break
        // the project; the editor cannot edit that file, so the whole rename is declined.
        assertTrue(edits.isEmpty(), "a cross-file rename must not half-apply: was $edits")
    }

    @Test
    fun `rename refuses a workspace edit carrying a file operation`() = withServer("val x = 1") {
        val server = async {
            respond(
                "textDocument/rename",
                """{"documentChanges":[
                  {"textDocument":{"uri":"$URI","version":2},"edits":[{"range":${range(0, 4, 0, 5)},"newText":"y"}]},
                  {"kind":"rename","oldUri":"$URI","newUri":"$OTHER_URI"}
                ]}""",
            )
        }

        val edits = service.rename(doc, 4, "y")
        server.await()

        assertTrue(edits.isEmpty(), "a rename that also renames the file is not ours to apply: was $edits")
    }

    @Test
    fun `codeActions refuse an action whose edit reaches another file`() = withServer("val x = 1") {
        val server = async {
            respond(
                "textDocument/codeAction",
                """[
                  {"title":"Move to Other.kt","kind":"refactor",
                   "edit":{"changes":{"$URI":[{"range":${range(0, 0, 0, 0)},"newText":""}],
                                      "$OTHER_URI":[{"range":${range(0, 0, 0, 0)},"newText":"val x = 1"}]}}},
                  {"title":"Local only","kind":"quickfix",
                   "edit":{"changes":{"$URI":[{"range":${range(0, 0, 0, 0)},"newText":"import foo\n"}]}}}
                ]""",
            )
        }

        val actions = service.codeActions(doc, 1..1)
        server.await()

        assertEquals(listOf("Local only"), actions.map { it.title })
    }

    @Test
    fun `a completion carries its additional edits`() = withServer("val x = fo") {
        val server = async {
            respond(
                "textDocument/completion",
                """[{"label":"foo","kind":3,
                   "textEdit":{"range":${range(0, 8, 0, 10)},"newText":"foo"},
                   "additionalTextEdits":[{"range":${range(0, 0, 0, 0)},"newText":"import a.foo\n"}]}]""",
            )
        }

        val items = service.completions(doc, 10)
        server.await()

        // The import half of an auto-import completion; without it the symbol lands unresolved.
        assertEquals(listOf(TextEdit(0 until 0, "import a.foo\n")), items[0].additionalEdits)
        assertEquals(8..9, items[0].replaceRange)
    }

    @Test
    fun `a diagnostic's opaque data is sent back with a code action request`() = withServer("val x = 1") {
        publish(
            URI,
            """[{"range":${range(0, 0, 0, 3)},"message":"unresolved","severity":1,"data":{"fixId":"import-42"}}]""",
        )
        val server = async { respond("textDocument/codeAction", "[]") }

        service.codeActions(doc, 1..1)
        val sent = server.await()

        // Servers key their quick fixes on this value and expect it back verbatim.
        val forwarded = sent.params["context"]!!.jsonObject["diagnostics"]!!.jsonArray[0].jsonObject
        assertEquals("import-42", forwarded["data"]!!.jsonObject["fixId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the adapter declares rename support`() {
        val client = LspClient(ChannelLspTransport(), CoroutineScope(Dispatchers.Default))
        assertTrue(LspLanguageService(client, URI, "kotlin").supportsRename)
    }

    @Test
    fun `codeActions omit an action the server marked disabled`() = withServer("val x = 1") {
        val server = async {
            respond(
                "textDocument/codeAction",
                """[
                  {"title":"Cannot apply here","kind":"quickfix","disabled":{"reason":"not in scope"},
                   "edit":{"changes":{"$URI":[{"range":${range(0, 0, 0, 0)},"newText":"nope"}]}}},
                  {"title":"Enabled","kind":"quickfix",
                   "edit":{"changes":{"$URI":[{"range":${range(0, 0, 0, 0)},"newText":"import foo\n"}]}}}
                ]""",
            )
        }

        val actions = service.codeActions(doc, 1..1)
        server.await()

        assertEquals(listOf("Enabled"), actions.map { it.title })
    }

    @Test
    fun `signature help keeps the server's parameter range for a repeated type`() = withServer("foo(1, 2)") {
        val server = async {
            respond(
                "textDocument/signatureHelp",
                """{"signatures":[{"label":"foo(Int, Int)",
                   "parameters":[{"label":[4,7]},{"label":[9,12]}]}],
                   "activeSignature":0,"activeParameter":1}""",
            )
        }

        val help = service.signatureHelp(doc, 7)
        server.await()

        val params = help!!.signatures[0].parameters
        assertEquals(listOf("Int", "Int"), params.map { it.label })
        // Both parameters read "Int"; only the range says which occurrence is the second one.
        assertEquals(4..6, params[0].labelRange)
        assertEquals(9..11, params[1].labelRange)
    }

    @Test
    fun `a string parameter label has no range`() = withServer("foo(1)") {
        val server = async {
            respond(
                "textDocument/signatureHelp",
                """{"signatures":[{"label":"foo(count: Int)","parameters":[{"label":"count: Int"}]}]}""",
            )
        }

        val help = service.signatureHelp(doc, 4)
        server.await()

        val param = help!!.signatures[0].parameters[0]
        assertEquals("count: Int", param.label)
        assertNull(param.labelRange, "a string label leaves the popup to match on text")
    }

    // ── Local behaviour ──────────────────────────────────────────────────────

    @Test
    fun `autoClose handles brackets and quotes`() = withServer("") {
        assertEquals("}", service.autoClose(doc, 0, '{'))
        assertEquals("]", service.autoClose(doc, 0, '['))
        assertEquals(")", service.autoClose(doc, 0, '('))
        assertEquals("\"", service.autoClose(doc, 0, '"'))
        assertNull(service.autoClose(doc, 0, 'a'))
    }
}
