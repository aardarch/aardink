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

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.languages.internal.HexColors
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentColorsTest {

    private suspend fun colorsIn(service: LanguageService?, text: String): List<Pair<String, Color>> =
        service!!.documentColors(CodeDocument(text)).map { text.substring(it.range.first, it.range.last + 1) to it.color }

    @Test
    fun `hex colours parse in both alpha orders`() {
        assertEquals(Color(0xFFFF0000), HexColors.parse("f00", alphaFirst = false))
        assertEquals(Color(0x80112233), HexColors.parse("11223380", alphaFirst = false))
        assertEquals(Color(0x80112233), HexColors.parse("80112233", alphaFirst = true))
        assertEquals(Color(0x88FF0000), HexColors.parse("8f00", alphaFirst = true))
        assertNull(HexColors.parse("12345", alphaFirst = false))
    }

    @Test
    fun `a colour is written back as its text was`() {
        assertEquals("#00ff00", HexColors.format(Color(0xFF00FF00), "#ff0000", alphaFirst = false))
        assertEquals("#0F0", HexColors.format(Color(0xFF00FF00), "#F00", alphaFirst = false))
        assertEquals("#80112233", HexColors.format(Color(0x80112233), "#ffffff", alphaFirst = true))
        assertEquals("#11223380", HexColors.format(Color(0x80112233), "#ffffff", alphaFirst = false))
    }

    @Test
    fun `xml attribute values are colours, alpha first`() = runTest {
        val text = "<r><c name=\"x\" value=\"#80FF0000\" other=\"#nothex\" /><color> #FF00FF00 </color></r>"
        assertEquals(
            listOf("#80FF0000" to Color(0x80FF0000), "#FF00FF00" to Color(0xFF00FF00)),
            colorsIn(BuiltInLanguages.Xml.languageService, text),
        )
    }

    @Test
    fun `html attribute values are colours, alpha last`() = runTest {
        val text = "<font color=\"#ff000080\">x</font>"
        assertEquals(listOf("#ff000080" to Color(0x80FF0000)), colorsIn(BuiltInLanguages.Html.languageService, text))
    }

    @Test
    fun `json and toml string values that are colours`() = runTest {
        assertEquals(
            listOf("#00f" to Color(0xFF0000FF)),
            colorsIn(BuiltInLanguages.Json.languageService, """{ "a": "#00f", "b": "#id x" }"""),
        )
        assertEquals(listOf("#123456" to Color(0xFF123456)), colorsIn(BuiltInLanguages.Toml.languageService, "accent = '#123456'"))
    }

    @Test
    fun `json and toml keys and comments are not colours`() = runTest {
        assertEquals(
            listOf("#0f0" to Color(0xFF00FF00)),
            colorsIn(BuiltInLanguages.Json.languageService, "{ \"#fff\": \"#0f0\", // \"#000\"\n /* \"#111\" */ \"k\": [\"#zzz\"] }"),
        )
        assertEquals(
            listOf("#fff" to Color(0xFFFFFFFF), "#000" to Color(0xFF000000)),
            colorsIn(
                BuiltInLanguages.Toml.languageService,
                "# accent = '#f00'\n\"#abc\" = 1\n\"#def\".x = 2\ncolors = [\"#fff\", '#000'] # '#123'\nnote = \"\"\"\n'#456'\n\"\"\"",
            ),
        )
    }

    @Test
    fun `css hashes in nested selectors, strings and urls are not colours`() = runTest {
        assertEquals(
            listOf("#fff" to Color(0xFFFFFFFF), "#000" to Color(0xFF000000)),
            colorsIn(
                BuiltInLanguages.Css.languageService,
                "@media screen { #abc { color: #fff; } a:hover #bcd { background: url(#cde) #000 } }\n" +
                    "p { content: \"#eee\"; }",
            ),
        )
    }

    @Test
    fun `css colours in declarations, not in selectors or comments`() = runTest {
        val text = "#abc { color: #fff; /* #000 */ border: 1px solid #12345678 }"
        assertEquals(
            listOf("#fff" to Color(0xFFFFFFFF), "#12345678" to Color(0x78123456)),
            colorsIn(BuiltInLanguages.Css.languageService, text),
        )
    }

    @Test
    fun `a picked colour becomes an edit of the colour's text`() = runTest {
        val document = CodeDocument("<c value=\"#ff0000\"/>")
        val service = BuiltInLanguages.Xml.languageService!!
        val info = service.documentColors(document).single()
        val presentation = service.colorPresentations(document, info, Color(0x8000FF00)).single()
        assertEquals("#8000ff00", presentation.label)
        assertEquals(info.range, presentation.textEdit!!.range)
    }
}
