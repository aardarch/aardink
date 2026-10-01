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
package com.aardarch.aardink.core.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnippetParserTest {

    /** The stops as `index:start-end` lists, in Tab order, for compact assertions. */
    private fun ExpandedSnippet.layout(): List<String> = stops.map { stop ->
        "${stop.index}:" + stop.ranges.joinToString(",") { "${it.first}-${it.last + 1}" }
    }

    @Test
    fun `plain text has only a final stop at its end`() {
        val snippet = SnippetParser.expand("println()")
        assertEquals("println()", snippet.text)
        assertEquals(listOf("0:9-9"), snippet.layout())
    }

    @Test
    fun `tab stops are visited in number order, the final one last`() {
        val snippet = SnippetParser.expand("for (\$2 in \$1) {\$0}")
        assertEquals("for ( in ) {}", snippet.text)
        assertEquals(listOf("1:9-9", "2:5-5", "0:12-12"), snippet.layout())
    }

    @Test
    fun `placeholders insert their text and select it`() {
        val snippet = SnippetParser.expand("fun \${1:name}(\${2}) {}")
        assertEquals("fun name() {}", snippet.text)
        assertEquals(listOf("1:4-8", "2:9-9", "0:13-13"), snippet.layout())
    }

    @Test
    fun `placeholders nest`() {
        val snippet = SnippetParser.expand("\${1:outer \${2:inner}}")
        assertEquals("outer inner", snippet.text)
        assertEquals(listOf("1:0-11", "2:6-11", "0:11-11"), snippet.layout())
    }

    @Test
    fun `a stop that occurs twice is one stop with two ranges, the bare one mirroring the placeholder`() {
        val snippet = SnippetParser.expand("<\${1:div}></\$1>")
        assertEquals("<div></div>", snippet.text)
        assertEquals(listOf("1:1-4,7-10", "0:11-11"), snippet.layout())
    }

    @Test
    fun `a choice inserts its first option and keeps the rest`() {
        val snippet = SnippetParser.expand("align=\"\${1|start,center,end|}\"")
        assertEquals("align=\"start\"", snippet.text)
        assertEquals(listOf("1:7-12", "0:13-13"), snippet.layout())
        assertEquals(listOf("start", "center", "end"), snippet.stops[0].choices)
        assertNull(snippet.stops[1].choices)
    }

    @Test
    fun `escapes give the characters themselves`() {
        val snippet = SnippetParser.expand("\\\$1 costs \\\\ \\} \$")
        assertEquals("\$1 costs \\ } \$", snippet.text)
        assertEquals(listOf("0:14-14"), snippet.layout())
    }

    @Test
    fun `known variables take their values, unknown ones become placeholders`() {
        val variables = mapOf("TM_LINE_NUMBER" to "7")
        val snippet = SnippetParser.expand("\$TM_LINE_NUMBER \${UNKNOWN} \${MISSING:fallback}", variable = { variables[it] })
        assertEquals("7 UNKNOWN fallback", snippet.text)
        assertEquals(listOf("1:2-9", "0:18-18"), snippet.layout())
    }

    @Test
    fun `malformed constructs are inserted as written`() {
        assertEquals("\${1:open", SnippetParser.expand("\${1:open").text)
        assertEquals("\${1/a/b/}", SnippetParser.expand("\${1/a/b/}").text)
        assertEquals("cost: \$ 5", SnippetParser.expand("cost: \$ 5").text)
    }

    @Test
    fun `later lines follow the indentation and their leading tabs become the indent unit`() {
        val snippet = SnippetParser.expand("fun f() {\n\t\$0\n}", indent = "    ", indentUnit = "  ")
        assertEquals("fun f() {\n      \n    }", snippet.text)
        assertEquals(listOf("0:16-16"), snippet.layout())
    }

    @Test
    fun `without an indent the whitespace is kept as written`() {
        assertEquals("a {\n\tb\n}", SnippetParser.expand("a {\n\tb\n}").text)
    }
}
