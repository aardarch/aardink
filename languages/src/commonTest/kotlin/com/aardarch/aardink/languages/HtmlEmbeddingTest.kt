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

import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.css.CssTokenizer
import com.aardarch.aardink.languages.internal.html.HtmlTokenizer
import com.aardarch.aardink.languages.internal.json.JsonTokenizer
import com.aardarch.aardink.languages.internal.typescript.TypeScriptTokenizer
import com.aardarch.aardink.languages.internal.xml.XmlTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CSS and JavaScript inside HTML's `<style>` and `<script>`. */
class HtmlEmbeddingTest {

    /** The tokens of [text] that lie in `text[from until to]`, moved to start at [from]'s 0. */
    private fun within(tokens: List<Token>, from: Int, to: Int) =
        tokens.filter { it.start >= from && it.end <= to }.map { Token(it.start - from, it.end - from, it.type) }

    private fun assertEmbedded(text: String, body: String, expected: List<Token>) {
        val from = text.indexOf(body)
        assertEquals(expected, within(HtmlTokenizer.tokenizeFull(text), from, from + body.length), text)
    }

    @Test
    fun `a style element's body is CSS`() {
        val body = "a { color: #fff; }"
        assertEmbedded("<p>x</p><style>$body</style>", body, CssTokenizer.tokenizeFull(body))
        assertEmbedded("<STYLE media=\"print\">\n$body\n</Style >", body, CssTokenizer.tokenizeFull(body))
    }

    @Test
    fun `a script element's body is JavaScript, to its closing tag only`() {
        val body = "if (a < b) { s = \"<p>\" + '</p>'; } // </scripts"
        val text = "<script>$body</script><p class=\"x\">t</p>"
        assertEmbedded(text, body, TypeScriptTokenizer.tokenizeFull(body))
        val tokens = HtmlTokenizer.tokenizeFull(text)
        val after = text.indexOf("<p class")
        assertEquals(TokenType.TypeName, tokens.single { it.start == after + 1 }.type, "markup again after </script>")
        assertEmbedded("<script type=\"module\">$body</script>", body, TypeScriptTokenizer.tokenizeFull(body))
    }

    @Test
    fun `a script's type says what its body is`() {
        val json = """{ "imports": { "a": "./a.js" } }"""
        assertEmbedded("<script type=\"importmap\">$json</script>", json, JsonTokenizer.tokenizeFull(json))
        assertEmbedded("<script type='application/ld+json'>$json</script>", json, JsonTokenizer.tokenizeFull(json))
        val template = "<p>{{ name }}</p>"
        assertEmbedded("<script type=text/x-template>$template</script>", template, emptyList())
    }

    @Test
    fun `an unclosed script runs to the end, and a self-closed one has no body`() {
        val body = "let x = 1 < 2;"
        assertEmbedded("<script>$body", body, TypeScriptTokenizer.tokenizeFull(body))
        val tokens = HtmlTokenizer.tokenizeFull("<script src=\"a.js\"/><b>")
        assertTrue(tokens.any { it.type == TokenType.TypeName && it.start == 21 }, "<b> after a self-closed script is a tag: $tokens")
    }

    @Test
    fun `XML has no embedded languages`() {
        val text = "<style>a { color: red; }</style>"
        val tokens = XmlTokenizer.tokenizeFull(text)
        assertTrue(tokens.none { it.start >= 7 && it.end <= 24 }, "the body is text: $tokens")
    }

    @Test
    fun `renaming the element recolours its body`() {
        val css = "<style>a { b: c }</style>"
        val first = HtmlTokenizer.tokenizeFull(css)
        val script = css.replace("style", "script")
        // An incremental result marks the lines it covers with empty tokens.
        val incremental = HtmlTokenizer.tokenizeLines(script, 0..0, first).filter { it.end > it.start }
        assertEquals(HtmlTokenizer.tokenizeFull(script), incremental)
        assertEquals(TypeScriptTokenizer.tokenizeFull("a { b: c }"), within(incremental, 8, 18))
    }
}
