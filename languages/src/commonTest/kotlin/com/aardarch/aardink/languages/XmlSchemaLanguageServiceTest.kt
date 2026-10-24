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
import com.aardarch.aardink.core.CompletionKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmlSchemaLanguageServiceTest {

    private val schema = XmlSchema.parse(
        """{
          "roots": ["wallpaper"],
          "dynamicValue": "^\\{.*\\}$",
          "elements": {
            "wallpaper": { "doc": "A live wallpaper.", "children": ["page"] },
            "page": { "doc": "One screen.", "children": ["text", "box"] },
            "box": { "children": ["text"], "attributes": { "fill": { "type": "color" } } },
            "text": {
              "doc": "A line of text.",
              "children": [],
              "attributes": {
                "value": { "doc": "What it says.", "required": true },
                "align": { "doc": "Where it sits.", "values": ["start", "center", { "value": "end", "doc": "The far side." }] }
              }
            }
          }
        }""",
    )
    private val service = XmlSchemaLanguageService(schema)

    @Test
    fun `the schema reads from JSON, and says where JSON it cannot use is wrong`() {
        assertEquals(listOf("wallpaper"), schema.roots)
        assertEquals(listOf("text", "box"), schema.elements.getValue("page").children)
        val align = schema.elements.getValue("text").attributes.getValue("align")
        assertEquals(listOf("start", "center", "end"), align.values.map { it.value })
        assertEquals("The far side.", align.values.last().documentation)
        assertTrue(schema.elements.getValue("text").attributes.getValue("value").required)
        assertEquals("color", schema.elements.getValue("box").attributes.getValue("fill").type)
        val error = assertFailsWith<IllegalArgumentException> { XmlSchema.parse("""{ "elements": { "x": { "attributes": [] } } }""") }
        assertTrue("elements.x.attributes" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `elements are offered by what the element at the caret may hold`() = runTest {
        val document = CodeDocument("<wallpaper>\n  <page>\n    <")
        val items = service.completions(document, document.length)
        assertEquals(listOf("text", "box"), items.map { it.label })
        val text = items.first()
        assertEquals(CompletionKind.Element, text.kind)
        assertTrue(text.isSnippet)
        assertEquals("text value=\"\$1\"/>\$0", text.insertText, "required attributes, self-closing as it holds nothing")
        assertEquals("box>\$0</box>", items[1].insertText)
        assertFalse(service.completionsAreIncomplete, "asked once per word")
        assertEquals(listOf("wallpaper"), service.completions(CodeDocument("<"), 1).map { it.label }, "only roots at the top")
    }

    @Test
    fun `attributes not yet given, with their values as a choice, then values`() = runTest {
        val document = CodeDocument("<text value=\"hi\" ")
        val attrs = service.completions(document, document.length)
        assertEquals(listOf("align"), attrs.map { it.label })
        assertEquals("align=\"\${1|start,center,end|}\"", attrs.single().insertText)
        val inValue = CodeDocument("<text align=\"c")
        val values = service.completions(inValue, inValue.length)
        assertEquals(listOf("start", "center", "end"), values.map { it.label })
        assertEquals(13 until 14, values.first().replaceRange)
        val afterEquals = CodeDocument("<text align=")
        assertEquals("\"start\"", service.completions(afterEquals, afterEquals.length).first().insertText)
    }

    @Test
    fun `hover documents elements, attributes and values`() = runTest {
        val document = CodeDocument("<text value=\"x\" align=\"end\"/>")
        assertEquals("A line of text.", service.hoverDoc(document, 2)?.content)
        assertTrue(service.hoverDoc(document, 18)!!.content.startsWith("Where it sits."))
        assertEquals("The far side.", service.hoverDoc(document, 24)?.content)
        assertNull(service.hoverDoc(document, 0))
    }

    @Test
    fun `markup inside a processing instruction or a declaration is not read as elements`() = runTest {
        val documents = listOf(
            "<?test > <bogus/> ?><wallpaper/>",
            "<!DOCTYPE wallpaper [<!ENTITY e \"x > <bogus/>\"><!-- it's > <bogus/> -->]><wallpaper/>",
        )
        for (text in documents) {
            val messages = service.diagnostics(CodeDocument(text)).filter { it.source == "schema" }.map { it.message }
            assertEquals(emptyList(), messages, text)
        }
    }

    @Test
    fun `attribute values are compared by what their references mean`() = runTest {
        val document = CodeDocument(
            "<wallpaper><page><text value=\"x\" align=\"st&#97;rt\"/><text value=\"x\" align=\"mid&amp;dle\"/></page></wallpaper>",
        )
        val messages = service.diagnostics(document).filter { it.source == "schema" }.map { it.message }
        assertEquals(listOf("'mid&amp;dle' is not a value of align; expected start, center, end"), messages)
        val hovered = CodeDocument("<text value=\"x\" align=\"&#x65;nd\"/>")
        assertEquals("The far side.", service.hoverDoc(hovered, 25)?.content)
    }

    @Test
    fun `diagnostics report what the schema does not allow`() = runTest {
        val document = CodeDocument(
            """<wallpaper>
              |  <page>
              |    <text align="middle" size="2"/>
              |    <text value="{clock:hour}" align="{clock:side}"/>
              |    <bogus/>
              |    <page/>
              |  </page>
              |</wallpaper>
            """.trimMargin(),
        )
        val messages = service.diagnostics(document).filter { it.source == "schema" }.map { it.message }
        assertEquals(
            setOf(
                "'middle' is not a value of align; expected start, center, end",
                "<text> has no attribute 'size'",
                "<text> needs the attribute 'value'",
                "Unknown element <bogus>",
                "<page> is not allowed in <page>",
            ),
            messages.toSet(),
        )
    }

    @Test
    fun `the base XML language still checks well-formedness and closes tags`() = runTest {
        val broken = CodeDocument("<wallpaper><page></wallpaper>")
        assertTrue(service.diagnostics(broken).any { it.source != "schema" }, "the XML checks come too")
        assertEquals("</page>", service.autoClose(CodeDocument("<page>"), 5, '>'))
        assertTrue('<' in service.triggerCharacters)
    }
}
