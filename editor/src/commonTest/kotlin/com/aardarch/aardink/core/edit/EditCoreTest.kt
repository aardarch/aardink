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
package com.aardarch.aardink.core.edit

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeDocument
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionSetTest {

    @Test
    fun `overlapping ranges and carets at one point merge, the primary stays first`() {
        val set = SelectionSet.of(listOf(TextRange(10, 12), TextRange(0, 3), TextRange(2, 5), TextRange(10), TextRange(20)))
        assertEquals(listOf(TextRange(10, 12), TextRange(0, 5), TextRange(20)), set.ranges)
    }

    @Test
    fun `a merged range keeps the direction of the primary`() {
        val set = SelectionSet.of(listOf(TextRange(5, 2), TextRange(4, 8)))
        assertEquals(TextRange(8, 2), set.primary)
    }

    @Test
    fun `mapping and clamping renormalise`() {
        val set = SelectionSet.of(listOf(TextRange(1), TextRange(5))).map { if (it == 5) 1 else it }
        assertTrue(set.isSingle)
        assertEquals(TextRange(3), SelectionSet.caret(9).clampedTo(3).primary)
    }
}

class TextChangeTest {

    @Test
    fun `changes apply high to low, keep same-offset order, and revert exactly`() {
        val document = CodeDocument("abcdef")
        val applied = document.applyChanges(
            listOf(TextChange(1, 1, "X"), TextChange(1, 1, "Y"), TextChange(3, 5, ""), TextChange(6, 6, "!")),
        )
        assertEquals("aXYbcf!", document.text)
        document.revert(applied)
        assertEquals("abcdef", document.text)
        document.reapply(applied)
        assertEquals("aXYbcf!", document.text)
    }

    @Test
    fun `random batches revert and reapply to the same text`() {
        val random = Random(3)
        val document = CodeDocument("the quick brown fox\njumps over\nthe lazy dog\n")
        repeat(200) {
            val before = document.text
            // Non-overlapping changes: pick sorted distinct cut points.
            val points = List(4) { random.nextInt(document.length + 1) }.distinct().sorted()
            val changes = points.chunked(2).filter { it.size == 2 }.map { (a, b) -> TextChange(a, b, "<${random.nextInt(9)}>") }
            val applied = document.applyChanges(changes)
            val after = document.text
            document.revert(applied)
            assertEquals(before, document.text)
            document.reapply(applied)
            assertEquals(after, document.text)
        }
    }

    @Test
    fun `mapOffset moves offsets after a change, snaps ones inside it, sticks to insertions`() {
        val changes = listOf(TextChange(2, 4, "XYZ"), TextChange(6, 6, "++"))
        assertEquals(1, mapOffset(1, changes))
        assertEquals(5, mapOffset(3, changes)) // inside 2..4: end of the replacement
        assertEquals(5, mapOffset(4, changes)) // at the end of a replaced span
        assertEquals(9, mapOffset(6, changes)) // at an insertion: after it
        assertEquals(7, mapOffset(6, changes, stickToEnd = false))
    }
}

class UndoHistoryTest {

    private fun caret(offset: Int) = SelectionSet.caret(offset)

    private fun typed(history: UndoHistory, at: Int, text: String) {
        history.record(TypingRules.kindOfTyping(text, 0), listOf(AppliedChange(at, "", text)), caret(at), caret(at + text.length))
    }

    @Test
    fun `typing groups by word, as in Monaco`() {
        val history = UndoHistory()
        "hello world".forEachIndexed { i, c -> typed(history, i, c.toString()) }
        assertEquals(" world", history.undo()!!.changes.joinToString("") { it.inserted })
        assertEquals("hello", history.undo()!!.changes.joinToString("") { it.inserted })
        assertFalse(history.canUndo)
    }

    @Test
    fun `moving the caret, another kind of edit, or an undo stop starts a new step`() {
        val history = UndoHistory()
        typed(history, 0, "a")
        typed(history, 5, "b") // caret moved
        history.record(EditKind.DeletingLeft, listOf(AppliedChange(5, "b", "")), caret(6), caret(5))
        typed(history, 5, "c")
        history.pushStop()
        typed(history, 6, "d")
        var steps = 0
        while (history.undo() != null) steps++
        assertEquals(5, steps)
    }

    @Test
    fun `backspaces group together`() {
        val history = UndoHistory()
        for (at in 5 downTo 3) history.record(EditKind.DeletingLeft, listOf(AppliedChange(at - 1, "x", "")), caret(at), caret(at - 1))
        assertEquals(3, history.undo()!!.changes.size)
        assertFalse(history.canUndo)
    }

    @Test
    fun `the alternative version id returns to the saved value on undo`() {
        val history = UndoHistory()
        typed(history, 0, "a")
        history.pushStop()
        val saved = history.alternativeVersionId
        typed(history, 1, "b")
        assertTrue(history.alternativeVersionId != saved)
        history.undo()
        assertEquals(saved, history.alternativeVersionId)
        history.redo()
        assertTrue(history.alternativeVersionId != saved)
        // A new edit after an undo never lands on an old id.
        history.undo()
        typed(history, 1, "c")
        assertTrue(history.alternativeVersionId != saved)
    }

    @Test
    fun `a new edit clears redo, and the stack is capped`() {
        val history = UndoHistory(capacity = 3)
        repeat(5) { history.record(EditKind.Other, listOf(AppliedChange(it, "", "x")), caret(it), caret(it + 1)) }
        history.undo()
        assertTrue(history.canRedo)
        history.record(EditKind.Other, listOf(AppliedChange(0, "", "y")), caret(0), caret(1))
        assertFalse(history.canRedo)
        var steps = 0
        while (history.undo() != null) steps++
        assertEquals(3, steps)
    }
}

class TextNavigatorTest {

    @Test
    fun `word movement follows VS Code word classes`() {
        val text = "val x = foo.bar(1)  // c"
        assertEquals(3, TextNavigator.wordRight(text, 0))
        assertEquals(5, TextNavigator.wordRight(text, 3))
        assertEquals(7, TextNavigator.wordRight(text, 5)) // "="
        assertEquals(11, TextNavigator.wordRight(text, 7))
        assertEquals(12, TextNavigator.wordRight(text, 11)) // "."
        assertEquals(8, TextNavigator.wordLeft(text, 11))
        assertEquals(6, TextNavigator.wordLeft(text, 8))
        assertEquals(0, TextNavigator.wordLeft(text, 3))
    }

    @Test
    fun `word movement stops at line breaks and crosses them when already there`() {
        val text = "ab\n  cd"
        assertEquals(3, TextNavigator.wordLeft(text, 5)) // skips the indent to the line start
        assertEquals(2, TextNavigator.wordLeft(text, 3)) // at a line start: to the end of the line above
        assertEquals(2, TextNavigator.wordRight(text, 0))
        assertEquals(3, TextNavigator.wordRight(text, 2))
    }

    @Test
    fun `characters are never split across a surrogate pair`() {
        val text = "a😀b"
        assertEquals(3, TextNavigator.charRight(text, 1))
        assertEquals(1, TextNavigator.charLeft(text, 3))
    }

    @Test
    fun `wordAt prefers the word after the caret and is empty between words`() {
        val text = "foo bar"
        assertEquals(4..6, TextNavigator.wordAt(text, 4))
        assertEquals(0..2, TextNavigator.wordAt(text, 3))
        assertTrue(TextNavigator.wordAt("a  b", 2).isEmpty())
    }

    @Test
    fun `smart home toggles between the first non-blank and column 0`() {
        val document = CodeDocument("x\n    indented")
        assertEquals(6, TextNavigator.smartHome(document, 10))
        assertEquals(2, TextNavigator.smartHome(document, 6))
    }

    @Test
    fun `vertical moves keep the column and clamp to short lines`() {
        val document = CodeDocument("long line\nab\nlong again")
        assertEquals(12, TextNavigator.verticalMove(document, 7, 1)) // col 7 -> end of "ab"
        assertEquals(20, TextNavigator.verticalMove(document, 12, 1, column = 7))
        assertEquals(0, TextNavigator.verticalMove(document, 3, -1))
        assertEquals(document.length, TextNavigator.verticalMove(document, 15, 1))
    }
}

class LineCommandsTest {

    private val slash = com.aardarch.aardink.core.CommentSyntax(line = "//", blockStart = "/*", blockEnd = "*/")
    private val xml = com.aardarch.aardink.core.CommentSyntax(blockStart = "<!--", blockEnd = "-->")

    private fun run(
        text: String,
        selections: List<TextRange>,
        command: (CodeDocument, SelectionSet) -> CommandEdit?,
    ): Pair<String, List<TextRange>> {
        val document = CodeDocument(text)
        val edit = command(document, SelectionSet.of(selections)) ?: return text to selections
        document.applyChanges(edit.changes)
        return document.text to edit.selectionsAfter.ranges
    }

    @Test
    fun `toggle comment adds at the smallest indent, then removes`() {
        val (commented, _) = run("  a\n    b\n\n  c", listOf(TextRange(0, 14))) { d, s -> LineCommands.toggleComment(d, s, slash) }
        assertEquals("  // a\n  //   b\n\n  // c", commented)
        val (uncommented, _) = run(commented, listOf(TextRange(0, commented.length))) { d, s -> LineCommands.toggleComment(d, s, slash) }
        assertEquals("  a\n    b\n\n  c", uncommented)
    }

    @Test
    fun `toggle comment keeps the caret on its text`() {
        val (text, selections) = run("val a", listOf(TextRange(4))) { d, s -> LineCommands.toggleComment(d, s, slash) }
        assertEquals("// val a", text)
        assertEquals(listOf(TextRange(7)), selections)
    }

    @Test
    fun `without a line comment the lines are wrapped in a block comment and unwrapped`() {
        val (wrapped, _) = run("  <a/>\n  <b/>", listOf(TextRange(0, 13))) { d, s -> LineCommands.toggleComment(d, s, xml) }
        assertEquals("  <!-- <a/>\n  <b/> -->", wrapped)
        val (unwrapped, _) = run(wrapped, listOf(TextRange(0, wrapped.length))) { d, s -> LineCommands.toggleComment(d, s, xml) }
        assertEquals("  <a/>\n  <b/>", unwrapped)
    }

    @Test
    fun `a selection ending at a line start does not reach that line`() {
        val document = CodeDocument("a\nb\nc")
        assertEquals(listOf(0..0), LineCommands.lineBlocks(document, SelectionSet.single(TextRange(0, 2))))
        assertEquals(listOf(0..2), LineCommands.lineBlocks(document, SelectionSet.of(listOf(TextRange(0), TextRange(2), TextRange(4)))))
    }

    @Test
    fun `moving lines swaps them and carries the selection`() {
        val (down, downSelection) = run("one\ntwo\nthree", listOf(TextRange(1))) { d, s -> LineCommands.moveLines(d, s, up = false) }
        assertEquals("two\none\nthree", down)
        assertEquals(listOf(TextRange(5)), downSelection)
        val (up, _) = run("one\ntwo\nthree", listOf(TextRange(9))) { d, s -> LineCommands.moveLines(d, s, up = true) }
        assertEquals("one\nthree\ntwo", up)
        assertEquals(null, LineCommands.moveLines(CodeDocument("a\nb"), SelectionSet.caret(0), up = true))
    }

    @Test
    fun `copying lines down moves the caret onto the copy`() {
        val (text, selections) = run("a\nbc\nd", listOf(TextRange(3))) { d, s -> LineCommands.copyLines(d, s, down = true) }
        assertEquals("a\nbc\nbc\nd", text)
        assertEquals(listOf(TextRange(6)), selections)
        val (textUp, selectionsUp) = run("a\nbc\nd", listOf(TextRange(3))) { d, s -> LineCommands.copyLines(d, s, down = false) }
        assertEquals("a\nbc\nbc\nd", textUp)
        assertEquals(listOf(TextRange(3)), selectionsUp)
    }

    @Test
    fun `deleting lines removes them with their newline`() {
        assertEquals("a\nc", run("a\nb\nc", listOf(TextRange(2))) { d, s -> LineCommands.deleteLines(d, s) }.first)
        assertEquals("a\nb", run("a\nb\nc", listOf(TextRange(4))) { d, s -> LineCommands.deleteLines(d, s) }.first)
        assertEquals("", run("only", listOf(TextRange(1))) { d, s -> LineCommands.deleteLines(d, s) }.first)
    }

    @Test
    fun `indent and outdent work on every selection`() {
        val (indented, _) = run("a\nb\nc", listOf(TextRange(0, 1), TextRange(4, 5))) { d, s -> LineCommands.indent(d, s) }
        assertEquals("    a\nb\n    c", indented)
        val (outdented, _) = run("    a\n  b\n\tc", listOf(TextRange(0, 11))) { d, s -> LineCommands.outdent(d, s) }
        assertEquals("a\nb\nc", outdented)
        val (carets, caretSelections) = run("ab", listOf(TextRange(1))) { d, s -> LineCommands.indent(d, s) }
        assertEquals("a    b", carets)
        assertEquals(listOf(TextRange(5)), caretSelections)
    }
}

class OccurrenceFinderTest {

    @Test
    fun `the first press selects the word, later ones add the next occurrence`() {
        val text = "foo bar foo foobar foo"
        val words = OccurrenceFinder.addNext(text, SelectionSet.caret(1), wholeWord = false)!!
        assertEquals(listOf(TextRange(0, 3)), words.ranges)
        val second = OccurrenceFinder.addNext(text, words, wholeWord = true)!!
        assertEquals(TextRange(8, 11), second.primary)
        val third = OccurrenceFinder.addNext(text, second, wholeWord = true)!!
        assertEquals(TextRange(19, 22), third.primary) // skips the "foo" inside "foobar"
        assertEquals(null, OccurrenceFinder.addNext(text, third, wholeWord = true))
    }

    @Test
    fun `the search wraps around the document`() {
        val text = "x a x a"
        val next = OccurrenceFinder.addNext(text, SelectionSet.single(TextRange(6, 7)), wholeWord = true)!!
        assertEquals(TextRange(2, 3), next.primary)
    }

    @Test
    fun `select all finds every occurrence and keeps the primary one`() {
        val text = "id id idx id"
        val all = OccurrenceFinder.selectAll(text, SelectionSet.caret(4))!!
        assertEquals(TextRange(3, 5), all.primary)
        assertEquals(3, all.ranges.size)
    }
}
