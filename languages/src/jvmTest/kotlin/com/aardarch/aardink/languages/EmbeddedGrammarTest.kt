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
 * A small template markup grammar, XML-like with `{name.path | filter: argument}` expressions in text
 * and in attribute values, that exercises the declarative grammar features a real-world markup
 * grammar leans on: rule cases over a word list, `@rematch`, `tokenPostfix`, nested attribute value
 * states and a multi-line state. The grammars are in `resources/embedding/`.
 *
 * `template-markup.grammar.json` writes the expression rules inline. `template-markup-embedding.grammar.json`
 * is the same grammar with `nextEmbedded` where it entered the expression state, and
 * `template-expression.grammar.json` is that state as a grammar of its own. Both have to find the
 * same tokens. The golden tokens in `template-markup.tokens.json` were generated from the inline
 * grammar and checked by hand.
 */
class EmbeddedGrammarTest {

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/embedding/$path")) { "missing test resource embedding/$path" }
            .bufferedReader()
            .use { it.readText() }
            .replace("\r\n", "\n")

    private val inlineGrammar = DeclarativeGrammar.parse(resource("template-markup.grammar.json"))
    private val inline = DeclarativeTokenizer(inlineGrammar)

    private val embeddingGrammar = DeclarativeGrammar.parse(resource("template-markup-embedding.grammar.json"))
    private val expressionGrammar = DeclarativeGrammar.parse(resource("template-expression.grammar.json"))
    private val embedded =
        DeclarativeTokenizer(embeddingGrammar) { id -> expressionGrammar.takeIf { id == "template-expression" } }

    /** Per line, each named token as (name, text). */
    private fun named(text: String, tokenizer: DeclarativeTokenizer = inline): List<List<Pair<String, String>>> {
        val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        val tokens = tokenizer.tokenizeFull(text).filter { it.end > it.start }
        return lineStarts.map { start ->
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            tokens.filter { it.start >= start && it.end <= end }
                .mapNotNull { token -> (token.type as? NamedTokenType)?.let { it.name to text.substring(token.start, token.end) } }
        }
    }

    /** [type] as the golden tokens name it: every `comment…` name is `comment`. */
    private fun goldenName(type: TokenType): String = when (type) {
        is NamedTokenType -> if (type.name.startsWith("comment")) "comment" else type.name
        TokenType.Comment -> "comment"
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

    private val fixtures get() = Json.parseToJsonElement(resource("template-markup.tokens.json")).jsonObject

    @Test
    fun `the inline grammar finds the golden tokens`() {
        assertTrue(fixtures.isNotEmpty(), "no fixtures in template-markup.tokens.json")
        for ((file, lines) in fixtures) {
            val text = resource("fixtures/$file")
            assertEquals(expected(lines.jsonArray), golden(text, inline.tokenizeFull(text)), "tokens differ for $file")
        }
    }

    @Test
    fun `the expression language as an embedded grammar finds the golden tokens`() {
        assertTrue(fixtures.isNotEmpty(), "no fixtures in template-markup.tokens.json")
        for ((file, lines) in fixtures) {
            val text = resource("fixtures/$file")
            assertEquals(expected(lines.jsonArray), golden(text, embedded.tokenizeFull(text)), "tokens differ for $file")
            // Not only the golden shape, which folds names together: the very same tokens.
            assertEquals(inline.tokenizeFull(text), embedded.tokenizeFull(text), "tokens differ for $file")
        }
    }

    @Test
    fun `the expression language as an embedded grammar really is another grammar`() {
        val line = "<label>{user.name | upper}</label>"
        // Without it, the tags still highlight and the expression has only its closing brace.
        val alone = named(line, DeclarativeTokenizer(embeddingGrammar)).single()
        assertTrue("tag.markup.tpl" to "label" in alone, "$alone")
        assertEquals(listOf("delimiter.expression.tpl" to "}"), alone.filter { it.first.contains("expression") })
        assertTrue(alone.none { it.first == "function.filter.tpl" }, "$alone")
        assertTrue("function.filter.tpl" to "upper" in named(line, embedded).single())
    }

    @Test
    fun `tokenNames stop at the grammar, so a colour check has to ask both`() {
        assertTrue("function.filter.tpl" in inlineGrammar.tokenNames)
        assertTrue("function.filter.tpl" !in embeddingGrammar.tokenNames)
        assertTrue("function.filter.tpl" in expressionGrammar.tokenNames)
        assertEquals(inlineGrammar.tokenNames, embeddingGrammar.tokenNames + expressionGrammar.tokenNames)
    }

    @Test
    fun `tokenPostfix names every token of both grammars`() {
        val text = fixtures.keys.joinToString("\n") { resource("fixtures/$it") }
        for (tokenizer in listOf(inline, embedded)) {
            val names = tokenizer.tokenizeFull(text).mapNotNull { (it.type as? NamedTokenType)?.name }
            assertTrue(names.isNotEmpty())
            assertTrue(names.all { it.endsWith(".tpl") }, "${names.filterNot { it.endsWith(".tpl") }}")
        }
        assertTrue((inlineGrammar.tokenNames + embeddingGrammar.tokenNames + expressionGrammar.tokenNames).all { it.endsWith(".tpl") })
    }

    @Test
    fun `an edit inside an embedded expression rescans its own line`() {
        val text = resource("fixtures/expressions.xml")
        val lines = text.split('\n')
        val line = lines.indexOfFirst { '{' in it }
        val lineStart = lines.take(line).sumOf { it.length + 1 }
        val at = lineStart + lines[line].indexOf('{') + 1
        val edited = text.substring(0, at) + "x" + text.substring(at)
        val lineEnd = edited.indexOf('\n', at).let { if (it < 0) edited.length else it }
        val partial = embedded.tokenizeLines(edited, line..line, embedded.tokenizeFull(text))
        assertTrue(partial.all { it.end <= lineEnd }, "stayed on line $line: ${partial.last()}")
        assertEquals(
            DeclarativeTokenizer(inlineGrammar).tokenizeFull(edited).filter { it.start >= lineStart && it.end <= lineEnd },
            partial.filter { it.end > it.start },
        )
    }

    @Test
    fun `both grammars find the names and spans expected of the expressions`() {
        for (tokenizer in listOf(inline, embedded)) {
            named("{user.name | upper}", tokenizer).single().let { line ->
                assertTrue("function.filter.tpl" to "upper" in line, "$line")
                assertTrue("variable.expression.tpl" to "user" in line, "$line")
                assertTrue("variable.property.tpl" to "name" in line, "$line")
                assertTrue("operator.pipe.tpl" to "|" in line, "$line")
            }
            named("{count | pad: 3 | suffix: x}", tokenizer).single().let { line ->
                assertTrue("function.filter.tpl" to "pad" in line, "$line")
                assertEquals(2, line.count { it.first == "function.filter.tpl" }, "$line")
                assertTrue("string.argument.tpl" to " 3 " in line, "$line")
            }
            named("<link href=\"a\" text=\"b\" hidden />", tokenizer).single().let { line ->
                assertTrue("attribute.name.tpl" to "href" in line, "$line")
                assertTrue("attribute.value.tpl" to "\"a\"" in line, "$line")
                assertTrue("attribute.name.tpl" to "hidden" in line, "$line")
            }
            assertTrue("variable.expression.tpl" to "title" in named("<label>{title}</label>", tokenizer).single())
            named("<label>{\$title}</label>", tokenizer).single().let { line ->
                assertTrue("delimiter.expression.tpl" to "{" in line, "$line")
                assertTrue("variable.expression.tpl" to "\$title" in line, "$line")
                assertTrue("delimiter.expression.tpl" to "}" in line, "$line")
            }
            named("<label for=\"{user.name}\">", tokenizer).single().let { line ->
                assertTrue("variable.expression.tpl" to "user" in line, "$line")
                assertTrue("variable.property.tpl" to "name" in line, "$line")
            }
            named("<link href='{site.url}' text=\"{count | pad: 2}\">", tokenizer).single().let { line ->
                assertTrue("variable.expression.tpl" to "site" in line, "$line")
                assertTrue("variable.property.tpl" to "url" in line, "$line")
                assertTrue("function.filter.tpl" to "pad" in line, "$line")
            }
        }
    }

    @Test
    fun `an unterminated expression recovers on the next line`() {
        for (tokenizer in listOf(inline, embedded)) {
            val lines = named("<note>{oops</note>\n<item id=\"x\">ok</item>", tokenizer)
            assertTrue("tag.markup.tpl" to "item" in lines[1], "${lines[1]}")
            assertTrue("attribute.name.tpl" to "id" in lines[1], "${lines[1]}")
        }
    }

    @Test
    fun `every item line is tagged alike`() {
        val sample = listOf(
            "<page title=\"Home\">",
            "  <list>",
            "    <item id=\"a\">{user.name | upper}</item>",
            "    <item id=\"b\">{count | pad: 3 | suffix: x}</item>",
            "    <item id=\"c\">{title | trim | lower}</item>",
            "    <item id=\"d\">{\$count}</item>",
            "    <item id=\"e\">{ratio.value}%</item>",
            "  </list>",
            "</page>",
        )
        for (tokenizer in listOf(inline, embedded)) {
            val lines = named(sample.joinToString("\n"), tokenizer)
            sample.forEachIndexed { i, source ->
                if (!source.trimStart().startsWith("<item ")) return@forEachIndexed
                assertEquals(
                    listOf("tag.markup.tpl" to "item", "tag.markup.tpl" to "item"),
                    lines[i].filter { it.first == "tag.markup.tpl" },
                    source,
                )
            }
            assertEquals(listOf("tag.markup.tpl" to "page"), lines[0].filter { it.first.startsWith("tag") })
        }
    }

    @Test
    fun `an element outside the word list is a plain tag`() {
        for (tokenizer in listOf(inline, embedded)) {
            val line = named("<widget><item/></widget>", tokenizer).single()
            assertEquals(
                listOf("tag.tpl" to "widget", "tag.markup.tpl" to "item", "tag.tpl" to "widget"),
                line.filter {
                    it.first.startsWith("tag")
                },
            )
        }
    }
}
