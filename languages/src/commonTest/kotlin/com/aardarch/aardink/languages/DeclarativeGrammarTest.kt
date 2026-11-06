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
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `a group's action cannot rematch or be another array`() {
        assertFailsWith<IllegalArgumentException> {
            DeclarativeGrammar.parse("""{ "tokenizer": { "root": [ ["(a)(b)", ["x", "@rematch"]] ] } }""")
        }
            .also { assertTrue(it.message!!.startsWith("tokenizer.root[0][1][1]: a group's action cannot be @rematch"), it.message) }
        assertFailsWith<IllegalArgumentException> {
            DeclarativeGrammar.parse("""{ "tokenizer": { "root": [ ["(a)(b)", ["x", { "cases": { "@default": ["y"] } }]] ] } }""")
        }
        assertFailsWith<IllegalArgumentException> {
            DeclarativeGrammar.parse("""{ "tokenizer": { "root": [ ["(a)(b)", ["x", { "token": "y", "next": "@nowhere" }]] ] } }""")
        }
            .also { assertTrue(it.message!!.contains("enters an unknown state: nowhere"), it.message) }
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
        val pieces = listOf("let ", "x", "=", "1", ";", "/*", "*/", "\"", "\n", " ", "<a", "if", "else", "42")
        assertIncrementalMatchesFull(grammar, pieces, seed = 7)
    }

    /** Random edits made of [pieces] to text in [grammar]: after each, the merged tokens are a full pass's. */
    private fun assertIncrementalMatchesFull(
        grammar: DeclarativeGrammar,
        pieces: List<String>,
        seed: Int,
        embedded: Map<String, DeclarativeGrammar> = emptyMap(),
    ) {
        val tokenizer = DeclarativeTokenizer(grammar) { embedded[it] }
        val random = Random(seed)
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
            val expected = DeclarativeTokenizer(grammar) { embedded[it] }.tokenizeFull(next).filter { it.end > it.start }
            val merged = merge(next, shifted, partial)
            assertEquals(expected, merged, "after editing <$text> into <$next>")
            text = next
            returned = partial
            current = merged
        }
    }

    // Monarch's actions and substitutions. `%` stands for `$` in these grammars, which a raw string
    // would read as a template.
    private fun monarch(json: String) = DeclarativeGrammar.parse(json.replace('%', '$'))

    private fun tokens(grammar: DeclarativeGrammar, text: String) = DeclarativeTokenizer(grammar).tokenizeFull(text).named(text)

    private fun assertRejected(json: String, message: String) {
        val error = assertFailsWith<IllegalArgumentException> { monarch(json) }
        assertTrue(error.message!!.contains(message), error.message)
    }

    // A here-document: `<<end` opens a body that the word `end` closes, and a comment that takes
    // two `*/` to close, with a `=>` that is read as far as its `=`.
    private val heredocs = """
        {
          "tokenizer": {
            "root": [
              ["<<", "delimiter", "@tag"],
              ["/\\*", "comment", "@comment.one"],
              ["=>", { "token": "arrow", "goBack": 1 }],
              [">", "greater"],
              ["[a-z]+", "word"],
              ["[{}()]", "@brackets"],
              ["\\s+", ""]
            ],
            "tag": [
              ["[a-z]+", { "token": "label", "switchTo": "@body.%#" }],
              ["(?=[^a-z])", { "token": "@rematch", "next": "@pop" }]
            ],
            "body": [
              ["[a-z]+", { "cases": { "%#==%S2": { "token": "label", "next": "@pop" }, "@default": "text" } }],
              ["\\s+", ""],
              [".", "text"]
            ],
            "comment": [
              ["\\*/", { "cases": {
                "%S2==one": { "token": "comment", "switchTo": "@comment.two" },
                "@default": { "token": "comment", "next": "@pop" }
              } }],
              ["[^*]+", "comment"],
              ["\\*", "comment"]
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `a body closes on the word that opened it`() {
        val text = "<<end\nfoo end\nbaz"
        assertEquals(
            listOf(
                "<<" to NamedTokenType("delimiter"),
                "end" to NamedTokenType("label"),
                "foo" to NamedTokenType("text"),
                "end" to NamedTokenType("label"),
                "baz" to NamedTokenType("word"),
            ),
            tokens(monarch(heredocs), text),
        )
    }

    @Test
    fun `an edit to what closes a body rescans the lines it changes`() {
        val tokenizer = DeclarativeTokenizer(monarch(heredocs))
        val text = "<<end\nfoo\nend\nbaz\nqux"
        val full = tokenizer.tokenizeFull(text)
        // Same state but for its name's last part: the lines below have to be read again.
        val edited = text.replace("<<end", "<<fin")
        val partial = tokenizer.tokenizeLines(edited, 0..0, full)
        assertEquals(edited.length, partial.last().end)
        assertEquals(
            listOf("foo", "end", "baz", "qux").map { it to NamedTokenType("text") },
            partial.named(edited).drop(2),
        )
    }

    @Test
    fun `a rescan inside a body stops where the state is the same again`() {
        val tokenizer = DeclarativeTokenizer(monarch(heredocs))
        val text = "<<end\n" + List(50) { "foo" }.joinToString("\n") + "\nend"
        val full = tokenizer.tokenizeFull(text)
        val lineStart = text.indexOf("foo")
        val edited = text.substring(0, lineStart) + "bar" + text.substring(lineStart + 3)
        val partial = tokenizer.tokenizeLines(edited, 1..1, full)
        assertTrue(partial.all { it.end <= lineStart + 3 }, "only line 1: $partial")
    }

    @Test
    fun `an incremental pass over switchTo, goBack, substitutions and brackets ends where a full pass does`() {
        val pieces = listOf("<<", "end", "fin", "end\n", "\n", " ", "x", "/*", "*/", "{", ")", "=>", ">")
        for (seed in 1..3) assertIncrementalMatchesFull(monarch(heredocs), pieces, seed)
    }

    /** Whether [guard] holds for [word], read in state `probe.alpha.beta`. */
    private fun holds(guard: String, word: String = "word"): Boolean {
        val g = monarch(
            """
            {
              "list": ["word", "alpha"],
              "tokenizer": {
                "root": [ ["\\(", "open", "@probe.alpha.beta"] ],
                "probe": [ ["[a-zA-Z%]+", { "cases": { "$guard": "yes", "@default": "no" } }] ]
              }
            }
            """.trimIndent(),
        )
        return NamedTokenType("yes") == tokens(g, "($word").last().second
    }

    @Test
    fun `a guard's subject is the match or a part of the state's name`() {
        assertTrue(holds("%S0==probe.alpha.beta"))
        assertTrue(holds("%S1==probe"))
        assertTrue(holds("%S2==alpha"))
        assertTrue(holds("%s2==alpha"), "\$s2 is \$S2")
        assertTrue(holds("%S3==beta"))
        assertFalse(holds("%S2==beta"))
        assertTrue(holds("%#==word"))
        assertFalse(holds("%#==other"))
    }

    @Test
    fun `a subject alone means it is not empty, and a part past the last is empty`() {
        assertTrue(holds("%S3"))
        assertFalse(holds("%S4"))
        assertTrue(holds("%S4!=x"))
        assertTrue(holds("%#"))
    }

    @Test
    fun `a guard's operators, with and without a subject`() {
        assertTrue(holds("word"))
        assertFalse(holds("other"))
        assertTrue(holds("==word"))
        assertFalse(holds("!=word"))
        assertTrue(holds("%#!=other"))
        assertTrue(holds("@list"))
        assertFalse(holds("!@list"))
        assertTrue(holds("%S2@list"))
        assertFalse(holds("%S3@list"))
        assertTrue(holds("%S3!@list"))
        assertTrue(holds("~wo.*"))
        assertFalse(holds("!~wo.*"))
        assertTrue(holds("%S2~al.*"))
        assertFalse(holds("%S2!~al.*"))
        assertTrue(holds("wo.*"), "with no operator, a regex")
    }

    @Test
    fun `a regex guard tests all of the subject, whichever alternative matches`() {
        // Monarch writes these as ^wo|rd.*$, where `wo` alone would do for `word`.
        assertFalse(holds("~wo|rd.*"))
        assertTrue(holds("~wo|rd.*", "wo"))
        assertTrue(holds("~wo|rd.*", "rdx"))
    }

    @Test
    fun `a key that starts with an operator is that operator, even with nothing after it`() {
        // At 0.8 these were the regexes ^(?:==)$ and ^(?:!=)$. In Monarch, as here, they compare with "".
        assertFalse(holds("=="))
        assertTrue(holds("!="))
    }

    /** The grammar [attributes] and [rules] make, whose first state is `probe.alpha`. */
    private fun withAttributes(attributes: String, rules: String) = monarch(
        """
        {
          $attributes,
          "tokenizer": {
            "root": [ ["\\(", { "token": "", "switchTo": "@probe.alpha" }] ],
            "probe": [ $rules ]
          }
        }
        """.trimIndent(),
    )

    private fun probed(grammar: DeclarativeGrammar, text: String) = tokens(grammar, "($text").filter { it.first != "(" }

    @Test
    fun `a regex attribute is the regex it says, backslashes and all`() {
        val g = withAttributes(""""digits": "\\d+"""", """["@digits", "number"]""")
        assertEquals(listOf("12" to name("number")), probed(g, "12"))
    }

    @Test
    fun `a dollar in a regex attribute is an end anchor`() {
        val g = withAttributes(""""last": "x%"""", """["@last", "end"], ["[a-z]", "letter"]""")
        assertEquals(listOf("a" to name("letter"), "x" to name("end")), probed(g, "ax"))
        assertEquals(listOf("xa" to name("letter")), probed(g, "xa"))
    }

    @Test
    fun `a regex attribute can use another, with its backslashes`() {
        val g = withAttributes(""""digit": "\\d", "pair": "@digit@digit"""", """["@pair", "pair"], ["\\d", "single"]""")
        assertEquals(listOf("12" to name("pair"), "3" to name("single")), probed(g, "123"))
    }

    @Test
    fun `a regex attribute works in a guard too`() {
        val g = withAttributes(
            """"digits": "\\d+", "ends": "\\w*x%"""",
            """["\\w+", { "cases": { "~@digits": "number", "!~@ends": "other", "@default": "ending" } }]""",
        )
        assertEquals(listOf("12" to name("number")), probed(g, "12"))
        assertEquals(listOf("ab" to name("other")), probed(g, "ab"))
        assertEquals(listOf("ax" to name("ending")), probed(g, "ax"))
    }

    @Test
    fun `a regex attribute works in a guard that depends on the match`() {
        val g = withAttributes(
            """"digits": "\\d+"""",
            """["\\w+", { "cases": { "~@digits|%S2": "yes", "@default": "no" } }]""",
        )
        assertEquals(listOf("12" to name("yes")), probed(g, "12"))
        assertEquals(listOf("alpha" to name("yes")), probed(g, "alpha"))
        assertEquals(listOf("beta" to name("no")), probed(g, "beta"))
    }

    @Test
    fun `a regex attribute is a group of its own, as in Monarch`() {
        val g = withAttributes(
            """"either": "a|b", "pair": "cd"""",
            """["x@either", "joined"], ["@pair+", "repeated"], ["\\w", "letter"]""",
        )
        assertEquals(listOf("xb" to name("joined")), probed(g, "xb"), "not x, then a, or else b")
        assertEquals(listOf("cdcd" to name("repeated")), probed(g, "cdcd"), "the + repeats all of it")
    }

    @Test
    fun `a guard's pattern can use the match and the state`() {
        assertTrue(holds("%#==%S2", "alpha"))
        assertFalse(holds("%#==%S2", "beta"))
        assertTrue(holds("%S3==%#", "beta"))
        assertTrue(holds("==%S3", "beta"))
        assertFalse(holds("%#!=%S2", "alpha"))
        assertTrue(holds("~%S2|x", "alpha"))
        assertFalse(holds("!~%S2|x", "alpha"))
        assertTrue(holds("%#==a%S1", "aprobe"))
        assertTrue(holds("%#==%%", "\$"), "\$\$ is a \$")
    }

    @Test
    fun `the keys Monarch takes for default`() {
        assertTrue(holds("@", "anything"))
        assertTrue(holds("", "anything"))
    }

    @Test
    fun `a guard can compare with a quote`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [ ["[\"']", { "token": "open", "next": "@text.%#" }] ],
                "text": [ ["x", { "cases": { "%S2==\"": "double", "@default": "single" } }] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(NamedTokenType("double"), tokens(g, "\"x").last().second)
        assertEquals(NamedTokenType("single"), tokens(g, "'x").last().second)
    }

    @Test
    fun `text filled into a guard's regex is literal`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [ ["\\(+", { "token": "open", "next": "@body.%#" }], ["\\s+", ""] ],
                "body": [
                  ["[(a]+", { "cases": { "~%S2": { "token": "close", "next": "@pop" }, "@default": "text" } }],
                  ["\\s+", ""]
                ]
              }
            }
            """.trimIndent(),
        )
        // `((` would not even compile as a regex. The second body has a pattern of its own.
        val text = "(( a (( ( ((  ("
        assertEquals(
            listOf(
                "((" to NamedTokenType("open"),
                "a" to NamedTokenType("text"),
                "((" to NamedTokenType("close"),
                "(" to NamedTokenType("open"),
                "((" to NamedTokenType("text"),
                "(" to NamedTokenType("close"),
            ),
            tokens(g, text),
        )
    }

    @Test
    fun `a guard's regex is checked when the grammar is read, substitutions or not`() {
        assertRejected(
            """{ "tokenizer": { "root": [ ["a", { "cases": { "(?<=a)%S2": "x" } }] ] } }""",
            "lookbehind is not supported",
        )
        assertRejected("""{ "tokenizer": { "root": [ ["a", { "cases": { "(%S2": "x" } }] ] } }""", "not a valid regex")
        assertRejected("""{ "tokenizer": { "root": [ ["a", { "cases": { "%S2@nope": "x" } }] ] } }""", "no array named nope")
    }

    @Test
    fun `next can name a state by the match or the state`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [ ["[a-z]+", { "token": "word", "next": "@inner.%#" }] ],
                "inner": [
                  [":", { "token": "colon", "next": "@deep.%S2" }],
                  ["[a-z]+", { "cases": { "%#==%S2": "same", "@default": "other" } }]
                ],
                "deep": [ ["[a-z]+", { "cases": { "%#==%S2": "kept", "@default": "lost" } }] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "ab" to NamedTokenType("word"),
                ":" to NamedTokenType("colon"),
                "ab" to NamedTokenType("kept"),
            ),
            tokens(g, "ab:ab"),
        )
    }

    @Test
    fun `a state a substitution names that does not exist leaves the stack as it is`() {
        val g = monarch("""{ "tokenizer": { "root": [ ["[a-z]+", { "token": "word", "next": "@missing.%#" }], ["\\s+", ""] ] } }""")
        assertEquals(listOf("ab" to NamedTokenType("word"), "cd" to NamedTokenType("word")), tokens(g, "ab\ncd"))
        val switching = monarch("""{ "tokenizer": { "root": [ ["[a-z]+", { "token": "word", "switchTo": "%#" }], ["\\s+", ""] ] } }""")
        assertEquals(listOf("ab" to NamedTokenType("word"), "cd" to NamedTokenType("word")), tokens(switching, "ab cd"))
    }

    @Test
    fun `a state name made from a match can pick a state with a dot in it`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [ ["[a-z]+", { "token": "word", "next": "@x.%#" }] ],
                "x": [ ["!", "plain"] ],
                "x.end": [ ["!", "ending"] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(NamedTokenType("ending"), tokens(g, "end!").last().second)
        assertEquals(NamedTokenType("plain"), tokens(g, "other!").last().second)
    }

    @Test
    fun `ignoreCase reaches guards and the states a match names`() {
        val g = monarch(
            """
            {
              "ignoreCase": true,
              "tokenizer": {
                "root": [ ["[a-z]+", { "token": "word", "next": "@x.%#" }] ],
                "x": [ ["[a-z]+", { "cases": { "%#==%S2": "same", "@default": "other" } }], ["!", "plain"] ],
                "x.end": [ ["!", "ending"] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(NamedTokenType("ending"), tokens(g, "END!").last().second, "END finds state x.end")
        assertEquals(NamedTokenType("same"), tokens(g, "Open OPEN".replace(" ", "\n")).last().second)
    }

    @Test
    fun `a guard in a group sees the group's text and the state the groups before it left`() {
        val own = monarch(
            """{ "tokenizer": { "root": [ ["(a)(b)", ["x", { "cases": { "%#==b": "hit", "@default": "miss" } }]] ] } }""",
        )
        assertEquals(listOf("a" to NamedTokenType("x"), "b" to NamedTokenType("hit")), tokens(own, "ab"))
        val moved = monarch(
            """
            {
              "tokenizer": {
                "root": [ ["(a)(b)", [{ "token": "x", "next": "@s.found" }, { "cases": { "%S2==found": "hit", "@default": "miss" } }]] ],
                "s": [ ["c", "y"] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("a" to NamedTokenType("x"), "b" to NamedTokenType("hit")), tokens(moved, "ab"))
    }

    @Test
    fun `switchTo replaces the state, so a pop goes past it`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [ ["\\(", "open", "@one"], ["x", "root.x"] ],
                "one": [ ["x", { "token": "one.x", "switchTo": "@two" }] ],
                "two": [ ["\\)", "close", "@pop"], ["x", "two.x"] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "(" to NamedTokenType("open"),
                "x" to NamedTokenType("one.x"),
                "x" to NamedTokenType("two.x"),
                ")" to NamedTokenType("close"),
                "x" to NamedTokenType("root.x"),
            ),
            tokens(g, "(xx)x"),
        )
    }

    @Test
    fun `switchTo wins over next, and works with rematch and in a group`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [
                  ["a", { "token": "a", "next": "@other", "switchTo": "@second" }],
                  ["(?=b)", { "token": "@rematch", "switchTo": "@second" }],
                  ["(c)(d)", ["c", { "token": "d", "switchTo": "@second" }]]
                ],
                "second": [ ["[a-z]", "second"] ],
                "other": [ ["[a-z]", "other"] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("a" to NamedTokenType("a"), "z" to NamedTokenType("second")), tokens(g, "az"))
        assertEquals(listOf("bz" to NamedTokenType("second")), tokens(g, "bz"))
        assertEquals(listOf("c" to NamedTokenType("c"), "d" to NamedTokenType("d"), "z" to NamedTokenType("second")), tokens(g, "cdz"))
    }

    @Test
    fun `popall goes to the bottom of the stack, which switchTo can have replaced`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [ ["a", { "token": "a", "switchTo": "@second" }] ],
                "second": [ ["b", "b", "@third"] ],
                "third": [ ["c", "c", "@popall"] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals("abcb".map { it.toString() to NamedTokenType(it.toString()) }, tokens(g, "abcb"))
    }

    @Test
    fun `a switchTo that cannot be used is an error with where it is`() {
        assertRejected(
            """{ "tokenizer": { "root": [ ["a", { "token": "x", "switchTo": "@nowhere" }] ] } }""",
            "tokenizer.root[0]: enters an unknown state: nowhere",
        )
        assertRejected("""{ "tokenizer": { "root": [ ["a", { "token": "x", "switchTo": "@pop" }] ] } }""", "enters an unknown state: pop")
        assertRejected(
            """{ "tokenizer": { "root": [ ["a", { "token": "x", "switchTo": 1 }] ] } }""",
            "tokenizer.root[0][1].switchTo: switchTo is a state name",
        )
    }

    @Test
    fun `goBack leaves the end of a match to be read again`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [ ["abc", { "token": "x", "goBack": 1, "next": "@rest" }] ],
                "rest": [ ["c", "y", "@pop"] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("ab" to NamedTokenType("x"), "c" to NamedTokenType("y")), tokens(g, "abc"))
    }

    @Test
    fun `eos is judged on the match, before it goes back`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [
                  ["ab", { "cases": { "@eos": { "token": "last", "goBack": 1 }, "@default": "middle" } }],
                  ["b", "b"]
                ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("a" to NamedTokenType("last"), "b" to NamedTokenType("b")), tokens(g, "ab"))
    }

    @Test
    fun `going back over a whole match always moves on`() {
        val g = monarch("""{ "defaultToken": "d", "tokenizer": { "root": [ ["ab", { "token": "x", "goBack": 2 }] ] } }""")
        assertEquals(listOf("ab" to NamedTokenType("d")), tokens(g, "ab"))
        // Two states that would hand each other the same text for ever, going back past the match.
        val bouncing = monarch(
            """
            {
              "defaultToken": "d",
              "tokenizer": {
                "root": [ ["ab", { "token": "x", "goBack": 9, "next": "@other" }] ],
                "other": [ ["ab", { "token": "y", "goBack": 9, "next": "@pop" }] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("zab" to NamedTokenType("d")), tokens(bouncing, "zab"))
    }

    @Test
    fun `a goBack that cannot be used is an error with where it is`() {
        for (value in listOf("-1", "1.5", "\"1\"")) {
            assertRejected(
                """{ "tokenizer": { "root": [ ["a", { "token": "x", "goBack": $value }] ] } }""",
                "tokenizer.root[0][1].goBack: goBack is a number of characters",
            )
        }
        assertRejected(
            """{ "tokenizer": { "root": [ ["(a)(b)", [{ "token": "x", "goBack": 1 }, "y"]] ] } }""",
            "tokenizer.root[0][1][0]: a group's action cannot goBack",
        )
    }

    @Test
    fun `a dollar in a token name is rejected, with where it is`() {
        val message = "a token name cannot use \$"
        assertRejected("""{ "tokenizer": { "root": [ ["a", "string.%S2"] ] } }""", "tokenizer.root[0][1]: $message")
        assertRejected("""{ "tokenizer": { "root": [ ["a", { "token": "%#" }] ] } }""", "tokenizer.root[0][1]: $message")
        assertRejected("""{ "tokenizer": { "root": [ ["a", { "token": "%1.x" }] ] } }""", "tokenizer.root[0][1]: $message")
        assertRejected("""{ "tokenizer": { "root": [ ["a", { "token": "@brackets.%S2" }] ] } }""", message)
        assertRejected(
            """{ "tokenizer": { "root": [ ["a", { "cases": { "@default": "x%S1" } }] ] } }""",
            "tokenizer.root[0][1].cases.@default: $message",
        )
        assertRejected("""{ "tokenizer": { "root": [ ["(a)(b)", ["x", "y%#"]] ] } }""", "tokenizer.root[0][1][1]: $message")
        assertRejected("""{ "defaultToken": "%S2", "tokenizer": { "root": [ ["a", "x"] ] } }""", "defaultToken: $message")
        assertRejected("""{ "tokenPostfix": ".%S2", "tokenizer": { "root": [ ["a", "x"] ] } }""", "tokenPostfix: $message")
        assertRejected("""{ "brackets": [["(", ")", "p%#"]], "tokenizer": { "root": [ ["a", "@brackets"] ] } }""", "brackets[0]: $message")
    }

    @Test
    fun `brackets take Monarch's tokens when a grammar has none of its own`() {
        val g = monarch("""{ "tokenPostfix": ".toy", "tokenizer": { "root": [ ["[{}()\\[\\]<>]", "@brackets"], ["x", "x"] ] } }""")
        assertEquals(
            listOf(
                "{" to NamedTokenType("delimiter.curly.toy"),
                "[" to NamedTokenType("delimiter.square.toy"),
                "(" to NamedTokenType("delimiter.parenthesis.toy"),
                "<" to NamedTokenType("delimiter.angle.toy"),
                "x" to NamedTokenType("x.toy"),
                ">" to NamedTokenType("delimiter.angle.toy"),
                ")" to NamedTokenType("delimiter.parenthesis.toy"),
                "]" to NamedTokenType("delimiter.square.toy"),
                "}" to NamedTokenType("delimiter.curly.toy"),
            ),
            tokens(g, "{[(<x>)]}"),
        )
    }

    @Test
    fun `tokenNames promises every name brackets can produce`() {
        val g = monarch("""{ "tokenPostfix": ".toy", "tokenizer": { "root": [ ["[()]", "@brackets.inner"], ["x", "x"] ] } }""")
        assertEquals(
            setOf(
                "delimiter.curly.toy.inner",
                "delimiter.square.toy.inner",
                "delimiter.parenthesis.toy.inner",
                "delimiter.angle.toy.inner",
                "x.toy",
            ),
            g.tokenNames,
        )
    }

    @Test
    fun `a grammar's own brackets, in either shape`() {
        val g = monarch(
            """
            {
              "ignoreCase": true,
              "defaultToken": "d",
              "brackets": [ ["begin", "end", "keyword.block"], { "open": "(", "close": ")", "token": "paren" } ],
              "tokenizer": { "root": [ ["[a-z]+|[()]", "@brackets"], ["\\s+", ""] ] }
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "BEGIN" to NamedTokenType("keyword.block"),
                "(" to NamedTokenType("paren"),
                ")" to NamedTokenType("paren"),
                "end" to NamedTokenType("keyword.block"),
                "other" to NamedTokenType("d"),
            ),
            tokens(g, "BEGIN ( ) end other"),
        )
        assertEquals(setOf("d", "keyword.block", "paren"), g.tokenNames)
    }

    @Test
    fun `brackets with a suffix, in cases and in groups`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [
                  ["\\(", { "cases": { "@default": "@brackets.inner" } }],
                  ["(\\[)(a)", ["@brackets", "x"]],
                  ["\\{", { "token": "@brackets", "bracket": "@open", "next": "@body" }]
                ],
                "body": [ ["\\}", "@brackets", "@pop"] ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "(" to NamedTokenType("delimiter.parenthesis.inner"),
                "[" to NamedTokenType("delimiter.square"),
                "a" to NamedTokenType("x"),
                "{}" to NamedTokenType("delimiter.curly"),
            ),
            tokens(g, "([a{}"),
        )
    }

    @Test
    fun `brackets that cannot be used are errors with where they are`() {
        val rules = """"tokenizer": { "root": [ ["a", "@brackets"] ] }"""
        assertRejected("""{ "brackets": [["(", "(", "p"]], $rules }""", "brackets[0]: a bracket's open and close have to differ: (")
        assertRejected("""{ "brackets": [["(", ")"]], $rules }""", "brackets[0]: a bracket is")
        assertRejected("""{ "brackets": [{ "open": "(" }], $rules }""", "brackets[0]: a bracket is")
        assertRejected("""{ "brackets": ["()"], $rules }""", "brackets[0]: a bracket is")
        assertRejected("""{ "brackets": "()", $rules }""", "brackets: brackets is an array")
    }

    @Test
    fun `a grammar with no @brackets action can keep brackets as an ordinary array or string`() {
        // Both loaded before @brackets existed, so nothing else may start refusing them.
        val array = monarch(
            """
            {
              "brackets": ["(", ")"],
              "tokenizer": { "root": [ ["[()x]", { "cases": { "@brackets": "delimiter", "@default": "" } }] ] }
            }
            """,
        )
        assertEquals(listOf("(" to NamedTokenType("delimiter"), ")" to NamedTokenType("delimiter")), tokens(array, "(x)"))
        assertEquals(setOf("delimiter"), array.tokenNames)

        val string = monarch("""{ "brackets": "[()]", "tokenizer": { "root": [ ["@brackets", "delimiter"] ] } }""")
        assertEquals(listOf("(" to NamedTokenType("delimiter")), tokens(string, "("))
    }

    @Test
    fun `brackets are only checked by a grammar that uses them, wherever the action is`() {
        val entries = """"brackets": ["(", ")"]"""
        val message = "brackets[0]: a bracket is"
        assertRejected("""{ $entries, "tokenizer": { "root": [ ["a", { "cases": { "@default": "@brackets" } }] ] } }""", message)
        assertRejected("""{ $entries, "tokenizer": { "root": [ ["(a)", ["@brackets.inner"]] ] } }""", message)
        assertRejected("""{ $entries, "tokenizer": { "root": [ ["a", { "token": "@brackets" }] ] } }""", message)
    }

    @Test
    fun `log is accepted, and changes nothing`() {
        val g = monarch("""{ "tokenizer": { "root": [ ["a", { "token": "x", "log": "found %0 in %S0" }] ] } }""")
        assertEquals(listOf("a" to NamedTokenType("x")), tokens(g, "a"))
    }

    // Embedding: `[[` hands the text to `mid` until `]]`, and `mid`'s `<<` to `low` until `>>`.
    private val outer = monarch(
        """
        {
          "tokenizer": {
            "root": [
              ["\\[\\[", { "token": "open", "next": "@inner", "nextEmbedded": "mid" }],
              ["[a-z]+", "word"],
              ["\\s+", ""]
            ],
            "inner": [
              ["[a-z]+", "raw"],
              ["\\]\\]", { "token": "close", "next": "@pop", "nextEmbedded": "@pop" }]
            ]
          }
        }
        """,
    )

    private val mid = monarch(
        """
        {
          "tokenPostfix": ".mid",
          "tokenizer": {
            "root": [
              ["<<", { "token": "open", "next": "@inner", "nextEmbedded": "low" }],
              ["/\\*", "comment", "@comment"],
              ["[a-z]+", { "cases": { "@eos": "last", "@default": "word" } }],
              ["\\s+", ""]
            ],
            "inner": [[">>", { "token": "close", "next": "@pop", "nextEmbedded": "@pop" }]],
            "comment": [["\\*/", "comment", "@pop"], ["[^*]+", "comment"], ["\\*", "comment"]]
          }
        }
        """,
    )

    private val low = monarch(
        """
        {
          "tokenPostfix": ".low",
          "tokenizer": {
            "root": [["[a-z]+", "word"], ["'", "quote", "@quote"], ["\\s+", ""]],
            "quote": [["[^']+", "quote"], ["'", "quote", "@pop"]]
          }
        }
        """,
    )

    private val languages = mapOf("mid" to mid, "low" to low)

    private fun embedding(grammar: DeclarativeGrammar = outer, known: Map<String, DeclarativeGrammar> = languages) =
        DeclarativeTokenizer(grammar) { known[it] }

    private fun embedded(text: String, grammar: DeclarativeGrammar = outer, known: Map<String, DeclarativeGrammar> = languages) =
        embedding(grammar, known).tokenizeFull(text).named(text)

    private fun name(name: String) = NamedTokenType(name)

    @Test
    fun `an embedded grammar reads the text between the rules that enter and leave it`() {
        assertEquals(
            listOf(
                "a" to name("word"),
                "[[" to name("open"),
                "b" to name("word.mid"),
                "<<" to name("open.mid"),
                "c" to name("word.low"),
                ">>" to name("close.mid"),
                "d" to name("word.mid"),
                "]]" to name("close"),
                "e" to name("word"),
            ),
            embedded("a [[b <<c>> d ]] e"),
        )
    }

    @Test
    fun `an embedded grammar's state carries over the lines, and its columns are the line's`() {
        val text = "[[ /* still\nmore */ x\n]] y"
        assertEquals(
            listOf(
                "[[" to name("open"),
                "/* still" to name("comment.mid"),
                "more */" to name("comment.mid"),
                "x" to name("last.mid"),
                "]]" to name("close"),
                "y" to name("word"),
            ),
            embedded(text),
        )
    }

    @Test
    fun `the text a rule leaves on is the parent's, even inside the embedded grammar's own state`() {
        assertEquals(
            listOf("[[" to name("open"), "/* " to name("comment.mid"), "]]" to name("close"), "y" to name("word")),
            embedded("[[ /* ]] y"),
        )
        // And what was open in it is gone: entering again starts it afresh.
        assertEquals("x" to name("last.mid"), embedded("[[ /* ]] [[ x]]").first { it.first == "x" })
    }

    @Test
    fun `an embedded grammar sees only its part of the line`() {
        assertEquals("b" to name("last.mid"), embedded("[[a b]] c").first { it.first == "b" })
    }

    @Test
    fun `an id nobody knows stays on the parent's rules, and its pop does nothing`() {
        val expected = listOf("[[" to name("open"), "b" to name("raw"), "]]" to name("close"), "c" to name("word"))
        assertEquals(expected, tokens(outer, "[[ b ]] c"), "no resolver at all")
        assertEquals(expected, embedded("[[ b ]] c", known = emptyMap()), "a resolver that does not know it")
    }

    @Test
    fun `a state with nothing that leaves stays embedded to the end`() {
        val forever = monarch(
            """{ "tokenizer": { "root": [ ["!", { "token": "open", "next": "@rest", "nextEmbedded": "low" }] ], "rest": [] } }""",
        )
        assertEquals(
            listOf("!" to name("open"), "a" to name("word.low"), "b" to name("word.low")),
            embedded("!a\nb", forever),
        )
    }

    @Test
    fun `entering and leaving can read the text again`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [
                  ["(?=\\{)", { "token": "@rematch", "next": "@body", "nextEmbedded": "braces" }],
                  ["\\}", "closed"],
                  ["[a-z]+", "word"]
                ],
                "body": [["(?=\\})", { "token": "@rematch", "next": "@pop", "nextEmbedded": "@pop" }]]
              }
            }
            """,
        )
        val braces = monarch("""{ "tokenizer": { "root": [ ["\\{", "brace"], ["[a-z]+", "inner"] ] } }""")
        assertEquals(
            listOf("a" to name("word"), "{" to name("brace"), "b" to name("inner"), "}" to name("closed"), "c" to name("word")),
            embedded("a{b}c", g, mapOf("braces" to braces)),
        )
    }

    @Test
    fun `leaving comes before next, entering after it`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [["\\[\\[", { "token": "open", "switchTo": "@inner", "nextEmbedded": "low" }]],
                "inner": [["\\]\\]", { "token": "close", "switchTo": "@after", "nextEmbedded": "@pop" }]],
                "after": [["[a-z]+", "tail"], ["\\[\\[", { "token": "open", "next": "@inner", "nextEmbedded": "low" }]]
              }
            }
            """,
        )
        assertEquals(
            listOf(
                "[[" to name("open"),
                "a" to name("word.low"),
                "]]" to name("close"),
                "b" to name("tail"),
                "[[" to name("open"),
                "c" to name("word.low"),
                "]]" to name("close"),
                "d" to name("tail"),
            ),
            embedded("[[a]]b[[c]]d", g),
        )
    }

    @Test
    fun `a language can be entered and left with no next, staying in the one state`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [
                  ["!", { "token": "open", "nextEmbedded": "low" }],
                  [";", { "token": "end", "nextEmbedded": "@pop" }],
                  ["[a-z]+", "own"]
                ]
              }
            }
            """,
        )
        assertEquals(
            listOf("a" to name("own"), "!" to name("open"), "b" to name("word.low"), ";" to name("end"), "c" to name("own")),
            embedded("a!b;c", g),
        )
    }

    @Test
    fun `the id can come from the match or the state`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [
                  ["low|mid", { "token": "name", "next": "@body", "nextEmbedded": "%#" }],
                  ["<([a-z]+)>", { "token": "tag", "next": "@named.low" }],
                  ["#([a-z]+)", { "token": "number", "next": "@body", "nextEmbedded": "%1" }]
                ],
                "named": [["=", { "token": "is", "switchTo": "@body", "nextEmbedded": "%S2" }]],
                "body": [["[a-z]+", "raw"], [";", { "token": "end", "next": "@pop", "nextEmbedded": "@pop" }]]
              }
            }
            """,
        )
        assertEquals(listOf("low" to name("name"), "a" to name("word.low"), ";" to name("end")), embedded("low a;", g))
        assertEquals(listOf("mid" to name("name"), "a" to name("last.mid"), ";" to name("end")), embedded("mid a;", g))
        assertEquals(listOf("<x>" to name("tag"), "=" to name("is"), "a" to name("word.low"), ";" to name("end")), embedded("<x>=a;", g))
        // `$1` is not supported: it stays literal, so it names nothing, and the parent's rules read on.
        val asked = mutableListOf<String>()
        val text = "#x a;"
        assertEquals(
            listOf("#x" to name("number"), "a" to name("raw"), ";" to name("end")),
            DeclarativeTokenizer(g) {
                asked += it
                null
            }.tokenizeFull(text).named(text),
        )
        assertEquals(listOf("\$1"), asked)
    }

    @Test
    fun `a rule that leaves only in some cases is looked past in the others`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [["\\[\\[", { "token": "open", "next": "@inner", "nextEmbedded": "low" }], ["[a-z]+", "after"]],
                "inner": [["[a-z]+", { "cases": {
                  "end": { "token": "close", "next": "@pop", "nextEmbedded": "@pop" },
                  "@default": "raw"
                } }]]
              }
            }
            """,
        )
        // `end` inside `bend` is not a match the rule would have made.
        assertEquals(
            listOf("[[" to name("open"), "bend" to name("word.low"), "x" to name("word.low"), "end" to name("close"), "y" to name("after")),
            embedded("[[ bend x end y", g),
        )
    }

    @Test
    fun `the earliest rule to leave wins, and the first of those at one place`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [
                  ["!", { "token": "open", "next": "@inner", "nextEmbedded": "low" }],
                  ["[a-z]+", "after"]
                ],
                "inner": [
                  ["\\.\\.", { "token": "two", "next": "@pop", "nextEmbedded": "@pop" }],
                  ["\\.", { "token": "one", "next": "@pop", "nextEmbedded": "@pop" }],
                  [";", { "token": "semi", "next": "@pop", "nextEmbedded": "@pop" }]
                ]
              }
            }
            """,
        )
        assertEquals(listOf("!" to name("open"), "a" to name("word.low"), ";" to name("semi"), "b" to name("after")), embedded("!a;b..", g))
        assertEquals(name("two"), embedded("!a..b", g)[2].second)
        assertEquals(name("one"), embedded("!a.b..", g)[2].second)
    }

    @Test
    fun `nextEmbedded works in a group's action, after the whole match`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [
                  ["(\\[)(x)(\\[)", ["open", { "token": "tag", "next": "@inner", "nextEmbedded": "low" }, "open"]],
                  ["[a-z]+", "after"]
                ],
                "inner": [["(\\])(!)", [{ "token": "close", "next": "@pop", "nextEmbedded": "@pop" }, "bang"]]]
              }
            }
            """,
        )
        assertEquals(
            listOf(
                "[" to name("open"),
                "x" to name("tag"),
                "[" to name("open"),
                "a" to name("word.low"),
                "]" to name("close"),
                "!" to name("bang"),
                "b" to name("after"),
            ),
            embedded("[x[a]!b", g),
        )
    }

    @Test
    fun `a group's nextEmbedded goes in on the state the whole match arrives at`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [
                  ["(\\[)(\\[)", ["open", { "token": "open", "nextEmbedded": "low" }], "@inner"],
                  ["(<)(<)", [{ "token": "open", "nextEmbedded": "low" }, { "token": "open", "next": "@inner" }]],
                  ["[a-z]+", "after"]
                ],
                "inner": [["\\]\\]", { "token": "close", "next": "@pop", "nextEmbedded": "@pop" }]]
              }
            }
            """,
        )
        val expected = { open: String ->
            listOf(open to name("open"), "a" to name("word.low"), "]]" to name("close"), "b" to name("after"))
        }
        assertEquals(expected("[["), embedded("[[a]]b", g), "the rule's next")
        assertEquals(expected("<<"), embedded("<<a]]b", g), "a later group's next")
    }

    @Test
    fun `a group's nextEmbedded leaves even after an earlier group's next has moved on`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [["\\[\\[", { "token": "open", "next": "@inner", "nextEmbedded": "low" }]],
                "inner": [["(\\])(!)", [{ "token": "mark", "next": "@tail" }, { "token": "close", "nextEmbedded": "@pop" }]], ["[a-z]+", "raw"]],
                "tail": [["[a-z]+", "tail", "@pop"]]
              }
            }
            """,
        )
        assertEquals(
            listOf(
                "[[" to name("open"),
                "a" to name("word.low"),
                "]" to name("mark"),
                "!" to name("close"),
                "b" to name("tail"),
                "c" to name("raw"),
            ),
            embedded("[[a]!b c", g),
        )
    }

    @Test
    fun `groups that turn out not to leave are the parent's, and the language reads on after them`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [["\\[\\[", { "token": "open", "next": "@inner", "nextEmbedded": "low" }], ["[a-z]+", "after"]],
                "inner": [["(\\])(!|\\?)", ["mark", { "cases": {
                  "!": { "token": "close", "next": "@pop", "nextEmbedded": "@pop" },
                  "@default": "skip"
                } }]]]
              }
            }
            """,
        )
        assertEquals(
            listOf(
                "[[" to name("open"),
                "a" to name("word.low"),
                "]" to name("mark"),
                "?" to name("skip"),
                "b" to name("word.low"),
                "]" to name("mark"),
                "!" to name("close"),
                "c" to name("after"),
            ),
            embedded("[[a]?b]!c", g),
        )
    }

    @Test
    fun `an empty line goes to the embedded grammar, the end of the line it was entered on does not`() {
        val g =
            monarch("""{ "tokenizer": { "root": [ ["!", { "token": "open", "next": "@rest", "nextEmbedded": "lines" }] ], "rest": [] } }""")
        val lines = monarch(
            """
            {
              "tokenizer": {
                "root": [["^$", { "token": "", "next": "@blank" }], ["[a-z]+", "word"]],
                "blank": [["[a-z]+", "afterBlank"]]
              }
            }
            """,
        )
        val known = mapOf("lines" to lines)
        assertEquals(listOf("!" to name("open"), "x" to name("word")), embedded("!\nx", g, known))
        assertEquals(listOf("!" to name("open"), "x" to name("afterBlank")), embedded("!\n\nx", g, known))
    }

    /** `(` enters the next of [count] grammars and `)` leaves it. Each has block comments, and `{` pushes. */
    private fun chain(count: Int): Map<String, DeclarativeGrammar> = (0 until count).associate { level ->
        "l$level" to monarch(
            """
            {
              "tokenPostfix": ".l$level",
              "tokenizer": {
                "root": [
                  ["\\(", { "token": "open", "next": "@inner", "nextEmbedded": "l${level + 1}" }],
                  ["\\{", "brace", "@push"],
                  ["\\}", "brace", "@pop"],
                  ["/\\*", "comment", "@comment"],
                  ["[a-z]+", "word"],
                  ["\\s+", ""]
                ],
                "inner": [["\\)", { "token": "close", "next": "@pop", "nextEmbedded": "@pop" }]],
                "comment": [["\\*/", "comment", "@pop"], ["[^*]+", "comment"], ["\\*", "comment"]]
              }
            }
            """,
        )
    }

    @Test
    fun `languages nest as far as the limit, and an id past it counts as unknown`() {
        val known = chain(20)
        val top = known.getValue("l0")
        assertEquals("x" to name("word.l16"), embedded("(".repeat(16) + "x", top, known).last())
        // One more: l16 enters no l17, so it stays on its own rules, and `inner` has none for `x`.
        assertTrue(embedded("(".repeat(17) + "x", top, known).none { it.first == "x" })
    }

    @Test
    fun `a grammar that embeds itself stops, even on a match that takes no text`() {
        val nesting = monarch(
            """
            {
              "tokenizer": {
                "root": [["\\(", { "token": "open", "next": "@inner", "nextEmbedded": "self" }], ["[a-z]+", "word"]],
                "inner": [["\\)", { "token": "close", "next": "@pop", "nextEmbedded": "@pop" }]]
              }
            }
            """,
        )
        val text = "(".repeat(5000) + "x" + ")".repeat(5000)
        assertEquals("(".repeat(17) to name("open"), embedded(text, nesting, mapOf("self" to nesting)).first())

        val endless = monarch(
            """
            {
              "tokenizer": {
                "root": [["(?=x)", { "token": "@rematch", "nextEmbedded": "self" }], ["[a-z]+", "word"]]
              }
            }
            """,
        )
        // Each level enters the next without taking text. The last can only move on by a character.
        assertEquals(listOf("y" to name("word")), embedded("xy", endless, mapOf("self" to endless)))

        val bouncing = monarch(
            """
            {
              "tokenizer": {
                "root": [
                  ["(?=x)", { "cases": {
                    "%S2==in": { "token": "@rematch", "switchTo": "@root.out", "nextEmbedded": "@pop" },
                    "@default": { "token": "@rematch", "switchTo": "@root.in", "nextEmbedded": "low" }
                  } }],
                  ["[a-z]", "word"]
                ]
              }
            }
            """,
        )
        // Entering and leaving on one spot, for ever, if nothing stopped it. It has to reach `y`.
        assertEquals("y", embedded("xy", bouncing).last().first)
    }

    @Test
    fun `a rescan stops at its line through deep stacks and sixteen languages`() {
        val known = chain(17)
        val top = known.getValue("l0")
        val body = List(300) { "xy" }.joinToString("\n")
        val text = "(".repeat(16) + "{".repeat(500) + "\n" + body + "\n" + "}".repeat(500) + ")".repeat(16) + "\nz"
        val tokenizer = embedding(top, known)
        val full = tokenizer.tokenizeFull(text)
        assertEquals(name("word.l16"), full.first { text.substring(it.start, it.end) == "xy" }.type)

        val line = 151
        val lineStart = text.split('\n').take(line).sumOf { it.length + 1 }
        val edited = text.substring(0, lineStart) + "new " + text.substring(lineStart)
        val partial = tokenizer.tokenizeLines(edited, line..line, full)
        val lineEnd = edited.indexOf('\n', lineStart)
        assertTrue(partial.all { it.start >= lineStart && it.end <= lineEnd }, "only line $line: $partial")
        assertEquals(name("word.l16"), partial.first { it.end > it.start }.type)

        // A comment opened in the innermost language runs on, and one closed at once does not.
        val opened = text.substring(0, lineStart) + "/*" + text.substring(lineStart)
        val toEnd = tokenizer.tokenizeLines(opened, line..line, full)
        assertTrue(toEnd.last().end > opened.lastIndexOf("xy"), "the comment reaches the lines below: ${toEnd.last()}")
        val closed = text.substring(0, lineStart) + "/**/" + text.substring(lineStart)
        assertEquals(closed.indexOf('\n', lineStart), tokenizer.tokenizeLines(closed, line..line, full).last().end)

        // The outermost language looks first, so this leaves all sixteen at once, for every line below.
        val left = text.substring(0, lineStart) + ")" + text.substring(lineStart)
        val leftTokens = tokenizer.tokenizeLines(left, line..line, full)
        assertTrue(leftTokens.last().end > left.indexOf('\n', lineStart), "the state changed, so the rescan goes on")
        assertEquals("xy" to name("word.l0"), leftTokens.named(left).first { it.first == "xy" })
    }

    @Test
    fun `an edit that only changes which language has the text still rescans what follows`() {
        val g = monarch(
            """
            {
              "tokenizer": {
                "root": [["low|mid", { "token": "name", "next": "@body", "nextEmbedded": "%#" }], ["\\s+", ""]],
                "body": [[";", { "token": "end", "next": "@pop", "nextEmbedded": "@pop" }]]
              }
            }
            """,
        )
        // Both languages are in a state named `root` at the end of each line: only the grammar differs.
        val tokenizer = embedding(g)
        val full = tokenizer.tokenizeFull("low\nab c\nd ;")
        val edited = "mid\nab c\nd ;"
        assertEquals(
            listOf("mid" to name("name"), "ab" to name("word.mid"), "c" to name("last.mid"), "d" to name("word.mid"), ";" to name("end")),
            tokenizer.tokenizeLines(edited, 0..0, full).named(edited),
        )
    }

    @Test
    fun `a rescan compares stacks far too deep to walk by recursion`() {
        val g = monarch("""{ "tokenizer": { "root": [ ["\\(", "open", "@push"], ["[a-z]", "word"] ] } }""")
        val tokenizer = DeclarativeTokenizer(g)
        val nested = "(".repeat(50_000)
        val text = "a$nested\nb\nc"
        val full = tokenizer.tokenizeFull(text)
        // The same depth, every state made anew: only comparing all of them shows the stacks match.
        val partial = tokenizer.tokenizeLines("x$nested\nb\nc", 0..0, full)
        assertEquals(text.indexOf('\n'), partial.last().end)
    }

    @Test
    fun `an incremental pass over embedded grammars ends where a full pass does`() {
        val pieces = listOf("[[", "]]", "<<", ">>", "/*", "*/", "'", "\n", "\n", " ", "a", "bc")
        for (seed in 1..4) assertIncrementalMatchesFull(outer, pieces, seed, languages)
    }

    @Test
    fun `an incremental pass over nesting that reaches the limit ends where a full pass does`() {
        val known = chain(20)
        val pieces = listOf("(", "(", "(", ")", "{", "}", "/*", "*/", "\n", " ", "a")
        for (seed in 1..4) assertIncrementalMatchesFull(known.getValue("l0"), pieces, seed, known)
    }

    @Test
    fun `the cooperative pass reads embedded grammars the same`() = runTest {
        val text = "a [[ b <<c\n/* d'\ne>> f ]] g"
        assertEquals(embedding().tokenizeFull(text), embedding().tokenizeFullCooperative(text))
    }

    @Test
    fun `a grammar the resolver comes to know is found the next time the text is read`() {
        var known = emptyMap<String, DeclarativeGrammar>()
        val tokenizer = DeclarativeTokenizer(outer) { known[it] }
        val text = "[[ b ]]"
        assertEquals(name("raw"), tokenizer.tokenizeFull(text).named(text)[1].second)
        known = languages
        assertEquals(name("word.mid"), tokenizer.tokenizeFull(text).named(text)[1].second)
    }

    @Test
    fun `a rescan after the resolver forgets a grammar goes back to the parent's rules`() {
        var known = languages
        val tokenizer = DeclarativeTokenizer(outer) { known[it] }
        val text = "[[ a\nb\nc ]] d"
        val full = tokenizer.tokenizeFull(text)
        known = emptyMap()
        val edited = "[[ a\nbb\nc ]] d"
        val partial = tokenizer.tokenizeLines(edited, 1..1, full).named(edited)
        assertEquals(listOf("bb" to name("raw"), "c" to name("raw"), "]]" to name("close"), "d" to name("word")), partial)
    }

    @Test
    fun `the line a forgotten grammar was reading goes to the parent's rules, up to where it leaves too`() {
        var known = languages
        val tokenizer = DeclarativeTokenizer(outer) { known[it] }
        val full = tokenizer.tokenizeFull("[[ a\nb ]] c")
        known = emptyMap()
        val edited = "[[ a\nbb ]] c"
        assertEquals(
            listOf("bb" to name("raw"), "]]" to name("close"), "c" to name("word")),
            tokenizer.tokenizeLines(edited, 1..1, full).named(edited),
        )
    }

    @Test
    fun `a grammar's token names are its own, not those of what it embeds`() {
        assertEquals(setOf("open", "word", "raw", "close"), outer.tokenNames)
        assertEquals(setOf("open.mid", "close.mid", "comment.mid", "last.mid", "word.mid"), mid.tokenNames)
    }

    @Test
    fun `a grammar that only names nextEmbedded parses without a resolver and keeps its old tokens`() {
        val g =
            monarch(
                """{ "tokenizer": { "root": [ ["a", { "token": "x", "nextEmbedded": "text/javascript" }], ["b", { "token": "y", "nextEmbedded": "@pop" }] ] } }""",
            )
        assertEquals(listOf("a" to name("x"), "b" to name("y")), tokens(g, "ab"))
    }

    @Test
    fun `a nextEmbedded that cannot be used is an error with where it is`() {
        val message = "tokenizer.root[0][1].nextEmbedded: nextEmbedded is a language's id or @pop"
        assertRejected("""{ "tokenizer": { "root": [ ["a", { "token": "x", "nextEmbedded": 1 }] ] } }""", message)
        assertRejected("""{ "tokenizer": { "root": [ ["a", { "token": "x", "nextEmbedded": "" }] ] } }""", message)
    }
}
