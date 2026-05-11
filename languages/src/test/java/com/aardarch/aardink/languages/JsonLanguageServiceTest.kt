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

import com.aardarch.editor.core.CodeDocument
import com.aardarch.editor.core.DiagnosticSeverity
import com.aardarch.editor.languages.internal.json.JsonLanguageService
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
}
