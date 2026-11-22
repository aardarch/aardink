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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

class DiffComputerScaleTest {

    private fun rebuild(original: List<String>, modified: List<String>, changes: List<LineChange>): List<String> {
        val out = ArrayList<String>()
        var at = 0
        for (change in changes) {
            while (at < change.originalStart) out += original[at++]
            for (i in change.modifiedStart until change.modifiedEnd) out += modified[i]
            at = change.originalEnd
        }
        while (at < original.size) out += original[at++]
        return out
    }

    @Test
    fun anEditEveryFiftyLinesOfAHundredThousand() {
        val original = List(100_000) { "line $it of the file" }
        val modified = ArrayList<String>(100_000)
        var edits = 0
        for ((i, line) in original.withIndex()) {
            if (i % 50 != 0) {
                modified += line
                continue
            }
            when ((i / 50) % 3) {
                0 -> modified += "$line, inserted".also { modified += "extra $i" }
                1 -> Unit
                else -> modified += "modified $i"
            }
            edits++
        }
        val originalText = original.joinToString("\n")
        val modifiedText = modified.joinToString("\n")
        DiffComputer.compute(originalText, modifiedText) // warm up the JIT, as an editor that has diffed before
        val (result, elapsed) = measureTimedValue { DiffComputer.compute(originalText, modifiedText) }
        println("100,000 lines, $edits edits: ${elapsed.inWholeMilliseconds} ms, ${result.changes.size} changes")
        assertFalse(result.hitTimeout)
        assertEquals(edits, result.changes.size)
        assertEquals(modified, rebuild(original, modified, result.changes))
        assertTrue(elapsed.inWholeMilliseconds < 1_000, "took $elapsed")
    }

    @Test
    fun fiveThousandLinesThatAllDiffer() {
        val original = List(5_000) { "original line $it" }
        val modified = List(5_000) { "changed line $it" }
        val (result, elapsed) = measureTimedValue {
            DiffComputer.compute(original.joinToString("\n"), modified.joinToString("\n"))
        }
        println("5,000 lines all different: ${elapsed.inWholeMilliseconds} ms, timeout ${result.hitTimeout}")
        assertEquals(modified, rebuild(original, modified, result.changes))
        assertTrue(elapsed.inWholeMilliseconds < 8_000, "took $elapsed")
    }
}
