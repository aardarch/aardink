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

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeLens
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The structural half of the spike's step S9: with hints on every line and a lens every 20, what a
 * frame and a keystroke ask of the injections does not grow with the document. The time it takes
 * is measured in the browser, by `tools/vite-smoke/perf.mjs`.
 */
class InlineScaleTest {

    private val lines = 100_000
    private val height = 400

    private val lens = Injection.rowAbove("3 references")
    private val plain = LineInjections.of(Injection(2, ": A"), Injection(4, ": B"), Injection(6, ": C"))
    private val lensed = LineInjections.of(Injection(2, ": A"), Injection(4, ": B"), Injection(6, ": C"), lens)

    @Test
    fun `a frame and a keystroke ask about the lines on screen, not the document`() {
        var asked = 0
        val view = viewOf(
            (0 until lines).joinToString("\n") { "line $it" },
            injections = {
                asked++
                if (it % 20 == 0) lensed else plain
            },
            height = height,
        )
        assertEquals(lines + lines / 20, view.lineMap.totalRows, "a lens every 20 lines is counted without laying them out")
        val onScreen = view.frame.lines.size
        assertTrue(onScreen in 1..30, "only what fits is laid out: $onScreen")

        for (y in listOf(50_000f, 900_000f, 1_500_000f)) {
            asked = 0
            view.scroll.scrollToY(y)
            view.prepare(600, height)
            assertTrue(view.frame.lines.size <= 30, "at $y: ${view.frame.lines.size} lines")
            assertTrue(asked < 100, "a scrolled frame asked $asked times at $y")
        }

        val first = view.frame.lines.first()
        asked = 0
        view.state.applyEdit(first.start, 0, "x", TextRange(first.start + 1))
        view.prepare(600, height)
        assertTrue(asked < 100, "a keystroke asked $asked times")

        asked = 0
        view.state.applyEdit(first.start, 0, "\n", TextRange(first.start + 1))
        view.prepare(600, height)
        assertTrue(asked < 100, "a new line asked $asked times")
    }

    @Test
    fun `real lenses are counted without laying their lines out, and an edit keeps them all`() {
        val view = viewOf((0 until lines).joinToString("\n") { "line $it" }, height = height)
        val lenses = (0 until lines step 20).map { CodeLens(view.state.document.lineStart(it), "3 references") }

        view.state.publishCodeLenses(lenses)
        view.prepare(600, height)

        assertEquals(lines + lines / 20, view.lineMap.totalRows)
        assertTrue(view.frame.lines.size <= 30, "only what fits is laid out: ${view.frame.lines.size}")

        val first = view.frame.lines.first()
        view.state.applyEdit(first.start + 2, 0, "x", TextRange(first.start + 3))
        view.prepare(600, height)

        assertEquals(lines + lines / 20, view.lineMap.totalRows)
        assertEquals(lenses.size, view.state.codeLenses.size)
        assertTrue(view.frame.lines.size <= 30, "only what fits is laid out: ${view.frame.lines.size}")
    }

    @Test
    fun `a keystroke builds the rows on screen, not one for each lens`() {
        val view = viewOf((0 until lines).joinToString("\n") { "line $it" }, height = height)
        val lenses = (0 until lines step 20).map { CodeLens(view.state.document.lineStart(it), "3 references") }
        view.state.publishCodeLenses(lenses)
        view.prepare(600, height)
        val first = view.frame.lines.first()

        for (typed in listOf("x", "\n")) {
            val built = view.inline.lensLinesBuilt
            view.state.applyEdit(first.start + 2, 0, typed, TextRange(first.start + 3))
            view.prepare(600, height)

            val keystroke = view.inline.lensLinesBuilt - built
            assertTrue(keystroke < 30, "typing $typed built $keystroke rows of ${lenses.size}")
        }

        assertEquals(1, view.inline.lookup(lines - 19)?.rowsAbove, "the last lens, a line down from where it was given")
        assertEquals(null, view.inline.lookup(lines - 20))
    }

    @Test
    fun `an answer like the last builds nothing more, and one with a lens more only that row`() {
        val view = viewOf((0 until lines).joinToString("\n") { "line $it" }, height = height)
        val lenses = (0 until lines step 20).map { CodeLens(view.state.document.lineStart(it), "3 references") }
        view.state.publishCodeLenses(lenses)
        view.prepare(600, height)

        val built = view.inline.lensLinesBuilt
        view.state.publishCodeLenses(lenses.map { it.copy(onClick = {}) })
        view.prepare(600, height)
        assertTrue(view.inline.lensLinesBuilt - built < 30, "only what is on screen is laid out again")

        val again = view.inline.lensLinesBuilt
        view.state.publishCodeLenses(lenses + CodeLens(view.state.document.lineStart(lines - 1), "one more"))
        view.prepare(600, height)
        assertTrue(view.inline.lensLinesBuilt - again < 30, "the new row, and what is on screen")
    }
}
