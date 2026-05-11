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
import com.aardarch.aardink.languages.internal.xml.HtmlLanguageService
import com.aardarch.aardink.languages.internal.xml.XmlLanguageService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmlLanguageServiceTest {

    private fun xml(text: String) = runBlocking { XmlLanguageService.diagnostics(CodeDocument(text)) }
    private fun html(text: String) = runBlocking { HtmlLanguageService.diagnostics(CodeDocument(text)) }

    @Test
    fun `well-formed XML yields no diagnostics`() {
        assertTrue(xml("<root><child/></root>").isEmpty())
        assertTrue(xml("<a attr=\"v\"><b/></a>").isEmpty())
        assertTrue(xml("<!-- comment --><a/>").isEmpty())
        assertTrue(xml("<?xml version=\"1.0\"?><a/>").isEmpty())
    }

    @Test
    fun `mismatched closing tag flagged`() {
        val diags = xml("<a><b></c></a>")
        assertTrue(diags.any { it.message.contains("Unmatched", ignoreCase = true) })
    }

    @Test
    fun `unclosed open tag flagged`() {
        val diags = xml("<a><b></a>")
        assertTrue(diags.any { it.message.contains("Unclosed", ignoreCase = true) })
    }

    @Test
    fun `unterminated comment flagged`() {
        val diags = xml("<a><!-- never ends</a>")
        assertEquals(1, diags.size)
        assertTrue(diags[0].message.contains("comment", ignoreCase = true))
    }

    @Test
    fun `html void elements do not need closing`() {
        assertTrue(html("<div><br><img src=\"x\"></div>").isEmpty())
    }

    @Test
    fun `html still flags unbalanced non-void tags`() {
        val diags = html("<div><span></div>")
        assertTrue(diags.isNotEmpty())
    }
}
