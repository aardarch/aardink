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

import com.aardarch.aardink.core.CodeDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The indent and bracket-pair guides' levels, pairs and active guide. */
class GuidesTest {

    /** Every bracket of a line, as the editor's bracket index gives those in code. */
    private fun brackets(document: CodeDocument): (Int, (Int, Boolean) -> Unit) -> Unit = { line, action ->
        document.lineText(line).forEachIndexed { column, c ->
            when (c) {
                '{', '(', '[' -> action(column, true)
                '}', ')', ']' -> action(column, false)
            }
        }
    }

    private fun guides(text: String, tabSize: Int = 4): Guides {
        val document = CodeDocument(text)
        return Guides.compute(document, 0, document.lineCount - 1, tabSize, withPairs = true, brackets(document))
    }

    private val code = """
        |fun a() {
        |    if (x) {
        |        y()
        |
        |        z()
        |    }
        |
        |}
    """.trimMargin()

    @Test
    fun `levels round indentation up, and tabs go to their stops`() {
        val shown = guides("a\n  b\n    c\n\td\n  \te", tabSize = 4)
        assertEquals(listOf(0, 1, 1, 1, 1), (0..4).map { shown.level(it) })
        assertEquals(listOf(0, 2, 4, 4, 4), (0..4).map { shown.indentColumns(it) })
    }

    @Test
    fun `a blank line between equals is at their level, and one before a deeper line one inside the line above`() {
        val shown = guides(code)
        assertEquals(2, shown.level(3), "between two lines at level 2")
        assertEquals(0, shown.level(6), "after level 1, before level 0: the level below, as VS Code's")
        assertEquals(1, guides("a {\n\n    b\n}").level(1), "one inside the line above")
    }

    @Test
    fun `pairs over several lines are guided between them, at the shallower indentation`() {
        val pairs = guides(code).pairs.sortedBy { it.depth }
        assertEquals(BracketGuide(fromLine = 1, toLine = 6, column = 0, depth = 0), pairs[0])
        assertEquals(BracketGuide(fromLine = 2, toLine = 4, column = 4, depth = 1), pairs[1])
        assertEquals(2, pairs.size, "a pair on one line is no guide")
    }

    @Test
    fun `the active guide is the block holding the caret, or the one its line opens`() {
        val shown = guides(code)
        assertEquals(ActiveGuide(level = 1, fromLine = 2, toLine = 4), shown.activeGuide(2))
        assertEquals(ActiveGuide(level = 1, fromLine = 2, toLine = 4), shown.activeGuide(1), "the line opens the block")
        assertNull(shown.activeGuide(7))
    }

    @Test
    fun `only a window around the lines shown is worked out`() {
        val document = CodeDocument((0 until 3_000).joinToString("\n") { "    x" })
        val shown = Guides.compute(document, 1_500, 1_520, 4, withPairs = false, brackets(document))
        assertEquals(1_000, shown.firstLine)
        assertEquals(2_020, shown.lastLine)
        assertTrue(shown.covers(1_400, 1_600, document.lineCount))
        assertTrue(!shown.covers(1_900, 1_950, document.lineCount))
    }
}
