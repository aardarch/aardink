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
}
