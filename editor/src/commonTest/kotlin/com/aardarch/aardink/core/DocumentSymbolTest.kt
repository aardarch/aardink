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

import com.aardarch.aardink.ui.symbolRows
import com.aardarch.aardink.ui.symbolStickyRanges
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentSymbolTest {

    // <a>\n  <b>x</b>\n  <c/>\n</a>
    //  a: 0..25, b: 6..13 (name 7..7), c: 17..20
    private val text = "<a>\n  <b>x</b>\n  <c/>\n</a>"
    private val b = DocumentSymbol("b", SymbolKind.Object, 6..13, 7..7)
    private val c = DocumentSymbol("c", SymbolKind.Object, 17..20, 18..18)
    private val a = DocumentSymbol("a", SymbolKind.Object, 0..25, 1..1, children = listOf(b, c))

    @Test
    fun `the path at an offset runs from the outermost symbol in`() {
        assertEquals(listOf("a", "b"), symbolPath(listOf(a), 9).map { it.name })
        assertEquals(listOf("a"), symbolPath(listOf(a), 15).map { it.name })
        // Just after a symbol's last character still counts as in it.
        assertEquals(listOf("a", "b"), symbolPath(listOf(a), 14).map { it.name })
        assertTrue(symbolPath(listOf(a), 27).isEmpty())
    }

    @Test
    fun `the state's symbols move with an edit, children and names too`() {
        val state = CodeEditorState(text, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        state.publishSymbols(listOf(a))
        state.document.insert(0, "<!-- x -->\n")
        val moved = state.symbols.single()
        val shift = 11
        assertEquals(shift..(25 + shift), moved.range)
        assertEquals((6 + shift)..(13 + shift), moved.children[0].range)
        assertEquals((7 + shift)..(7 + shift), moved.children[0].selectionRange)
        assertEquals(listOf("a", "c"), state.symbolPathAt(18 + shift).map { it.name })
    }

    @Test
    fun `replacing the text drops the symbols`() {
        val state = CodeEditorState(text, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        state.publishSymbols(listOf(a))
        state.loadText("other")
        assertTrue(state.symbols.isEmpty())
    }

    @Test
    fun `a composite service takes the primary's symbols, or else the fallback's`() = runTest {
        val none = object : BaseTestService() {}
        val some = object : BaseTestService() {
            override suspend fun documentSymbols(document: CodeDocument) = listOf(a)
        }
        assertEquals(listOf(a), CompositeLanguageService(none, some).documentSymbols(CodeDocument(text)))
        assertEquals(listOf(a), CompositeLanguageService(some, none).documentSymbols(CodeDocument(text)))
    }

    @Test
    fun `the picker lists every symbol as a tree, or the matches flat`() {
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 1), symbolRows(listOf(a), "").map { it.symbol.name to it.depth })
        assertEquals(listOf("c" to 0), symbolRows(listOf(a), "c").map { it.symbol.name to it.depth })
    }

    @Test
    fun `sticky scroll uses the symbols that span lines`() {
        val ranges = symbolStickyRanges(listOf(a), CodeDocument(text))
        assertEquals(listOf(0 to 3), ranges.map { it.startLine to it.endLine })
    }

    private abstract class BaseTestService : LanguageService {
        override suspend fun completions(document: CodeDocument, cursorOffset: Int) = emptyList<CompletionItem>()

        override suspend fun diagnostics(document: CodeDocument) = emptyList<Diagnostic>()

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions) = ""

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions) = document.text
    }
}
