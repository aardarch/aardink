package com.aardarch.editor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenCacheTest {

    private fun doc(text: String) = CodeDocument(text)

    @Test
    fun `empty cache has no tokens`() {
        val cache = TokenCache()
        assertTrue(cache.tokens.isEmpty())
    }

    @Test
    fun `reset stores tokens bucketed by line`() {
        val cache = TokenCache()
        val text = "foo\nbar"
        val document = doc(text)
        val tokens = listOf(
            Token(0, 3, TokenType.Identifier), // "foo" on line 0
            Token(4, 7, TokenType.Identifier), // "bar" on line 1
        )
        cache.reset(document, tokens)

        assertEquals(2, cache.tokens.size)
        assertEquals(1, cache.tokensForLine(0).size)
        assertEquals(1, cache.tokensForLine(1).size)
    }

    @Test
    fun `tokensForLine returns empty for unknown line`() {
        val cache = TokenCache()
        assertTrue(cache.tokensForLine(99).isEmpty())
    }

    @Test
    fun `merge updates only dirty lines`() {
        val cache = TokenCache()
        val text = "foo\nbar\nbaz"
        val document = doc(text)
        val initial = listOf(
            Token(0, 3, TokenType.Identifier),
            Token(4, 7, TokenType.Identifier),
            Token(8, 11, TokenType.Identifier),
        )
        cache.reset(document, initial)

        // Simulate an edit on line 1 only
        val updatedLine1 = listOf(Token(4, 7, TokenType.Keyword))
        cache.merge(document, 1..1, updatedLine1)

        // Line 0 unchanged
        assertEquals(TokenType.Identifier, cache.tokensForLine(0).first().type)
        // Line 1 updated
        assertEquals(TokenType.Keyword, cache.tokensForLine(1).first().type)
        // Line 2 unchanged
        assertEquals(TokenType.Identifier, cache.tokensForLine(2).first().type)
    }

    @Test
    fun `pruneLines removes entries beyond lastValidLine`() {
        val cache = TokenCache()
        val text = "a\nb\nc"
        val document = doc(text)
        cache.reset(
            document,
            listOf(
                Token(0, 1, TokenType.Default),
                Token(2, 3, TokenType.Default),
                Token(4, 5, TokenType.Default),
            ),
        )
        assertEquals(3, cache.tokens.size)

        cache.pruneLines(1) // keep only lines 0 and 1
        assertEquals(2, cache.tokens.size)
    }

    @Test
    fun `tokens are sorted by start offset after reset`() {
        val cache = TokenCache()
        val text = "abc def"
        val document = doc(text)
        val tokens = listOf(
            Token(4, 7, TokenType.Keyword),
            Token(0, 3, TokenType.Identifier),
        )
        cache.reset(document, tokens)
        val flat = cache.tokens
        assertEquals(0, flat[0].start)
        assertEquals(4, flat[1].start)
    }
}
