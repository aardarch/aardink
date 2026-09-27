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

import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeclarativeGrammarTest {

    // A small language: keywords, numbers, strings, block comments over lines, and name=value pairs.
    private val grammar = DeclarativeGrammar.parse(
        """
        {
          "keywords": ["if", "else", "let"],
          "digits": "[0-9]+",
          "tokenizer": {
            "root": [
              ["[a-z_]\\w*(?==)", "attribute.name"],
              ["[a-z_]\\w*", { "cases": { "@keywords": "keyword", "@default": "identifier" } }],
              ["@digits", "number"],
              ["\"", "string", "@string"],
              ["/\\*", "comment", "@comment"],
              ["(<)(\\w+)", ["delimiter", "tag"]],
              { "include": "@whitespace" },
              ["[=;<>]", "delimiter"]
            ],
            "string": [
              ["[^\"]+", "string"],
              ["\"", "string", "@pop"]
            ],
            "comment": [
              ["\\*/", "comment", "@pop"],
              ["[^*]+", "comment"],
              ["\\*", "comment"]
            ],
            "whitespace": [["\\s+", ""]]
          }
        }
        """.trimIndent(),
    )

    private val tokenizer = DeclarativeTokenizer(grammar)

    private fun List<Token>.named(text: String) = filter { it.end > it.start }.map { text.substring(it.start, it.end) to it.type }

    @Test
    fun `rules, cases, groups and states tokenize a line`() {
        val text = "let x = 42; if a=1 <tag \"hi\""
        assertEquals(
            listOf(
                "let" to NamedTokenType("keyword"),
                "x" to NamedTokenType("identifier"),
                "=" to NamedTokenType("delimiter"),
                "42" to NamedTokenType("number"),
                ";" to NamedTokenType("delimiter"),
                "if" to NamedTokenType("keyword"),
                "a" to NamedTokenType("attribute.name"),
                "=" to NamedTokenType("delimiter"),
                "1" to NamedTokenType("number"),
                "<" to NamedTokenType("delimiter"),
                "tag" to NamedTokenType("tag"),
                "\"hi\"" to TokenType.StringLiteral,
            ),
            tokenizer.tokenizeFull(text).named(text),
        )
    }

    @Test
    fun `a state carries over to the next lines`() {
        val text = "let /* a\nstill comment\n*/ x"
        assertEquals(
            listOf(
                "let" to NamedTokenType("keyword"),
                "/* a" to TokenType.Comment,
                "still comment" to TokenType.Comment,
                "*/" to TokenType.Comment,
                "x" to NamedTokenType("identifier"),
            ),
            tokenizer.tokenizeFull(text).named(text),
        )
    }

    @Test
    fun `the token names a grammar can produce`() {
        assertEquals(
            setOf("attribute.name", "keyword", "identifier", "number", "string", "comment", "delimiter", "tag"),
            grammar.tokenNames,
        )
    }

    @Test
    fun `a lookbehind is rejected, with where it is`() {
        val error = assertFailsWith<IllegalArgumentException> {
            DeclarativeGrammar.parse("""{ "tokenizer": { "root": [ ["a", "x"], ["(?<=a)b", "y"] ] } }""")
        }
        assertTrue(error.message!!.startsWith("tokenizer.root[1][0]: lookbehind is not supported"), error.message)
    }

    @Test
    fun `unknown states, arrays and includes are errors with where they are`() {
        assertFailsWith<IllegalArgumentException> {
            DeclarativeGrammar.parse("""{ "tokenizer": { "root": [ ["a", "x", "@nowhere"] ] } }""")
        }
            .also { assertTrue(it.message!!.contains("tokenizer.root[0]: enters an unknown state: nowhere"), it.message) }
        assertFailsWith<IllegalArgumentException> {
            DeclarativeGrammar.parse("""{ "tokenizer": { "root": [ ["a", { "cases": { "@nope": "x" } }] ] } }""")
        }
        assertFailsWith<IllegalArgumentException> {
            DeclarativeGrammar.parse("""{ "tokenizer": { "root": [ { "include": "@missing" } ] } }""")
        }
        assertFailsWith<IllegalArgumentException> { DeclarativeGrammar.parse("""{ "tokenizer": { "root": [ ["(", "x"] ] } }""") }
        assertFailsWith<IllegalArgumentException> { DeclarativeGrammar.parse("not json") }
    }

    @Test
    fun `rematch changes state without taking text, and a dotted state falls back`() {
        val g = DeclarativeGrammar.parse(
            """
            {
              "tokenizer": {
                "root": [ ["(?=<)", { "token": "@rematch", "next": "@tag.open" }], ["\\w+", "text"] ],
                "tag": [ ["<\\w+", "tag"], [">", "tag", "@pop"], ["\\s+", ""] ]
              }
            }
            """.trimIndent(),
        )
        val text = "a <b> c"
        assertEquals(
            // Neighbouring tokens of one type are one token.
            listOf("a" to NamedTokenType("text"), "<b>" to NamedTokenType("tag"), "c" to NamedTokenType("text")),
            DeclarativeTokenizer(g).tokenizeFull(text).named(text),
        )
    }

    @Test
    fun `text no rule matches gets the default token`() {
        val g = DeclarativeGrammar.parse("""{ "defaultToken": "invalid", "tokenizer": { "root": [ ["a+", "a"] ] } }""")
        val text = "aa?a"
        assertEquals(
            listOf("aa" to NamedTokenType("a"), "?" to NamedTokenType("invalid"), "a" to NamedTokenType("a")),
            DeclarativeTokenizer(g).tokenizeFull(text).named(text),
        )
    }

    @Test
    fun `ignoreCase and the token postfix`() {
        val g = DeclarativeGrammar.parse(
            """{ "ignoreCase": true, "tokenPostfix": ".toy", "words": ["select"], "tokenizer": { "root": [ ["[a-z]+", { "cases": { "@words": "keyword" } }] ] } }""",
        )
        val text = "SELECT"
        assertEquals(listOf("SELECT" to NamedTokenType("keyword.toy")), DeclarativeTokenizer(g).tokenizeFull(text).named(text))
    }

    @Test
    fun `an edit rescans its own line, not the document`() {
        val lines = List(200) { "let x$it = $it;" }
        val text = lines.joinToString("\n")
        val full = tokenizer.tokenizeFull(text)
        val lineStart = lines.take(150).sumOf { it.length + 1 }
        val edited = text.substring(0, lineStart) + "if " + text.substring(lineStart)
        val partial = tokenizer.tokenizeLines(edited, 150..150, full)
        assertTrue(partial.all { it.start >= lineStart && it.end <= lineStart + lines[150].length + 3 }, "only line 150: $partial")
        assertEquals(NamedTokenType("keyword"), partial.first { it.end > it.start }.type)
    }

    @Test
    fun `an open comment is followed to the end, a closed one stops the rescan at its line`() {
        val text = List(50) { "let a = 1;" }.joinToString("\n")
        val full = tokenizer.tokenizeFull(text)
        // Every line below is comment now: the rescan has to go to the end.
        val opened = "/*" + text
        assertEquals(opened.length, tokenizer.tokenizeLines(opened, 0..0, full).last().end)
        // Opened and closed on the first line: the lines below end in the same state as before.
        val closed = "/* note */" + text
        val partial = tokenizer.tokenizeLines(closed, 0..0, full)
        assertEquals(closed.indexOf('\n'), partial.last().end)
    }

    @Test
    fun `the cooperative pass finds the same tokens`() = runTest {
        val text = List(300) { "let x$it = $it; /* a\n comment */ if \"s\"" }.joinToString("\n")
        assertEquals(tokenizer.tokenizeFull(text), tokenizer.tokenizeFullCooperative(text))
    }

    /** Applies [partial] (lines replaced as the editor replaces them) to [old] tokens of [text]. */
    private fun merge(text: String, old: List<Token>, partial: List<Token>): List<Token> {
        if (partial.isEmpty()) return old
        val lineStart = { offset: Int -> text.lastIndexOf('\n', offset - 1) + 1 }
        val from = lineStart(partial.first().start)
        val lastEnd = partial.last().end
        val to = text.indexOf('\n', lastEnd).let { if (it < 0) text.length else it }
        return (
            old.filter {
                it.end <= from && it.start < from
            } + partial.filter { it.end > it.start } + old.filter { it.start > to }
            ).filter {
            it.end >
                it.start
        }
    }

    @Test
    fun `an incremental pass always ends where a full pass does`() {
        val random = Random(7)
        val pieces = listOf("let ", "x", "=", "1", ";", "/*", "*/", "\"", "\n", " ", "<a", "if", "else", "42")
        var text = List(80) { pieces.random(random) }.joinToString("")
        // What the tokenizer returned last (it knows its own result by identity), and what the
        // editor holds: every line's tokens.
        var returned = tokenizer.tokenizeFull(text)
        var current = returned.filter { it.end > it.start }
        repeat(300) {
            val at = random.nextInt(0, text.length + 1)
            val remove = random.nextInt(0, minOf(4, text.length - at) + 1)
            val insert = List(random.nextInt(0, 3)) { pieces.random(random) }.joinToString("")
            val next = text.substring(0, at) + insert + text.substring(at + remove)
            // The editor's view of the old tokens: shifted along with the text.
            val delta = insert.length - remove
            val shifted = current.mapNotNull { t ->
                when {
                    t.end <= at -> t
                    t.start >= at + remove -> Token(t.start + delta, t.end + delta, t.type)
                    else -> null
                }
            }
            val firstLine = next.substring(0, at).count { it == '\n' }
            val lastLine = next.substring(0, (at + insert.length).coerceAtMost(next.length)).count { it == '\n' }
            val partial = tokenizer.tokenizeLines(next, firstLine..lastLine, returned)
            val expected = DeclarativeTokenizer(grammar).tokenizeFull(next).filter { it.end > it.start }
            val merged = merge(next, shifted, partial)
            assertEquals(expected, merged, "after editing <$text> into <$next>")
            text = next
            returned = partial
            current = merged
        }
    }
}
