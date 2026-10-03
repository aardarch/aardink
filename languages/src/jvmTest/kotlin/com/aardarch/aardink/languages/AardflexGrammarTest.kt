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

import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The aardflex-xml grammar both aardflex apps ship, from the aardflex repo's `shared/grammar/`
 * (copied to `resources/aardflex/`), so a grammar feature they rely on cannot break without
 * Aardink noticing. The golden tokens are the ones the web app's Aardink browser test writes and
 * the Android app's unit test reads.
 */
class AardflexGrammarTest {

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/aardflex/$path")) { "missing test resource aardflex/$path" }
            .bufferedReader()
            .use { it.readText() }
            .replace("\r\n", "\n")

    private val aardflex = DeclarativeTokenizer(DeclarativeGrammar.parse(resource("aardflex-xml.grammar.json")))

    /** Per line, each named token as (name, text): the pairs aardflex's Monaco tests checked. */
    private fun aardflexTokens(text: String): List<List<Pair<String, String>>> {
        val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        val tokens = aardflex.tokenizeFull(text).filter { it.end > it.start }
        return lineStarts.map { start ->
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            tokens.filter { it.start >= start && it.end <= end }
                .mapNotNull { token -> (token.type as? NamedTokenType)?.let { it.name to text.substring(token.start, token.end) } }
        }
    }

    /** [type] as the golden tokens name it: every `comment…` and `string…` name is `comment` or `string`. */
    private fun goldenName(type: TokenType): String = when (type) {
        is NamedTokenType -> when {
            type.name.startsWith("comment") -> "comment"
            type.name.startsWith("string") -> "string"
            else -> type.name
        }

        TokenType.Comment -> "comment"

        TokenType.StringLiteral -> "string"

        else -> ""
    }

    /** [tokens] in the golden shape: per line, `type to text`, gaps untyped, neighbours of one type merged. */
    private fun golden(text: String, tokens: List<Token>): List<List<Pair<String, String>>> {
        val types = arrayOfNulls<String>(text.length)
        for (token in tokens) for (i in token.start until token.end) types[i] = goldenName(token.type)
        var lineStart = 0
        return text.split('\n').map { line ->
            val runs = mutableListOf<Pair<String, String>>()
            for (i in line.indices) {
                val type = types[lineStart + i] ?: ""
                val last = runs.lastOrNull()
                if (last != null && last.first == type) {
                    runs[runs.size - 1] = type to last.second + line[i]
                } else {
                    runs += type to line[i].toString()
                }
            }
            lineStart += line.length + 1
            runs
        }
    }

    private fun expected(fixture: JsonArray): List<List<Pair<String, String>>> = fixture.map { line ->
        line.jsonArray
            .map { pair -> pair.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content } }
            .filter { it.second.isNotEmpty() }
    }

    @Test
    fun `aardflex's grammar finds the golden tokens`() {
        val fixtures = Json.parseToJsonElement(resource("aardflex-xml.tokens.json")).jsonObject
        assertTrue(fixtures.isNotEmpty(), "no fixtures in aardflex-xml.tokens.json")
        for ((file, lines) in fixtures) {
            val text = resource("fixtures/$file")
            assertEquals(expected(lines.jsonArray), golden(text, aardflex.tokenizeFull(text)), "tokens differ for $file")
        }
    }

    // The cases of aardflex-web-app's former src/editor/setup.test.ts, which checked them against Monaco.
    @Test
    fun `aardflex's grammar finds the tokens Monaco finds`() {
        aardflexTokens("{dt:hour | toWords}").single().let { line ->
            assertTrue("function.transform.xml" to "toWords" in line, "$line")
            assertTrue("variable.module.xml" to "dt" in line, "$line")
            assertTrue("variable.property.xml" to "hour" in line, "$line")
        }
        aardflexTokens("{dt:minute | toWords | replace: :}").single().let { line ->
            assertTrue("function.transform.xml" to "replace" in line, "$line")
            assertEquals(2, line.count { it.first == "function.transform.xml" }, "$line")
        }
        aardflexTokens("<font name=\"main\" provider=\"system\" />").single().let { line ->
            assertTrue("attribute.name.xml" to "name" in line, "$line")
            assertTrue("attribute.value.xml" to "\"main\"" in line, "$line")
            assertTrue("attribute.name.xml" to "provider" in line, "$line")
        }
        assertTrue("variable.xml" to "hourWords" in aardflexTokens("<text>{hourWords}</text>").single())
        aardflexTokens("<text>{\$hourWords}</text>").single().let { line ->
            assertTrue("delimiter.expression.xml" to "{" in line, "$line")
            assertTrue("variable.xml" to "\$hourWords" in line, "$line")
            assertTrue("delimiter.expression.xml" to "}" in line, "$line")
        }
        aardflexTokens("<text color=\"{theme:colors.onBackground}\">").single().let { line ->
            assertTrue("variable.module.xml" to "theme" in line, "$line")
            assertTrue("variable.property.xml" to "colors.onBackground" in line, "$line")
        }
        aardflexTokens("<text color=\"{colors:accent}\" visible=\"{system:battery | gte:20}\">").single().let { line ->
            assertTrue("variable.module.xml" to "colors" in line, "$line")
            assertTrue("variable.property.xml" to "accent" in line, "$line")
            assertTrue("function.transform.xml" to "gte" in line, "$line")
        }
        assertTrue("tag.aardflex.xml" to "var" in aardflexTokens("<text>{oops</text>\n<var name=\"x\">ok</var>")[1])
    }

    @Test
    fun `aardflex's grammar tags every var line alike`() {
        val sample = listOf(
            "<wallpaper name=\"Textytime Dashboard\" themeId=\"bundled-material-dark\">",
            "  <variables>",
            "    <var name=\"hourWords\">{dt:hour | toWords}</var>",
            "    <var name=\"minuteWords\">{dt:minute | toWords | replace: :}</var>",
            "    <var name=\"dayRest\">{dt:date:EEEE | substr:3 | lowercase}</var>",
            "    <var name=\"dayNum\">{dt:date:d}</var>",
            "    <var name=\"moonIllum\">{moon:illumination}%</var>",
            "    <var name=\"riseTime\">{sun:sunrise:HHmm}</var>",
            "  </variables>",
            "</wallpaper>",
        )
        val lines = aardflexTokens(sample.joinToString("\n"))
        sample.forEachIndexed { i, source ->
            if (!source.trimStart().startsWith("<var ")) return@forEachIndexed
            assertEquals(
                listOf("tag.aardflex.xml" to "var", "tag.aardflex.xml" to "var"),
                lines[i].filter { it.first == "tag.aardflex.xml" },
                source,
            )
        }
        assertEquals(listOf("tag.aardflex.xml" to "wallpaper"), lines[0].filter { it.first.startsWith("tag") })
    }
}
