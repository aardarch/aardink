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
package com.aardarch.aardink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FormattingOptionsTest {

    @Test
    fun `an indent unit is tabSize spaces or a tab`() {
        assertEquals("    ", FormattingOptions().indentUnit)
        assertEquals("  ", FormattingOptions(tabSize = 2).indentUnit)
        assertEquals("\t", FormattingOptions(insertSpaces = false).indentUnit)
    }

    @Test
    fun `indentOf fills whole tabs and spaces for the rest`() {
        val tabs = FormattingOptions(tabSize = 4, insertSpaces = false)
        assertEquals("\t\t", tabs.indentOf(8))
        assertEquals("\t  ", tabs.indentOf(6))
        assertEquals("      ", FormattingOptions().indentOf(6))
        assertEquals("", FormattingOptions().indentOf(-3))
    }

    @Test
    fun `indentWidth counts a tab to the next tab stop`() {
        val options = FormattingOptions(tabSize = 4)
        assertEquals(4, options.indentWidth("\tx"))
        assertEquals(4, options.indentWidth("  \tx"))
        assertEquals(6, options.indentWidth("\t  x"))
        assertEquals(0, options.indentWidth("x  "))
        assertEquals(3, options.indentWidth("   "))
    }

    @Test
    fun `indentAfter adds and removes levels and never goes below none`() {
        val tabs = FormattingOptions(tabSize = 2, insertSpaces = false)
        assertEquals("\t\t", tabs.indentAfter("\tfoo {", levels = 1))
        assertEquals("", tabs.indentAfter("\tfoo", levels = -3))
        assertEquals("    ", FormattingOptions(tabSize = 2).indentAfter("\tx", levels = 1))
    }

    @Test
    fun `tabSize must be 1 to 16`() {
        assertFailsWith<IllegalArgumentException> { FormattingOptions(tabSize = 0) }
        assertFailsWith<IllegalArgumentException> { FormattingOptions(tabSize = 17) }
    }

    @Test
    fun `a document's line ending comes from its first line break`() {
        assertEquals(LineEnding.LF, CodeDocument("a\nb").eol)
        assertEquals(LineEnding.CRLF, CodeDocument("a\r\nb\nc").eol)
        assertEquals(LineEnding.LF, CodeDocument("no break").eol)
        val document = CodeDocument("a\nb")
        document.replaceAll("x\r\ny")
        assertEquals(LineEnding.CRLF, document.eol)
        document.insert(0, "z\n")
        assertEquals(LineEnding.CRLF, document.eol, "an edit does not change it")
    }
}
