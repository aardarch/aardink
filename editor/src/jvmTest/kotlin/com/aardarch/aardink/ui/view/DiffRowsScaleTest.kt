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

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.diff.LineChange
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * The 0.12 diff spike's step D6: what a diff costs a view, on the JVM. Times are printed for the
 * spike's report and held only to generous limits here.
 */
class DiffRowsScaleTest {

    private companion object {
        val DELETED = System.getProperty("diffDeleted")?.toInt() ?: 2_000
    }

    private val band = Color(0x40FF0000)

    @Test
    fun `fillers every 20 lines of 100,000 cost a frame only what is on screen`() {
        val lines = 100_000
        val text = (0 until lines).joinToString("\n") { "line $it of the modified side" }
        val view = viewOf(text, height = 400)
        val blocks = (0 until lines step 20).map { LineChange(it, it + 3, it, it + 1, null) }
        val (_, right) = DiffRows.sideBySide(blocks, InlineFixture.stateOf(text).document, view.state.document, band)
        val publish = measureTime {
            view.state.publishDiffRows(right)
            view.prepare(600, 400)
        }
        assertEquals(lines + 2 * blocks.size, view.lineMap.totalRows)
        val frames = ArrayList<Long>()
        for (y in listOf(10_000f, 500_000f, 2_000_000f)) {
            frames += measureTime {
                view.scroll.scrollToY(y)
                view.prepare(600, 400)
            }.inWholeMilliseconds
            assertTrue(view.frame.lines.size <= 30, "at $y: ${view.frame.lines.size} lines laid out")
        }
        println("D6 fillers: ${blocks.size} blocks published and laid out in ${publish.inWholeMilliseconds} ms; frames $frames ms")
        assertTrue(publish.inWholeMilliseconds < 2_000, "publish took $publish")
    }

    @Test
    fun `a block of 2,000 deleted lines inline shows each of them, and laying it out is measured`() {
        val deleted = DELETED
        val originalText = "before\n" + (0 until deleted).joinToString("\n") { "deleted line $it with some text after it" } + "\nafter"
        val original = viewOf(originalText).state
        val view = viewOf("before\nafter", height = 400)
        val rows = DiffRows.inline(listOf(LineChange(1, 1 + deleted, 1, 1, null)), original, view.state.document, { null }, band, 4)
        view.state.publishDiffRows(rows)
        val firstLayout = measureTime { view.prepare(600, 400) }
        val shownRows = view.lineMap.rowsOf(1) - 1
        val frames = ArrayList<Long>()
        for (y in listOf(4_000f, 20_000f, 39_000f)) {
            frames += measureTime {
                view.scroll.scrollToY(y)
                view.prepare(600, 400)
            }.inWholeMilliseconds
        }
        println(
            "D6 inline: $shownRows of $deleted deleted rows shown; first layout ${firstLayout.inWholeMilliseconds} ms; frames $frames ms",
        )
        assertEquals(deleted, shownRows, "every deleted line is a row")
    }
}
