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
package com.aardarch.aardink.ui

import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.core.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Which tokenizer the code in documentation gets. */
class CodeTokenizerTest {

    private class Named : IncrementalTokenizer {
        override fun tokenizeFull(text: String): List<Token> = emptyList()

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = emptyList()

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    private val tagged = Named()
    private val document = Named()

    @Test
    fun `a tag the host knows takes the host's tokenizer, over the document's`() {
        assertSame(tagged, codeTokenizer("kotlin", { tagged }, document))
    }

    @Test
    fun `the host is asked for the tag as written`() {
        val asked = ArrayList<String>()
        codeTokenizer("Kotlin") {
            asked += it
            null
        }
        assertEquals(listOf("Kotlin"), asked)
    }

    @Test
    fun `a tag the host does not know is plain, not the document's language`() {
        assertSame(PlainTextTokenizer, codeTokenizer("cobol", { null }, document))
    }

    @Test
    fun `no tag takes the document's tokenizer, without asking the host`() {
        assertSame(document, codeTokenizer(null, { error("asked for a language with no tag") }, document))
    }

    @Test
    fun `no tag and no document is plain`() {
        assertSame(PlainTextTokenizer, codeTokenizer(null, { tagged }, null))
    }

    private fun codeTokenizer(language: String?, byLanguage: (String) -> IncrementalTokenizer?) =
        codeTokenizer(language, byLanguage, documentTokenizer = null)
}
