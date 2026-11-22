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
package com.aardarch.aardink.core.diff

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffComputerTest {

    private val exact = DiffOptions(ignoreTrimWhitespace = false)

    private fun lines(text: String): List<String> = text.split('\n')

    /** The modified lines rebuilt from the original ones and [changes]. */
    private fun rebuild(original: List<String>, modified: List<String>, changes: List<LineChange>): List<String> {
        val out = ArrayList<String>()
        var at = 0
        for (change in changes) {
            // The lines between two changes are unchanged: they sit at the same place on both sides.
            while (at < change.originalStart) {
                out += original[at++]
            }
            assertEquals(out.size, change.modifiedStart)
            for (i in change.modifiedStart until change.modifiedEnd) out += modified[i]
            at = change.originalEnd
        }
        while (at < original.size) out += original[at++]
        return out
    }

    private fun lcs(a: List<String>, b: List<String>): Int {
        val t = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) {
            for (j in b.indices) {
                t[i + 1][j + 1] = if (a[i] == b[j]) t[i][j] + 1 else maxOf(t[i][j + 1], t[i + 1][j])
            }
        }
        return t[a.size][b.size]
    }

    private fun assertWellFormed(changes: List<LineChange>, originalSize: Int, modifiedSize: Int) {
        var previous: LineChange? = null
        for (change in changes) {
            assertTrue(change.originalStart <= change.originalEnd && change.modifiedStart <= change.modifiedEnd)
            assertTrue(change.originalStart < change.originalEnd || change.modifiedStart < change.modifiedEnd, "empty change")
            assertTrue(change.originalEnd <= originalSize && change.modifiedEnd <= modifiedSize)
            if (previous != null) {
                assertTrue(change.originalStart > previous.originalEnd, "overlapping or adjacent: $previous $change")
                assertTrue(change.modifiedStart > previous.modifiedEnd, "overlapping or adjacent: $previous $change")
            }
            previous = change
        }
    }

    private fun check(original: String, modified: String) {
        val a = lines(original)
        val b = lines(modified)
        val result = DiffComputer.compute(original, modified, exact)
        assertWellFormed(result.changes, a.size, b.size)
        assertEquals(b, rebuild(a, b, result.changes), "$original -> $modified")
        val unchanged = a.size - result.changes.sumOf { it.originalEnd - it.originalStart }
        assertEquals(lcs(a, b), unchanged, "not minimal: $original -> $modified")
        assertFalse(result.hitTimeout)
        assertEquals(original == modified, result.identical)
    }

    @Test
    fun randomInputsMatchTheLongestCommonSubsequence() {
        val random = Random(42)
        val alphabet = listOf("a", "b", "c", "d")
        repeat(2_000) {
            val size = 2 + random.nextInt(3)
            fun text() = List(random.nextInt(13)) { alphabet[random.nextInt(size)] }.joinToString("\n")
            check(text(), text())
        }
    }

    @Test
    fun identicalAndEmptyInputs() {
        val same = DiffComputer.compute("a\nb\nc", "a\nb\nc")
        assertTrue(same.identical)
        assertTrue(same.changes.isEmpty())
        assertTrue(DiffComputer.compute("", "").identical)
        check("", "")
        check("a\nb", "a\nb")
    }

    @Test
    fun oneSideEmpty() {
        check("", "a\nb\nc")
        check("a\nb\nc", "")
        val insertion = DiffComputer.compute("a\nc", "a\nb\nc").changes.single()
        assertEquals(LineChange(1, 1, 1, 2, null), insertion)
        val deletion = DiffComputer.compute("a\nb\nc", "a\nc").changes.single()
        assertEquals(LineChange(1, 2, 1, 1, null), deletion)
    }

    @Test
    fun adjacentChangesAreOne() {
        val changes = DiffComputer.compute("a\nb\nc\nd", "a\nx\ny\nd", exact).changes
        assertEquals(1, changes.size)
        assertEquals(1, changes[0].originalStart)
        assertEquals(3, changes[0].originalEnd)
        assertEquals(3, changes[0].modifiedEnd)
    }

    @Test
    fun ignoreTrimWhitespace() {
        val original = "a\n  b\nc  \nd"
        val modified = "a\nb\nc\nd"
        val ignoring = DiffComputer.compute(original, modified)
        assertTrue(ignoring.changes.isEmpty())
        assertFalse(ignoring.identical)
        val exact = DiffComputer.compute(original, modified, exact)
        assertEquals(1, exact.changes.size)
        assertEquals(1, exact.changes[0].originalStart)
        assertEquals(3, exact.changes[0].originalEnd)
    }

    @Test
    fun carriageReturnsAreNotContent() {
        val result = DiffComputer.compute("a\r\nb\r\nc", "a\nb\nc", exact)
        assertTrue(result.changes.isEmpty())
        assertTrue(result.identical)
        val edited = DiffComputer.compute("a\r\nb\r\nc", "a\nx\nc", exact).changes.single()
        assertEquals(LineChange(1, 2, 1, 2, edited.charChanges), edited)
        assertEquals(listOf(CharChange(3, 4, 2, 3)), edited.charChanges)
    }

    @Test
    fun aTimeoutStillReconstructsTheText() {
        val a = List(200) { if (it % 3 == 0) "same$it" else "old$it" }
        val b = List(200) { if (it % 3 == 0) "same$it" else "new$it" }
        var now = 0L
        val result = DiffComputer.compute(a.joinToString("\n"), b.joinToString("\n"), DiffOptions(maxComputationTimeMs = 100)) {
            now += 1_000
            now
        }
        assertTrue(result.hitTimeout)
        assertWellFormed(result.changes, a.size, b.size)
        assertEquals(b, rebuild(a, b, result.changes))
        assertTrue(result.changes.all { it.charChanges == null })
    }

    @Test
    fun noTimeoutWithoutALimit() {
        var now = 0L
        val result = DiffComputer.compute("a\nb\nc", "a\nx\nc", DiffOptions(maxComputationTimeMs = 0)) {
            now += 1_000_000
            now
        }
        assertFalse(result.hitTimeout)
    }

    @Test
    fun aReplacedWordIsExactlyTheCharChange() {
        val change = DiffComputer.compute("let foo = 1", "let bar = 1").changes.single()
        assertEquals(listOf(CharChange(4, 7, 4, 7)), change.charChanges)
    }

    @Test
    fun charChangeOffsetsAreAbsolute() {
        val original = "x\ny\nlet foo = 1\nz"
        val modified = "x\ny\nlet quxx = 1\nz"
        val change = DiffComputer.compute(original, modified).changes.single()
        assertEquals(listOf(CharChange(8, 11, 8, 12)), change.charChanges)
        assertEquals("foo", original.substring(8, 11))
        assertEquals("quxx", modified.substring(8, 12))
    }

    @Test
    fun charChangesAFewCharactersApartAreOne() {
        val merged = DiffComputer.compute("abc\nfoo", "abcd\nbar").changes.single()
        assertEquals(listOf(CharChange(3, 7, 3, 8)), merged.charChanges)
        val apart = DiffComputer.compute("foo -- bar", "qux -- zzz").changes.single()
        assertEquals(listOf(CharChange(0, 3, 0, 3), CharChange(7, 10, 7, 10)), apart.charChanges)
    }

    @Test
    fun whitespaceAtTheEdgesOfALineIsNotACharChange() {
        val original = "  foo bar"
        val modified = "foo baz"
        assertEquals(
            listOf(CharChange(8, 9, 6, 7)),
            DiffComputer.compute(original, modified).changes.single().charChanges,
        )
        assertEquals(
            listOf(CharChange(0, 2, 0, 0), CharChange(8, 9, 6, 7)),
            DiffComputer.compute(original, modified, exact).changes.single().charChanges,
        )
    }

    @Test
    fun aLongBlockHasNoCharChanges() {
        val long = "x".repeat(50)
        val change = DiffComputer.compute("a$long", "b$long", DiffOptions(maxCharChangeChars = 20)).changes.single()
        assertNull(change.charChanges)
        val off = DiffComputer.compute("a", "b", DiffOptions(computeCharChanges = false)).changes.single()
        assertNull(off.charChanges)
        assertNotNull(DiffComputer.compute("a", "b").changes.single().charChanges)
    }
}
