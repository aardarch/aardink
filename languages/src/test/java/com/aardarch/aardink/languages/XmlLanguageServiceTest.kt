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
import com.aardarch.aardink.languages.internal.xml.HtmlLanguageService
import com.aardarch.aardink.languages.internal.xml.XmlLanguageService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmlLanguageServiceTest {

    private fun xml(text: String) = runBlocking { XmlLanguageService.diagnostics(CodeDocument(text)) }
    private fun html(text: String) = runBlocking { HtmlLanguageService.diagnostics(CodeDocument(text)) }

    @Test
    fun `well-formed XML yields no diagnostics`() {
        assertTrue(xml("<root><child/></root>").isEmpty())
        assertTrue(xml("<a attr=\"v\"><b/></a>").isEmpty())
        assertTrue(xml("<!-- comment --><a/>").isEmpty())
        assertTrue(xml("<?xml version=\"1.0\"?><a/>").isEmpty())
        assertTrue(xml("<a>Text &amp; more</a>").isEmpty())
    }

    @Test
    fun `mismatched closing tag flagged`() {
        val diags = xml("<a><b></c></a>")
        assertTrue(diags.any { it.message.contains("Unmatched", ignoreCase = true) })
    }

    @Test
    fun `unclosed open tag flagged`() {
        val diags = xml("<a><b></a>")
        assertTrue(diags.any { it.message.contains("Unclosed", ignoreCase = true) })
    }

    @Test
    fun `unterminated comment flagged`() {
        val diags = xml("<a><!-- never ends</a>")
        assertEquals(1, diags.size)
        assertTrue(diags[0].message.contains("comment", ignoreCase = true))
    }

    @Test
    fun `duplicate attribute flagged`() {
        val diags = xml("<item android:name=\"a\" android:name=\"b\"/>")
        assertEquals(1, diags.size)
        assertTrue(diags[0].message.contains("Duplicate attribute", ignoreCase = true))
    }

    @Test
    fun `unescaped ampersand flagged`() {
        val diags = xml("<a>Rock & Roll</a>")
        assertEquals(1, diags.size)
        assertTrue(diags[0].message.contains("Unescaped '&'", ignoreCase = true))
    }

    @Test
    fun `auto close closing tag on angle bracket`() {
        val doc = CodeDocument("<LinearLayout>")
        val auto = XmlLanguageService.autoClose(doc, 13, '>')
        assertEquals("</LinearLayout>", auto)
    }

    @Test
    fun `auto close tag name on slash`() {
        val doc = CodeDocument("<a><b></")
        val auto = XmlLanguageService.autoClose(doc, 7, '/')
        assertEquals("b>", auto)
    }

    @Test
    fun `completions for elements and attributes`() {
        val doc1 = CodeDocument("<")
        val elems = runBlocking { XmlLanguageService.completions(doc1, 1) }
        assertTrue(elems.any { it.label == "activity" })

        val doc2 = CodeDocument("<activity ")
        val attrs = runBlocking { XmlLanguageService.completions(doc2, 10) }
        assertTrue(attrs.any { it.label == "android:name" })
    }

    @Test
    fun `format indents nested tags`() = runBlocking {
        val doc = CodeDocument("<a>\n<b>\n</b>\n</a>")
        val formatted = XmlLanguageService.format(doc)
        assertEquals("<a>\n    <b>\n    </b>\n</a>", formatted)
    }

    @Test
    fun `html void elements do not need closing`() {
        assertTrue(html("<div><br><img src=\"x\"></div>").isEmpty())
    }

    @Test
    fun `html still flags unbalanced non-void tags`() {
        val diags = html("<div><span></div>")
        assertTrue(diags.isNotEmpty())
    }

    @Test
    fun `a custom element only prefixed by a void name is not void`() = runBlocking {
        val doc = CodeDocument("<input-group>\n<span>\n</span>\n</input-group>")
        val formatted = HtmlLanguageService.format(doc)
        assertEquals("<input-group>\n    <span>\n    </span>\n</input-group>", formatted)

        assertTrue(html("<input-group><span></span></input-group>").isEmpty())
        assertTrue(html("<input-group>").isNotEmpty(), "it still needs a closing tag")
    }

    @Test
    fun `duplicate attributes fold case only in html`() {
        assertTrue(xml("""<a foo="1" FOO="2"/>""").isEmpty(), "XML attribute names are case-sensitive")
        assertTrue(xml("""<a foo="1" foo="2"/>""").any { it.message.contains("Duplicate attribute") })
        assertTrue(html("""<a foo="1" FOO="2"/>""").any { it.message.contains("Duplicate attribute") })
    }

    @Test
    fun `an equals inside an attribute value is not an attribute`() {
        assertTrue(xml("""<a data=" foo=1 foo=2"/>""").isEmpty(), "the duplicate is value text, not two attributes")
        assertTrue(xml("""<a data=' foo=1 foo=2'/>""").isEmpty())
        assertTrue(
            xml("""<a foo="x" data=" foo=1" foo="y"/>""").any { it.message.contains("Duplicate attribute 'foo'") },
            "a real duplicate around a value is still reported",
        )
    }

    @Test
    fun `tag names fold case only in html`() {
        assertTrue(xml("<Foo></foo>").isNotEmpty(), "XML element names are case-sensitive")
        assertTrue(xml("<Foo></Foo>").isEmpty())
        assertTrue(html("<DIV></div>").isEmpty(), "HTML element names are not")
    }

    // ── Formatting leaves content alone ──────────────────────────────────────

    @Test
    fun `format preserves text node whitespace`() = runBlocking {
        val src = "<a>\n<p>\n    leading and trailing   \n</p>\n</a>"
        val formatted = XmlLanguageService.format(CodeDocument(src))
        assertTrue(formatted.contains("\n    leading and trailing   \n"), "text nodes are content: $formatted")
        assertTrue(formatted.contains("\n    <p>\n"), "surrounding structure is still indented: $formatted")
    }

    @Test
    fun `format leaves the inside of a multi-line comment alone`() = runBlocking {
        val src = "<a>\n<!--\n      note\n-->\n</a>"
        val formatted = XmlLanguageService.format(CodeDocument(src))
        assertTrue(formatted.contains("\n      note\n"), "comment body survives verbatim: $formatted")
    }

    @Test
    fun `format leaves a mixed content line alone`() = runBlocking {
        val src = "<a>\n  some <b>bold</b> text\n</a>"
        val formatted = XmlLanguageService.format(CodeDocument(src))
        assertTrue(formatted.contains("\n  some <b>bold</b> text\n"), "mixed content survives verbatim: $formatted")
    }

    @Test
    fun `attribute completions replace the partial name`() = runBlocking {
        val src = """<a android:"""
        val items = XmlLanguageService.completions(CodeDocument(src), src.length)
        val name = items.single { it.label == "android:name" }
        assertEquals(3 until src.length, name.replaceRange, "the whole partial name goes, not just the part after ':'")
    }
}
