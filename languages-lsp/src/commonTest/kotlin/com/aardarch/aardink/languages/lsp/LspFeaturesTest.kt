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

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.ColorInfo
import com.aardarch.aardink.core.ColorPresentation
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.DocumentHighlight
import com.aardarch.aardink.core.DocumentHighlightKind
import com.aardarch.aardink.core.DocumentLink
import com.aardarch.aardink.core.DocumentSymbol
import com.aardarch.aardink.core.FoldKind
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.LinkedEditingRanges
import com.aardarch.aardink.core.Location
import com.aardarch.aardink.core.SelectionRange
import com.aardarch.aardink.core.SymbolKind
import com.aardarch.aardink.core.TextEdit
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val RESOLVE = "completionItem/resolve"
private const val RESOLVING_SERVER = """{"completionProvider":{"resolveProvider":true}}"""

private fun json(text: String): JsonElement = LspJson.parseToJsonElement(text)

/** Has the server offer [itemsJson], and returns what the service made of them. */
private suspend fun Harness.offered(itemsJson: String, document: CodeDocument = doc): List<CompletionItem> {
    val server = async { respond("textDocument/completion", itemsJson) }
    val items = service.completions(document, 0)
    server.await()
    return items
}

class LspFeaturesTest {

    // ── What the server supports ─────────────────────────────────────────────

    @Test
    fun `anything counts as supported while the capabilities are not known`() = withServer("x") {
        assertTrue(service.supports("renameProvider"))
        assertTrue(service.supports("anyOtherProvider"))
    }

    @Test
    fun `a provider counts when it is true or an options object`() = withServer("x") {
        serverDeclares("""{"hoverProvider":true,"completionProvider":{},"definitionProvider":false,"referencesProvider":null}""")

        assertTrue(service.supports("hoverProvider"))
        assertTrue(service.supports("completionProvider"))
        assertFalse(service.supports("definitionProvider"))
        assertFalse(service.supports("referencesProvider"))
        assertFalse(service.supports("renameProvider"))
    }

    @Test
    fun `a feature registered later counts until it is taken back`() = withServer("x") {
        serverDeclares("{}")
        assertFalse(service.supports("colorProvider"))
        assertFalse(service.supports("documentFormattingProvider"))
        assertFalse(service.supports("documentSymbolProvider"))

        serverRegisters(
            registration("textDocument/documentColor"),
            registration("textDocument/formatting"),
            registration("textDocument/documentSymbol"),
        )
        assertTrue(service.supports("colorProvider"))
        assertTrue(service.supports("documentFormattingProvider"))
        assertTrue(service.supports("documentSymbolProvider"))

        serverAsks("2", "client/unregisterCapability", """{"unregisterations":[{"id":"textDocument/documentColor"}]}""")
        nextSent()
        assertFalse(service.supports("colorProvider"))
    }

    @Test
    fun `a request the server did not declare is not sent`() = withServer("val x = 1") {
        serverDeclares("{}")
        val anyColor = ColorInfo(0..0, Color.Red)

        assertTrue(service.completions(doc, 1).isEmpty())
        assertNull(service.hoverDoc(doc, 1))
        assertEquals(doc.text, service.format(doc, FormattingOptions()))
        assertTrue(service.formatRange(doc, 0..1, FormattingOptions()).isEmpty())
        assertTrue(service.codeActions(doc, 0..1).isEmpty())
        assertNull(service.definition(doc, 1))
        assertTrue(service.references(doc, 1).isEmpty())
        assertNull(service.signatureHelp(doc, 1))
        assertNull(service.prepareRename(doc, 1))
        assertTrue(service.rename(doc, 1, "y").isEmpty())
        assertTrue(service.documentSymbols(doc).isEmpty())
        assertTrue(service.documentHighlights(doc, 1).isEmpty())
        assertTrue(service.selectionRanges(doc, listOf(1)).isEmpty())
        assertNull(service.linkedEditingRanges(doc, 1))
        assertTrue(service.documentLinks(doc).isEmpty())
        assertTrue(service.documentColors(doc).isEmpty())
        assertTrue(service.colorPresentations(doc, anyColor, Color.Blue).isEmpty())
        assertTrue(service.typeDefinition(doc, 1).isEmpty())
        assertTrue(service.implementation(doc, 1).isEmpty())
        assertTrue(service.declaration(doc, 1).isEmpty())
        assertTrue(LspFoldingProvider(service).requestFoldableRanges(doc).isEmpty())

        assertNothingSent("a server that declared nothing is asked nothing")
    }

    @Test
    fun `a request the server registered later is sent`() = withServer("val x = 1") {
        serverDeclares("{}")
        serverRegisters(registration("textDocument/hover"))

        val server = async { respond("textDocument/hover", """{"contents":"Doc"}""") }

        assertEquals("Doc", service.hoverDoc(doc, 1)?.content)
        server.await()
    }

    // ── Rename's gate ────────────────────────────────────────────────────────

    @Test
    fun `rename support follows the server when the host did not say`() = withServer("val value = 1") {
        assertTrue(service.supportsRename, "not known yet")

        serverDeclares("{}")

        assertFalse(service.supportsRename)
    }

    @Test
    fun `rename support follows a server that declared it`() = withServer("val value = 1") {
        serverDeclares("""{"renameProvider":true}""")

        assertTrue(service.supportsRename)
        assertEquals(4..8, service.prepareRename(doc, 5), "it has no prepareRename, so the word is the range")
        assertNothingSent("no prepareRename to ask")
    }

    @Test
    fun `rename registered later is supported, and prepareRename probed`() = withServer("val value = 1") {
        serverDeclares("{}")
        serverRegisters(registration("textDocument/rename"))
        assertTrue(service.supportsRename)

        val server = async { respondWithMethodNotFound("textDocument/prepareRename") }

        assertEquals(4..8, service.prepareRename(doc, 5))
        server.await()
    }

    @Test
    fun `the host's word on rename beats what the server declared`() = withServer("val value = 1") {
        serverDeclares("""{"renameProvider":true}""")
        assertFalse(serviceWithRenameSupport(LspRenameSupport.Unsupported).supportsRename)

        serverRegisters(registration("textDocument/prepareRename"))
        val other = LspLanguageService(client, OTHER_URI, "kotlin", serverRenameSupport = LspRenameSupport.Rename)
        assertTrue(other.supportsRename)
    }

    @Test
    fun `a host that declared rename itself still gets it from a server that said nothing`() = withServer("val value = 1") {
        serverDeclares("{}")
        val service = serviceWithRenameSupport(LspRenameSupport.Rename)

        val server = async { respond("textDocument/rename", "null") }

        assertTrue(service.rename(doc, 5, "other").isEmpty())
        server.await()
    }

    // ── Document symbols ─────────────────────────────────────────────────────

    @Test
    fun `hierarchical symbols keep their tree, in document order`() = withServer("class A {\n  fun b() {}\n}\nval c = 1") {
        val server = async {
            respond(
                "textDocument/documentSymbol",
                """[
                  {"name":"c","kind":13,"range":${range(3, 0, 3, 9)},"selectionRange":${range(3, 4, 3, 5)}},
                  {"name":"A","kind":5,"detail":"class","range":${range(0, 0, 2, 1)},"selectionRange":${range(0, 6, 0, 7)},
                   "children":[{"name":"b","kind":6,"detail":"","range":${range(1, 2, 1, 12)},"selectionRange":${range(1, 6, 1, 7)}}]}
                ]""",
            )
        }

        val symbols = service.documentSymbols(doc)
        val sent = server.await()

        assertEquals(URI, sent.params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(
                DocumentSymbol(
                    name = "A",
                    kind = SymbolKind.Class,
                    range = 0..23,
                    selectionRange = 6..6,
                    detail = "class",
                    children = listOf(DocumentSymbol("b", SymbolKind.Method, 12..21, 16..16)),
                ),
                DocumentSymbol("c", SymbolKind.Variable, 25..33, 29..29),
            ),
            symbols,
        )
    }

    @Test
    fun `a flat symbol list is nested by range, without other files`() = withServer("class A {\n  fun b() {}\n}\nval c = 1") {
        val server = async {
            respond(
                "textDocument/documentSymbol",
                """[
                  {"name":"b","kind":6,"location":{"uri":"$URI","range":${range(1, 2, 1, 12)}},"containerName":"A"},
                  {"name":"c","kind":13,"location":{"uri":"$URI","range":${range(3, 0, 3, 9)}}},
                  {"name":"elsewhere","kind":5,"location":{"uri":"$OTHER_URI","range":${range(0, 0, 1, 0)}}},
                  {"name":"A","kind":5,"location":{"uri":"$URI","range":${range(0, 0, 2, 1)}}}
                ]""",
            )
        }

        val symbols = service.documentSymbols(doc)
        server.await()

        assertEquals(
            listOf(
                DocumentSymbol("A", SymbolKind.Class, 0..23, children = listOf(DocumentSymbol("b", SymbolKind.Method, 12..21))),
                DocumentSymbol("c", SymbolKind.Variable, 25..33),
            ),
            symbols,
        )
    }

    @Test
    fun `a symbol of a kind this client does not know is kept`() = withServer("x") {
        val server = async {
            respond(
                "textDocument/documentSymbol",
                """[{"name":"x","kind":99,"range":${range(0, 0, 0, 1)},"selectionRange":${range(0, 0, 0, 1)}}]""",
            )
        }

        assertEquals(SymbolKind.Property, service.documentSymbols(doc).single().kind)
        server.await()
    }

    @Test
    fun `no answer means no symbols`() = withServer("x") {
        val server = async { respond("textDocument/documentSymbol", "null") }

        assertTrue(service.documentSymbols(doc).isEmpty())
        server.await()
    }

    // ── Highlights, selection, linked editing ────────────────────────────────

    @Test
    fun `highlights map their kinds`() = withServer("val x = x + x + x") {
        val server = async {
            respond(
                "textDocument/documentHighlight",
                """[{"range":${range(0, 4, 0, 5)},"kind":3},{"range":${range(0, 8, 0, 9)},"kind":2},
                    {"range":${range(0, 12, 0, 13)},"kind":1},{"range":${range(0, 16, 0, 17)}}]""",
            )
        }

        val highlights = service.documentHighlights(doc, 4)
        val sent = server.await()

        assertEquals(4, sent.params["position"]!!.jsonObject["character"]!!.jsonPrimitive.int)
        assertEquals(
            listOf(
                DocumentHighlight(4..4, DocumentHighlightKind.Write),
                DocumentHighlight(8..8, DocumentHighlightKind.Read),
                DocumentHighlight(12..12, DocumentHighlightKind.Text),
                DocumentHighlight(16..16, DocumentHighlightKind.Text),
            ),
            highlights,
        )
    }

    @Test
    fun `selection ranges keep their chain of parents, one chain per offset`() = withServer("foo(bar)\nbaz") {
        val server = async {
            respond(
                "textDocument/selectionRange",
                """[{"range":${range(0, 4, 0, 7)},"parent":{"range":${range(0, 0, 0, 8)}}},{"range":${range(1, 0, 1, 3)}}]""",
            )
        }

        val ranges = service.selectionRanges(doc, listOf(5, 10))
        val sent = server.await()

        val positions = sent.params["positions"]!!.jsonArray.map {
            it.jsonObject["line"]!!.jsonPrimitive.int to
                it.jsonObject["character"]!!.jsonPrimitive.int
        }
        assertEquals(listOf(0 to 5, 1 to 1), positions)
        assertEquals(listOf(SelectionRange(4..6, SelectionRange(0..7)), SelectionRange(9..11)), ranges)
    }

    @Test
    fun `selection ranges that do not match the offsets are none at all`() = withServer("foo") {
        val server = async { respond("textDocument/selectionRange", """[{"range":${range(0, 0, 0, 3)}}]""") }

        assertTrue(service.selectionRanges(doc, listOf(0, 1)).isEmpty())
        server.await()
    }

    @Test
    fun `linked editing ranges map to offsets, and null is none`() = withServer("<a></a>") {
        val server = async {
            respond("textDocument/linkedEditingRange", """{"ranges":[${range(0, 1, 0, 2)},${range(0, 5, 0, 6)}],"wordPattern":"[a-z]+"}""")
        }
        assertEquals(LinkedEditingRanges(listOf(1..1, 5..5)), service.linkedEditingRanges(doc, 1))
        server.await()

        val nothing = async { respond("textDocument/linkedEditingRange", "null") }
        assertNull(service.linkedEditingRanges(doc, 1))
        nothing.await()

        val empty = async { respond("textDocument/linkedEditingRange", """{"ranges":[]}""") }
        assertNull(service.linkedEditingRanges(doc, 1))
        empty.await()
    }

    // ── Links and colours ────────────────────────────────────────────────────

    @Test
    fun `links with a target are kept, and those left to resolve are not`() = withServer("import a\nimport b") {
        val server = async {
            respond(
                "textDocument/documentLink",
                """[{"range":${range(0, 7, 0, 8)},"target":"file:///a.kt"},{"range":${range(1, 7, 1, 8)},"data":{"id":2}}]""",
            )
        }

        assertEquals(listOf(DocumentLink(7..7, "file:///a.kt")), service.documentLinks(doc))
        server.await()
    }

    @Test
    fun `document colours map their channels, and a wild one is kept in range`() = withServer("red blue") {
        val server = async {
            respond(
                "textDocument/documentColor",
                """[{"range":${range(0, 0, 0, 3)},"color":{"red":1,"green":0,"blue":0,"alpha":1}},
                    {"range":${range(0, 4, 0, 8)},"color":{"red":-1,"green":0,"blue":2,"alpha":0.5}},
                    {"range":${range(0, 4, 0, 8)},"color":{"red":1}}]""",
            )
        }

        val colors = service.documentColors(doc)
        server.await()

        assertEquals(listOf(ColorInfo(0..2, Color(1f, 0f, 0f, 1f)), ColorInfo(4..7, Color(0f, 0f, 1f, 0.5f))), colors)
    }

    @Test
    fun `colour presentations send the colour and its range`() = withServer("#000") {
        val server = async {
            respond(
                "textDocument/colorPresentation",
                """[{"label":"#ff0000"},
                    {"label":"red","textEdit":{"range":${range(0, 0, 0, 4)},"newText":"Color.Red"}},
                    {"label":"needs an import","additionalTextEdits":[{"range":${range(0, 0, 0, 0)},"newText":"import x\n"}]}]""",
            )
        }

        val presentations = service.colorPresentations(doc, ColorInfo(0..3, Color.Black), Color(1f, 0f, 0f, 0.5f))
        val sent = server.await()

        val color = sent.params["color"]!!.jsonObject
        assertEquals(1f, color["red"]!!.jsonPrimitive.float)
        assertEquals(0f, color["blue"]!!.jsonPrimitive.float)
        assertEquals(0.5f, color["alpha"]!!.jsonPrimitive.float, 0.01f)
        assertEquals(json(range(0, 0, 0, 4)), sent.params["range"])
        assertEquals(listOf(ColorPresentation("#ff0000"), ColorPresentation("red", TextEdit(0..3, "Color.Red"))), presentations)
    }

    // ── Navigation for a host's own menu items ───────────────────────────────

    @Test
    fun `typeDefinition, implementation and declaration keep every target`() = withServer("val x = 1") {
        val location = """{"uri":"$URI","range":${range(0, 4, 0, 5)}}"""
        val link = """{"targetUri":"$OTHER_URI","targetRange":${range(8, 0, 9, 0)},"targetSelectionRange":${range(8, 2, 8, 3)}}"""
        val here = Location(URI, 4..4, 0, 4)
        val there = Location(OTHER_URI, IntRange.EMPTY, 8, 2)

        val typeServer = async { respond("textDocument/typeDefinition", "[$link]") }
        assertEquals(listOf(there), service.typeDefinition(doc, 4))
        typeServer.await()

        val implementationServer = async { respond("textDocument/implementation", "[$location,$location]") }
        assertEquals(listOf(here, here), service.implementation(doc, 4))
        implementationServer.await()

        val declarationServer = async { respond("textDocument/declaration", location) }
        assertEquals(listOf(here), service.declaration(doc, 4))
        declarationServer.await()

        val noServer = async { respond("textDocument/declaration", "null") }
        assertTrue(service.declaration(doc, 4).isEmpty())
        noServer.await()
    }

    // ── Resolving a completion ───────────────────────────────────────────────

    @Test
    fun `the server is sent back its own item, and what it adds is kept`() = withServer("x") {
        val raw = """{"label":"map","kind":2,"insertText":"map()","data":{"id":7},"labelDetails":{"detail":"(f)"}}"""
        val item = offered("[$raw]").single()
        assertNull(item.documentation)

        val server = async {
            respond(
                RESOLVE,
                """{"label":"map","detail":"fun map()","documentation":{"kind":"markdown","value":"**maps**"},"data":{"id":7}}""",
            )
        }
        val resolved = service.resolveCompletion(item)
        val sent = server.await()

        assertEquals(json(raw), sent["params"], "item for item, with what this client does not read")
        assertEquals(item.copy(detail = "fun map()", documentation = "**maps**"), resolved)
    }

    @Test
    fun `resolved documentation in plain text is escaped`() = withServer("x") {
        val item = offered("""[{"label":"a"}]""").single()

        val server = async { respond(RESOLVE, """{"label":"a","documentation":"a*b"}""") }

        assertEquals("""a\*b""", service.resolveCompletion(item).documentation)
        server.await()
    }

    @Test
    fun `equal items are told apart by instance`() = withServer("x") {
        val items = offered("""[{"label":"a","data":1},{"label":"a","data":2}]""")
        assertEquals(items[0], items[1])

        val server = async { respond(RESOLVE, """{"label":"a"}""") }
        service.resolveCompletion(items[1])
        val sent = server.await()

        assertEquals(2, sent.params["data"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an item the editor re-addressed still resolves, keeping its new range`() = withServer("val v = fo") {
        val raw = """{"label":"foo","textEdit":{"range":${range(0, 8, 0, 10)},"newText":"foo"},"data":1}"""
        val item = offered("""[$raw,{"label":"bar","textEdit":{"range":${range(0, 8, 0, 10)},"newText":"bar"},"data":2}]""")[0]
        val carried = item.copy(replaceRange = 8..10)

        val server = async { respond(RESOLVE, """{"label":"foo","documentation":"Doc"}""") }
        val resolved = service.resolveCompletion(carried)
        val sent = server.await()

        assertEquals(1, sent.params["data"]!!.jsonPrimitive.int)
        assertEquals(8..10, resolved.replaceRange)
        assertEquals("Doc", resolved.documentation)
    }

    @Test
    fun `a re-addressed item that fits two offered ones is left as it is`() = withServer("val v = fo") {
        val edit = """{"range":${range(0, 8, 0, 10)},"newText":"foo"}"""
        val carried = offered("""[{"label":"foo","textEdit":$edit,"data":1},{"label":"foo","textEdit":$edit,"data":2}]""")[0].copy(
            replaceRange =
            8..10,
        )

        assertSame(carried, service.resolveCompletion(carried))
        assertNothingSent("which of the two it is cannot be known")
    }

    @Test
    fun `an item the service did not offer comes back as it is`() = withServer("x") {
        offered("""[{"label":"a"}]""")
        val stranger = CompletionItem("b", CompletionKind.Text, "b")

        assertSame(stranger, service.resolveCompletion(stranger))
        assertNothingSent("the server never saw it")
    }

    @Test
    fun `items of a closed document are forgotten`() = withServer("x") {
        val item = offered("""[{"label":"a"}]""").single()
        service.didClose()
        assertEquals("textDocument/didClose", nextSent().method)

        assertSame(item, service.resolveCompletion(item))
        assertNothingSent("the document is gone")
    }

    @Test
    fun `a server without resolveProvider is not asked`() = withServer("x") {
        serverDeclares("""{"completionProvider":{}}""")
        val item = offered("""[{"label":"a"}]""").single()

        assertSame(item, service.resolveCompletion(item))
        assertNothingSent("it did not declare resolveProvider")
    }

    @Test
    fun `a server that declared resolveProvider is asked`() = withServer("x") {
        serverDeclares(RESOLVING_SERVER)
        val item = offered("""[{"label":"a"}]""").single()

        val server = async { respond(RESOLVE, """{"label":"a","detail":"d"}""") }

        assertEquals("d", service.resolveCompletion(item).detail)
        server.await()
    }

    @Test
    fun `resolveProvider in a registration's options counts, and so do no options`() {
        fun check(options: String?, asked: Boolean) = withServer("x") {
            serverDeclares("{}")
            val member = options?.let { ""","registerOptions":$it""" }.orEmpty()
            serverRegisters("""{"id":"c","method":"textDocument/completion"$member}""")
            val item = offered("""[{"label":"a"}]""").single()

            if (asked) {
                val server = async { respond(RESOLVE, """{"label":"a","detail":"d"}""") }
                assertEquals("d", service.resolveCompletion(item).detail)
                server.await()
            } else {
                assertSame(item, service.resolveCompletion(item))
                assertNothingSent("for $options")
            }
        }
        check("""{"resolveProvider":true}""", asked = true)
        check(null, asked = true)
        check("""{"resolveProvider":false}""", asked = false)
        check("{}", asked = true)
    }

    @Test
    fun `a failed or empty answer leaves the item as it is`() = withServer("x") {
        val item = offered("""[{"label":"a"}]""").single()

        val failing = async { respondWithError(RESOLVE) }
        assertSame(item, service.resolveCompletion(item))
        failing.await()

        val empty = async { respond(RESOLVE, "null") }
        assertSame(item, service.resolveCompletion(item))
        empty.await()
    }

    @Test
    fun `resolved edits are placed in the attached text as it is now`() = withServer("") {
        serverDeclares("""{"textDocumentSync":2,"completionProvider":{"resolveProvider":true}}""")
        val state = editorState("ab\ncd")
        service.attach(state)
        assertEquals("textDocument/didOpen", nextSent().method)
        val item = offered("""[{"label":"a"}]""", state.document.snapshot()).single()

        state.applyTextEdits(listOf(TextEdit(0 until 0, "x\n")))
        assertEquals("textDocument/didChange", nextSent().method)
        val edit = """{"range":${range(2, 0, 2, 0)},"newText":"import y\n"}"""
        val server = async { respond(RESOLVE, """{"label":"a","additionalTextEdits":[$edit]}""") }
        val resolved = service.resolveCompletion(item)
        server.await()

        assertEquals(listOf(TextEdit(5 until 5, "import y\n")), resolved.additionalEdits)
    }

    @Test
    fun `resolved edits are placed in what didOpen and didChange last sent`() = withServer("ab\ncd") {
        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)
        val item = offered("""[{"label":"a"}]""").single()
        val edit = """{"range":${range(1, 0, 1, 0)},"newText":"y"}"""

        val before = async { respond(RESOLVE, """{"label":"a","additionalTextEdits":[$edit]}""") }
        assertEquals(listOf(TextEdit(3 until 3, "y")), service.resolveCompletion(item).additionalEdits)
        before.await()

        service.didChange(CodeDocument("longer\ncd"), 2)
        assertEquals("textDocument/didChange", nextSent().method)
        val after = async { respond(RESOLVE, """{"label":"a","additionalTextEdits":[$edit]}""") }
        assertEquals(listOf(TextEdit(7 until 7, "y")), service.resolveCompletion(item).additionalEdits)
        after.await()
    }

    @Test
    fun `with no text to place them in, resolved edits are dropped and the rest kept`() = withServer("ab") {
        val own = """{"range":${range(0, 0, 0, 0)},"newText":"own"}"""
        val item = offered("""[{"label":"a","additionalTextEdits":[$own]}]""").single()

        val edit = """{"range":${range(0, 1, 0, 1)},"newText":"y"}"""
        val server = async { respond(RESOLVE, """{"label":"a","detail":"d","additionalTextEdits":[$edit]}""") }
        val resolved = service.resolveCompletion(item)
        server.await()

        assertEquals("d", resolved.detail)
        assertEquals(item.additionalEdits, resolved.additionalEdits)
    }

    @Test
    fun `an answer without edits keeps the item's`() = withServer("ab") {
        val own = """{"range":${range(0, 0, 0, 0)},"newText":"own"}"""
        val item = offered("""[{"label":"a","additionalTextEdits":[$own]}]""").single()

        val server = async { respond(RESOLVE, """{"label":"a","additionalTextEdits":[]}""") }

        assertEquals(item.additionalEdits, service.resolveCompletion(item).additionalEdits)
        server.await()
    }

    // ── Folding ──────────────────────────────────────────────────────────────

    private val tenLines = (1..10).joinToString("\n") { "line $it" }

    @Test
    fun `folding ranges map their kinds and collapsed text, in order`() = withServer(tenLines) {
        val server = async {
            respond(
                "textDocument/foldingRange",
                """[{"startLine":5,"endLine":6,"kind":"imports"},
                    {"startLine":0,"endLine":2,"kind":"comment","collapsedText":"/* licence */"},
                    {"startLine":3,"endLine":4,"kind":"region"},
                    {"startLine":7,"endLine":8,"kind":"something else"}]""",
            )
        }

        val ranges = LspFoldingProvider(service).requestFoldableRanges(doc)
        val sent = server.await()

        assertEquals(URI, sent.params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(
                FoldRange(0, 2, "/* licence */", FoldKind.Comment),
                FoldRange(3, 4, kind = FoldKind.Region),
                FoldRange(5, 6, kind = FoldKind.Imports),
                FoldRange(7, 8),
            ),
            ranges,
        )
    }

    @Test
    fun `folding ranges stay in the document, fold something, and one line starts one`() = withServer(tenLines) {
        val server = async {
            respond(
                "textDocument/foldingRange",
                """[{"startLine":8,"endLine":50},{"startLine":3,"endLine":3},{"startLine":60,"endLine":70},{"startLine":-1,"endLine":2},
                    {"startLine":1,"endLine":2},{"startLine":1,"endLine":4}]""",
            )
        }

        val ranges = LspFoldingProvider(service).requestFoldableRanges(doc)
        server.await()

        assertEquals(listOf(FoldRange(1, 4), FoldRange(8, 9)), ranges)
    }

    @Test
    fun `an empty answer folds nothing, and a server that has no folding says the same`() = withServer(tenLines) {
        val empty = async { respond("textDocument/foldingRange", "[]") }
        assertTrue(LspFoldingProvider(service).requestFoldableRanges(doc).isEmpty())
        empty.await()

        val missing = async { respondWithMethodNotFound("textDocument/foldingRange") }
        assertTrue(LspFoldingProvider(service).requestFoldableRanges(doc).isEmpty())
        missing.await()
    }

    @Test
    fun `no answer is not an empty one, so that nothing gets unfolded`() = withServer(tenLines) {
        val failing = async { respondWithError("textDocument/foldingRange") }
        assertFailsWith<IllegalStateException> { LspFoldingProvider(service).requestFoldableRanges(doc) }
        failing.await()

        val nothing = async { respond("textDocument/foldingRange", "null") }
        assertFailsWith<IllegalStateException> { LspFoldingProvider(service).requestFoldableRanges(doc) }
        nothing.await()
    }

    @Test
    fun `the synchronous call cannot wait for the server`() = withServer(tenLines) {
        assertTrue(LspFoldingProvider(service).foldableRanges(doc).isEmpty())
        assertNothingSent("nothing to wait for it with")
    }

    // ── Markup ───────────────────────────────────────────────────────────────

    private suspend fun Harness.hover(contentsJson: String): String? {
        val server = async { respond("textDocument/hover", """{"contents":$contentsJson}""") }
        val content = service.hoverDoc(doc, 0)?.content
        server.await()
        return content
    }

    private suspend fun Harness.plainHover(text: String): String? = hover("""{"kind":"plaintext","value":${JsonPrimitive(text)}}""")

    @Test
    fun `plain text comes out as Markdown that reads to the letter`() = withServer("x") {
        val text = "# not a heading\n- not a list\n1. not a list\n=== not a rule\n*a* _b_ `c` <d> &amp;"
        val markdown = "\\# not a heading\\\n\\- not a list\\\n1\\. not a list\\\n\\=== not a rule\\\n\\*a\\* \\_b\\_ \\`c\\` \\<d> \\&amp;"

        assertEquals(markdown, plainHover(text))
    }

    @Test
    fun `only what would be Markdown is escaped`() = withServer("x") {
        val text = "a - b + c # d = e 1. f > g ~ h, foo.bar(baz) 2) i"

        assertEquals(text, plainHover(text))
    }

    @Test
    fun `the rest of a line's opening marks and links are escaped`() = withServer("x") {
        assertEquals("""\+ x""", plainHover("+ x"))
        assertEquals("""\~~~""", plainHover("~~~"))
        assertEquals("""12\) x""", plainHover("12) x"))
        assertEquals("""\[a\](b)""", plainHover("[a](b)"))
        assertEquals("""C:\\dir""", plainHover("""C:\dir"""))
    }

    @Test
    fun `plain text keeps its lines and paragraphs, though not its indentation`() = withServer("x") {
        assertEquals("a\\\nb", plainHover("a\nb"))
        assertEquals("a\\\nb", plainHover("a\r\nb"))
        assertEquals("a\n\nb", plainHover("a\n\nb"))
        assertEquals("a\\\nb", plainHover("a\n    b"))
        assertEquals("\\- b", plainHover("\t- b"))
        assertEquals("a\n\nb", plainHover("a\n   \nb"), "a line of spaces is a blank one")
    }

    @Test
    fun `a URL in plain text stays whole for the renderer to link`() = withServer("x") {
        assertEquals("""See https://a.dev/x_y?a=1&b=2 and a\_b""", plainHover("See https://a.dev/x_y?a=1&b=2 and a_b"))
    }

    @Test
    fun `hover in plain text is escaped, and in Markdown left alone`() = withServer("x") {
        assertEquals("""a\*b""", hover("""{"kind":"plaintext","value":"a*b"}"""))
        assertEquals("a*b", hover("""{"kind":"markdown","value":"a*b"}"""))
    }

    @Test
    fun `a bare hover string is a MarkedString, which is Markdown already`() = withServer("x") {
        assertEquals("**a**", hover("\"**a**\""))
        assertEquals("**a**\n\n**b**", hover("""["**a**","**b**"]"""))
    }

    @Test
    fun `a MarkedString's language becomes a code fence`() = withServer("x") {
        assertEquals("```kotlin\nval x: Int\n```", hover("""{"language":"kotlin","value":"val x: Int"}"""))
        assertEquals("Doc\n\n```kotlin\nval x\n```", hover("""["Doc",{"language":"kotlin","value":"val x"}]"""))
        assertEquals("```\nval x\n```", hover("""{"language":"","value":"val x"}"""))
    }

    @Test
    fun `a code fence outlasts the backticks in its code`() = withServer("x") {
        assertEquals("````md\n```\ncode\n```\n````", hover("""{"language":"md","value":"```\ncode\n```"}"""))
    }

    @Test
    fun `an empty MarkedString adds nothing`() = withServer("x") {
        assertEquals("Doc", hover("""["Doc",{"language":"kotlin","value":""}]"""))
        assertNull(hover("""{"language":"kotlin","value":""}"""))
    }

    @Test
    fun `a bare documentation string is plain text, unlike a hover's`() = withServer("x") {
        val items = offered(
            """[{"label":"a","documentation":"a*b"},
                {"label":"b","documentation":{"kind":"markdown","value":"a*b"}},
                {"label":"c","documentation":{"kind":"plaintext","value":"a*b"}}]""",
        )

        assertEquals(listOf("""a\*b""", "a*b", """a\*b"""), items.map { it.documentation })
    }

    @Test
    fun `signature documentation is Markdown too`() = withServer("foo(") {
        val server = async {
            respond(
                "textDocument/signatureHelp",
                """{"signatures":[{"label":"foo(a)","documentation":"a_b","parameters":[{"label":"a","documentation":"c_d"}]}]}""",
            )
        }

        val signature = service.signatureHelp(doc, 4)!!.signatures.single()
        server.await()

        assertEquals("""a\_b""", signature.documentation)
        assertEquals("""c\_d""", signature.parameters.single().documentation)
    }
}
