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
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.FoldKind
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.languages.LanguageConfiguration.AutoClosingPair
import com.aardarch.aardink.languages.LanguageConfiguration.CharacterPair
import com.aardarch.aardink.languages.LanguageConfiguration.IndentAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `language-configuration.json` read by [LanguageConfiguration.parse] and applied by [LanguageDefinition.withConfiguration]. */
class LanguageConfigurationTest {

    /** A language with a grammar only, as the configuration's mechanism is for. */
    private val bare = LanguageDefinition("bare", "Bare", listOf("bare"), PlainTextTokenizer)

    private fun service(json: String): LanguageService =
        assertNotNull(bare.withConfiguration(LanguageConfiguration.parse(json)).languageService)

    /** A document from [text] with `|` where the caret is, and that offset. */
    private fun at(text: String): Pair<CodeDocument, Int> = CodeDocument(text.replace("|", "")) to text.indexOf('|')

    /** What the service closes [typed] with, typed at the `|` of [text]. */
    private fun LanguageService.closing(text: String, typed: Char): String? {
        val (document, caret) = at(text.replace("|", "|$typed"))
        return autoClose(document, caret, typed)
    }

    /** What the line starts with that Enter makes at the `|` of [text]. */
    private fun LanguageService.enter(text: String, options: FormattingOptions = FormattingOptions()): String {
        val (document, caret) = at(text.replace("|", "|\n"))
        return smartIndent(document, document.offsetToLineCol(caret + 1).first, options)
    }

    private fun refused(json: String): String = assertFailsWith<IllegalArgumentException> { LanguageConfiguration.parse(json) }.message!!

    // ── Parsing ───────────────────────────────────────────────────────────────

    @Test
    fun `a VS Code file parses, comments and trailing commas included`() {
        val configuration = LanguageConfiguration.parse(
            """
            // A language.
            {
              "comments": { "lineComment": "//", "blockComment": ["/*", "*/"] }, /* both kinds */
              "brackets": [["{", "}"], ["[", "]"],],
              "autoClosingPairs": [
                ["{", "}"],
                { "open": "'", "close": "'", "notIn": ["string", "comment"] },
                { "open": "/**", "close": " */", "notIn": ["string"], },
              ],
              "surroundingPairs": [["'", "'"], { "open": "(", "close": ")" }],
              "wordPattern": "(?<=never)read",
              "autoCloseBefore": ";",
              "folding": { "offSide": true, "markers": { "start": "^\\s*//\\s*#region", "end": "^\\s*//\\s*#endregion" } },
              "indentationRules": {
                "increaseIndentPattern": "\\{\\s*$",
                "decreaseIndentPattern": { "pattern": "^\\s*END", "flags": "i" },
                "indentNextLinePattern": "^if$",
                "unIndentedLinePattern": "^//"
              },
              "onEnterRules": [
                {
                  "beforeText": "^\\s*/\\*\\*",
                  "afterText": { "pattern": "^\\s*\\*/$" },
                  "previousLineText": "^$",
                  "action": { "indent": "indentOutdent", "appendText": " * ", "removeText": 1 },
                },
              ],
            }
            """.trimIndent(),
        )
        assertEquals(CommentSyntax("//", "/*", "*/"), configuration.comments)
        assertEquals(listOf(CharacterPair("{", "}"), CharacterPair("[", "]")), configuration.brackets)
        assertEquals(
            listOf(
                AutoClosingPair("{", "}"),
                AutoClosingPair("'", "'", notInString = true, notInComment = true),
                AutoClosingPair("/**", " */", notInString = true),
            ),
            configuration.autoClosingPairs,
        )
        assertEquals(listOf(CharacterPair("'", "'"), CharacterPair("(", ")")), configuration.surroundingPairs)
        assertTrue(configuration.foldingMarkers!!.start.containsMatchIn("  // #region a"))
        assertTrue(configuration.indentationRules!!.decrease!!.containsMatchIn("  end"), "the i flag")
        val rule = configuration.onEnterRules.single()
        assertEquals(IndentAction.IndentOutdent, rule.action.indent)
        assertEquals(" * ", rule.action.appendText)
        assertEquals(1, rule.action.removeText)
        assertTrue(rule.afterText!!.containsMatchIn(" */"))
    }

    @Test
    fun `without autoClosingPairs the brackets close, and without surroundingPairs those pairs surround`() {
        val fromBrackets = LanguageConfiguration.parse("""{ "brackets": [["(", ")"]] }""")
        assertEquals(listOf(AutoClosingPair("(", ")")), fromBrackets.autoClosingPairs)
        assertEquals(listOf(CharacterPair("(", ")")), fromBrackets.surroundingPairs)
        val fromPairs = LanguageConfiguration.parse("""{ "brackets": [["(", ")"]], "autoClosingPairs": [["[", "]"]] }""")
        assertEquals(listOf(CharacterPair("[", "]")), fromPairs.surroundingPairs)
        val none = LanguageConfiguration.parse("""{ "brackets": [["(", ")"]], "autoClosingPairs": [], "surroundingPairs": [] }""")
        assertTrue(none.autoClosingPairs.isEmpty(), "an empty list is an answer")
        assertTrue(none.surroundingPairs.isEmpty())
    }

    @Test
    fun `an empty file says nothing, and a null is as good as absent`() {
        assertEquals(LanguageConfiguration(), LanguageConfiguration.parse("{}"))
        assertEquals(LanguageConfiguration(), LanguageConfiguration.parse("""{ "comments": null, "brackets": null, "folding": {} }"""))
    }

    @Test
    fun `VS Code's object form of a line comment gives its prefix`() {
        val comments = LanguageConfiguration.parse("""{ "comments": { "lineComment": { "comment": "#", "noIndent": true } } }""").comments
        assertEquals(CommentSyntax(line = "#"), comments)
    }

    @Test
    fun `a lookbehind is refused wherever a regex goes, saying where`() {
        fun assertRefused(path: String, json: String) {
            assertEquals("$path: lookbehind is not supported: (?<=a)b", refused(json))
        }
        assertRefused(
            "onEnterRules[1].beforeText",
            """{ "onEnterRules": [{ "action": { "indent": "none" } }, { "beforeText": "(?<=a)b", "action": { "indent": "none" } }] }""",
        )
        assertRefused(
            "onEnterRules[0].afterText",
            """{ "onEnterRules": [{ "afterText": { "pattern": "(?<=a)b" }, "action": { "indent": "none" } }] }""",
        )
        assertRefused(
            "onEnterRules[0].previousLineText",
            """{ "onEnterRules": [{ "previousLineText": "(?<=a)b", "action": { "indent": "none" } }] }""",
        )
        assertRefused("indentationRules.increaseIndentPattern", """{ "indentationRules": { "increaseIndentPattern": "(?<=a)b" } }""")
        assertRefused("indentationRules.decreaseIndentPattern", """{ "indentationRules": { "decreaseIndentPattern": "(?<=a)b" } }""")
        assertRefused("indentationRules.indentNextLinePattern", """{ "indentationRules": { "indentNextLinePattern": "(?<=a)b" } }""")
        assertRefused("indentationRules.unIndentedLinePattern", """{ "indentationRules": { "unIndentedLinePattern": "(?<=a)b" } }""")
        assertRefused("folding.markers.start", """{ "folding": { "markers": { "start": "(?<=a)b", "end": "x" } } }""")
        assertRefused("folding.markers.end", """{ "folding": { "markers": { "start": "x", "end": "(?<=a)b" } } }""")
        assertEquals(
            "indentationRules.increaseIndentPattern: lookbehind is not supported: (?<!a)b",
            refused("""{ "indentationRules": { "increaseIndentPattern": "(?<!a)b" } }"""),
        )
    }

    @Test
    fun `what it cannot use is refused, saying where`() {
        assertTrue(refused("{ nope").startsWith("Not JSON"))
        assertEquals("A language configuration is a JSON object", refused("[]"))
        assertEquals("brackets: not an array", refused("""{ "brackets": 1 }"""))
        assertEquals("brackets[0]: a pair is [open, close]", refused("""{ "brackets": [["("]] }"""))
        assertEquals("brackets[0]: a pair is [open, close] or { open, close }", refused("""{ "brackets": ["("] }"""))
        assertEquals("brackets[0][1]: not a string", refused("""{ "brackets": [["(", 1]] }"""))
        assertEquals("surroundingPairs[0]: a pair's open and close are not empty", refused("""{ "surroundingPairs": [["", ")"]] }"""))
        assertEquals("autoClosingPairs[1]: needs \"close\"", refused("""{ "autoClosingPairs": [["(", ")"], { "open": "[" }] }"""))
        assertEquals(
            "autoClosingPairs[0].notIn: not an array",
            refused("""{ "autoClosingPairs": [{ "open": "[", "close": "]", "notIn": "string" }] }"""),
        )
        assertEquals("comments.blockComment: a pair is [open, close]", refused("""{ "comments": { "blockComment": ["/*"] } }"""))
        assertEquals("comments.lineComment: not a string", refused("""{ "comments": { "lineComment": 1 } }"""))
        assertEquals("onEnterRules[0]: a rule needs an action", refused("""{ "onEnterRules": [{ "beforeText": "a" }] }"""))
        assertEquals("onEnterRules[0].action: needs \"indent\"", refused("""{ "onEnterRules": [{ "action": {} }] }"""))
        assertEquals(
            "onEnterRules[0].action.indent: none, indent, outdent or indentOutdent, not 'sideways'",
            refused("""{ "onEnterRules": [{ "action": { "indent": "sideways" } }] }"""),
        )
        assertEquals(
            "onEnterRules[0].action.removeText: a number of characters",
            refused("""{ "onEnterRules": [{ "action": { "indent": "none", "removeText": -1 } }] }"""),
        )
        assertEquals("folding.markers: markers need an end", refused("""{ "folding": { "markers": { "start": "a" } } }"""))
        assertTrue(
            refused(
                """{ "indentationRules": { "increaseIndentPattern": "(" } }""",
            ).startsWith("indentationRules.increaseIndentPattern: not a regex"),
        )
    }

    // ── autoClosingPairs ─────────────────────────────────────────────────────

    private val closes = """
        {
          "comments": { "lineComment": "//", "blockComment": ["/*", "*/"] },
          "autoClosingPairs": [
            ["(", ")"],
            ["/*", " */"],
            ["/**", " */"],
            { "open": "\"", "close": "\"", "notIn": ["string"] },
            { "open": "'", "close": "'", "notIn": ["string", "comment"] },
            { "open": "{", "close": "}", "notIn": ["comment"] }
          ]
        }
    """.trimIndent()

    @Test
    fun `a pair closes, and a character of no pair does not`() {
        val service = service(closes)
        assertEquals(")", service.closing("a|", '('))
        assertEquals(")", service.closing("|", '('))
        assertNull(service.closing("a|", 'x'))
    }

    @Test
    fun `an opening of several characters closes when the line ends in it`() {
        val service = service(closes)
        assertEquals(" */", service.closing("/|", '*'))
        assertNull(service.closing("a|", '*'), "only the end of the opening was typed")
        assertEquals(" */", service.closing("/*|", '*'), "the longest fits, and no end follows")
    }

    @Test
    fun `a longer opening over a shorter one's end does not close twice`() {
        assertNull(service(closes).closing("/*| */", '*'))
    }

    @Test
    fun `a pair closes before what ends an expression, not before a word`() {
        val service = service(closes)
        assertEquals(")", service.closing("|)", '('))
        assertEquals(")", service.closing("| x", '('))
        assertEquals(")", service.closing("|;", '('))
        assertEquals(")", service.closing("|\"", '('))
        assertEquals(")", service.closing("|}", '('), "a closing of the language's own")
        assertNull(service.closing("|word", '('))
        assertNull(service.closing("|1", '('))
        assertNull(service.closing("|\"", '"'), "a quote does not close before a quote")
    }

    @Test
    fun `an apostrophe after a word does not close`() {
        val service = service(closes)
        assertNull(service.closing("don|", '\''))
        assertEquals("'", service.closing("say |", '\''))
    }

    @Test
    fun `notIn reads the caret's line up to it`() {
        val service = service(closes)
        assertNull(service.closing("a = \"b |", '\''), "in a string")
        assertEquals("'", service.closing("a = \"b\" + |", '\''), "after one")
        assertNull(service.closing("a = \"b\\\" |", '\''), "an escaped quote leaves it open")
        assertNull(service.closing("// x |", '\''), "in a line comment")
        assertNull(service.closing("/* x |", '\''), "in a block comment")
        assertEquals("'", service.closing("/* x */ |", '\''), "after one")
        assertEquals("}", service.closing("\"a |", '{'), "notIn lists only the comment")
        assertNull(service.closing("// a |", '{'))
        assertEquals("\"", service.closing("// a |", '"'), "notIn lists only the string")
        assertEquals("'", service.closing("\"//\" |", '\''), "not a comment in a string")
        assertNull(service.closing("// it's |", '{'), "nor a string in a comment")
    }

    @Test
    fun `the opening itself does not count as what it is in`() {
        val service =
            service(
                """{ "comments": { "blockComment": ["/*", "*/"] }, "autoClosingPairs": [{ "open": "/*", "close": "*/", "notIn": ["comment"] }] }""",
            )
        assertEquals("*/", service.closing("/|", '*'))
    }

    @Test
    fun `without comments a tokenizer's own serve the line scan`() {
        val configuration = LanguageConfiguration.parse(
            """ { "autoClosingPairs": [{ "open": "(", "close": ")", "notIn": ["comment"] }] } """,
        )
        val service = BuiltInLanguages.Toml.copy(languageService = null).withConfiguration(configuration).languageService!!
        assertNull(service.closing("# a |", '('))
        assertEquals(")", service.closing("a |", '('))
    }

    @Test
    fun `a carriage return is not part of the line`() {
        val document = CodeDocument("(\r\nb")
        assertEquals(")", service(closes).autoClose(document, 0, '('), "the end of a line in a CR LF document")
    }

    // ── surroundingPairs ─────────────────────────────────────────────────────

    @Test
    fun `surroundingPairs answer by their one-character opening`() {
        val service = service("""{ "surroundingPairs": [["(", ")"], ["'", "'"], ["<!--", "-->"]] }""")
        assertEquals(")", service.surroundingClose('('))
        assertEquals("'", service.surroundingClose('\''))
        assertNull(service.surroundingClose('<'))
        assertNull(service.surroundingClose('['))
    }

    // ── onEnterRules ──────────────────────────────────────────────────────────

    private fun rule(action: String, conditions: String = "\"beforeText\": \"x\"") =
        """{ "onEnterRules": [{ $conditions, "action": $action }] }"""

    @Test
    fun `none keeps the line's indentation, indent adds a level, outdent takes one`() {
        assertEquals("    ", service(rule("""{ "indent": "none" }""")).enter("    x|"))
        assertEquals("        ", service(rule("""{ "indent": "indent" }""")).enter("    x|"))
        assertEquals("", service(rule("""{ "indent": "outdent" }""")).enter("    x|"))
        assertEquals("", service(rule("""{ "indent": "outdent" }""")).enter("x|"), "never below none")
    }

    @Test
    fun `the indentation is tabs or spaces as the editor is set up`() {
        val tabs = FormattingOptions(tabSize = 4, insertSpaces = false)
        assertEquals("\t\t", service(rule("""{ "indent": "indent" }""")).enter("\tx|", tabs))
        assertEquals("    ", service(rule("""{ "indent": "indent" }""")).enter("  x|", FormattingOptions(tabSize = 2)))
    }

    @Test
    fun `appendText follows the indentation`() {
        assertEquals("  * ", service(rule("""{ "indent": "none", "appendText": "* " }""")).enter("  x|"))
        assertEquals(
            "      // ",
            service(rule("""{ "indent": "indent", "appendText": "// " }""")).enter("  x|", FormattingOptions(tabSize = 4)),
        )
    }

    @Test
    fun `removeText takes characters off the indentation first`() {
        assertEquals("  ", service(rule("""{ "indent": "none", "removeText": 1 }""")).enter("   x|"))
        assertEquals("", service(rule("""{ "indent": "none", "removeText": 9 }""")).enter("   x|"))
        assertEquals(" * ", service(rule("""{ "indent": "none", "removeText": 1, "appendText": "* " }""")).enter("  x|"))
    }

    @Test
    fun `indentOutdent leaves the closer at the opener's indentation, adding no text`() {
        val service =
            service(rule("""{ "indent": "indentOutdent", "appendText": " * " }""", """"beforeText": "\\{$", "afterText": "^\\}""""))
        assertEquals("    ", service.enter("    {|}"))
    }

    @Test
    fun `a rule needs every regex to match, and the first that does wins`() {
        val service = service(
            """
            {
              "onEnterRules": [
                { "beforeText": "^a", "afterText": "^b", "action": { "indent": "none", "appendText": "1" } },
                { "beforeText": "^a", "previousLineText": "^p", "action": { "indent": "none", "appendText": "2" } },
                { "beforeText": "^a", "action": { "indent": "none", "appendText": "3" } },
                { "beforeText": "^a", "action": { "indent": "none", "appendText": "4" } }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("1", service.enter("a|b"))
        assertEquals("2", service.enter("p\na|"))
        assertEquals("3", service.enter("q\na|"))
        assertEquals("3", service.enter("a|"), "the first line has no line above: its text is empty")
    }

    @Test
    fun `the JSDoc rules of VS Code's TypeScript configuration work on one line each`() {
        val service = service(
            """
            {
              "onEnterRules": [
                { "beforeText": "^\\s*/\\*\\*(?!/)([^\\*]|\\*(?!/))*$", "afterText": "^\\s*\\*/$", "action": { "indent": "indentOutdent", "appendText": " * " } },
                { "beforeText": "^\\s*/\\*\\*(?!/)([^\\*]|\\*(?!/))*$", "action": { "indent": "none", "appendText": " * " } },
                { "beforeText": "^(\\t|[ ])*[ ]\\*([ ]([^\\*]|\\*(?!/))*)?$", "action": { "indent": "none", "appendText": "* " } },
                { "beforeText": "^(\\t|[ ])*[ ]\\*/\\s*$", "action": { "indent": "none", "removeText": 1 } }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("", service.enter("/**| */"))
        assertEquals(" * ", service.enter("/**|"))
        assertEquals("     * ", service.enter("    /**|"))
        assertEquals(" * ", service.enter(" * text|"))
        assertEquals("", service.enter(" */|"))
    }

    @Test
    fun `a line's carriage return reaches no rule`() {
        val service = service(rule("""{ "indent": "indent" }""", """"beforeText": "x$", "afterText": "^$""""))
        val document = CodeDocument("x\r\n\r\ny")
        assertEquals("    ", service.smartIndent(document, 1, FormattingOptions()))
    }

    // ── brackets ──────────────────────────────────────────────────────────────

    @Test
    fun `an opening bracket at the end of the line indents`() {
        val service = service("""{ "brackets": [["{", "}"], ["begin", "end"]] }""")
        assertEquals("    ", service.enter("{|"))
        assertEquals("      ", service.enter("  if {  |"))
        assertEquals("    ", service.enter("begin|"))
        assertEquals("", service.enter("rebegin|"), "a word bracket needs a word of its own")
    }

    @Test
    fun `between a pair the closer stays at the opener's indentation`() {
        val service = service("""{ "brackets": [["{", "}"], ["begin", "end"]] }""")
        assertEquals("  ", service.enter("  {|}"))
        assertEquals("", service.enter("begin|end"))
        assertEquals("    ", service.enter("begin|ending"), "not that word's end")
    }

    @Test
    fun `no bracket keeps the line's indentation`() {
        assertEquals("  ", service("""{ "brackets": [["{", "}"]] }""").enter("  x|"))
        assertEquals("  ", service("{}").enter("  x|"))
    }

    // ── indentationRules ─────────────────────────────────────────────────────

    private val rules = service(
        """
        {
          "indentationRules": {
            "increaseIndentPattern": "^\\s*(do|then)\\s*$",
            "decreaseIndentPattern": "^\\s*(done|fi)\\b",
            "indentNextLinePattern": "^\\s*when\\b.*:$",
            "unIndentedLinePattern": "^\\s*#"
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `increaseIndentPattern indents what follows`() {
        assertEquals("  ", rules.enter("do|", FormattingOptions(tabSize = 2)))
        assertEquals("  ", rules.enter("  x|", FormattingOptions(tabSize = 2)))
    }

    @Test
    fun `decreaseIndentPattern outdents the text left on the new line`() {
        assertEquals("", rules.enter("    x|done"))
        assertEquals("    ", rules.enter("    do|done"), "after an opener, back to the same place")
    }

    @Test
    fun `decreaseIndentPattern on the line above says nothing more`() {
        assertEquals("    ", rules.enter("    done|"))
    }

    @Test
    fun `indentNextLinePattern indents one line, the next goes back`() {
        assertEquals("      ", rules.enter("  when x:|"))
        assertEquals("  ", rules.enter("  when x:\n      y|"))
        assertEquals("  ", rules.enter("  when x:\n    when y:\n        z|"), "back past a run of them")
    }

    @Test
    fun `unIndentedLinePattern looks past the line`() {
        assertEquals("    ", rules.enter("do\n# note|"))
        assertEquals("    ", rules.enter("do\n# note\n  # more|"))
    }

    @Test
    fun `a rule and a bracket come before indentationRules`() {
        val service = service(
            """
            {
              "brackets": [["(", ")"]],
              "onEnterRules": [{ "beforeText": "^then$", "action": { "indent": "none", "appendText": "rule" } }],
              "indentationRules": { "increaseIndentPattern": "(then|\\()$", "decreaseIndentPattern": "^never" }
            }
            """.trimIndent(),
        )
        assertEquals("rule", service.enter("then|"))
        assertEquals("", service.enter("(|)"), "the bracket's indent-outdent, not one level in")
    }

    // ── comments and folding ──────────────────────────────────────────────────

    @Test
    fun `comments go on the tokenizer, which otherwise stays as it was`() {
        val configured = BuiltInLanguages.Kotlin.withConfiguration(
            LanguageConfiguration.parse("""{ "comments": { "lineComment": "--" } }"""),
        )
        assertEquals(CommentSyntax(line = "--"), configured.tokenizer.commentSyntax)
        val text = "val a = 1"
        assertEquals(BuiltInLanguages.Kotlin.tokenizer.tokenizeFull(text), configured.tokenizer.tokenizeFull(text))
        assertEquals(BuiltInLanguages.Kotlin.keyboardToolbarChars, configured.keyboardToolbarChars)
        assertEquals(BuiltInLanguages.Kotlin.tokenizer.keyboardToolbarChars(), configured.tokenizer.keyboardToolbarChars())
    }

    @Test
    fun `without comments the tokenizer is the same one`() {
        assertSame(BuiltInLanguages.Kotlin.tokenizer, BuiltInLanguages.Kotlin.withConfiguration(LanguageConfiguration()).tokenizer)
    }

    @Test
    fun `folding markers add to the language's own folding`() {
        val configured = BuiltInLanguages.Json.withConfiguration(
            LanguageConfiguration.parse("""{ "folding": { "markers": { "start": "^\\s*#begin", "end": "^\\s*#end" } } }"""),
        )
        val document = CodeDocument("#begin\n{\n  \"a\": 1\n}\n#end")
        val ranges = configured.foldingProvider.foldableRanges(document)
        assertTrue(FoldRange(0, 4, kind = FoldKind.Region) in ranges, "$ranges")
        assertTrue(ranges.any { it.startLine == 1 && it.kind != FoldKind.Region }, "$ranges")
    }

    @Test
    fun `without markers the folding provider is the same one`() {
        assertSame(BuiltInLanguages.Json.foldingProvider, BuiltInLanguages.Json.withConfiguration(LanguageConfiguration()).foldingProvider)
    }

    // ── Over a language's own service ─────────────────────────────────────────

    /** A service of its own for everything: what the configuration must leave alone, or win against. */
    private object Own : LanguageService {
        override val triggerCharacters: Set<Char> = setOf('.')
        override val completionsAreIncomplete: Boolean = false

        override suspend fun completions(document: CodeDocument, cursorOffset: Int) =
            listOf(CompletionItem("own", CompletionKind.Value, "own"))

        override suspend fun diagnostics(document: CodeDocument) = listOf(Diagnostic(0..0, "own", DiagnosticSeverity.Warning))

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions) = "own"

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char) = "own"

        override fun surroundingClose(typed: Char) = "own"

        override suspend fun hoverDoc(document: CodeDocument, offset: Int) = HoverDoc("own", "own")

        override suspend fun format(document: CodeDocument, options: FormattingOptions) = "own"
    }

    private val owned = bare.copy(languageService = Own)

    private fun over(json: String): LanguageService = owned.withConfiguration(LanguageConfiguration.parse(json)).languageService!!

    @Test
    fun `the language's service answers what the configuration has no word on`() = runTest {
        val service = over("{}")
        val document = CodeDocument("a")
        assertEquals("own", service.completions(document, 0).single().label)
        assertFalse(service.completionsAreIncomplete, "asked once per word, as the service says")
        assertEquals(setOf('.'), service.triggerCharacters)
        assertEquals("own", service.diagnostics(document).single().message)
        assertEquals("own", service.hoverDoc(document, 0)?.title)
        assertEquals("own", service.format(document, FormattingOptions()))
        assertEquals("own", service.closing("|", '('))
        assertEquals("own", service.surroundingClose('('))
        assertEquals("own", service.enter("  x|"), "no rule: not the configuration's kept indentation")
    }

    @Test
    fun `a rule that decides on no indentation is not passed over`() {
        val service = over("""{ "onEnterRules": [{ "beforeText": "x", "action": { "indent": "outdent" } }] }""")
        assertEquals("", service.enter("x|"))
        assertEquals("own", service.enter("y|"))
    }

    @Test
    fun `a pair that decides against closing is not passed over`() {
        val service = over("""{ "autoClosingPairs": [{ "open": "(", "close": ")" }], "surroundingPairs": [["(", ")"]] }""")
        assertEquals(")", service.closing("|", '('))
        assertNull(service.closing("|word", '('))
        assertEquals("own", service.closing("|", '['), "no pair for it")
        assertEquals(")", service.surroundingClose('('))
        assertEquals("own", service.surroundingClose('['))
    }

    @Test
    fun `indentation rules that find nothing leave Enter to the service`() {
        val service = over("""{ "indentationRules": { "increaseIndentPattern": "do$", "decreaseIndentPattern": "^done" } }""")
        assertEquals("own", service.enter("x|"))
        assertEquals("    ", service.enter("do|"))
    }

    @Test
    fun `a built-in language keeps its own indentation under a configuration`() {
        val service = BuiltInLanguages.Kotlin.withConfiguration(
            LanguageConfiguration.parse("""{ "surroundingPairs": [["(", ")"]] }"""),
        ).languageService!!
        assertEquals("    ", service.enter("val a = |"))
        assertEquals(")", service.surroundingClose('('))
    }
}
