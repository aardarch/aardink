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

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenStoreTest {

    /** One Identifier token per run of letters: a tiny tokenizer to feed the store. */
    private fun words(text: String): List<Token> =
        Regex("[A-Za-z]+").findAll(text).map { Token(it.range.first, it.range.last + 1, TokenType.Identifier) }.toList()

    private fun TokenStore.lineWords(document: CodeDocument, line: Int): List<String> =
        tokensForLine(line).map { document.text.substring(it.start, it.end) }

    @Test
    fun `empty store has no tokens`() {
        val store = TokenStore(CodeDocument("abc"))
        assertTrue(store.allTokens().isEmpty())
        assertTrue(store.tokensForLine(99).isEmpty())
    }

    @Test
    fun `replaceAll stores tokens bucketed by line`() {
        val document = CodeDocument("foo\nbar")
        val store = TokenStore(document)
        store.replaceAll(listOf(Token(0, 3, TokenType.Identifier), Token(4, 7, TokenType.Identifier)))

        assertEquals(2, store.allTokens().size)
        assertEquals(listOf(Token(0, 3, TokenType.Identifier)), store.tokensForLine(0))
        assertEquals(listOf(Token(4, 7, TokenType.Identifier)), store.tokensForLine(1))
        assertNull(document.dirtyLines)
    }

    @Test
    fun `merge updates only the lines it covers`() {
        val document = CodeDocument("foo\nbar\nbaz")
        val store = TokenStore(document)
        store.replaceAll(words(document.text))
        store.merge(1..1, listOf(Token(4, 7, TokenType.Keyword)))

        assertEquals(TokenType.Identifier, store.tokensForLine(0).single().type)
        assertEquals(TokenType.Keyword, store.tokensForLine(1).single().type)
        assertEquals(TokenType.Identifier, store.tokensForLine(2).single().type)
    }

    @Test
    fun `merge of a full list replaces every line it reaches`() {
        val document = CodeDocument("x\ny\nz")
        val store = TokenStore(document)
        store.replaceAll(words(document.text))
        store.merge(1..1, words(document.text).map { it.copy(type = TokenType.Keyword) })
        assertEquals(3, store.allTokens().size)
        assertTrue(store.allTokens().all { it.type == TokenType.Keyword })
    }

    @Test
    fun `runs are sorted by start even when the tokenizer's are not`() {
        val document = CodeDocument("abc def")
        val store = TokenStore(document)
        store.replaceAll(listOf(Token(4, 7, TokenType.Keyword), Token(0, 3, TokenType.Identifier)))
        assertEquals(listOf(0, 4), store.allTokens().map { it.start })
    }

    @Test
    fun `a token spanning lines is split into one run per line`() {
        val document = CodeDocument("a /* one\ntwo\nthree */ b")
        val store = TokenStore(document)
        store.replaceAll(listOf(Token(2, 21, TokenType.Comment)))
        assertEquals(listOf("/* one"), store.lineWords(document, 0))
        assertEquals(listOf("two"), store.lineWords(document, 1))
        assertEquals(listOf("three */"), store.lineWords(document, 2))
    }

    @Test
    fun `lines below a new line keep their own colours`() {
        // The TokenCache this replaces keyed tokens by line number and never shifted them, so a
        // line inserted above left every line below showing the colours of the line before it.
        val document = CodeDocument("val a\nfun b\n\nx")
        val store = TokenStore(document)
        store.replaceAll(
            listOf(
                Token(0, 3, TokenType.Keyword),
                Token(6, 9, TokenType.Keyword),
                Token(10, 11, TokenType.FunctionCall),
                Token(13, 14, TokenType.Identifier),
            ),
        )
        document.insert(6, "\n") // a new empty line 1, above "fun b"

        assertEquals(document.lineCount, store.lineCount)
        assertTrue(store.tokensForLine(1).isEmpty())
        assertEquals(listOf("fun", "b"), store.lineWords(document, 2))
        assertTrue(store.tokensForLine(3).isEmpty(), "the blank line stays blank")
        assertEquals(listOf("x"), store.lineWords(document, 4))
    }

    @Test
    fun `typing inside a token keeps it one colour and shifts the rest of the line`() {
        val document = CodeDocument("value = other")
        val store = TokenStore(document)
        store.replaceAll(words(document.text))
        document.insert(2, "LU") // vaLUlue
        assertEquals(listOf("vaLUlue", "other"), store.lineWords(document, 0))
    }

    @Test
    fun `splitting a line moves the tail's tokens to the new line`() {
        val document = CodeDocument("alpha beta gamma")
        val store = TokenStore(document)
        store.replaceAll(words(document.text))
        document.insert(8, "\n  ") // al... be|ta
        assertEquals(listOf("alpha", "be"), store.lineWords(document, 0))
        assertEquals(listOf("ta", "gamma"), store.lineWords(document, 1))
    }

    @Test
    fun `deleting across lines joins the surviving runs`() {
        val document = CodeDocument("one two\nthree\nfour five")
        val store = TokenStore(document)
        store.replaceAll(words(document.text))
        document.delete(4, 15) // "two\nthree\nfour " -> "one five"
        assertEquals("one five", document.text)
        assertEquals(listOf("one", "five"), store.lineWords(document, 0))
    }

    @Test
    fun `deleting inside a token shrinks it`() {
        val document = CodeDocument("abcdef gh")
        val store = TokenStore(document)
        store.replaceAll(words(document.text))
        document.delete(1, 2) // adef gh
        assertEquals(listOf("adef", "gh"), store.lineWords(document, 0))
    }

    @Test
    fun `random edits keep one entry per line and every run inside its line`() {
        val random = Random(7)
        val document = CodeDocument("fun main() {\n    println(\"hi\")\n}\n")
        val store = TokenStore(document)
        store.replaceAll(words(document.text))
        val fragments = listOf("x", "\n", "ab\ncd", " ", "word", "\n\n")
        repeat(400) { step ->
            if (random.nextBoolean() || document.length == 0) {
                document.insert(random.nextInt(document.length + 1), fragments[random.nextInt(fragments.size)])
            } else {
                val offset = random.nextInt(document.length)
                document.delete(offset, random.nextInt(1, minOf(8, document.length - offset) + 1))
            }
            assertEquals(document.lineCount, store.lineCount, "step $step")
            for (line in 0 until document.lineCount) {
                val lineLength = document.lineEnd(line) - document.lineStart(line)
                val runs = store.lineTokens(line)
                for (i in 0 until runs.size) {
                    assertTrue(runs.start(i) in 0 until runs.end(i) && runs.end(i) <= lineLength, "step $step line $line run $i")
                }
            }
            if (step % 50 == 0) {
                store.replaceAll(words(document.text))
                assertEquals(words(document.text), store.allTokens())
            }
        }
    }
}
