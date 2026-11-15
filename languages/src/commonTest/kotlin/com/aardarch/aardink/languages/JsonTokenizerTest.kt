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

import com.aardarch.aardink.languages.internal.json.JsonTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonTokenizerTest {

    @Test
    fun `keys are told from string values`() {
        assertEquals(
            listOf("{" to "delimiter", "\"key\"" to "annotation", ":" to "delimiter", "\"value\"" to "string", "}" to "delimiter"),
            JsonTokenizer.scopes("""{ "key": "value" }"""),
        )
    }

    @Test
    fun `numbers and the three literals`() {
        assertEquals(
            listOf(
                "[" to "delimiter", "-1.5e2" to "number", "," to "delimiter", "0" to "number", "," to "delimiter",
                "true" to "keyword", "," to "delimiter", "false" to "keyword", "," to "delimiter", "null" to "keyword", "]" to "delimiter",
            ),
            JsonTokenizer.scopes("[-1.5e2, 0, true, false, null]"),
        )
    }

    @Test
    fun `nesting keeps keys keys`() {
        assertEquals(
            listOf(
                "{" to "delimiter", "\"a\"" to "annotation", ":" to "delimiter", "[" to "delimiter", "{" to "delimiter",
                "\"k\"" to "annotation", ":" to "delimiter", "1" to "number", "}" to "delimiter", "]" to "delimiter", "}" to "delimiter",
            ),
            JsonTokenizer.scopes("""{"a": [{"k": 1}]}"""),
        )
    }

    @Test
    fun `text after the value is not coloured as JSON`() {
        assertEquals("}" to "delimiter", JsonTokenizer.scopes("""{"a": 1} trailing""").last())
    }
}
