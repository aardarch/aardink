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
package com.aardarch.aardink.languages

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [LanguageDefinition.extending]: a dialect over a built-in language keeps the base's service. */
class LanguageExtendingTest {

    /** A dialect's own service: one element and one rule of its own. */
    private object Dialect : LanguageService {
        override val triggerCharacters: Set<Char> = setOf('{')

        override suspend fun completions(document: CodeDocument, cursorOffset: Int) =
            listOf(CompletionItem("page", CompletionKind.Element, "page"))

        override suspend fun diagnostics(document: CodeDocument) =
            if ("<page" in document) emptyList() else listOf(Diagnostic(0..0, "a page is needed", DiagnosticSeverity.Warning))

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions) = ""

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions) = document.text
    }

    private val xml = BuiltInLanguages.Xml

    @Test
    fun `a dialect keeps the base's checks, closing tags and trigger characters`() = runTest {
        val dialect = LanguageDefinition.extending(xml, Dialect, id = "dialect")
        assertEquals("dialect", dialect.id)
        assertEquals(xml.tokenizer, dialect.tokenizer)
        val service = dialect.languageService!!
        assertTrue(service is CompositeLanguageService)
        val problems = service.diagnostics(CodeDocument("<a><b></a>")).map { it.message }
        assertTrue("a page is needed" in problems, "its own rule")
        assertTrue(problems.size > 1, "and the base's well-formedness checks: $problems")
        assertEquals("</a>", service.autoClose(CodeDocument("<a>"), 2, '>'), "the base closes a tag on '>'")
        assertTrue('{' in service.triggerCharacters && '<' in service.triggerCharacters)
        assertEquals("page", service.completions(CodeDocument("<"), 1).first().label)
    }

    @Test
    fun `without inheriting, the base's completions are left out`() = runTest {
        val dialect = LanguageDefinition.extending(xml, Dialect, inheritCompletions = false)
        assertEquals(listOf("page"), dialect.languageService!!.completions(CodeDocument("<"), 1).map { it.label })
    }
}
