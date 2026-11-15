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

import com.aardarch.aardink.languages.internal.css.CssTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

class CssTokenizerTest {

    @Test
    fun `selectors, with classes and ids as types, elements, combinators and pseudo-classes`() {
        assertEquals(
            listOf(
                ".card" to "type", ">" to "operator", "h1" to "identifier", ":hover" to "function", "," to "delimiter",
                "#id" to "type", "::before" to "function", "{" to "delimiter", "}" to "delimiter",
            ),
            CssTokenizer.scopes(".card > h1:hover, #id::before { }"),
        )
    }

    @Test
    fun `declarations, with properties, numbers with units, colours and important`() {
        assertEquals(
            listOf(
                "a" to "identifier", "{" to "delimiter",
                "color" to "keyword", "#ff0000" to "number", ";" to "delimiter",
                "margin" to "keyword", "0" to "number", "1.5em" to "number", "10px" to "number", ";" to "delimiter",
                "width" to "keyword", "!important" to "annotation", ";" to "delimiter",
                "}" to "delimiter",
            ),
            CssTokenizer.scopes("a { color: #ff0000; margin: 0 1.5em 10px; width: !important; }").filterNot { it.first == ":" },
        )
    }

    @Test
    fun `at-rules, custom properties and comments`() {
        assertEquals(
            listOf(
                "@media" to "annotation", "(" to "delimiter", "min-width" to "keyword", "600px" to "number", ")" to "delimiter",
                "{" to "delimiter", "}" to "delimiter",
                "/* c */" to "comment", ":root" to "function", "{" to "delimiter", "--gap" to "type", "4px" to "number",
                ";" to "delimiter", "}" to "delimiter",
            ),
            CssTokenizer.scopes("@media (min-width: 600px) { }\n/* c */ :root { --gap: 4px; }").filterNot { it.first == ":" },
        )
    }

    @Test
    fun `a function's name and its bracket`() {
        assertEquals("url(" to "function", CssTokenizer.scopes("a { background: url('x') }").first { it.first.startsWith("url") })
    }
}
