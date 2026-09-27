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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindAndDiffTest {

    @Test
    fun `cooperative find matches findAll below the limit`() = runTest {
        val text = "ab ab ab\nAB".repeat(50)
        val result = FindEngine.findAllCooperatively(text, "ab", FindEngine.Options(), limit = 10_000)
        assertEquals(FindEngine.findAll(text, "ab"), result.matches)
        assertFalse(result.capped)
    }

    @Test
    fun `cooperative find stops at the limit and says so`() = runTest {
        val result = FindEngine.findAllCooperatively("x".repeat(500), "x", FindEngine.Options(), limit = 100)
        assertEquals(100, result.matches.size)
        assertTrue(result.capped)
    }

    @Test
    fun `a long search can be cancelled between chunks`() = runTest {
        val text = "needle and haystack ".repeat(20_000) // 400 KB: several yield points
        val search = async { FindEngine.findAllCooperatively(text, "needle", FindEngine.Options(), limit = Int.MAX_VALUE) }
        yield()
        search.cancel()
        val cancelled = try {
            search.await()
            false
        } catch (_: CancellationException) {
            true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `show opens find only, or with the replace row`() {
        val state = FindReplaceState()
        state.show()
        assertTrue(state.visible)
        assertFalse(state.replaceMode)
        state.show(replace = true)
        assertTrue(state.replaceMode)
        state.hide()
        assertFalse(state.visible)
    }

    @Test
    fun `inserting a line marks only that line`() {
        val base = listOf("a", "b", "c", "d")
        val current = listOf("a", "new", "b", "c", "d")
        assertEquals(listOf(LineDiff(1, LineDiffKind.Added)), SimpleDiffProvider.diff(base, current))
    }

    @Test
    fun `changed lines between shared ones are modified, extra ones added`() {
        val base = listOf("a", "b", "c", "z")
        val current = listOf("a", "B", "C", "D", "z")
        assertEquals(
            listOf(LineDiff(1, LineDiffKind.Modified), LineDiff(2, LineDiffKind.Modified), LineDiff(3, LineDiffKind.Added)),
            SimpleDiffProvider.diff(base, current),
        )
    }

    @Test
    fun `identical texts have no diff`() {
        assertTrue(SimpleDiffProvider.diff(listOf("a", "b"), listOf("a", "b")).isEmpty())
    }
}
