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

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The spike's step S3: the laws every line's map keeps, on random lines. */
class OffsetMapTest {

    private fun mapOf(length: Int, vararg items: Injection) = OffsetMap.of(LineInjections.of(*items), length)

    @Test
    fun `with nothing injected every column is its own position`() {
        val map = OffsetMap.Identity
        assertTrue(map.isIdentity)
        assertFalse(map.hasBreaks)
        for (column in listOf(0, 1, 7, 10_000)) {
            assertEquals(column, map.toLayout(column))
            assertEquals(column, map.charIndex(column))
            assertEquals(column, map.rangeEnd(column))
            assertEquals(column, map.toDocument(column))
        }
    }

    @Test
    fun `a caret stays before ghost text and the character after it`() {
        // "x" + "gg" + "y": the caret at column 1 is before the ghost text, the y after it.
        val map = mapOf(2, Injection(1, "gg"))
        assertEquals(1, map.toLayout(1))
        assertEquals(3, map.charIndex(1))
        assertEquals(1, map.rangeEnd(1))
        assertEquals(4, map.rangeEnd(2))
        assertEquals(4, map.toLayout(2))
    }

    @Test
    fun `a caret goes after a row above the line`() {
        val map = mapOf(3, Injection.rowAbove("lens"))
        assertEquals(5, map.toLayout(0))
        assertEquals(5, map.charIndex(0))
        assertEquals(0, map.toDocument(2))
        assertTrue(map.hasBreaks)
    }

    @Test
    fun `at one column the caret sits between text that goes before it and text that goes after it`() {
        val map = mapOf(1, Injection(1, "ghost"), Injection(1, "ab", caretAfter = true))
        assertEquals(3, map.toLayout(1))
        assertEquals(1, map.rangeEnd(1))
    }

    @Test
    fun `a position past the text stays past it, for a fold's placeholder`() {
        val map = mapOf(3, Injection(1, "abc"))
        assertEquals(3, map.toDocument(6))
        assertEquals(4, map.toDocument(7))
        assertEquals(8, map.toDocument(11))
    }

    @Test
    fun `an injected position is told from a document one`() {
        val hint = Injection(1, "ab")
        val map = mapOf(2, hint)
        assertNull(map.injectionAt(0))
        assertEquals(hint, map.injectionAt(1))
        assertEquals(hint, map.injectionAt(2))
        assertNull(map.injectionAt(3))
    }

    @Test
    fun `a tab counts the columns injected before it`() {
        val map = mapOf(4, Injection(2, "abc"), Injection(3, "x\nyz"))
        assertEquals(7, map.visualColumnAfter(2, 4))
        assertEquals(2, map.visualColumnAfter(3, 9), "a break starts the count again")
        assertEquals(5, map.visualColumnAfter(1, 5))
    }

    @Test
    fun `runs cover the document characters of a range and nothing else`() {
        val map = mapOf(5, Injection(2, "ab"), Injection(4, "c\nd"))
        val runs = ArrayList<Pair<Int, Int>>()
        map.forEachRun(1, 5) { start, end -> runs += start to end }
        assertEquals(listOf(1 to 2, 4 to 6, 9 to 10), runs)
        runs.clear()
        map.forEachRun(1, 5, breaksOnly = true) { start, end -> runs += start to end }
        assertEquals(listOf(1 to 6, 9 to 10), runs, "cut only at the line break")
    }

    @Test
    fun `an injection past the text is dropped`() {
        val kept = Injection(3, "kept")
        val fitted = LineInjections.of(kept, Injection(4, "gone")).fitted("abc", 0, 100)
        assertEquals(listOf(kept), fitted?.items)
        assertNull(LineInjections.of(Injection(9, "gone")).fitted("abc", 0, 100))
    }

    @Test
    fun `an injection inside the indentation moves to its end, a row above the line does not`() {
        val row = Injection.rowAbove("lens")
        val fitted = assertNotNull(LineInjections.of(row, Injection(0, "a"), Injection(2, "b")).fitted("    x", 4, 100))
        assertEquals(listOf(0, 4, 4), fitted.items.map { it.column })
    }

    @Test
    fun `an injection never splits a surrogate pair`() {
        val fitted = assertNotNull(LineInjections.of(Injection(2, "x")).fitted("a😀b", 0, 100))
        assertEquals(1, fitted.items.single().column)
    }

    @Test
    fun `injected text stops at the budget`() {
        val fitted = LineInjections.of(Injection(0, "abcd"), Injection(1, "efgh")).fitted("xy", 0, 6)
        assertEquals(listOf("abcd"), fitted?.items?.map { it.text })
    }

    @Test
    fun `what fits unchanged is the same object, so a cached layout's key stays cheap`() {
        val injections = LineInjections.of(Injection(1, "a"))
        assertSame(injections, injections.fitted("abc", 0, 100))
    }

    @Test
    fun `injections compare by value`() {
        assertEquals(LineInjections.of(Injection(1, "a")), LineInjections.of(Injection(1, "a")))
        assertFalse(LineInjections.of(Injection(1, "a")) == LineInjections.of(Injection(2, "a")))
    }

    @Test
    fun `rows are counted without a layout`() {
        val injections = LineInjections.of(Injection.rowAbove("one"), Injection.rowAbove("two"), Injection(3, "a\nb\nc"))
        assertEquals(4, injections.addedRows)
        assertEquals(2, injections.rowsAbove)
        assertEquals(5, injections.inlineChars)
    }

    @Test
    fun `10,000 random lines keep every law`() {
        val random = Random(9)
        repeat(10_000) { line ->
            val length = random.nextInt(0, 60)
            val items = List(random.nextInt(0, 7)) {
                val text = CharArray(random.nextInt(1, 6)) { if (random.nextInt(5) == 0) '\n' else 'i' }.concatToString()
                Injection(random.nextInt(0, length + 1), text, caretAfter = random.nextBoolean())
            }
            val injections = LineInjections.of(items)
            val map = OffsetMap.of(injections, length)
            checkLaws(map, injections, length, "line $line: $length columns, $items")
        }
    }

    private fun checkLaws(map: OffsetMap, injections: LineInjections, length: Int, context: String) {
        // The layout's text, as measureLine splices it: -1 for an injected character, else its column.
        val layout = ArrayList<Int>()
        var appended = 0
        for (item in injections.items) {
            for (column in appended until item.column) layout += column
            appended = item.column
            repeat(item.text.length) { layout += -1 }
        }
        for (column in appended until length) layout += column
        val total = layout.size

        for (column in 0..length) {
            assertEquals(column, map.toDocument(map.toLayout(column)), "the caret round-trips at $column: $context")
            assertEquals(column, map.toDocument(map.charIndex(column)), "the character round-trips at $column: $context")
            if (column > 0) {
                assertTrue(map.toLayout(column) > map.toLayout(column - 1), "carets go forward at $column: $context")
                assertEquals(map.charIndex(column - 1) + 1, map.rangeEnd(column), "a range ends after its last character: $context")
            }
            if (column < length) assertEquals(column, layout[map.charIndex(column)], "the character is where it says: $context")
            assertTrue(map.rangeEnd(column) <= map.toLayout(column), "the caret is not before the range end: $context")
            assertTrue(map.toLayout(column) <= map.charIndex(column), "the caret is not after the character: $context")
        }

        var previous = 0
        for (position in 0..total) {
            val column = map.toDocument(position)
            assertTrue(column >= previous, "toDocument never goes back at $position: $context")
            assertTrue(column <= length, "toDocument stays in the text at $position: $context")
            previous = column
        }
        for (position in 0 until total) {
            if (layout[position] >= 0) {
                assertEquals(layout[position], map.toDocument(position), "a document position: $context")
                assertNull(map.injectionAt(position), "a document character is not injected: $context")
            } else {
                val injection = assertNotNull(map.injectionAt(position), "an injected character: $context")
                assertEquals(injection.column, map.toDocument(position), "an injected position snaps to its column: $context")
            }
        }
        assertEquals(length + 3, map.toDocument(total + 3), "past the text stays past it: $context")

        val columns = ArrayList<Int>()
        map.forEachRun(0, length) { start, end -> for (position in start until end) columns += layout[position] }
        assertEquals((0 until length).toList(), columns, "runs are the document's characters: $context")
    }
}
