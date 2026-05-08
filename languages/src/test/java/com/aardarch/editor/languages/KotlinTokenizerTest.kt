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
package com.aardarch.editor.languages

import com.aardarch.editor.core.TokenType
import com.aardarch.editor.languages.internal.kotlin.KotlinTokenizer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinTokenizerTest {

    @Test
    fun `recognises keywords and identifiers`() {
        val tokens = KotlinTokenizer.tokenizeFull("fun greet(name: String): String = name")
        assertTrue(tokens.any { it.type == TokenType.Keyword })
        assertTrue(tokens.any { it.type == TokenType.TypeName })
        assertTrue(tokens.any { it.type == TokenType.Identifier })
    }

    @Test
    fun `string literals span properly`() {
        val src = "val s = \"hello world\""
        val tokens = KotlinTokenizer.tokenizeFull(src)
        val str = tokens.first { it.type == TokenType.StringLiteral }
        assertTrue(src.substring(str.start, str.end) == "\"hello world\"")
    }

    @Test
    fun `block comment spans newlines`() {
        val src = "/* one\n  two */\nval x = 1"
        val tokens = KotlinTokenizer.tokenizeFull(src)
        val comment = tokens.first { it.type == TokenType.Comment }
        assertTrue(src.substring(comment.start, comment.end).startsWith("/*"))
        assertTrue(src.substring(comment.start, comment.end).endsWith("*/"))
    }

    @Test
    fun `numbers tokenized`() {
        val tokens = KotlinTokenizer.tokenizeFull("val x = 42 + 0xFF + 1.5e3")
        assertTrue(tokens.count { it.type == TokenType.Number } >= 3)
    }

    @Test
    fun `annotations recognised`() {
        val tokens = KotlinTokenizer.tokenizeFull("@Composable fun Foo() {}")
        assertTrue(tokens.any { it.type == TokenType.Annotation })
    }
}
