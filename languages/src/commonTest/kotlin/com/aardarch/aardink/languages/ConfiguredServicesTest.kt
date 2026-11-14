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
import com.aardarch.aardink.languages.internal.css.CssFormatter
import com.aardarch.aardink.languages.internal.grammar.CSS_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.MARKDOWN_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.TYPESCRIPT_CONFIGURATION
import com.aardarch.aardink.languages.internal.markdown.MarkdownTables
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** TypeScript's, CSS's and Markdown's services: their configurations, CSS's formatter and Markdown's tables. */
class ConfiguredServicesTest {

    private val options = FormattingOptions(tabSize = 4, insertSpaces = true)

    @Test
    fun `the configurations parse`() {
        for (configuration in listOf(TYPESCRIPT_CONFIGURATION, CSS_CONFIGURATION, MARKDOWN_CONFIGURATION)) {
            LanguageConfiguration.parse(configuration)
        }
    }

    @Test
    fun `TypeScript closes its pairs and indents on Enter`() {
        val ts = BuiltInLanguages.TypeScript.languageService!!
        assertEquals("}", ts.autoClose(CodeDocument("{"), 0, '{'))
        assertEquals("`", ts.autoClose(CodeDocument("`"), 0, '`'))
        assertNull(ts.autoClose(CodeDocument("don'"), 3, '\''), "not the apostrophe in a word")
        assertNull(ts.autoClose(CodeDocument("// ('"), 4, '\''), "not a quote in a comment")
        assertEquals("    ", ts.smartIndent(CodeDocument("function f() {\n"), 1, options))
        assertEquals("      ", ts.smartIndent(CodeDocument("  call(\n"), 1, options))
        assertEquals(" * ", ts.smartIndent(CodeDocument("/**\n"), 1, options))
        assertEquals(" * ", ts.smartIndent(CodeDocument("/**\n * Says what.\n"), 2, options))
        assertEquals("", ts.smartIndent(CodeDocument("/**\n * Says what.\n */\n"), 3, options))
        assertEquals(")", ts.surroundingClose('('))
    }

    @Test
    fun `CSS closes its pairs outside comments and keeps its colours`() = runTest {
        val css = BuiltInLanguages.Css.languageService!!
        assertEquals("}", css.autoClose(CodeDocument("a {"), 2, '{'))
        assertNull(css.autoClose(CodeDocument("/* {"), 3, '{'))
        assertEquals("    ", css.smartIndent(CodeDocument("a {\n"), 1, options))
        assertEquals(1, css.documentColors(CodeDocument("a { color: #00ff00; }")).size)
    }

    @Test
    fun `CSS formats a rule to a line per selector and per declaration`() = runTest {
        val text = "a,b{color:red;margin:0  auto}\n/* note */\n@media (max-width: 600px){.c{display:none}}\n"
        val expected = """
            a,
            b {
                color: red;
                margin: 0 auto;
            }

            /* note */
            @media (max-width: 600px) {
                .c {
                    display: none;
                }
            }

        """.trimIndent()
        val formatted = BuiltInLanguages.Css.languageService!!.format(CodeDocument(text), options)
        assertEquals(expected, formatted)
        assertEquals(formatted, CssFormatter.format(formatted, options), "formatting again changes nothing")
    }

    @Test
    fun `CSS formatting keeps strings, trailing comments, blank lines and CR LF`() {
        val text = "a {\r\n  content: \"x;  {y}\";  /* why */\r\n\r\n\r\n  background: url(data:a;b)\r\n}"
        val expected = "a {\r\n    content: \"x;  {y}\"; /* why */\r\n\r\n    background: url(data:a;b);\r\n}"
        assertEquals(expected, CssFormatter.format(text, options))
    }

    @Test
    fun `SCSS and LESS are not formatted as CSS`() = runTest {
        val text = "a{ // a line comment\n  b{c:d} }"
        assertEquals(text, BuiltInLanguages.Scss.languageService!!.format(CodeDocument(text), options))
        assertEquals(text, BuiltInLanguages.Less.languageService!!.format(CodeDocument(text), options))
    }

    @Test
    fun `Markdown carries a list or a quote on, and does not close an angle bracket`() {
        val md = BuiltInLanguages.Markdown.languageService!!
        assertEquals("- ", md.smartIndent(CodeDocument("- one\n"), 1, options))
        assertEquals("  * ", md.smartIndent(CodeDocument("  * one\n"), 1, options))
        assertEquals("- [ ] ", md.smartIndent(CodeDocument("- [x] done\n"), 1, options))
        assertEquals("> ", md.smartIndent(CodeDocument("> said\n"), 1, options))
        assertEquals("", md.smartIndent(CodeDocument("- \n"), 1, options), "an empty item ends the list")
        assertEquals("]", md.autoClose(CodeDocument("["), 0, '['))
        assertNull(md.autoClose(CodeDocument("<"), 0, '<'))
        assertEquals("*", md.surroundingClose('*'))
    }

    @Test
    fun `Markdown formatting lines up a table's columns as its delimiter row aligns them`() = runTest {
        val text = "Text | not a table\n\n| a | long header |\n|:-|-:|\n| x | 1 |\n| yy |\n\nAfter.\n"
        val expected = "Text | not a table\n\n" +
            "| a   | long header |\n" +
            "| :-- | ----------: |\n" +
            "| x   |           1 |\n" +
            "| yy  |             |\n" +
            "\nAfter.\n"
        val md = BuiltInLanguages.Markdown.languageService!!
        assertEquals(expected, md.format(CodeDocument(text), options))
        assertEquals(expected, MarkdownTables.format(expected))
        assertEquals(listOf("Heading"), md.documentSymbols(CodeDocument("# Heading\n")).map { it.name })
    }

    @Test
    fun `Markdown leaves tables in code alone, and centres where asked`() {
        val fenced = "```\n|a|b|\n|-|-|\n```\n    |a|b|\n    |-|-|\n"
        assertEquals(fenced, MarkdownTables.format(fenced))
        assertEquals("| abc | d   |\r\n| :-: | --- |\r\n|  e  | f   |", MarkdownTables.format("|abc|d|\r\n|:-:|---|\r\n|e|f|"))
    }
}
