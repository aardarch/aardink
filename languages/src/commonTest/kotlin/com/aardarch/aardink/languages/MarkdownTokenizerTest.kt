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

import com.aardarch.aardink.languages.internal.markdown.MarkdownTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTokenizerTest {

    @Test
    fun `a heading is one token`() {
        assertEquals(listOf("# Title" to "annotation"), MarkdownTokenizer.scopes("# Title\n\nplain text"))
    }

    @Test
    fun `emphasis, strong and code spans`() {
        assertEquals(
            listOf("*em*" to "keyword", "**strong**" to "keyword", "`code`" to "string"),
            MarkdownTokenizer.scopes("Some *em* and **strong** and `code`."),
        )
    }

    @Test
    fun `list markers, quotes and a rule`() {
        val scopes = MarkdownTokenizer.scopes("text\n- item\n1. one\n> quote\n\n---")
        assertEquals(listOf("-", "1.", ">", "---"), scopes.map { it.first.trim() })
        assertEquals(List(4) { "delimiter" }, scopes.map { it.second })
    }

    @Test
    fun `links and images`() {
        assertEquals(
            listOf("[link](http://x)" to "function", "![img](y.png)" to "function"),
            MarkdownTokenizer.scopes("[link](http://x) ![img](y.png)"),
        )
    }

    @Test
    fun `a fenced block is one string, fences and all`() {
        val fence = "```kotlin\nval x = 1\n```"
        assertEquals(listOf(fence to "string"), MarkdownTokenizer.scopes("$fence\n"))
    }
}
