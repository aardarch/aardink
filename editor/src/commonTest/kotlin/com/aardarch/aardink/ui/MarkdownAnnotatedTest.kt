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
package com.aardarch.aardink.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownAnnotatedTest {

    private val spans = MarkdownSpans(code = SpanStyle(background = Color.Gray), link = SpanStyle(color = Color.Blue))

    private fun annotated(text: String, onOpenLink: ((String) -> Unit)? = null): AnnotatedString =
        markdownAnnotated(parseInlines(text), spans, onOpenLink)

    private fun AnnotatedString.styled(text: String): List<SpanStyle> {
        val start = this.text.indexOf(text)
        return spanStyles.filter { it.start == start && it.end == start + text.length }.map { it.item }
    }

    @Test
    fun `the marks are gone from the text`() {
        assertEquals("a b c d", annotated("*a* **b** `c` [d](e)").text)
    }

    @Test
    fun `emphasis is italic, strong is bold, code takes the code style`() {
        val text = annotated("*a* **b** `c`")
        assertEquals(listOf(FontStyle.Italic), text.styled("a").map { it.fontStyle })
        assertEquals(listOf(FontWeight.Bold), text.styled("b").map { it.fontWeight })
        assertEquals(listOf(spans.code), text.styled("c"))
    }

    @Test
    fun `a hard break is a line end`() {
        assertEquals("a\nb", annotated("a  \nb").text)
    }

    @Test
    fun `a link is one the host's handler opens, never a URL that opens itself`() {
        val opened = ArrayList<String>()
        val text = annotated("see [docs](https://a.dev/x)", opened::add)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("docs", text.text.substring(link.start, link.end))
        val clickable = assertIs<LinkAnnotation.Clickable>(link.item)
        assertEquals(spans.link, clickable.styles?.style)
        clickable.linkInteractionListener?.onClick(clickable)
        assertEquals(listOf("https://a.dev/x"), opened)
    }

    @Test
    fun `with no handler a link is its text`() {
        val text = annotated("see [docs](https://a.dev/x)")
        assertEquals("see docs", text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }

    @Test
    fun `emphasis nested as deep as input can make it still builds, with all its text`() {
        val text = annotated("*a ".repeat(3_000) + " a*".repeat(3_000))
        assertTrue(text.spanStyles.isNotEmpty())
        assertEquals(6_000, text.text.count { it == 'a' })
    }
}
