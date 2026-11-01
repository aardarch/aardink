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
package com.aardarch.aardink.languages

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.DocumentSymbol
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.SymbolKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentSymbolsTest {

    /** The tree as `name(child, child)` strings, for compact comparison. */
    private fun outline(symbols: List<DocumentSymbol>): List<String> = symbols.map { symbol ->
        if (symbol.children.isEmpty()) symbol.name else "${symbol.name}(${outline(symbol.children).joinToString(", ")})"
    }

    private suspend fun symbolsOf(service: LanguageService?, text: String): List<DocumentSymbol> =
        service!!.documentSymbols(CodeDocument(text))

    private fun DocumentSymbol.textIn(text: String) = text.substring(range.first, range.last + 1)

    private fun DocumentSymbol.nameIn(text: String) = text.substring(selectionRange.first, selectionRange.last + 1)

    @Test
    fun `xml elements nest, named by tag with their id as detail`() = runTest {
        val text = "<?xml version=\"1.0\"?>\n<page id=\"home\">\n  <text/>\n  <row><cell>x</cell></row>\n</page>"
        val symbols = symbolsOf(BuiltInLanguages.Xml.languageService, text)
        assertEquals(listOf("page(text, row(cell))"), outline(symbols))
        val page = symbols.single()
        assertEquals("home", page.detail)
        assertTrue(page.textIn(text).startsWith("<page") && page.textIn(text).endsWith("</page>"))
        assertEquals("page", page.nameIn(text))
    }

    @Test
    fun `an unclosed xml element ends at its parent's end`() = runTest {
        val text = "<a>\n  <b>\n</a>"
        val symbols = symbolsOf(BuiltInLanguages.Xml.languageService, text)
        assertEquals(listOf("a(b)"), outline(symbols))
    }

    @Test
    fun `html void elements have no children`() = runTest {
        val text = "<body><br><p>hi</p></body>"
        assertEquals(listOf("body(br, p)"), outline(symbolsOf(BuiltInLanguages.Html.languageService, text)))
    }

    @Test
    fun `a schema service has the xml structure through its base`() = runTest {
        val service = XmlSchemaLanguageService(XmlSchema(elements = emptyMap(), strict = false))
        assertEquals(listOf("a(b)"), outline(service.documentSymbols(CodeDocument("<a><b/></a>"))))
    }

    @Test
    fun `json properties nest, arrays name container items by index`() = runTest {
        val text = "{\n  \"name\": \"x\",\n  \"deps\": { \"a\": 1 },\n  \"list\": [ { \"k\": true }, 2 ]\n}"
        val symbols = symbolsOf(BuiltInLanguages.Json.languageService, text)
        assertEquals(listOf("name", "deps(a)", "list(0(k))"), outline(symbols))
        assertEquals(SymbolKind.String, symbols[0].kind)
        assertEquals(SymbolKind.Object, symbols[1].kind)
        assertEquals("\"deps\": { \"a\": 1 }", symbols[1].textIn(text))
        assertEquals("deps", symbols[1].nameIn(text))
    }

    @Test
    fun `broken json keeps the structure it has`() = runTest {
        val text = "{ \"a\": { \"b\": 1, \"c\": "
        assertEquals(listOf("a(b)"), outline(symbolsOf(BuiltInLanguages.Json.languageService, text)))
    }

    @Test
    fun `toml tables hold their keys`() = runTest {
        val text = "title = \"t\"\n\n[versions]\nkotlin = \"2.4\"\n\n[[plugins]]\nid = \"x\"\n"
        val symbols = symbolsOf(BuiltInLanguages.Toml.languageService, text)
        assertEquals(listOf("title", "versions(kotlin)", "plugins(id)"), outline(symbols))
        assertEquals(SymbolKind.Array, symbols[2].kind)
        assertEquals("versions", symbols[1].nameIn(text))
    }

    @Test
    fun `kotlin declarations nest by their braces, locals left out`() = runTest {
        val text = """
            package p

            data class User(val name: String) {
                val id = 1
                fun greet(): String {
                    val local = "{"
                    return name
                }
                companion object {
                    const val MAX = 3
                }
            }

            fun String.shout() = uppercase()

            interface Shape
        """.trimIndent()
        val symbols = symbolsOf(BuiltInLanguages.Kotlin.languageService, text)
        assertEquals(listOf("User(id, greet, Companion(MAX))", "shout", "Shape"), outline(symbols))
        assertEquals(SymbolKind.Class, symbols[0].kind)
        assertEquals(SymbolKind.Method, symbols[0].children[1].kind)
        assertEquals(SymbolKind.Constant, symbols[0].children[2].children[0].kind)
        assertEquals(SymbolKind.Function, symbols[1].kind)
        assertEquals(SymbolKind.Interface, symbols[2].kind)
        assertEquals("User", symbols[0].nameIn(text))
    }

    @Test
    fun `markdown headings nest by level, not inside code fences`() = runTest {
        val text = "# Title\nintro\n## One\n```\n# not a heading\n```\n### Deep\n## Two\n# Next"
        val symbols = symbolsOf(BuiltInLanguages.Markdown.languageService, text)
        assertEquals(listOf("Title(One(Deep), Two)", "Next"), outline(symbols))
        assertEquals("Title", symbols[0].nameIn(text))
        assertTrue(symbols[0].textIn(text).endsWith("## Two"))
    }

    @Test
    fun `markdown keeps a hash in a heading's text, drops a closing run`() = runTest {
        val text = "# C#\n## Foo ##\n## F# #"
        val symbols = symbolsOf(BuiltInLanguages.Markdown.languageService, text)
        assertEquals(listOf("C#(Foo, F#)"), outline(symbols))
        assertEquals("C#", symbols[0].nameIn(text))
        assertEquals("F#", symbols[0].children[1].nameIn(text))
    }

    @Test
    fun `a markdown fence closes only with as long a run of its own character`() = runTest {
        val text = "# Top\n````md\n```\n# not a heading\n~~~~\n````\n## After"
        assertEquals(listOf("Top(After)"), outline(symbolsOf(BuiltInLanguages.Markdown.languageService, text)))
    }

    @Test
    fun `kotlin names are selected where they are declared`() = runTest {
        val text = "val a = 1\nfun f() {}\n@Suppress(\"f\") fun f2() {}\nobject O {\n    companion object {}\n}"
        val symbols = symbolsOf(BuiltInLanguages.Kotlin.languageService, text)
        assertEquals(listOf("a", "f", "f2", "O(Companion)"), outline(symbols))
        assertEquals(text.indexOf("a ="), symbols[0].selectionRange.first)
        assertEquals(text.indexOf("f()"), symbols[1].selectionRange.first)
        assertEquals("f2", symbols[2].nameIn(text))
        assertEquals("object", symbols[3].children[0].nameIn(text))
        assertEquals(text.indexOf("companion"), symbols[3].children[0].range.first)
    }

    @Test
    fun `a kotlin header over several lines holds its body`() = runTest {
        val text = """
            class User(
                val name: String,
                val age: Int = maxOf(1, 2),
            ) : Base(
                name,
            ) {
                fun greet(
                    loud: Boolean,
                ): String {
                    return name
                }
            }

            data class Point(
                val x: Int,
            )

            fun after() = 1
        """.trimIndent()
        val symbols = symbolsOf(BuiltInLanguages.Kotlin.languageService, text)
        assertEquals(listOf("User(greet)", "Point", "after"), outline(symbols))
        assertTrue(symbols[0].textIn(text).endsWith("return name\n    }\n}"))
        assertTrue(symbols[0].children[0].textIn(text).endsWith("return name\n    }"))
        assertTrue(symbols[1].textIn(text).endsWith(")"))
    }

    @Test
    fun `an unclosed kotlin header does not hide what follows`() = runTest {
        val text = "fun typing(\n\nclass Next {\n    fun inside() {}\n}"
        assertEquals(listOf("typing", "Next(inside)"), outline(symbolsOf(BuiltInLanguages.Kotlin.languageService, text)))
    }

    @Test
    fun `json keys are named with their escapes decoded`() = runTest {
        // The keys as JSON spells them: an escaped `a` (backslash, u, 0061), a newline escape, and a backslash before q, no escape.
        val escapedA = "\\" + "u0061"
        val text = "{ \"$escapedA\": 1, \"a\\nb\": 2, \"\\q\": 3 }"
        val symbols = symbolsOf(BuiltInLanguages.Json.languageService, text)
        assertEquals(listOf("a", "a\nb", "\\q"), symbols.map { it.name })
        assertEquals(escapedA, symbols[0].nameIn(text))
    }

    @Test
    fun `a json comment right after a value is skipped`() = runTest {
        val text = "{\"a\":1/*}*/,\"b\":2}"
        assertEquals(listOf("a", "b"), outline(symbolsOf(BuiltInLanguages.Json.languageService, text)))
    }

    @Test
    fun `a markdown fence indented four spaces is code, not a fence`() = runTest {
        val text = "    ```\n\n# Visible"
        assertEquals(listOf("Visible"), outline(symbolsOf(BuiltInLanguages.Markdown.languageService, text)))
    }

    @Test
    fun `toml names keep quoted brackets and equals signs`() = runTest {
        val text = "[\"a]b\"]\n\"k=v\" = 1\n[[\"x]]y\"]]\nz = 2"
        val symbols = symbolsOf(BuiltInLanguages.Toml.languageService, text)
        assertEquals(listOf("\"a]b\"(\"k=v\")", "\"x]]y\"(z)"), outline(symbols))
        assertEquals("\"a]b\"", symbols[0].nameIn(text))
        assertEquals("\"k=v\"", symbols[0].children[0].nameIn(text))
        assertEquals("\"x]]y\"", symbols[1].nameIn(text))
    }
}
