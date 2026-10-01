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
package com.aardarch.aardink.ui.view

import com.aardarch.aardink.ui.RenderWhitespace
import kotlin.test.Test
import kotlin.test.assertEquals

class WhitespaceColumnsTest {

    private val line = "\tval x  = 1 \t"

    @Test
    fun `none draws nothing`() {
        assertEquals(emptyList(), whitespaceColumns(line, line.length, RenderWhitespace.None))
    }

    @Test
    fun `all draws every space and tab`() {
        assertEquals(listOf(0, 4, 6, 7, 9, 11, 12), whitespaceColumns(line, line.length, RenderWhitespace.All))
    }

    @Test
    fun `boundary skips a single space between two words`() {
        // The space after `val` and after `=` are word gaps; the double space and the ends are not.
        assertEquals(listOf(0, 6, 7, 11, 12), whitespaceColumns(line, line.length, RenderWhitespace.Boundary))
    }

    @Test
    fun `boundary keeps a single tab between two words`() {
        assertEquals(listOf(1), whitespaceColumns("a\tb", 3, RenderWhitespace.Boundary))
    }

    @Test
    fun `trailing draws only after the last character`() {
        assertEquals(listOf(11, 12), whitespaceColumns(line, line.length, RenderWhitespace.Trailing))
    }

    @Test
    fun `trailing draws all of a blank line`() {
        assertEquals(listOf(0, 1, 2), whitespaceColumns("  \t", 3, RenderWhitespace.Trailing))
    }

    @Test
    fun `selection draws only the selected columns`() {
        assertEquals(listOf(4, 6), whitespaceColumns(line, line.length, RenderWhitespace.Selection, listOf(2..6)))
        assertEquals(emptyList(), whitespaceColumns(line, line.length, RenderWhitespace.Selection))
    }

    @Test
    fun `columns past the shown length are left out`() {
        assertEquals(listOf(0, 4), whitespaceColumns(line, 5, RenderWhitespace.All))
    }
}
