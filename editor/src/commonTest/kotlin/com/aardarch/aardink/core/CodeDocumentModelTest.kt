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

import com.aardarch.aardink.core.text.DocumentChange
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The gap buffer and incremental line index behind [CodeDocument], checked against a plain
 * [StringBuilder] after every edit of a long seeded random sequence.
 */
class CodeDocumentModelTest {

    private val fragments = listOf("a", "bc", "\n", "x\ny", "\r\n", "😀", "\n\n", "fun main() {\n    val s = \"😀\"\n}\n", "", " ")

    @Test
    fun `random edits match a StringBuilder reference`() {
        repeat(5) { seed -> runRandomEdits(seed) }
    }

    private fun runRandomEdits(seed: Int) {
        val random = Random(seed)
        val reference = StringBuilder("start\nline two\n")
        val document = CodeDocument(reference.toString())
        repeat(600) { step ->
            if (random.nextInt(10) < 6 || reference.isEmpty()) {
                val offset = random.nextInt(reference.length + 1)
                val text = fragments[random.nextInt(fragments.size)]
                document.insert(offset, text)
                reference.insert(offset, text)
            } else {
                val offset = random.nextInt(reference.length)
                val length = random.nextInt(1, minOf(12, reference.length - offset) + 1)
                document.delete(offset, length)
                reference.deleteRange(offset, offset + length)
            }
            if (step % 25 == 0 || step == 599) assertMatches(reference.toString(), document, random, "seed $seed step $step")
        }
    }

    private fun assertMatches(expected: String, document: CodeDocument, random: Random, where: String) {
        assertEquals(expected, document.text, where)
        assertEquals(expected.length, document.length, where)
        val lineStarts = listOf(0) + expected.indices.filter { expected[it] == '\n' }.map { it + 1 }
        assertEquals(lineStarts.size, document.lineCount, "$where: line count")
        lineStarts.forEachIndexed { line, start ->
            assertEquals(start, document.lineStart(line), "$where: start of line $line")
            val end = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else expected.length
            assertEquals(end, document.lineEnd(line), "$where: end of line $line")
            assertEquals(expected.substring(start, end), document.lineText(line), "$where: text of line $line")
        }
        repeat(20) {
            val offset = random.nextInt(expected.length + 1)
            val line = lineStarts.indexOfLast { it <= offset }
            assertEquals(line to offset - lineStarts[line], document.offsetToLineCol(offset), "$where: offset $offset")
            if (offset < expected.length) assertEquals(expected[offset], document[offset], "$where: char $offset")
            val end = random.nextInt(offset, expected.length + 1)
            assertEquals(expected.substring(offset, end), document.subSequence(offset, end).toString(), "$where: slice")
        }
    }

    @Test
    fun `a document is a CharSequence that regex can search directly`() {
        val document = CodeDocument("val a = 1\nval b = 2\n")
        document.insert(0, "// head\n")
        assertEquals(listOf(8, 18), Regex("val").findAll(document).map { it.range.first }.toList())
        assertEquals(document.text, document.toString())
    }

    @Test
    fun `a snapshot keeps its text and line index through later edits`() {
        val document = CodeDocument("one\ntwo\n")
        val snapshot = document.snapshot()
        assertSame(snapshot, document.snapshot(), "unchanged document reuses its snapshot")
        document.insert(0, "zero\n")
        document.delete(document.length - 4, 4)
        assertEquals("one\ntwo\n", snapshot.text)
        assertEquals(3, snapshot.lineCount)
        assertEquals(4, snapshot.lineStart(1))
        assertEquals(1 to 2, snapshot.offsetToLineCol(6))
        assertTrue(snapshot.isSnapshot)
        assertFailsWith<IllegalStateException> { snapshot.insert(0, "x") }
        assertFailsWith<IllegalStateException> { snapshot.delete(0, 1) }
        assertFailsWith<IllegalStateException> { snapshot.replaceAll("") }
    }

    @Test
    fun `text is built once per version`() {
        val document = CodeDocument("abc")
        assertSame(document.text, document.text)
        document.insert(3, "d")
        assertEquals("abcd", document.text)
    }

    @Test
    fun `change events describe the edit in lines and columns`() {
        val document = CodeDocument("ab\ncd\nef")
        val changes = mutableListOf<DocumentChange>()
        document.addChangeListener { changes += it }

        document.insert(4, "X\nYY") // inside "cd": c|d
        with(changes.last()) {
            assertEquals(1, startLine)
            assertEquals(1, startColumn)
            assertEquals(1, addedLines)
            assertEquals(0, removedLines)
            assertEquals(1, oldTailColumn)
            assertEquals(2, tailColumn) // "d" now starts at column 2 of the new line "YYd"
        }
        assertEquals("ab\ncX\nYYd\nef", document.text)

        document.delete(1, 7) // from "b" through "YY": ab\ncX\nYY -> a
        with(changes.last()) {
            assertEquals(0, startLine)
            assertEquals(1, startColumn)
            assertEquals(2, removedLines)
            assertEquals(2, oldTailColumn) // "d" started at column 2 of old line 2
            assertEquals(1, tailColumn)
        }
        assertEquals("ad\nef", document.text)

        document.replaceAll("new")
        assertTrue(changes.last().isReset)
        assertEquals(changes.size.toLong(), document.version)
    }
}
