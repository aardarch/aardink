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
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.languages.internal.toml.TomlLanguageService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TomlLanguageServiceTest {

    private fun diagnose(text: String) = runBlocking {
        TomlLanguageService.diagnostics(CodeDocument(text))
    }

    private fun complete(text: String, cursorOffset: Int) = runBlocking {
        TomlLanguageService.completions(CodeDocument(text), cursorOffset)
    }

    @Test
    fun `valid TOML produces no diagnostics`() {
        val src = """
            [versions]
            kotlin = "2.1.10"
            agp = "8.9.0"

            [libraries]
            core = { module = "androidx.core:core-ktx" }
        """.trimIndent()
        assertTrue(diagnose(src).isEmpty())
    }

    @Test
    fun `unclosed table header produces error diagnostic`() {
        val diags = diagnose("[versions")
        assertEquals(1, diags.size)
        assertEquals(DiagnosticSeverity.Error, diags[0].severity)
        assertTrue(diags[0].message.contains("Unclosed table header", ignoreCase = true))
    }

    @Test
    fun `duplicate key in same section produces warning`() {
        val src = """
            [versions]
            kotlin = "2.1.10"
            kotlin = "2.1.0"
        """.trimIndent()
        val diags = diagnose(src)
        assertEquals(1, diags.size)
        assertEquals(DiagnosticSeverity.Warning, diags[0].severity)
        assertTrue(diags[0].message.contains("Duplicate key", ignoreCase = true))
    }

    @Test
    fun `missing equals sign flagged`() {
        val src = """
            [versions]
            kotlin "2.1.10"
        """.trimIndent()
        val diags = diagnose(src)
        assertEquals(1, diags.size)
        assertEquals(DiagnosticSeverity.Error, diags[0].severity)
        assertTrue(diags[0].message.contains("Expected '='", ignoreCase = true))
    }

    @Test
    fun `completions for table headers`() {
        val items = complete("[", 1)
        assertTrue(items.any { it.label == "versions" })
        assertTrue(items.any { it.label == "libraries" })
        assertTrue(items.any { it.label == "plugins" })
    }

    @Test
    fun `completions after equals`() {
        val doc = CodeDocument("key =")
        val items = runBlocking { TomlLanguageService.completions(doc, 5) }
        assertTrue(items.any { it.label == "true" })
        assertTrue(items.any { it.label == "false" })
    }

    @Test
    fun `auto close brackets and quotes`() {
        val doc = CodeDocument("")
        assertEquals("]", TomlLanguageService.autoClose(doc, 0, '['))
        assertEquals("\"", TomlLanguageService.autoClose(doc, 0, '"'))
        assertEquals("'", TomlLanguageService.autoClose(doc, 0, '\''))
    }

    @Test
    fun `format normalizes spacing`() = runBlocking {
        val doc = CodeDocument("a=1\n  b  =  2 ")
        val formatted = TomlLanguageService.format(doc)
        assertEquals("a = 1\nb = 2", formatted)
    }

    // ── Multiline values ─────────────────────────────────────────────────────

    @Test
    fun `multiline array produces no diagnostics`() {
        val src = """
            [libraries]
            targets = [
              "android",
              "jvm",
            ]
            other = 1
        """.trimIndent()
        assertTrue(diagnose(src).isEmpty(), "got ${diagnose(src).map { it.message }}")
    }

    @Test
    fun `multiline string content is not parsed as declarations`() {
        val src = """
            [tool]
            description = ${"\"\"\""}
            not a key = value
            [not a header]
            ${"\"\"\""}
            after = 1
        """.trimIndent()
        assertTrue(diagnose(src).isEmpty(), "got ${diagnose(src).map { it.message }}")
    }

    @Test
    fun `a key repeated after a multiline value is still a duplicate`() {
        val src = """
            [versions]
            list = [
              "a",
            ]
            list = 2
        """.trimIndent()
        val diags = diagnose(src)
        assertEquals(1, diags.size)
        assertEquals(DiagnosticSeverity.Warning, diags[0].severity)
        assertTrue(diags[0].message.contains("Duplicate key"))
    }

    @Test
    fun `format preserves whitespace inside a multiline string`() = runBlocking {
        val quotes = "\"\"\""
        val src = "text = $quotes\n    indented line   \n$quotes\nb=2"
        val formatted = TomlLanguageService.format(CodeDocument(src))
        assertTrue(formatted.contains("    indented line   "), "string content must survive verbatim: $formatted")
        assertTrue(formatted.endsWith("b = 2"), "declarations outside the string are still normalized: $formatted")
    }

    @Test
    fun `format keeps array elements on their own lines without splitting on equals`() = runBlocking {
        val src = "arr = [\n  \"a=1\",\n]"
        val formatted = TomlLanguageService.format(CodeDocument(src))
        assertEquals("arr = [\n\"a=1\",\n]", formatted)
    }
}
