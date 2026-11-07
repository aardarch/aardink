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

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What ghost text shows of a completion, and what happens to it, with no layout: the answers are given here. */
class InlineCompletionTest {

    /** [text] with the caret where `|` is, and a provider standing by. */
    private fun stateAt(text: String): CodeEditorState {
        val caret = text.indexOf('|')
        val state = CodeEditorState(
            initialText = text.removeRange(caret, caret + 1),
            tokenizeDebounceMs = 0,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        state.computeDispatcher = Dispatchers.Unconfined
        state.selection = TextRange(caret)
        state.inlineCompletions.provided = true
        return state
    }

    /** Asks at the caret and answers with [completions], as a layout would. */
    private fun CodeEditorState.offer(vararg completions: InlineCompletion) {
        inlineCompletions.askAgain(now = true)
        inlineCompletions.answer(assertNotNull(inlineCompletions.ask), document.version, completions.toList())
    }

    private fun CodeEditorState.offer(text: String) = offer(InlineCompletion(text))

    /** The line with what shows put in, each piece in brackets: `foo([bar])`. */
    private val CodeEditorState.drawn: String?
        get() = inlineCompletions.shown?.let { ghost ->
            val start = document.lineStart(ghost.line)
            buildString {
                var at = start
                for (piece in ghost.pieces) {
                    append(document.subSequence(at, piece.offset))
                    append('[').append(piece.text).append(']')
                    at = piece.offset
                }
                append(document.subSequence(at, document.lineEnd(ghost.line)))
            }
        }

    private fun CodeEditorState.typeAtCaret(text: String) {
        val caret = selection.end
        applyInput(caret, caret, text)
    }

    private fun CodeEditorState.accept() = assertTrue(execute(EditorCommand.AcceptInlineCompletion), "accepted")

    // ── Where it shows: Monaco's computeGhostText ────────────────────────────

    @Test
    fun `text at the end of a line shows after the caret`() {
        val state = stateAt("val x = |")
        state.offer("compute()")
        assertEquals("val x = [compute()]", state.drawn)
    }

    @Test
    fun `text that repeats what follows the caret does not double it`() {
        val state = stateAt("foo(|)")
        state.offer("bar)")
        assertEquals("foo([bar])", state.drawn)
        state.accept()
        assertEquals("foo(bar)", state.document.text)
    }

    @Test
    fun `text around what follows the caret shows on each side of it`() {
        val state = stateAt("if (|)")
        val caret = state.selection.end
        state.offer(InlineCompletion("x == 1) {", caret until caret + 1))
        assertEquals("if ([x == 1])[ {]", state.drawn)
        state.accept()
        assertEquals("if (x == 1) {", state.document.text)
    }

    @Test
    fun `an explicit range over text the completion does not hold shows nothing`() {
        val state = stateAt("foo(|x)")
        val caret = state.selection.end
        state.offer(InlineCompletion("bar", caret until caret + 2))
        assertNull(state.inlineCompletions.shown)
    }

    @Test
    fun `with no range, text that does not hold the rest of the line goes in at the caret`() {
        val state = stateAt("foo(|x, y)")
        state.offer("bar, ")
        assertEquals("foo([bar, ]x, y)", state.drawn)
    }

    @Test
    fun `with no range, the word typed so far is taken as typed`() {
        val state = stateAt("val s = pri|")
        state.offer("println()")
        assertEquals("val s = pri[ntln()]", state.drawn)
    }

    @Test
    fun `an empty range is a plain insertion, as in Monaco`() {
        val state = stateAt("foo(|)")
        val caret = state.selection.end
        state.offer(InlineCompletion("bar)", caret until caret))
        assertEquals("foo([bar)])", state.drawn)
    }

    @Test
    fun `a range that does not hold the caret shows nothing`() {
        val state = stateAt("one| two")
        state.offer(InlineCompletion("three", 4 until 7))
        assertNull(state.inlineCompletions.shown)
    }

    @Test
    fun `a range past the document shows nothing`() {
        val state = stateAt("one|")
        state.offer(InlineCompletion("three", 3 until 9))
        assertNull(state.inlineCompletions.shown)
    }

    @Test
    fun `text the document already has shows nothing`() {
        val state = stateAt("fo|o")
        state.offer("foo")
        assertNull(state.inlineCompletions.shown)
        assertFalse(state.canExecute(EditorCommand.AcceptInlineCompletion))
    }

    @Test
    fun `ghost text never shows before the caret`() {
        val state = stateAt("foo()|")
        val start = state.document.lineStart(0)
        state.offer(InlineCompletion("foo(x)", start until state.selection.end))
        assertNull(state.inlineCompletions.shown)
    }

    @Test
    fun `text past a repeated start shows where it goes, ahead of the caret`() {
        val state = stateAt("fo|o")
        state.offer("foobar")
        assertEquals("foo[bar]", state.drawn)
    }

    @Test
    fun `text on more lines keeps its breaks and what follows the caret comes after them`() {
        val state = stateAt("foo(|)")
        state.offer("a,\n    b)")
        assertEquals("foo([a,\n    b])", state.drawn)
        assertEquals(0, state.inlineCompletions.shown?.line)
        state.accept()
        assertEquals("foo(a,\n    b)", state.document.text)
    }

    @Test
    fun `a line of indentation keeps it, and the text shows after it`() {
        val state = stateAt("    |")
        state.offer(InlineCompletion("  return x", 0 until 4))
        assertEquals("    [return x]", state.drawn)
        state.accept()
        assertEquals("    return x", state.document.text)
    }

    @Test
    fun `more indentation than the line has shows what it adds`() {
        val state = stateAt("    |")
        state.offer(InlineCompletion("        return x", 0 until 4))
        assertEquals("    [    return x]", state.drawn)
    }

    @Test
    fun `a line ending in CR LF is left out of what the text must repeat`() {
        val state = stateAt("foo(|)\r\nnext")
        state.offer("bar)")
        assertEquals("foo([bar])\r", state.drawn)
    }

    @Test
    fun `a completion's own CR LF is read as a line break`() {
        val state = stateAt("a|")
        state.offer("b\r\nc")
        assertEquals("a[b\nc]", state.drawn)
    }

    @Test
    fun `a pair sharing its first half is not split`() {
        val state = stateAt("😀|")
        val start = 0
        state.offer(InlineCompletion("😀😁", start until 2))
        assertEquals("😀[😁]", state.drawn)
    }

    @Test
    fun `a completion longer than a line can show is not shown, so Tab cannot take what cannot be seen`() {
        val state = stateAt("a|")
        state.offer("b".repeat(10_001))
        assertNull(state.inlineCompletions.shown)
    }

    // ── Typing ───────────────────────────────────────────────────────────────

    @Test
    fun `typing what it says keeps it and shortens it, and asks nothing`() {
        val state = stateAt("val x = |")
        state.offer("compute()")
        state.typeAtCaret("c")
        assertEquals("val x = c[ompute()]", state.drawn)
        state.typeAtCaret("o")
        assertEquals("val x = co[mpute()]", state.drawn)
        assertNull(state.inlineCompletions.ask, "nothing to ask while it shows")
    }

    @Test
    fun `typing through a line break moves it to the new line`() {
        val state = stateAt("a|")
        state.offer("\n  b")
        state.typeAtCaret("\n")
        assertEquals("[  b]", state.drawn)
        assertEquals(1, state.inlineCompletions.shown?.line)
    }

    @Test
    fun `typing anything else drops it and asks again`() {
        val state = stateAt("val x = |")
        state.offer("compute()")
        state.typeAtCaret("z")
        assertNull(state.inlineCompletions.shown)
        assertEquals(state.selection.end, state.inlineCompletions.ask?.caret)
        assertEquals(false, state.inlineCompletions.ask?.now, "after a pause")
    }

    @Test
    fun `typing the last of it leaves nothing, and asks again`() {
        val state = stateAt("a|")
        state.offer("b")
        state.typeAtCaret("b")
        assertNull(state.inlineCompletions.shown)
        assertNotNull(state.inlineCompletions.ask)
    }

    @Test
    fun `typing what only another completion says moves on to that one`() {
        val state = stateAt("val x = |")
        state.offer(InlineCompletion("compute()"), InlineCompletion("zero"))
        state.typeAtCaret("z")
        assertEquals("val x = z[ero]", state.drawn)
        assertNull(state.inlineCompletions.ask)
    }

    @Test
    fun `an auto-closed bracket is not doubled by a completion that has it`() {
        val state = stateAt("foo|")
        state.offer("(a)")
        val service = object : LanguageService by NoAnswers {
            override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = if (charTyped == '(') ")" else null
        }
        val caret = state.selection.end
        state.applyInput(caret, caret, "(", service = service)
        assertEquals("foo()", state.document.text)
        assertEquals("foo([a])", state.drawn)
        state.accept()
        assertEquals("foo(a)", state.document.text)
    }

    @Test
    fun `deleting drops it and asks again`() {
        val state = stateAt("val x = |")
        state.offer("compute()")
        assertTrue(state.deleteLeft())
        assertNull(state.inlineCompletions.shown)
        assertNotNull(state.inlineCompletions.ask)
    }

    @Test
    fun `pasting asks, and so does indenting`() {
        val state = stateAt("|")
        state.paste("x")
        assertNotNull(state.inlineCompletions.ask)
        val asked = state.inlineCompletions.ask
        state.indentSelection()
        assertNotNull(state.inlineCompletions.ask)
        assertTrue(asked !== state.inlineCompletions.ask, "a new ask, calling off the last")
    }

    @Test
    fun `moving the caret drops it and asks nothing`() {
        val state = stateAt("val x = |")
        state.offer("compute()")
        state.selection = TextRange(0)
        assertNull(state.inlineCompletions.shown)
        assertNull(state.inlineCompletions.ask)
    }

    @Test
    fun `moving the caret calls off an ask`() {
        val state = stateAt("val x = |")
        state.typeAtCaret("c")
        assertNotNull(state.inlineCompletions.ask)
        state.selection = TextRange(0)
        assertNull(state.inlineCompletions.ask)
    }

    @Test
    fun `setting the selection where it is keeps it`() {
        val state = stateAt("val x = |")
        state.offer("compute()")
        state.selection = TextRange(state.selection.end)
        assertEquals("val x = [compute()]", state.drawn)
    }

    @Test
    fun `an edit from elsewhere drops it and asks nothing`() {
        val state = stateAt("one\ntwo|")
        state.offer("three")
        state.applyTextEdits(listOf(TextEdit(0 until 1, "O")))
        assertNull(state.inlineCompletions.shown)
        assertNull(state.inlineCompletions.ask)
    }

    @Test
    fun `new text drops it`() {
        val state = stateAt("one|")
        state.offer("two")
        state.loadText("else")
        assertNull(state.inlineCompletions.shown)
    }

    // ── Answers ──────────────────────────────────────────────────────────────

    @Test
    fun `an answer for text that has moved on is dropped`() {
        val state = stateAt("a|")
        state.inlineCompletions.askAgain()
        val asked = assertNotNull(state.inlineCompletions.ask)
        val version = state.document.version
        state.typeAtCaret("b")
        state.inlineCompletions.answer(asked, version, listOf(InlineCompletion("c")))
        assertNull(state.inlineCompletions.shown)
        assertNotNull(state.inlineCompletions.ask, "what typing asked is still wanted")
    }

    @Test
    fun `an answer for a document edited behind the state is dropped`() {
        val state = stateAt("a|")
        state.inlineCompletions.askAgain()
        val asked = assertNotNull(state.inlineCompletions.ask)
        val version = state.document.version
        state.document.insert(0, "b")
        state.inlineCompletions.answer(asked, version, listOf(InlineCompletion("c")))
        assertNull(state.inlineCompletions.shown)
    }

    @Test
    fun `nothing is accepted after the document was edited behind the state`() {
        val state = stateAt("a|")
        state.offer("c")
        state.document.insert(0, "b")
        assertFalse(state.execute(EditorCommand.AcceptInlineCompletion))
        assertEquals("ba", state.document.text)
    }

    @Test
    fun `completions that would show the same are offered once`() {
        val state = stateAt("val x = |")
        val caret = state.selection.end
        state.offer(InlineCompletion("c"), InlineCompletion("c", caret until caret))
        state.inlineCompletions.cycle(forward = true)
        assertEquals("val x = [c]", state.drawn)
    }

    @Test
    fun `cycling goes through what is on offer, both ways, wrapping`() {
        val state = stateAt("|")
        state.offer(InlineCompletion("one"), InlineCompletion("two"), InlineCompletion("three"))
        assertEquals("[one]", state.drawn)
        state.inlineCompletions.cycle(forward = true)
        assertEquals("[two]", state.drawn)
        state.inlineCompletions.cycle(forward = false)
        state.inlineCompletions.cycle(forward = false)
        assertEquals("[three]", state.drawn)
        state.inlineCompletions.cycle(forward = true)
        assertEquals("[one]", state.drawn)
    }

    @Test
    fun `cycling with nothing on offer asks now`() {
        val state = stateAt("|")
        assertTrue(state.inlineCompletions.cycle(forward = true))
        assertEquals(true, state.inlineCompletions.ask?.now)
    }

    // ── When nothing shows ───────────────────────────────────────────────────

    @Test
    fun `several carets show nothing and ask nothing`() {
        val state = stateAt("one|\ntwo")
        state.offer("!")
        state.setSelections(listOf(TextRange(3), TextRange(7)))
        assertNull(state.inlineCompletions.shown)
        state.applyInput(3, 3, "x")
        assertNull(state.inlineCompletions.ask)
        assertFalse(state.inlineCompletions.canCycle)
    }

    @Test
    fun `a selection shows nothing and asks nothing`() {
        val state = stateAt("one|")
        state.offer("!")
        state.selection = TextRange(0, 3)
        assertNull(state.inlineCompletions.shown)
        state.inlineCompletions.askAgain()
        assertNull(state.inlineCompletions.ask)
    }

    @Test
    fun `a read-only editor shows nothing and asks nothing`() {
        val state = stateAt("one|")
        state.readOnly = true
        state.inlineCompletions.askAgain()
        assertNull(state.inlineCompletions.ask)
        assertFalse(state.inlineCompletions.canCycle)
    }

    @Test
    fun `a snippet being filled in shows nothing`() {
        val state = stateAt("one|")
        state.offer("!")
        state.execute(EditorCommand.InsertSnippet("(\$1, \$2)"))
        assertNotNull(state.snippet)
        assertNull(state.inlineCompletions.shown)
        state.inlineCompletions.askAgain()
        assertNull(state.inlineCompletions.ask, "Tab belongs to the snippet's stops")
    }

    @Test
    fun `a composition ends what is on offer, asks nothing, and asks when it is over`() {
        val state = stateAt("val x = |")
        state.offer("compute()")
        state.inlineCompletions.setComposing(true)
        assertNull(state.inlineCompletions.shown)
        state.inlineCompletions.askAgain()
        assertNull(state.inlineCompletions.ask, "nothing during composition")
        assertFalse(state.inlineCompletions.canCycle)
        state.inlineCompletions.setComposing(false)
        assertNotNull(state.inlineCompletions.ask)
    }

    @Test
    fun `an open list hides it, and it comes back when the list goes`() {
        val state = stateAt("val x = |")
        state.offer("compute()")
        state.inlineCompletions.hideForList(true)
        assertNull(state.inlineCompletions.shown)
        assertFalse(state.canExecute(EditorCommand.AcceptInlineCompletion), "Tab is the list's")
        assertFalse(state.inlineCompletions.canCycle)
        state.typeAtCaret("c")
        state.inlineCompletions.hideForList(false)
        assertEquals("val x = c[ompute()]", state.drawn)
    }

    @Test
    fun `an open list that outlives what fits leaves nothing to come back`() {
        val state = stateAt("val x = |")
        state.offer("compute()")
        state.inlineCompletions.hideForList(true)
        state.typeAtCaret("z")
        state.inlineCompletions.hideForList(false)
        assertNull(state.inlineCompletions.shown)
    }

    // ── Accepting, and the undo stack ────────────────────────────────────────

    @Test
    fun `accepting places the caret at the end, on the last line`() {
        val state = stateAt("val x = |\nnext")
        state.offer("compute(\n    1,\n)")
        state.accept()
        assertEquals("val x = compute(\n    1,\n)\nnext", state.document.text)
        assertEquals(TextRange("val x = compute(\n    1,\n)".length), state.selection)
    }

    @Test
    fun `accepting leaves the caret at the end of what it added, before what it repeated`() {
        val state = stateAt("foo(|)")
        state.offer("bar)")
        state.accept()
        assertEquals(TextRange("foo(bar".length), state.selection, "as Monaco: the end of the edit, not of the range")
    }

    @Test
    fun `accepting text in several pieces ends after the last of them`() {
        val state = stateAt("if (|)")
        val caret = state.selection.end
        state.offer(InlineCompletion("x) {", caret until caret + 1))
        state.accept()
        assertEquals("if (x) {", state.document.text)
        assertEquals(TextRange("if (x) {".length), state.selection)
    }

    @Test
    fun `accepting is one undo step of its own, whatever typing came before`() {
        val state = stateAt("|")
        state.typeAtCaret("v")
        state.typeAtCaret("a")
        state.offer("l x = 1")
        state.accept()
        state.typeAtCaret("0")
        assertEquals("val x = 10", state.document.text)
        state.undo()
        assertEquals("val x = 1", state.document.text, "typing after it is not part of it")
        state.undo()
        assertEquals("va", state.document.text, "it goes in one step, in several pieces or not")
        assertEquals(TextRange(2), state.selection)
        state.undo()
        assertEquals("", state.document.text, "typing before it is not part of it")
    }

    @Test
    fun `undoing in several pieces takes them all`() {
        val state = stateAt("if (|)")
        val caret = state.selection.end
        state.offer(InlineCompletion("x) {", caret until caret + 1))
        state.accept()
        state.undo()
        assertEquals("if ()", state.document.text)
        assertEquals(TextRange(caret), state.selection)
    }

    @Test
    fun `undo drops it, and does not ask, and brings none back`() {
        val state = stateAt("|")
        state.typeAtCaret("a")
        state.offer("b")
        state.accept()
        state.undo()
        assertNull(state.inlineCompletions.shown)
        assertNull(state.inlineCompletions.ask, "undoing is not asking for more")
        state.redo()
        assertNull(state.inlineCompletions.shown)
        assertNull(state.inlineCompletions.ask)
    }

    @Test
    fun `undo with it showing takes back typing, not it`() {
        val state = stateAt("|")
        state.typeAtCaret("a")
        state.offer("b")
        assertTrue(state.canUndo)
        state.undo()
        assertEquals("", state.document.text)
        assertNull(state.inlineCompletions.shown)
    }

    @Test
    fun `showing and dismissing it touch neither the document nor the undo stack`() {
        val state = stateAt("val x|")
        val version = state.document.version
        val alternative = state.alternativeVersionId
        state.offer("compute()")
        state.inlineCompletions.clear()
        assertEquals("val x", state.document.text)
        assertEquals(version, state.document.version)
        assertEquals(alternative, state.alternativeVersionId)
        assertFalse(state.canUndo)
    }

    @Test
    fun `copying a caret's line leaves it out`() {
        val state = stateAt("val x|")
        state.offer(" = 1")
        assertEquals("val x\n", state.copySelections().text)
    }

    @Test
    fun `accepting puts in the document's line endings`() {
        val state = stateAt("a|\r\nb")
        state.offer("1\n2")
        state.accept()
        assertEquals("a1\r\n2\r\nb", state.document.text)
        assertEquals(TextRange("a1\r\n2".length), state.selection)
    }

    @Test
    fun `accepting asks for what comes next, now`() {
        val state = stateAt("a|")
        state.offer("b")
        state.accept()
        assertEquals(true, state.inlineCompletions.ask?.now)
        assertEquals(state.selection.end, state.inlineCompletions.ask?.caret)
    }

    @Test
    fun `accepting is a command only while something shows`() {
        val state = stateAt("a|")
        assertFalse(EditorCommand.AcceptInlineCompletion in state.availableCommands)
        assertFalse(state.execute(EditorCommand.AcceptInlineCompletion))
        state.offer("b")
        assertTrue(EditorCommand.AcceptInlineCompletion in state.availableCommands)
    }

    @Test
    fun `a read-only editor accepts nothing`() {
        val state = stateAt("a|")
        state.offer("b")
        state.readOnly = true
        assertFalse(state.execute(EditorCommand.AcceptInlineCompletion))
        assertEquals("a", state.document.text)
    }

    @Test
    fun `a layout told lines goes by where the ghost is now`() {
        val state = stateAt("one\ntwo|")
        val told = ArrayList<List<Int>>()
        state.ghostListeners += { told += it }
        state.offer("!")
        assertEquals(listOf(listOf(1)), told, "shown on the second line")
        told.clear()
        state.applyTextEdits(listOf(TextEdit(0 until 0, "zero\n")))
        assertEquals(listOf(listOf(2)), told, "dropped from where the edit moved it to")
    }

    @Test
    fun `a state with no provider asks nothing`() {
        val state = stateAt("a|")
        state.inlineCompletions.provided = false
        state.typeAtCaret("b")
        assertNull(state.inlineCompletions.ask)
    }

    @Test
    fun `the same ask is not answered twice`() {
        val state = stateAt("a|")
        state.inlineCompletions.askAgain()
        val asked = assertNotNull(state.inlineCompletions.ask)
        val version = state.document.version
        state.inlineCompletions.answer(asked, version, listOf(InlineCompletion("b")))
        val shown = state.inlineCompletions.shown
        state.inlineCompletions.answer(asked, version, listOf(InlineCompletion("c")))
        assertSame(shown, state.inlineCompletions.shown)
    }

    private object NoAnswers : LanguageService {
        override val triggerCharacters: Set<Char> get() = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text
    }
}
