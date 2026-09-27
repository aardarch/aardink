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

import com.aardarch.aardink.core.FoldRange
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VisualLineMapTest {

    private fun mapOf(vararg rows: Int) = VisualLineMap().apply { reset(rows.size) { rows[it] } }

    @Test
    fun `rows and lines map both ways`() {
        val map = mapOf(1, 3, 1, 2)
        assertEquals(7, map.totalRows)
        assertEquals(listOf(0, 1, 4, 5), (0 until 4).map { map.firstRowOf(it) })
        assertEquals(listOf(0, 1, 1, 1, 2, 3, 3), (0 until 7).map { map.lineAtRow(it) })
    }

    @Test
    fun `hidden lines own no rows and are skipped`() {
        val map = mapOf(1, 1, 0, 0, 1)
        assertEquals(3, map.totalRows)
        assertEquals(listOf(0, 1, 4), (0 until 3).map { map.lineAtRow(it) })
        assertTrue(map.isHidden(3))
        assertEquals(1, map.visibleLineAtOrBefore(3))
        assertEquals(4, map.visibleLineAtOrBefore(4))
    }

    @Test
    fun `a row past the end gives the last shown line`() {
        val map = mapOf(1, 1, 0)
        assertEquals(1, map.lineAtRow(99))
    }

    @Test
    fun `setRows reports the change and moves the lines below`() {
        val map = mapOf(1, 1, 1)
        assertEquals(2, map.setRows(1, 3))
        assertEquals(4, map.firstRowOf(2))
        assertEquals(0, map.setRows(1, 3))
    }

    @Test
    fun `splice keeps the counts of the lines around an edit`() {
        val map = mapOf(1, 2, 3, 4)
        // Line 1 split into three lines: two new ones after it.
        map.splice(1, removed = 0, added = 2) { 7 }
        assertEquals(listOf(1, 7, 7, 7, 3, 4), (0 until 6).map { map.rowsOf(it) })
        // Lines 2..3 merged into line 1.
        map.splice(1, removed = 2, added = 0) { 5 }
        assertEquals(listOf(1, 5, 3, 4), (0 until 4).map { map.rowsOf(it) })
        assertEquals(13, map.totalRows)
    }

    @Test
    fun `the tree agrees with a plain count over random edits`() {
        val random = Random(7)
        val rows = MutableList(300) { random.nextInt(0, 4) }
        val map = VisualLineMap().apply { reset(rows.size) { rows[it] } }
        repeat(500) {
            when (random.nextInt(3)) {
                0 -> {
                    val line = random.nextInt(rows.size)
                    val count = random.nextInt(0, 4)
                    rows[line] = count
                    map.setRows(line, count)
                }

                1 -> {
                    val line = random.nextInt(rows.size)
                    val added = random.nextInt(0, 3)
                    val removed = random.nextInt(0, minOf(3, rows.size - line))
                    val fresh = List(added + 1) { random.nextInt(0, 4) }
                    repeat(removed + 1) { rows.removeAt(line) }
                    rows.addAll(line, fresh)
                    map.splice(line, removed, added) { fresh[it - line] }
                }

                else -> Unit
            }
            assertEquals(rows.sum(), map.totalRows)
            val probe = random.nextInt(rows.size)
            assertEquals(rows.take(probe).sum(), map.firstRowOf(probe))
            if (map.totalRows > 0) {
                val row = random.nextInt(map.totalRows)
                var line = 0
                var seen = 0
                while (seen + rows[line] <= row) seen += rows[line++]
                assertEquals(line, map.lineAtRow(row))
            }
        }
    }
}

class FoldLayoutTest {

    @Test
    fun `closed folds hide the lines after their first`() {
        val folds = FoldLayout.of(listOf(FoldRange(2, 5)), lineCount = 10)
        assertFalse(folds.isHidden(2))
        assertTrue((3..5).all { folds.isHidden(it) })
        assertFalse(folds.isHidden(6))
        assertEquals(FoldRange(2, 5), folds.startingAt(2))
        assertNull(folds.startingAt(3))
    }

    @Test
    fun `a fold inside a closed one is dropped`() {
        val folds = FoldLayout.of(listOf(FoldRange(4, 6), FoldRange(1, 8)), lineCount = 10)
        assertNull(folds.startingAt(4))
        assertTrue(folds.isHidden(5))
    }

    @Test
    fun `folds past the end of the document are cut or dropped`() {
        val folds = FoldLayout.of(listOf(FoldRange(1, 20), FoldRange(9, 12)), lineCount = 5)
        assertEquals(FoldRange(1, 4), folds.startingAt(1))
        assertTrue(folds.isHidden(4))
        assertEquals(FoldLayout.None, FoldLayout.of(listOf(FoldRange(4, 9)), lineCount = 5))
    }
}

class LineLayoutCacheTest {

    private fun key(text: String, tokens: Any? = null) = LineLayoutCache.Key(text, tokens, null)

    @Test
    fun `a line is measured once while it stays in use`() {
        val cache = LineLayoutCache<String>(capacity = 4)
        var measured = 0
        repeat(3) {
            cache.getOrPut(key("a")) {
                measured++
                "A"
            }
        }
        assertEquals(1, measured)
    }

    @Test
    fun `token runs are compared by identity`() {
        val cache = LineLayoutCache<String>()
        val runs = Any()
        cache.getOrPut(key("a", runs)) { "first" }
        assertEquals("first", cache.getOrPut(key("a", runs)) { "again" })
        assertEquals("other", cache.getOrPut(key("a", Any())) { "other" })
    }

    @Test
    fun `entries used recently survive a generation change`() {
        val cache = LineLayoutCache<String>(capacity = 2)
        val kept = cache.getOrPut(key("kept")) { "K" }
        cache.getOrPut(key("b")) { "B" }
        cache.getOrPut(key("c")) { "C" } // "kept" and "b" become the older generation
        assertSame(kept, cache.getOrPut(key("kept")) { "new" }) // moved back into the newer one
        cache.getOrPut(key("d")) { "D" }
        cache.getOrPut(key("e")) { "E" } // "b" was never used again: gone
        var remeasured = false
        cache.getOrPut(key("b")) {
            remeasured = true
            "B2"
        }
        assertTrue(remeasured)
    }
}
