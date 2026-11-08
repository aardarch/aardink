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

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.PlainTextTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The JVM only: on wasm a stack overflow is a trap, which no Kotlin code can catch. */
class ConfigurationRegexOverflowTest {

    /** So deep that a loop around it overflows the stack on a line well within the length limit. */
    private val nested = (1..400).fold("a|b") { regex, _ -> "(?:$regex)|c" }

    private val line = "a".repeat(200)

    @Test
    fun `this regex overflows the stack on a line of this length`() {
        assertFailsWith<StackOverflowError> { Regex("^(?:$nested)*$").containsMatchIn(line) }
    }

    @Test
    fun `a rule whose regex overflows the stack does not match`() {
        val json = """{ "onEnterRules": [{ "beforeText": "^(?:$nested)*$", "action": { "indent": "indent" } }] }"""
        val language = LanguageDefinition("bare", "Bare", listOf("bare"), PlainTextTokenizer)
        val service = language.withConfiguration(LanguageConfiguration.parse(json)).languageService!!
        assertEquals("", service.smartIndent(CodeDocument("$line\n"), 1, FormattingOptions()))
    }
}
