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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XmlLinkedEditingTest {

    private val xml = BuiltInLanguages.Xml.languageService!!
    private val html = BuiltInLanguages.Html.languageService!!

    private suspend fun linkedNames(text: String, offset: Int, html: Boolean = false): List<String>? =
        (if (html) this.html else xml).linkedEditingRanges(CodeDocument(text), offset)
            ?.ranges?.map { text.substring(it.first, it.last + 1) }

    @Test
    fun `an element's name is linked to its closing tag's, from either`() = runTest {
        val text = "<root><item>a</item><item/></root>"
        assertEquals(listOf("item", "item"), linkedNames(text, text.indexOf("item") + 2))
        assertEquals(listOf("root", "root"), linkedNames(text, text.lastIndexOf("root")))
        // Just after the name counts, as the caret is there while typing it.
        assertEquals(listOf("root", "root"), linkedNames(text, 5))
    }

    @Test
    fun `self-closing, void and unmatched tags have no link`() = runTest {
        assertNull(linkedNames("<a><b/></a>", 4))
        assertNull(linkedNames("<p><br></p>", 5, html = true))
        assertNull(linkedNames("<a><b></a>", 4))
    }

    @Test
    fun `text, attributes and comments are not tag names`() = runTest {
        val text = "<a x=\"b\">text<!-- <c> --></a>"
        assertNull(linkedNames(text, text.indexOf("text") + 2))
        assertNull(linkedNames(text, text.indexOf("x=")))
        assertNull(linkedNames(text, text.indexOf("c>")))
        // The same text again, answered from what the first ask scanned.
        assertEquals(listOf("a", "a"), linkedNames(text, 1))
        assertEquals(listOf("a", "a"), linkedNames(text, text.lastIndexOf('a')))
    }

    @Test
    fun `attributes that refer elsewhere are links`() = runTest {
        val text = "<page><include component=\"header\"/><a href=\"https://x.io\">x</a><img src=\"logo.png\"/></page>"
        val links = xml.documentLinks(CodeDocument(text))
        assertEquals(listOf("header", "https://x.io", "logo.png"), links.map { it.target })
        assertEquals("header", text.substring(links[0].range.first, links[0].range.last + 1))
    }
}
