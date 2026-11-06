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
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.languages.LanguageConfiguration.CharacterPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VS Code's own `language-configuration.json` files, at the bottom of this file, as they ship but
 * for `wordPattern`, which is not read, and most of LaTeX's brackets. Their regexes are
 * JavaScript's, so these also prove that each target's engine takes what
 * [LanguageConfiguration.parse] makes of them.
 */
class VsCodeConfigurationTest {

    private fun service(json: String): LanguageService {
        val bare = LanguageDefinition("bare", "Bare", listOf("bare"), PlainTextTokenizer)
        return assertNotNull(bare.withConfiguration(LanguageConfiguration.parse(json)).languageService)
    }

    /** What the line starts with that Enter makes at the `|` of [text]. */
    private fun LanguageService.enter(text: String): String {
        val document = CodeDocument(text.replace("|", "\n"))
        return smartIndent(document, document.offsetToLineCol(text.indexOf('|') + 1).first, FormattingOptions())
    }

    /** What the service closes [typed] with, typed at the `|` of [text]. */
    private fun LanguageService.closing(text: String, typed: Char): String? =
        autoClose(CodeDocument(text.replace("|", "$typed")), text.indexOf('|'), typed)

    /** Whether [source], a regex as JavaScript reads it, finds a match in [text]. */
    private fun regex(source: String, text: String): Boolean {
        val json = """{ "indentationRules": { "increaseIndentPattern": "${source.replace("\\", "\\\\")}" } }"""
        return LanguageConfiguration.parse(json).indentationRules!!.increase!!.containsMatchIn(text)
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    @Test
    fun `every file here parses, on every target`() {
        for (json in listOf(Cpp, TypeScript, Go, JsonFile, Latex, Xsl, Html, Julia, PowerShell)) LanguageConfiguration.parse(json)
    }

    @Test
    fun `TypeScript's dollar has an empty close and wraps nothing`() {
        assertTrue(CharacterPair("$", "") in LanguageConfiguration.parse(TypeScript).surroundingPairs)
        val service = service(TypeScript)
        assertNull(service.surroundingClose('$'))
        assertEquals(")", service.surroundingClose('('))
    }

    @Test
    fun `only a surrounding pair's close may be empty`() {
        val refused = assertFailsWith<IllegalArgumentException> { LanguageConfiguration.parse("""{ "autoClosingPairs": [["$", ""]] }""") }
        assertEquals("autoClosingPairs[0]: a pair's open and close are not empty", refused.message)
    }

    @Test
    fun `Go's bare brace reads as itself`() {
        val service = service(Go)
        assertEquals("    ", service.enter("case 1:|"), "increaseIndentPattern")
        assertEquals("    ", service.enter("default:|"))
        assertEquals("", service.enter("    a|}"), "decreaseIndentPattern")
        assertEquals("    ", service.enter("    a|"))
    }

    @Test
    fun `JSON's bare brace before a lookahead reads as itself`() {
        val increase = LanguageConfiguration.parse(JsonFile).indentationRules!!.increase!!
        assertTrue(increase.containsMatchIn("{"))
        assertTrue(increase.containsMatchIn("""  "a": ["""))
        assertFalse(increase.containsMatchIn("""{ "a": 1,"""), "as in JavaScript: the opener ends the line")
        assertFalse(increase.containsMatchIn("""{ "a": 1 }"""))
        assertFalse(increase.containsMatchIn(""""a{": 1"""), "a brace in a string")
    }

    @Test
    fun `LaTeX's braces after a backslash word read as themselves`() {
        val service = service(Latex)
        assertEquals("    ", service.enter("\\begin{itemize}|"))
        assertEquals("", service.enter("\\begin{document}|"), "the document does not indent")
        assertEquals("", service.enter("\\begin{a} \\end{a}|"), "closed on its own line")
        assertEquals("", service.enter("    x|\\end{itemize}"))
    }

    @Test
    fun `XSL's empty line comment is none, so nothing is a comment for it`() {
        assertEquals(CommentSyntax(blockStart = "<!--", blockEnd = "-->"), LanguageConfiguration.parse(Xsl).comments)
        assertNull(LanguageConfiguration.parse("""{ "comments": { "lineComment": "" } }""").comments)
        val service = service(
            """
            {
              "comments": { "lineComment": "", "blockComment": ["<!--", "-->"] },
              "autoClosingPairs": [{ "open": "(", "close": ")", "notIn": ["comment"] }]
            }
            """.trimIndent(),
        )
        assertEquals(")", service.closing("a |", '('))
        assertNull(service.closing("<!-- a |", '('))
    }

    // ── JavaScript's regexes ──────────────────────────────────────────────────

    @Test
    fun `a brace that starts no quantifier is itself`() {
        assertTrue(regex("""a{b""", "a{b"))
        assertTrue(regex("""^{$""", "{"))
        assertTrue(regex("""a{,2}""", "a{,2}"), "JavaScript has no quantifier without a minimum")
        assertTrue(regex("""a{2""", "a{2"))
    }

    @Test
    fun `a quantifier stays one`() {
        assertTrue(regex("""^a{2}$""", "aa"))
        assertFalse(regex("""^a{2}$""", "a{2}"))
        assertTrue(regex("""^a{2,}$""", "aaa"))
        assertTrue(regex("""^a{1,2}$""", "aa"))
        assertFalse(regex("""^a{1,2}$""", "aaa"))
    }

    @Test
    fun `an escaped brace stays escaped, and an escaped backslash escapes nothing after it`() {
        assertTrue(regex("""^\{$""", "{"))
        assertTrue(regex("""^\\{$""", "\\{"))
    }

    @Test
    fun `a bracket in a character class is itself`() {
        assertTrue(regex("""^[[(]$""", "["))
        assertTrue(regex("""^[[(]$""", "("))
        assertTrue(regex("""^[\[]$""", "["))
        assertTrue(regex("""^[^[]$""", "a"))
        assertFalse(regex("""^[^[]$""", "["))
        assertTrue(regex("""^[{]$""", "{"), "nor is a brace in one more than itself")
    }

    @Test
    fun `a Unicode property keeps its braces, in a class or out of one`() {
        val json = """{ "indentationRules": { "increaseIndentPattern": { "pattern": "^\\p{Lu}", "flags": "u" } } }"""
        val increase = LanguageConfiguration.parse(json).indentationRules!!.increase!!
        assertTrue(increase.containsMatchIn("Ab"))
        assertFalse(increase.containsMatchIn("ab"))
        assertFalse(regex("""^\P{Lu}""", "Ab"))
        assertTrue(regex("""^\P{Lu}""", "ab"))
        assertTrue(regex("""^[\p{Lu}]$""", "A"))
        assertTrue(regex("""^\p{Lu}{2}$""", "AB"), "and a quantifier after one is still one")
    }
}

/** `cpp/language-configuration.json`. */
private val Cpp = """
    {
      "comments": {"lineComment": "//", "blockComment": ["/*", "*/"]},
      "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
      "autoClosingPairs": [{"open": "[", "close": "]"}, {"open": "{", "close": "}"}, {"open": "(", "close": ")"}, {"open": "'", "close": "'", "notIn": ["string", "comment"]}, {"open": "\"", "close": "\"", "notIn": ["string"]}, {"open": "/*", "close": "*/", "notIn": ["string", "comment"]}, {"open": "/**", "close": " */", "notIn": ["string"]}],
      "surroundingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["\"", "\""], ["'", "'"], ["<", ">"]],
      "folding": {"markers": {"start": "^\\s*#\\s*pragma\\s+region\\b", "end": "^\\s*#\\s*pragma\\s+endregion\\b"}},
      "indentationRules": {"decreaseIndentPattern": {"pattern": "^\\s*[\\}\\]\\)].*$"}, "increaseIndentPattern": {"pattern": "^.*(\\{[^}]*|\\([^)]*|\\[[^\\]]*)$"}},
      "onEnterRules": [{"previousLineText": "^\\s*(((else ?)?if|for|while)\\s*\\(.*\\)\\s*|else\\s*)$", "beforeText": "^\\s+([^{i\\s]|i(?!f\\b))", "action": {"indent": "outdent"}}, {"beforeText": "^\\s*//\\s*\\S|\\s//\\s+\\S", "afterText": "^(?!\\s*$)", "action": {"indent": "none", "appendText": "// "}}]
    }
""".trimIndent()

/** `typescript-basics/language-configuration.json`. */
private val TypeScript = """
    {
      "comments": {"lineComment": "//", "blockComment": ["/*", "*/"]},
      "brackets": [["${'$'}{", "}"], ["{", "}"], ["[", "]"], ["(", ")"]],
      "autoClosingPairs": [{"open": "${'$'}{", "close": "}"}, {"open": "{", "close": "}"}, {"open": "[", "close": "]"}, {"open": "(", "close": ")"}, {"open": "'", "close": "'", "notIn": ["string", "comment"]}, {"open": "\"", "close": "\"", "notIn": ["string"]}, {"open": "`", "close": "`", "notIn": ["string", "comment"]}, {"open": "/**", "close": " */", "notIn": ["string"]}],
      "surroundingPairs": [["${'$'}{", "}"], ["$", ""], ["{", "}"], ["[", "]"], ["(", ")"], ["'", "'"], ["\"", "\""], ["`", "`"], ["<", ">"]],
      "autoCloseBefore": ";:.,=}])>` \n\t",
      "folding": {"markers": {"start": "^\\s*//\\s*#?region\\b", "end": "^\\s*//\\s*#?endregion\\b"}},
      "indentationRules": {"decreaseIndentPattern": {"pattern": "^\\s*[\\}\\]\\)].*$"}, "increaseIndentPattern": {"pattern": "^.*(\\{[^}]*|\\([^)]*|\\[[^\\]]*)$"}, "unIndentedLinePattern": {"pattern": "^(\\t|[ ])*[ ]\\*[^/]*\\*/\\s*$|^(\\t|[ ])*[ ]\\*/\\s*$|^(\\t|[ ])*\\*([ ]([^\\*]|\\*(?!/))*)?$"}, "indentNextLinePattern": {"pattern": "^((.*=>\\s*)|((.*[^\\w]+|\\s*)((if|while|for)\\s*\\(.*\\)\\s*|else\\s*)))$"}},
      "onEnterRules": [{"beforeText": {"pattern": "^\\s*/\\*\\*(?!/)([^\\*]|\\*(?!/))*$"}, "afterText": {"pattern": "^\\s*\\*/$"}, "action": {"indent": "indentOutdent", "appendText": " * "}}, {"beforeText": {"pattern": "^\\s*/\\*\\*(?!/)([^\\*]|\\*(?!/))*$"}, "action": {"indent": "none", "appendText": " * "}}, {"beforeText": {"pattern": "^(\\t|[ ])*\\*([ ]([^\\*]|\\*(?!/))*)?$"}, "previousLineText": {"pattern": "(?=^(\\s*(/\\*\\*|\\*)).*)(?=(?!(\\s*\\*/)))"}, "action": {"indent": "none", "appendText": "* "}}, {"beforeText": {"pattern": "^(\\t|[ ])*[ ]\\*/\\s*$"}, "action": {"indent": "none", "removeText": 1}}, {"beforeText": {"pattern": "^(\\t|[ ])*[ ]\\*[^/]*\\*/\\s*$"}, "action": {"indent": "none", "removeText": 1}}, {"beforeText": {"pattern": "^\\s*(\\bcase\\s.+:|\\bdefault:)$"}, "afterText": {"pattern": "^(?!\\s*(\\bcase\\b|\\bdefault\\b))"}, "action": {"indent": "indent"}}, {"previousLineText": "^\\s*(((else ?)?if|for|while)\\s*\\(.*\\)\\s*|else\\s*)$", "beforeText": "^\\s+([^{i\\s]|i(?!f\\b))", "action": {"indent": "outdent"}}, {"beforeText": "^.*\\([^\\)]*$", "afterText": "^\\s*\\).*$", "action": {"indent": "indentOutdent", "appendText": "\t"}}, {"beforeText": "^.*\\{[^\\}]*$", "afterText": "^\\s*\\}.*$", "action": {"indent": "indentOutdent", "appendText": "\t"}}, {"beforeText": "^.*\\[[^\\]]*$", "afterText": "^\\s*\\].*$", "action": {"indent": "indentOutdent", "appendText": "\t"}}, {"beforeText": "^\\s*//\\s*\\S|\\s//\\s+\\S", "afterText": "^(?!\\s*$)", "action": {"indent": "none", "appendText": "// "}}]
    }
""".trimIndent()

/** `go/language-configuration.json`. */
private val Go = """
    {
      "comments": {"lineComment": "//", "blockComment": ["/*", "*/"]},
      "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
      "autoClosingPairs": [["{", "}"], ["[", "]"], ["(", ")"], {"open": "`", "close": "`", "notIn": ["string"]}, {"open": "\"", "close": "\"", "notIn": ["string"]}, {"open": "'", "close": "'", "notIn": ["string", "comment"]}],
      "surroundingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["\"", "\""], ["'", "'"], ["`", "`"]],
      "indentationRules": {"increaseIndentPattern": "^.*(\\bcase\\b.*:|\\bdefault\\b:|(\\b(func|if|else|switch|select|for|struct)\\b.*)?{[^}\"'`]*|\\([^)\"'`]*)$", "decreaseIndentPattern": "^\\s*(\\bcase\\b.*:|\\bdefault\\b:|}[)}]*[),]?|\\)[,]?)$"},
      "folding": {"markers": {"start": "^\\s*//\\s*#?region\\b", "end": "^\\s*//\\s*#?endregion\\b"}},
      "onEnterRules": [{"beforeText": "^\\s*//\\s*\\S|\\s//\\s+\\S", "afterText": "^(?!\\s*$)", "action": {"indent": "none", "appendText": "// "}}]
    }
""".trimIndent()

/** `json/language-configuration.json`. */
private val JsonFile = """
    {
      "comments": {"lineComment": "//", "blockComment": ["/*", "*/"]},
      "brackets": [["{", "}"], ["[", "]"]],
      "autoClosingPairs": [{"open": "{", "close": "}", "notIn": ["string"]}, {"open": "[", "close": "]", "notIn": ["string"]}, {"open": "(", "close": ")", "notIn": ["string"]}, {"open": "'", "close": "'", "notIn": ["string"]}, {"open": "\"", "close": "\"", "notIn": ["string", "comment"]}, {"open": "`", "close": "`", "notIn": ["string", "comment"]}],
      "indentationRules": {"increaseIndentPattern": "({+(?=((\\\\.|[^\"\\\\])*\"(\\\\.|[^\"\\\\])*\")*[^\"}]*)$)|(\\[+(?=((\\\\.|[^\"\\\\])*\"(\\\\.|[^\"\\\\])*\")*[^\"\\]]*)$)", "decreaseIndentPattern": "^\\s*[}\\]],?\\s*$"},
      "folding": {"markers": {"start": "^\\s*//\\s*#?region\\b", "end": "^\\s*//\\s*#?endregion\\b"}},
      "onEnterRules": [{"beforeText": "^\\s*//\\s*\\S|\\s//\\s+\\S", "afterText": "^(?!\\s*$)", "action": {"indent": "none", "appendText": "// "}}]
    }
""".trimIndent()

/** `latex/latex-language-configuration.json`. */
private val Latex = """
    {
      "comments": {"lineComment": "%"},
      "brackets": [["{", "}"], ["[", "]"], ["(", ")"], ["[", ")"], ["(", "]"], ["\\{", "\\}"]],
      "autoClosingPairs": [["\\left(", "\\right)"], ["\\left[", "\\right]"], ["\\left\\{", "\\right\\}"], ["\\bigl(", "\\bigr)"], ["\\bigl[", "\\bigr]"], ["\\bigl\\{", "\\bigr\\}"], ["\\Bigl(", "\\Bigr)"], ["\\Bigl[", "\\Bigr]"], ["\\Bigl\\{", "\\Bigr\\}"], ["\\biggl(", "\\biggr)"], ["\\biggl[", "\\biggr]"], ["\\biggl\\{", "\\biggr\\}"], ["\\Biggl(", "\\Biggr)"], ["\\Biggl[", "\\Biggr]"], ["\\Biggl\\{", "\\Biggr\\}"], ["\\(", "\\)"], ["\\[", "\\]"], ["\\{", "\\}"], ["{", "}"], ["[", "]"], ["(", ")"], ["`", "'"]],
      "surroundingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["\"", "\""], ["'", "'"], ["`", "'"], ["$", "$"]],
      "indentationRules": {"increaseIndentPattern": "\\\\begin{(?!document)([^}]*)}(?!.*\\\\end{\\1})", "decreaseIndentPattern": "^\\s*\\\\end{(?!document)"},
      "folding": {"markers": {"start": "^\\s*(%\\s*region|%?\\s*\\\\begingroup)\\b", "end": "^\\s*(%\\s*endregion|%?\\s*\\\\endgroup)\\b"}},
      "autoCloseBefore": ";:.,={}])>\\` \n\t$"
    }
""".trimIndent()

/** `xml/xsl.language-configuration.json`. */
private val Xsl = """
    {
      "comments": {"lineComment": "", "blockComment": ["<!--", "-->"]},
      "brackets": [["<!--", "-->"], ["<", ">"], ["{", "}"], ["(", ")"], ["[", "]"]]
    }
""".trimIndent()

/** `html/language-configuration.json`. */
private val Html = """
    {
      "comments": {"blockComment": ["<!--", "-->"]},
      "brackets": [["<!--", "-->"], ["{", "}"], ["(", ")"]],
      "autoClosingPairs": [{"open": "{", "close": "}"}, {"open": "[", "close": "]"}, {"open": "(", "close": ")"}, {"open": "'", "close": "'"}, {"open": "\"", "close": "\""}, {"open": "<!--", "close": "-->", "notIn": ["comment", "string"]}],
      "surroundingPairs": [{"open": "'", "close": "'"}, {"open": "\"", "close": "\""}, {"open": "{", "close": "}"}, {"open": "[", "close": "]"}, {"open": "(", "close": ")"}, {"open": "<", "close": ">"}],
      "folding": {"markers": {"start": "^\\s*<!--\\s*#region\\b.*-->", "end": "^\\s*<!--\\s*#endregion\\b.*-->"}},
      "onEnterRules": [{"beforeText": {"pattern": "<(?!(?:area|base|br|col|embed|hr|img|input|keygen|link|menuitem|meta|param|source|track|wbr))([_:\\w][_:\\w-.\\d]*)(?:(?:[^'\"/>]|\"[^\"]*\"|'[^']*')*?(?!\\/)>)[^<]*$", "flags": "i"}, "afterText": {"pattern": "^<\\/([_:\\w][_:\\w-.\\d]*)\\s*>", "flags": "i"}, "action": {"indent": "indentOutdent"}}, {"beforeText": {"pattern": "<(?!(?:area|base|br|col|embed|hr|img|input|keygen|link|menuitem|meta|param|source|track|wbr))([_:\\w][_:\\w-.\\d]*)(?:(?:[^'\"/>]|\"[^\"]*\"|'[^']*')*?(?!\\/)>)[^<]*$", "flags": "i"}, "action": {"indent": "indent"}}],
      "indentationRules": {"increaseIndentPattern": "<(?!\\?|(?:area|base|br|col|frame|hr|html|img|input|keygen|link|menuitem|meta|param|source|track|wbr)\\b|[^>]*\\/>)([-_\\.A-Za-z0-9]+)(?=\\s|>)\\b[^>]*>(?!.*<\\/\\1>)|<!--(?!.*-->)|\\{[^}\"']*$", "decreaseIndentPattern": "^\\s*(<\\/(?!html)[-_\\.A-Za-z0-9]+\\b[^>]*>|-->|\\})"}
    }
""".trimIndent()

/** `julia/language-configuration.json`. */
private val Julia = """
    {
      "comments": {"lineComment": "#", "blockComment": ["#=", "=#"]},
      "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
      "autoClosingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["`", "`"], {"open": "\"", "close": "\"", "notIn": ["string", "comment"]}],
      "surroundingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["\"", "\""], ["`", "`"]],
      "folding": {"markers": {"start": "^\\s*#region", "end": "^\\s*#endregion"}},
      "indentationRules": {"increaseIndentPattern": "^(\\s*|.*=\\s*|.*@\\w*\\s*)[\\w\\s]*(?:[\"'`][^\"'`]*[\"'`])*[\\w\\s]*\\b(if|while|for|function|macro|(mutable\\s+)?struct|abstract\\s+type|primitive\\s+type|let|quote|try|begin|.*\\)\\s*do|else|elseif|catch|finally)\\b(?!(?:.*\\bend\\b[^\\]]*)|(?:[^\\[]*\\].*)$).*$", "decreaseIndentPattern": "^\\s*(end|else|elseif|catch|finally)\\b.*$"}
    }
""".trimIndent()

/** `powershell/language-configuration.json`. */
private val PowerShell = """
    {
      "comments": {"lineComment": "#", "blockComment": ["<#", "#>"]},
      "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
      "autoClosingPairs": [["{", "}"], ["[", "]"], ["(", ")"], {"open": "@'", "close": "\n'@", "notIn": ["string", "comment"]}, {"open": "@\"", "close": "\n\"@", "notIn": ["string", "comment"]}, {"open": "\"", "close": "\"", "notIn": ["string"]}, {"open": "'", "close": "'", "notIn": ["string", "comment"]}, ["<#", "#>"]],
      "surroundingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["\"", "\""], ["'", "'"]],
      "folding": {"markers": {"start": "^\\s*#[rR]egion\\b", "end": "^\\s*#[eE]nd[rR]egion\\b"}}
    }
""".trimIndent()
