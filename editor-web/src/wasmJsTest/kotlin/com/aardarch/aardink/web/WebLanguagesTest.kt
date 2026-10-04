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

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenFontStyle
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.ui.EditorThemes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Languages and themes registered from the page: grammars, their colours, and the providers. */
class WebLanguagesTest {

    private val toy = """
        {
          "id": "toy",
          "grammar": {
            "tokenPostfix": ".toy",
            "comments": { "lineComment": "#" },
            "tokenizer": { "root": [ ["\\bshout\\b", "keyword"], ["<\\w+", "tag"], ["\\w+", "text"] ] }
          }
        }
    """.trimIndent()

    @Test
    fun `a registered language highlights with its grammar`() {
        assertEquals("toy", AardinkWeb.registerLanguage(toy))
        val language = AardinkWeb.registry.byId("toy")!!
        val tokens = language.tokenizer.tokenizeFull("shout <b")
        assertEquals(listOf(NamedTokenType("keyword.toy"), NamedTokenType("tag.toy")), tokens.map { it.type })
        assertEquals(CommentSyntax(line = "#"), language.tokenizer.commentSyntax)
    }

    @Test
    fun `a grammar with a lookbehind is refused, saying where`() {
        val error = assertFailsWith<IllegalArgumentException> {
            AardinkWeb.registerLanguage("""{ "id": "bad", "grammar": { "tokenizer": { "root": [ ["(?<=a)b", "x"] ] } } }""")
        }
        assertTrue(error.message!!.startsWith("grammar.tokenizer.root[0][0]: lookbehind is not supported"), error.message)
        assertFailsWith<IllegalArgumentException> {
            AardinkWeb.registerLanguage("""{ "id": "x", "extends": "nope", "grammar": { "tokenizer": { "root": [] } } }""")
        }
    }

    @Test
    fun `a theme colours a grammar's names by the most specific it has, then by the built-in names`() {
        AardinkWeb.registerTheme(
            "toy-dark",
            """{ "type": "dark", "tokenColors": [ { "scope": "keyword.toy", "settings": { "foreground": "#ff00ff", "fontStyle": "italic" } } ] }""",
        )
        // The rule's name is kept for the editor, which colours `keyword.toy.x` by it, and `tag.toy` by
        // `tag` (as the built-in themes colour element names).
        val theme = AardinkWeb.registeredThemes.getValue("toy-dark")
        assertEquals(Color(0xFFFF00FF), theme.tokenColors[NamedTokenType("keyword.toy")])
        assertEquals(TokenFontStyle(italic = true), theme.tokenFontStyles[NamedTokenType("keyword.toy")])
    }

    @Test
    fun `a theme with its own keyword colour does not keep the base's keyword sub-names`() {
        AardinkWeb.registerTheme(
            "toy-keywords",
            """{ "type": "dark", "tokenColors": [ { "scope": "keyword", "settings": { "foreground": "#ff0000" } } ] }""",
        )
        val theme = AardinkWeb.registeredThemes.getValue("toy-keywords")
        assertEquals(Color(0xFFFF0000), theme.tokenColors[TokenType.Keyword])
        assertEquals(null, theme.tokenColors[NamedTokenType("keyword.flow")])
        // The base's string.escape stays: the theme left strings alone.
        assertEquals(
            EditorThemes.VsCodeDark.tokenColors[NamedTokenType("string.escape")],
            theme.tokenColors[NamedTokenType("string.escape")],
        )
    }

    @Test
    fun `a light theme takes what it does not set from vscode-light`() {
        AardinkWeb.registerTheme("toy-light", """{ "type": "light", "colors": { "editor.background": "#fafafa" }, "tokenColors": [] }""")
        val theme = AardinkWeb.registeredThemes.getValue("toy-light")
        assertEquals(Color(0xFFFAFAFA), theme.background)
        assertEquals(EditorThemes.VsCodeLight.tokenColors[TokenType.Keyword], theme.tokenColors[TokenType.Keyword])
        assertEquals(EditorThemes.VsCodeLight.cursorColor, theme.cursorColor)
    }

    @Test
    fun `a theme's diagnostic colours replace its base's, and those it leaves out stay the base's`() {
        AardinkWeb.registerTheme("toy-diagnostics", """{ "base": "vscode-dark", "colors": { "editorInfo.foreground": "#89b4fa" } }""")
        val theme = AardinkWeb.registeredThemes.getValue("toy-diagnostics")
        assertEquals(Color(0xFF89B4FA), theme.infoColor)
        assertEquals(EditorThemes.VsCodeDark.errorColor, theme.errorColor)
    }

    @Test
    fun `the host's answers come first, then those of the language it extends`() = runTest {
        AardinkWeb.registerLanguage(
            """{ "id": "toy-xml", "extends": "xml", "grammar": { "tokenizer": { "root": [ ["<\\w+", "tag"] ] } } }""",
            WebLanguageProviders(
                completions = { _, line, column -> """[{ "label": "myTag", "kind": "element", "detail": "at $line:$column" }]""" },
                diagnostics = { """[{ "line": 1, "startColumn": 1, "endColumn": 2, "message": "from the host" }]""" },
                hover = { _, _, _ -> error("the host's hover failed") },
            ),
        )
        val service = AardinkWeb.registry.byId("toy-xml")!!.languageService!!
        val document = CodeDocument("<root>\n  <")
        val completions = service.completions(document, document.length)
        assertEquals("myTag", completions.first().label)
        assertEquals("at 2:4", completions.first().documentation)
        assertTrue(completions.size > 1, "the XML service's own completions follow")
        val diagnostics = service.diagnostics(document)
        assertEquals("from the host", diagnostics.first().message)
        // A provider that throws costs its answer, not the editor.
        service.hoverDoc(document, 1)
    }

    @Test
    fun `a completion the host offers is not offered again by the language it extends`() = runTest {
        val document = CodeDocument("<root>\n  <")
        val inherited = AardinkWeb.registry.byId("xml")!!.languageService!!.completions(document, document.length).first()
        val kind = inherited.kind.name.lowercase()
        AardinkWeb.registerLanguage(
            """{ "id": "toy-xml-same", "extends": "xml", "grammar": { "tokenizer": { "root": [] } } }""",
            WebLanguageProviders(
                completions = { _, _, _ -> """[{ "label": "${inherited.label}", "kind": "$kind", "documentation": "the host's" }]""" },
            ),
        )
        val service = AardinkWeb.registry.byId("toy-xml-same")!!.languageService!!

        val same = service.completions(document, document.length).filter { it.kind == inherited.kind && it.label == inherited.label }

        assertEquals(listOf("the host's"), same.map { it.documentation })
    }

    @Test
    fun `insertTextRules makes a completion a snippet, as Monaco's flags do`() = runTest {
        AardinkWeb.registerLanguage(
            """{ "id": "toy-snippets", "grammar": { "tokenizer": { "root": [] } } }""",
            WebLanguageProviders(
                completions = { _, _, _ ->
                    """[
                      { "label": "a", "insertText": "a=\"${'$'}1\"", "insertTextRules": 4 },
                      { "label": "b", "insertText": "b", "insertTextRules": 5 },
                      { "label": "c" }
                    ]"""
                },
            ),
        )
        val service = AardinkWeb.registry.byId("toy-snippets")!!.languageService!!
        val items = service.completions(CodeDocument(""), 0)
        assertEquals(listOf(true, true, false), items.map { it.isSnippet })
        assertEquals(listOf(false, true, false), items.map { it.keepWhitespace })
        assertEquals("a=\"${'$'}1\"", items[0].insertText)
    }

    private fun register(id: String, definition: String = "", completions: String? = null, hover: String? = null): LanguageService {
        AardinkWeb.registerLanguage(
            """{ "id": "$id", "grammar": { "tokenizer": { "root": [] } } $definition }""",
            WebLanguageProviders(
                completions = completions?.let { json -> { _, _, _ -> json } },
                hover = hover?.let { json -> { _, _, _ -> json } },
            ),
        )
        return AardinkWeb.registry.byId(id)!!.languageService!!
    }

    @Test
    fun `trigger characters add to those of the language extended, or replace them without its completions`() {
        val xml = AardinkWeb.registry.byId("xml")!!.languageService!!.triggerCharacters
        assertEquals(xml, register("toy-triggers-none", """, "extends": "xml"""").triggerCharacters, "none given: XML's, as before")
        assertEquals(
            setOf('{', '|', '@', '$') + xml,
            register("toy-triggers", """, "extends": "xml", "triggerCharacters": ["{", "|", "@", "$"]""").triggerCharacters,
        )
        assertEquals(
            setOf('{'),
            register(
                "toy-triggers-own",
                """, "extends": "xml", "inheritCompletions": false, "triggerCharacters": ["{"]""",
            ).triggerCharacters,
        )
        assertEquals(setOf('.'), register("toy-triggers-alone", """, "triggerCharacters": ["."]""").triggerCharacters)
        val error = assertFailsWith<IllegalArgumentException> { register("toy-triggers-bad", """, "triggerCharacters": ["{{"]""") }
        assertTrue(error.message!!.startsWith("triggerCharacters:"), error.message)
        assertFailsWith<IllegalArgumentException> { register("toy-inherit-bad", """, "inheritCompletions": "no"""") }
    }

    @Test
    fun `inheritCompletions false leaves out the extended language's completions, but not the rest of its service`() = runTest {
        val document = CodeDocument("<root>\n  <!-- x --> <a ")
        val service = register("toy-own-only", """, "extends": "xml", "inheritCompletions": false""", """[{ "label": "mine" }]""")
        assertEquals(listOf("mine"), service.completions(document, document.length).map { it.label })
        assertTrue(service.diagnostics(document).isNotEmpty(), "XML still reports the unclosed tags")
        // With nothing of the host's, nothing at all.
        val silent = register("toy-own-only-silent", """, "extends": "xml", "inheritCompletions": false""", "[]")
        assertEquals(emptyList(), silent.completions(document, document.length))
    }

    @Test
    fun `an exclusive completion list leaves out the extended language's completions for that request`() = runTest {
        val document = CodeDocument("<root>\n  <")
        val exclusive =
            register("toy-exclusive", """, "extends": "xml"""", """{ "suggestions": [{ "label": "mine" }], "exclusive": true }""")
        assertEquals(listOf("mine"), exclusive.completions(document, document.length).map { it.label })
        // Monaco's list shape without `exclusive` is merged as an array is.
        val merged = register("toy-list", """, "extends": "xml"""", """{ "suggestions": [{ "label": "mine" }], "incomplete": false }""")
        val labels = merged.completions(document, document.length).map { it.label }
        assertEquals("mine", labels.first())
        assertTrue(labels.size > 1, "XML's follow")
    }

    @Test
    fun `a completion's range is the text it replaces`() = runTest {
        val document = CodeDocument("<a color=\"@acc\">\n</a>")
        val service = register(
            "toy-range",
            completions = """[
              { "label": "@accent", "range": { "startLineNumber": 1, "startColumn": 11, "endLineNumber": 1, "endColumn": 15 } },
              { "label": "@primary", "range": {
                  "insert": { "startLineNumber": 1, "startColumn": 11, "endLineNumber": 1, "endColumn": 15 },
                  "replace": { "startLineNumber": 1, "startColumn": 11, "endLineNumber": 1, "endColumn": 16 } } },
              { "label": "beyond", "range": { "startLineNumber": 9, "startColumn": 1, "endLineNumber": 9, "endColumn": 2 } },
              { "label": "plain" }
            ]""",
        )
        val items = service.completions(document, 14)
        assertEquals(listOf(10 until 14, 10 until 15, null, null), items.map { it.replaceRange })
    }

    @Test
    fun `filterText keeps only the items matching what has been typed of them`() = runTest {
        // The caret after "{dt:ho": the editor's own guess of the word is "ho".
        val document = CodeDocument("<text>{dt:ho</text>")
        val service = register(
            "toy-filter",
            completions = """[
              { "label": "hour", "filterText": "hour" },
              { "label": "hourWords", "filterText": "hourWords" },
              { "label": "dayOfWeekHours", "filterText": "dayOfWeekHours" },
              { "label": "minute", "filterText": "minute" },
              { "label": "unfiltered" },
              { "label": "@accent", "filterText": "@accent", "range": { "startLineNumber": 1, "startColumn": 11, "endLineNumber": 1, "endColumn": 13 } }
            ]""",
        )
        assertEquals(listOf("hour", "hourWords", "dayOfWeekHours", "unfiltered"), service.completions(document, 12).map { it.label })
    }

    @Test
    fun `typed text matches in order, ignoring case, from the start of a word`() {
        assertTrue(fuzzyMatches("", "anything"))
        assertTrue(fuzzyMatches("hw", "hourWords"))
        assertTrue(fuzzyMatches("WOR", "hourWords"), "a capital starts a word")
        assertTrue(fuzzyMatches("acc", "@accent"), "after a character that is not a letter")
        assertTrue(fuzzyMatches("col", "theme:colors.primary"))
        assertTrue(!fuzzyMatches("our", "hour"), "not from the middle of a word")
        assertTrue(!fuzzyMatches("hx", "hour"))
    }

    @Test
    fun `sortText orders the host's items, by label where it is missing`() = runTest {
        val document = CodeDocument("")
        val sorted = register(
            "toy-sort",
            completions = """[{ "label": "c", "sortText": "1" }, { "label": "b" }, { "label": "a", "sortText": "0" }, { "label": "0z" }]""",
        )
        assertEquals(listOf("a", "0z", "c", "b"), sorted.completions(document, 0).map { it.label })
        val unsorted = register("toy-unsorted", completions = """[{ "label": "c" }, { "label": "a" }]""")
        assertEquals(listOf("c", "a"), unsorted.completions(document, 0).map { it.label }, "without sortText, the order given")
    }

    @Test
    fun `kinds take the core's names and Monaco's`() = runTest {
        val service = register(
            "toy-kinds",
            completions = """[
              { "label": "a", "kind": "transform" }, { "label": "b", "kind": "function" },
              { "label": "c", "kind": "colorRef" }, { "label": "d", "kind": "color" },
              { "label": "e", "kind": "keyword" },
              { "label": "f", "documentation": { "value": "from Monaco's IMarkdownString" } }
            ]""",
        )
        val items = service.completions(CodeDocument(""), 0)
        assertEquals(
            listOf(
                CompletionKind.Transform,
                CompletionKind.Transform,
                CompletionKind.ColorRef,
                CompletionKind.ColorRef,
                CompletionKind.Value,
                CompletionKind.Value,
            ),
            items.map { it.kind },
        )
        assertEquals("from Monaco's IMarkdownString", items.last().documentation)
    }

    @Test
    fun `a hover's example is shown as code`() = runTest {
        val document = CodeDocument("x")
        val withExample =
            register("toy-hover", hover = """{ "title": "upper", "contents": "Upper-cases the value.", "example": "{dt:day | upper}" }""")
        assertEquals(HoverDoc("upper", "Upper-cases the value.", example = "{dt:day | upper}"), withExample.hoverDoc(document, 0))
        val without = register("toy-hover-plain", hover = """{ "contents": "Plain." }""")
        assertEquals(HoverDoc("", "Plain."), without.hoverDoc(document, 0))
    }
}
