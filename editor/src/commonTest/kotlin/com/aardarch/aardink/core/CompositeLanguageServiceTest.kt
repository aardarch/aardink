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

import com.aardarch.aardink.ui.view.HintWindow
import com.aardarch.aardink.ui.view.InlineContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompositeLanguageServiceTest {

    /** A service answering with fixed values; null or empty for "no answer". */
    private class Fake(
        val items: List<CompletionItem> = emptyList(),
        val problems: List<Diagnostic> = emptyList(),
        val hover: HoverDoc? = null,
        val indent: String = "",
        val closing: String? = null,
        val surrounding: String? = null,
        val formatted: String? = null,
        override val triggerCharacters: Set<Char> = emptySet(),
        override val supportsRename: Boolean = false,
        val renameTo: String = "",
        override val completionsAreIncomplete: Boolean = true,
        val tokens: List<Token> = emptyList(),
        val hints: List<InlayHint> = emptyList(),
    ) : LanguageService {
        override suspend fun completions(document: CodeDocument, cursorOffset: Int) = items

        override suspend fun diagnostics(document: CodeDocument) = problems

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions) = indent

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char) = closing

        override fun surroundingClose(typed: Char) = surrounding

        override suspend fun hoverDoc(document: CodeDocument, offset: Int) = hover

        override suspend fun format(document: CodeDocument, options: FormattingOptions) = formatted ?: document.text

        override suspend fun rename(document: CodeDocument, offset: Int, newName: String) = listOf(TextEdit(0..0, renameTo))

        override suspend fun semanticTokens(document: CodeDocument) = tokens

        override suspend fun inlayHints(document: CodeDocument, range: IntRange) = hints
    }

    private fun item(label: String, kind: CompletionKind = CompletionKind.Element) = CompletionItem(label, kind, label)

    private val doc = CodeDocument("<a>")

    @Test
    fun `completions are the primary's then the fallback's, without repeats`() = runTest {
        val composite = CompositeLanguageService(
            Fake(items = listOf(item("page"), item("text"))),
            Fake(items = listOf(item("text"), item("text", CompletionKind.Attribute), item("div"))),
        )
        assertEquals(listOf("page", "text", "text", "div"), composite.completions(doc, 1).map { it.label })
        assertEquals(CompletionKind.Attribute, composite.completions(doc, 1)[2].kind)
    }

    @Test
    fun `a complete side's completions are filtered when the other side is incomplete`() = runTest {
        val typed = CodeDocument("al")
        val completePrimary = CompositeLanguageService(
            Fake(items = listOf(item("alpha"), item("beta")), completionsAreIncomplete = false),
            Fake(items = listOf(item("gamma"))),
        )
        assertTrue(completePrimary.completionsAreIncomplete)
        assertEquals(listOf("alpha", "gamma"), completePrimary.completions(typed, 2).map { it.label })
        val completeFallback = CompositeLanguageService(
            Fake(items = listOf(item("gamma"))),
            Fake(items = listOf(item("alpha"), item("beta")), completionsAreIncomplete = false),
        )
        assertEquals(listOf("gamma", "alpha"), completeFallback.completions(typed, 2).map { it.label })
        val bothComplete = CompositeLanguageService(
            Fake(items = listOf(item("alpha"), item("beta")), completionsAreIncomplete = false),
            Fake(items = listOf(item("gamma")), completionsAreIncomplete = false),
        )
        assertEquals(listOf("alpha", "beta", "gamma"), bothComplete.completions(typed, 2).map { it.label })
    }

    @Test
    fun `without inheriting, only the primary's completions and trigger characters`() = runTest {
        val composite = CompositeLanguageService(
            Fake(items = listOf(item("page")), triggerCharacters = setOf('{')),
            Fake(items = listOf(item("div")), triggerCharacters = setOf('<')),
            inheritCompletions = false,
        )
        assertEquals(listOf("page"), composite.completions(doc, 1).map { it.label })
        assertEquals(setOf('{'), composite.triggerCharacters)
        assertEquals(
            setOf('{', '<'),
            CompositeLanguageService(Fake(triggerCharacters = setOf('{')), Fake(triggerCharacters = setOf('<'))).triggerCharacters,
        )
    }

    @Test
    fun `diagnostics merge, hover and auto-close fall back`() = runTest {
        val mine = Diagnostic(0..0, "mine", DiagnosticSeverity.Error)
        val theirs = Diagnostic(1..1, "theirs", DiagnosticSeverity.Warning)
        val composite = CompositeLanguageService(
            Fake(problems = listOf(mine)),
            Fake(problems = listOf(theirs), hover = HoverDoc("t", "c"), closing = "</a>"),
        )
        assertEquals(listOf(mine, theirs), composite.diagnostics(doc))
        assertEquals("t", composite.hoverDoc(doc, 1)?.title)
        assertEquals("</a>", composite.autoClose(doc, 2, '>'))
    }

    @Test
    fun `surrounding a selection comes from the primary, or else the fallback`() {
        assertEquals("]", CompositeLanguageService(Fake(surrounding = "]"), Fake(surrounding = ")")).surroundingClose('['))
        assertEquals(")", CompositeLanguageService(Fake(), Fake(surrounding = ")")).surroundingClose('('))
        assertNull(CompositeLanguageService(Fake(), Fake()).surroundingClose('('))
    }

    @Test
    fun `indentation and formatting come from the primary unless it has none`() = runTest {
        val composite = CompositeLanguageService(Fake(indent = ""), Fake(indent = "\t", formatted = "<a/>"))
        assertEquals("\t", composite.smartIndent(doc, 1, FormattingOptions()))
        assertEquals("<a/>", composite.format(doc, FormattingOptions()))
        val own = CompositeLanguageService(Fake(indent = "  ", formatted = "<A>"), Fake(indent = "\t", formatted = "<a/>"))
        assertEquals("  ", own.smartIndent(doc, 1, FormattingOptions()))
        assertEquals("<A>", own.format(doc, FormattingOptions()))
    }

    /** Resolves [known] by adding [documentation], and leaves any other item as it is. */
    private class Resolving(val known: CompletionItem, val documentation: String) : LanguageService by Fake() {
        var asked = 0

        override suspend fun resolveCompletion(item: CompletionItem): CompletionItem {
            asked++
            return if (item == known) item.copy(documentation = documentation) else item
        }
    }

    @Test
    fun `an item is resolved as it is by default`() = runTest {
        val item = item("page")
        assertTrue(Fake().resolveCompletion(item) === item)
    }

    @Test
    fun `resolving a completion asks the primary, then the fallback if nothing changed`() = runTest {
        val own = item("page")
        val inherited = item("div")
        val primary = Resolving(own, "from the primary")
        val fallback = Resolving(inherited, "from the fallback")
        val composite = CompositeLanguageService(primary, fallback)

        assertEquals("from the primary", composite.resolveCompletion(own).documentation)
        assertEquals(0, fallback.asked)
        assertEquals("from the fallback", composite.resolveCompletion(inherited).documentation)
        assertEquals(2, primary.asked)
    }

    @Test
    fun `without inherited completions the fallback resolves none`() = runTest {
        val inherited = item("div")
        val fallback = Resolving(inherited, "from the fallback")
        val composite = CompositeLanguageService(Fake(), fallback, inheritCompletions = false)

        assertEquals(inherited, composite.resolveCompletion(inherited))
        assertEquals(0, fallback.asked)
    }

    @Test
    fun `rename goes to whichever service supports it`() = runTest {
        val composite = CompositeLanguageService(Fake(), Fake(supportsRename = true, renameTo = "x"))
        assertTrue(composite.supportsRename)
        assertEquals("x", composite.rename(doc, 1, "y").single().newText)
        assertNull(CompositeLanguageService(Fake(), Fake()).prepareRename(doc, 1))
    }

    @Test
    fun `semantic tokens are the primary's, or else the fallback's`() = runTest {
        val own = listOf(Token(0, 1, NamedTokenType("type.class")))
        val inherited = listOf(Token(1, 2, NamedTokenType("variable.parameter")))

        assertEquals(own, CompositeLanguageService(Fake(tokens = own), Fake(tokens = inherited)).semanticTokens(doc))
        assertEquals(inherited, CompositeLanguageService(Fake(), Fake(tokens = inherited)).semanticTokens(doc))
        assertEquals(emptyList(), CompositeLanguageService(Fake(), Fake()).semanticTokens(doc))
    }

    @Test
    fun `inlay hints are both's, less the fallback's at an offset the primary has one at`() = runTest {
        val own = listOf(InlayHint(1, "own"), InlayHint(1, "also own"))
        val inherited = listOf(InlayHint(0, "inherited"), InlayHint(1, "taken"), InlayHint(2, "inherited too"))

        assertEquals(
            own + listOf(InlayHint(0, "inherited"), InlayHint(2, "inherited too")),
            CompositeLanguageService(Fake(hints = own), Fake(hints = inherited)).inlayHints(doc, 0..2),
        )
        assertEquals(inherited, CompositeLanguageService(Fake(), Fake(hints = inherited)).inlayHints(doc, 0..2))
        assertEquals(own, CompositeLanguageService(Fake(hints = own), Fake()).inlayHints(doc, 0..2))
    }

    @Test
    fun `both are asked about the same range`() = runTest {
        val asked = mutableListOf<IntRange>()
        val recording = object : LanguageService by Fake() {
            override suspend fun inlayHints(document: CodeDocument, range: IntRange): List<InlayHint> {
                asked += range
                return emptyList()
            }
        }

        CompositeLanguageService(recording, recording).inlayHints(doc, 1..2)

        assertEquals(listOf(1..2, 1..2), asked)
    }

    /** A fallback that keeps the caller waiting until told what to say. */
    private class HeldHints : LanguageService by Fake() {
        val answer = CompletableDeferred<List<InlayHint>>()
        var asked = 0

        override suspend fun inlayHints(document: CodeDocument, range: IntRange): List<InlayHint> {
            asked++
            return answer.await()
        }
    }

    private val own = listOf(InlayHint(1, "own"))
    private val inherited = listOf(InlayHint(1, "taken"), InlayHint(2, "inherited"))
    private val all = own + InlayHint(2, "inherited")

    @Test
    fun `a primary's hints are offered while the fallback has yet to answer`() = runTest {
        val held = HeldHints()
        val offers = mutableListOf<List<InlayHint>>()

        val answer = async(EarlyInlayHints { offers += it }) { CompositeLanguageService(Fake(hints = own), held).inlayHints(doc, 0..2) }
        runCurrent()

        assertEquals(listOf(own), offers)
        assertEquals(1, held.asked, "the fallback still gets its turn")
        held.answer.complete(inherited)
        assertEquals(all, answer.await())
    }

    @Test
    fun `a primary with no hints offers nothing, which would take a fallback's off the screen`() = runTest {
        val held = HeldHints()
        val offers = mutableListOf<List<InlayHint>>()

        val answer = async(EarlyInlayHints { offers += it }) { CompositeLanguageService(Fake(), held).inlayHints(doc, 0..2) }
        runCurrent()
        held.answer.complete(inherited)

        assertEquals(inherited, answer.await())
        assertTrue(offers.isEmpty())
    }

    @Test
    fun `a composite behind another offers on top of what is there`() = runTest {
        val held = HeldHints()
        val offers = mutableListOf<List<InlayHint>>()
        val behind = CompositeLanguageService(Fake(hints = inherited), held)

        val answer = async(EarlyInlayHints { offers += it }) { CompositeLanguageService(Fake(hints = own), behind).inlayHints(doc, 0..2) }
        runCurrent()
        held.answer.complete(listOf(InlayHint(0, "last")))

        assertEquals(listOf(own, all), offers)
        assertEquals(all + InlayHint(0, "last"), answer.await())
    }

    @Test
    fun `the editor shows the primary's hints once the fallback has kept it waiting, then the whole answer`() = runTest {
        val held = HeldHints()
        val shown = mutableListOf<List<InlayHint>>()
        val service = CompositeLanguageService(Fake(hints = own), held)

        val answer = async { InlineContent.askForHints(service, doc, HintWindow(listOf(0..0)), EmptyCoroutineContext) { shown += it } }
        advanceTimeBy(999)
        runCurrent()
        assertTrue(shown.isEmpty(), "a fallback that answers soon keeps what it has on show from flickering")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(own), shown)

        held.answer.complete(inherited)

        assertEquals(all, answer.await())
    }

    @Test
    fun `a fallback that answers in time makes it one step`() = runTest {
        val held = HeldHints()
        val shown = mutableListOf<List<InlayHint>>()
        val service = CompositeLanguageService(Fake(hints = own), held)

        val answer = async { InlineContent.askForHints(service, doc, HintWindow(listOf(0..0)), EmptyCoroutineContext) { shown += it } }
        advanceTimeBy(500)
        runCurrent()
        held.answer.complete(inherited)
        assertEquals(all, answer.await())
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(shown.isEmpty())
    }

    @Test
    fun `each run is asked at once, and what is offered early is all of theirs`() = runTest {
        val document = CodeDocument("ab\ncd\nef")
        val held = HeldHints()
        val primary = object : LanguageService by Fake() {
            override suspend fun inlayHints(document: CodeDocument, range: IntRange) = listOf(InlayHint(range.first, "at ${range.first}"))
        }
        val shown = mutableListOf<List<InlayHint>>()

        val answer = async {
            InlineContent.askForHints(
                CompositeLanguageService(primary, held),
                document,
                HintWindow(listOf(0..0, 2..2)),
                EmptyCoroutineContext,
            ) { shown += it }
        }
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(2, held.asked, "a question stuck on one run holds no other back")
        assertEquals(listOf(listOf(InlayHint(0, "at 0"), InlayHint(6, "at 6"))), shown)
        held.answer.complete(listOf(InlayHint(3, "both ask, one is kept")))
        assertEquals(
            listOf(InlayHint(0, "at 0"), InlayHint(3, "both ask, one is kept"), InlayHint(6, "at 6")),
            answer.await(),
        )
    }
}
