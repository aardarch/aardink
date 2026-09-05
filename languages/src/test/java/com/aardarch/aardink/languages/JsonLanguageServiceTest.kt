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
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.languages.internal.json.JsonLanguageService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonLanguageServiceTest {

    private fun diagnose(text: String) = runBlocking {
        JsonLanguageService.diagnostics(CodeDocument(text))
    }

    @Test
    fun `valid JSON yields no diagnostics`() {
        assertTrue(diagnose("""{"a": 1, "b": [true, null, "x"]}""").isEmpty())
        assertTrue(diagnose("[]").isEmpty())
        assertTrue(diagnose("\"hi\"").isEmpty())
        assertTrue(diagnose("3.14e-2").isEmpty())
    }

    @Test
    fun `unterminated string flagged`() {
        val diags = diagnose("""{"a": "open}""")
        assertEquals(1, diags.size)
        assertEquals(DiagnosticSeverity.Error, diags[0].severity)
        assertTrue(diags[0].message.contains("string", ignoreCase = true))
    }

    @Test
    fun `duplicate key flagged`() {
        val diags = diagnose("""{"a": 1, "a": 2}""")
        assertEquals(1, diags.size)
        assertTrue(diags[0].message.contains("Duplicate key", ignoreCase = true))
    }

    @Test
    fun `trailing comma flagged`() {
        val diags = diagnose("""{"a": 1, "b": 2,}""")
        assertEquals(1, diags.size)
        assertTrue(diags[0].message.contains("Trailing comma", ignoreCase = true))
    }

    @Test
    fun `missing colon flagged`() {
        val diags = diagnose("""{"a" 1}""")
        assertEquals(1, diags.size)
        assertTrue(diags[0].message.contains(":", ignoreCase = true))
    }

    @Test
    fun `bare literal mistyped flagged`() {
        val diags = diagnose("""{"a": tru}""")
        assertEquals(1, diags.size)
    }

    @Test
    fun `trailing content after value flagged`() {
        val diags = diagnose("""{"a": 1} extra""")
        assertEquals(1, diags.size)
    }

    @Test
    fun `empty document yields no diagnostics`() {
        assertTrue(diagnose("").isEmpty())
        assertTrue(diagnose("   \n  ").isEmpty())
    }

    @Test
    fun `auto close quotes and braces`() {
        val doc = CodeDocument("")
        assertEquals("}", JsonLanguageService.autoClose(doc, 0, '{'))
        assertEquals("]", JsonLanguageService.autoClose(doc, 0, '['))
        assertEquals("\"", JsonLanguageService.autoClose(doc, 0, '"'))
    }

    @Test
    fun `completions after colon`() = runBlocking {
        val doc = CodeDocument("{\"key\":")
        val items = JsonLanguageService.completions(doc, 7)
        assertTrue(items.any { it.label == "true" })
        assertTrue(items.any { it.label == "false" })
    }

    @Test
    fun `format indents JSON objects`() = runBlocking {
        val doc = CodeDocument("{\"a\":1,\"b\":[true]}")
        val formatted = JsonLanguageService.format(doc)
        assertEquals("{\n    \"a\": 1,\n    \"b\": [\n        true\n    ]\n}", formatted)
    }

    /**
     * A single backslash, for spelling JSON escapes as the document actually contains them —
     * `"${'$'}{bs}u0061"` is the six source characters, not the letter they denote.
     */
    private val bs = '\\'

    @Test
    fun `keys spelled differently but naming one member are duplicates`() {
        // The unicode escape spells "a", so the two members are one and the second is a duplicate.
        val diags = diagnose("""{"a": 1, "${bs}u0061": 2}""")
        assertEquals(1, diags.size)
        assertEquals(DiagnosticSeverity.Error, diags[0].severity)
        assertTrue(diags[0].message.contains("Duplicate key 'a'"), diags[0].message)
    }

    @Test
    fun `escapes decode rather than comparing as source text`() {
        // Two spellings of a newline name one key; a newline and the letter n name two.
        assertEquals(1, diagnose("""{"${bs}n": 1, "${bs}u000A": 2}""").size)
        assertTrue(diagnose("""{"${bs}n": 1, "n": 2}""").isEmpty())
        assertTrue(diagnose("""{"a": 1, "b": 2}""").isEmpty())
    }

    private fun complete(text: String) = runBlocking {
        JsonLanguageService.completions(CodeDocument(text), text.length)
    }

    private fun List<CompletionItem>.labels() = map { it.label }

    @Test
    fun `a comma inside an array offers values, not properties`() {
        // Accepting `"id": ""` after `[1,` would leave invalid JSON inside the array.
        val labels = complete("[1,").labels()
        assertTrue("true" in labels, labels.toString())
        assertTrue("{}" in labels, labels.toString())
        assertTrue("\"id\"" !in labels, labels.toString())
    }

    @Test
    fun `an opening bracket offers values`() {
        assertTrue("null" in complete("[").labels())
        assertTrue("\"id\"" in complete("{").labels())
    }

    @Test
    fun `a comma continues the innermost container`() {
        assertTrue("true" in complete("""{"a": [1,""").labels(), "inside an array nested in an object")
        assertTrue("\"id\"" in complete("""[{"a": 1,""").labels(), "inside an object nested in an array")
        assertTrue("true" in complete("""[{"a": 1},""").labels(), "back in the array once the object closes")
        assertTrue("\"id\"" in complete("""{"a": 1,""").labels(), "a plain object member list")
    }

    @Test
    fun `brackets and commas inside strings are not structure`() {
        val labels = complete("""{"a": "[,",""").labels()
        assertTrue("\"id\"" in labels, labels.toString())
        assertTrue("true" !in labels, labels.toString())
    }

    @Test
    fun `array values leave a space only after a comma`() {
        assertEquals(" true", complete("[1,").first { it.label == "true" }.insertText)
        assertEquals("true", complete("[").first { it.label == "true" }.insertText)
    }
}
