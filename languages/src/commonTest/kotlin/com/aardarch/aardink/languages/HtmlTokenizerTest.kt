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

import com.aardarch.aardink.languages.internal.html.HtmlTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlTokenizerTest {

    @Test
    fun `doctype and comments`() {
        assertEquals(
            listOf("<!DOCTYPE html>" to "annotation", "<!-- comment -->" to "comment"),
            HtmlTokenizer.scopes("<!DOCTYPE html>\n<!-- comment -->"),
        )
    }

    @Test
    fun `tags, attributes in each quote and a bare one`() {
        assertEquals(
            listOf(
                "<" to "delimiter", "div" to "type", "class" to "identifier", "=" to "operator", "\"a\"" to "string",
                "id" to "identifier", "=" to "operator", "'b'" to "string", "hidden" to "identifier", ">" to "delimiter",
                "<" to "delimiter", "/" to "delimiter", "div" to "type", ">" to "delimiter",
            ),
            HtmlTokenizer.scopes("<div class=\"a\" id='b' hidden>Text</div>"),
        )
    }

    @Test
    fun `text is not coloured, an entity is`() {
        assertEquals(listOf("&amp;" to "number"), HtmlTokenizer.scopes("<p>Text &amp; more</p>").filter { it.first.startsWith("&") })
        assertEquals(emptyList(), HtmlTokenizer.scopes("<p>Text &amp; more</p>").filter { it.first == "Text" || it.first == "more" })
    }

    @Test
    fun `a self-closing tag`() {
        assertEquals(
            listOf("<" to "delimiter", "br" to "type", "/" to "delimiter", ">" to "delimiter"),
            HtmlTokenizer.scopes("<br/>"),
        )
    }
}
