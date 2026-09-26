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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Typing into a large document must cost the size of the edit, not of the document. The bound is
 * loose on purpose (it guards against a return to rebuilding the line index or copying the text
 * per keystroke, which would take minutes here, not against a slow CI machine).
 */
class CodeDocumentScalingTest {

    @Test
    fun `ten thousand keystrokes into a 2 MB document stay fast`() {
        val line = "    val value = compute(\"a string\", 42) // comment\n"
        val document = CodeDocument(line.repeat(2 * 1024 * 1024 / line.length))
        val middle = document.lineStart(document.lineCount / 2)
        val elapsed = measureTime {
            var caret = middle
            repeat(10_000) { i ->
                if (i % 50 == 49) {
                    document.insert(caret, "\n")
                } else {
                    document.insert(caret, "x")
                }
                caret++
                // Reading positions after each edit is what the editor does per keystroke.
                document.offsetToLineCol(caret)
                document.lineStart(document.lineCount - 1)
            }
            repeat(2_000) {
                caret--
                document.delete(caret, 1)
            }
        }
        println("10k inserts + 2k deletes into ${document.length / 1024} KB: $elapsed")
        assertTrue(elapsed.inWholeMilliseconds < 2_000, "took $elapsed")
        assertEquals(line.repeat(2 * 1024 * 1024 / line.length).length + 8_000, document.length)
    }
}
