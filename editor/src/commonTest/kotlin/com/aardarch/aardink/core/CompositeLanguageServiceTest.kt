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

import kotlinx.coroutines.test.runTest
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
        val formatted: String? = null,
        override val triggerCharacters: Set<Char> = emptySet(),
        override val supportsRename: Boolean = false,
        val renameTo: String = "",
        override val completionsAreIncomplete: Boolean = true,
    ) : LanguageService {
        override suspend fun completions(document: CodeDocument, cursorOffset: Int) = items

        override suspend fun diagnostics(document: CodeDocument) = problems

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions) = indent

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char) = closing

        override suspend fun hoverDoc(document: CodeDocument, offset: Int) = hover

        override suspend fun format(document: CodeDocument, options: FormattingOptions) = formatted ?: document.text

        override suspend fun rename(document: CodeDocument, offset: Int, newName: String) = listOf(TextEdit(0..0, renameTo))
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
    fun `indentation and formatting come from the primary unless it has none`() = runTest {
        val composite = CompositeLanguageService(Fake(indent = ""), Fake(indent = "\t", formatted = "<a/>"))
        assertEquals("\t", composite.smartIndent(doc, 1, FormattingOptions()))
        assertEquals("<a/>", composite.format(doc, FormattingOptions()))
        val own = CompositeLanguageService(Fake(indent = "  ", formatted = "<A>"), Fake(indent = "\t", formatted = "<a/>"))
        assertEquals("  ", own.smartIndent(doc, 1, FormattingOptions()))
        assertEquals("<A>", own.format(doc, FormattingOptions()))
    }

    @Test
    fun `rename goes to whichever service supports it`() = runTest {
        val composite = CompositeLanguageService(Fake(), Fake(supportsRename = true, renameTo = "x"))
        assertTrue(composite.supportsRename)
        assertEquals("x", composite.rename(doc, 1, "y").single().newText)
        assertNull(CompositeLanguageService(Fake(), Fake()).prepareRename(doc, 1))
    }
}
