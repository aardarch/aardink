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

import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.toml.TomlTokenizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TomlTokenizerTest {

    @Test
    fun `tokenizes table headers`() {
        val tokens = TomlTokenizer.tokenizeFull("[versions]\n[[libraries]]")
        assertTrue(tokens.count { it.type == TokenType.TypeName } == 2)
    }

    @Test
    fun `tokenizes keys before equals`() {
        val src = "agp = \"8.9.0\""
        val tokens = TomlTokenizer.tokenizeFull(src)
        val keyToken = tokens.first { it.type == TokenType.Annotation }
        assertEquals("agp", src.substring(keyToken.start, keyToken.end))
    }

    @Test
    fun `tokenizes string literals`() {
        val src = "key = \"value\""
        val tokens = TomlTokenizer.tokenizeFull(src)
        val strToken = tokens.first { it.type == TokenType.StringLiteral }
        assertEquals("\"value\"", src.substring(strToken.start, strToken.end))
    }

    @Test
    fun `tokenizes booleans and numbers`() {
        val tokens = TomlTokenizer.tokenizeFull("a = true\nb = 42\nc = 2026-03-01")
        assertTrue(tokens.any { it.type == TokenType.Keyword })
        assertTrue(tokens.count { it.type == TokenType.Number } >= 2)
    }

    @Test
    fun `tokenizes comments`() {
        val tokens = TomlTokenizer.tokenizeFull("# This is a TOML comment")
        assertTrue(tokens.any { it.type == TokenType.Comment })
    }

    @Test
    fun `an array value is not a table header`() {
        // The header rule matched anywhere on a line, so a bracketed array value became one
        // TypeName token and its strings were never highlighted.
        val src = "compose = [\"androidx-compose-ui\", \"androidx-compose-material3\"]"
        val tokens = TomlTokenizer.tokenizeFull(src)
        assertTrue(tokens.none { it.type == TokenType.TypeName }, "no header on a key/value line")
        assertEquals(2, tokens.count { it.type == TokenType.StringLiteral })
    }
}
