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
    private fun assertIncrementalMatchesFull(grammar: DeclarativeGrammar, pieces: List<String>, seed: Int) {
        val tokenizer = DeclarativeTokenizer(grammar)
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
            val expected = DeclarativeTokenizer(grammar).tokenizeFull(next).filter { it.end > it.start }
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
}
