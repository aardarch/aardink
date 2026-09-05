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
import com.aardarch.aardink.languages.internal.kotlin.KotlinLanguageService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinLanguageServiceTest {

    private fun diagnose(text: String) = runBlocking {
        KotlinLanguageService.diagnostics(CodeDocument(text))
    }

    @Test
    fun `valid Kotlin produces no diagnostics`() {
        val src = """
            package com.example
            @Composable
            fun Hello(name: String = "World") {
                val list = listOf(1, 2, 3)
                list.forEach { println(it) }
            }
        """.trimIndent()
        assertTrue(diagnose(src).isEmpty())
    }

    @Test
    fun `unclosed string flagged`() {
        val diags = diagnose("val x = \"hello")
        assertEquals(1, diags.size)
        assertEquals(DiagnosticSeverity.Error, diags[0].severity)
        assertTrue(diags[0].message.contains("Unterminated string", ignoreCase = true))
    }

    @Test
    fun `delimiters inside character literals are data`() {
        assertTrue(diagnose("val closing = '}'").isEmpty())
        assertTrue(diagnose("val opening = '{'").isEmpty())
        assertTrue(diagnose("fun f() { val p = '(' }").isEmpty())
        assertTrue(diagnose("""val escaped = '\''""").isEmpty())
        assertTrue(diagnose("""val backslash = '\\'""").isEmpty())
    }

    @Test
    fun `a lone quote does not hide a real unmatched delimiter`() {
        // Not a character literal, so the brace after it is still checked.
        val diags = diagnose("val s = \"it's\"\n}")
        assertTrue(diags.any { it.message.contains("Unmatched closing delimiter", ignoreCase = true) })
    }

    @Test
    fun `unmatched delimiter flagged`() {
        val diags = diagnose("fun foo() { val x = 1 } }")
        assertEquals(1, diags.size)
        assertEquals(DiagnosticSeverity.Error, diags[0].severity)
        assertTrue(diags[0].message.contains("Unmatched closing delimiter", ignoreCase = true))
    }

    @Test
    fun `dot completions suggest stdlib methods`() = runBlocking {
        val doc = CodeDocument("list.")
        val items = KotlinLanguageService.completions(doc, 5)
        assertTrue(items.any { it.label == "map { }" })
        assertTrue(items.any { it.label == "filter { }" })
    }

    @Test
    fun `annotation completions suggest composable`() = runBlocking {
        val doc = CodeDocument("@")
        val items = KotlinLanguageService.completions(doc, 1)
        assertTrue(items.any { it.label == "Composable" })
    }

    @Test
    fun `auto close brackets and quotes`() {
        val doc = CodeDocument("")
        assertEquals("}", KotlinLanguageService.autoClose(doc, 0, '{'))
        assertEquals(")", KotlinLanguageService.autoClose(doc, 0, '('))
        assertEquals("\"", KotlinLanguageService.autoClose(doc, 0, '"'))
    }

    @Test
    fun `format indents code blocks`() = runBlocking {
        val doc = CodeDocument("fun foo() {\nval x = 1\n}")
        val formatted = KotlinLanguageService.format(doc)
        assertEquals("fun foo() {\n    val x = 1\n}", formatted)
    }
}
