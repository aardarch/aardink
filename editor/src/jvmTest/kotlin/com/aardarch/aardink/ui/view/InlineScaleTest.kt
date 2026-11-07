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
}
