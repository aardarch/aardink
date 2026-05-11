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
import com.aardarch.aardink.languages.internal.xml.XmlTokenizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmlTokenizerTest {

    @Test
    fun `tags produce element name punctuation and attributes`() {
        val src = "<root attr=\"value\">text</root>"
        val tokens = XmlTokenizer.tokenizeFull(src)
        assertTrue(tokens.any { it.type == TokenType.TypeName && src.substring(it.start, it.end) == "root" })
        assertTrue(tokens.any { it.type == TokenType.Identifier && src.substring(it.start, it.end) == "attr" })
        assertTrue(tokens.any { it.type == TokenType.StringLiteral && src.substring(it.start, it.end) == "\"value\"" })
    }

    @Test
    fun `comments are tokenised as comments`() {
        val src = "<!-- skip me --><a/>"
        val tokens = XmlTokenizer.tokenizeFull(src)
        assertEquals(TokenType.Comment, tokens.first().type)
        assertEquals("<!-- skip me -->", src.substring(tokens.first().start, tokens.first().end))
    }

    @Test
    fun `processing instruction recognised`() {
        val src = "<?xml version=\"1.0\"?><root/>"
        val tokens = XmlTokenizer.tokenizeFull(src)
        val pi = tokens.first { it.type == TokenType.Annotation }
        assertEquals("<?xml version=\"1.0\"?>", src.substring(pi.start, pi.end))
    }
}
