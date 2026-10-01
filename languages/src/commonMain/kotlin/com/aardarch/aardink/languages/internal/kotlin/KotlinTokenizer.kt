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
package com.aardarch.aardink.languages.internal.kotlin

import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.BACKSLASH_ESCAPE
import com.aardarch.aardink.languages.internal.COMMENT_DOC
import com.aardarch.aardink.languages.internal.KEYWORD_FLOW
import com.aardarch.aardink.languages.internal.NUMBER_BINARY
import com.aardarch.aardink.languages.internal.NUMBER_FLOAT
import com.aardarch.aardink.languages.internal.NUMBER_HEX
import com.aardarch.aardink.languages.internal.RegexTokenizer

/** Regex-driven Kotlin tokenizer used by [com.aardarch.aardink.languages.BuiltInLanguages.Kotlin]. */
object KotlinTokenizer : RegexTokenizer() {

    private val keywords = setOf(
        "as", "by", "class", "companion", "const", "data", "dynamic",
        "enum", "external", "false", "final", "fun", "get", "import", "in",
        "init", "inline", "inner", "interface", "internal", "is", "lateinit", "let", "noinline",
        "null", "object", "open", "operator", "out", "override", "package", "private", "protected",
        "public", "reified", "sealed", "set", "super", "suspend", "tailrec", "this",
        "true", "typealias", "val", "var", "where", "with",
        "yield",
    )

    /** Keywords that change where execution goes: Monaco's `keyword.flow`, VS Code's `keyword.control`. */
    private val flowKeywords = setOf(
        "break", "catch", "continue", "do", "else", "finally", "for", "if", "return", "throw", "try", "when", "while",
    )

    override val multiLineConstructs: Boolean = true

    override val rules: List<Pair<Regex, TokenType>> = listOf(
        // KDoc before other block comments (`/**/` is an empty comment, not KDoc).
        Regex("/\\*\\*(?!/)[\\s\\S]*?\\*/") to COMMENT_DOC,
        Regex("/\\*[\\s\\S]*?\\*/") to TokenType.Comment,
        Regex("//[^\\n]*") to TokenType.Comment,
        Regex("\"\"\"[\\s\\S]*?\"\"\"") to TokenType.StringLiteral,
        Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"") to TokenType.StringLiteral,
        // Char literal: any single character, escape sequence, or 4-digit unicode escape
        Regex("'(?:\\\\u[0-9A-Fa-f]{4}|\\\\.|[^'\\\\\\n])'") to TokenType.StringLiteral,
        // Declaration names (`fun name`, `class Name`, ...) are typed in refine() below rather
        // than by lookbehind rules here -- see refine() for why.
        Regex("@[A-Za-z_][A-Za-z0-9_]*") to TokenType.Annotation,
        Regex("\\b0[xX][\\dA-Fa-f_]+[lLuU]*\\b") to NUMBER_HEX,
        Regex("\\b0[bB][01_]+[lLuU]*\\b") to NUMBER_BINARY,
        Regex("\\b\\d[\\d_]*(?:\\.\\d[\\d_]*(?:[eE][+-]?\\d+)?|[eE][+-]?\\d+)[fF]?\\b|\\b\\d[\\d_]*[fF]\\b") to NUMBER_FLOAT,
        Regex("\\b\\d[\\d_]*[lLuU]*\\b") to TokenType.Number,
        Regex("\\b(?:" + flowKeywords.joinToString("|") + ")\\b") to KEYWORD_FLOW,
        Regex("\\b(?:" + keywords.joinToString("|") + ")\\b") to TokenType.Keyword,
        Regex("\\b[A-Z][A-Za-z0-9_]*\\b") to TokenType.TypeName,
        Regex("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()") to TokenType.FunctionCall,
        Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b") to TokenType.Identifier,
        Regex("[{}\\[\\]();,.:]") to TokenType.Punctuation,
        Regex("[+\\-*/%=!<>&|^~?]+") to TokenType.Operator,
    )

    /**
     * Highlights the name right after a declaration keyword: `fun name` as a function, `class`,
     * `object`, `interface` or `enum` followed by a name as a type. These were lookbehind rules
     * (`(?<=\bfun\s)...`), which Kotlin/wasm's regex engine evaluates at every candidate position
     * -- the `class|object|...` one took seconds to tokenize a 3 KB file in the browser. The
     * other rules already give the name its full `[A-Za-z_][A-Za-z0-9_]*` extent, so only its
     * type needs changing.
     */
    override fun refine(text: String, tokens: MutableList<Token>) {
        for (i in 1 until tokens.size) {
            val keyword = tokens[i - 1]
            val name = tokens[i]
            if (keyword.type != TokenType.Keyword) continue
            // Exactly one whitespace character between them, as `\s` in the old rules required.
            if (name.start != keyword.end + 1 || text[keyword.end] !in REGEX_WHITESPACE) continue
            if (!text[name.start].isAsciiIdentifierStart() || name.end != identifierEnd(text, name.start)) continue
            val declared = when (text.substring(keyword.start, keyword.end)) {
                "fun" -> TokenType.FunctionCall
                "class", "object", "interface", "enum" -> TokenType.TypeName
                else -> continue
            }
            tokens[i] = name.copy(type = declared)
        }
    }

    // What `\s` matches in the JVM and Kotlin regex engines.
    private const val REGEX_WHITESPACE = " \t\n\u000B\u000C\r"

    private fun Char.isAsciiIdentifierStart(): Boolean = this == '_' || this in 'A'..'Z' || this in 'a'..'z'

    private fun identifierEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end].let { it == '_' || it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) end++
        return end
    }

    override val stringEscapes: Regex = BACKSLASH_ESCAPE

    // A raw string has no escapes.
    override fun hasEscapes(text: String, token: Token): Boolean = !text.startsWith("\"\"\"", token.start)

    override val commentSyntax: CommentSyntax = CommentSyntax(line = "//", blockStart = "/*", blockEnd = "*/")

    override fun keyboardToolbarChars(): List<Char> = listOf('{', '}', '(', ')', '"', '$', '.', ':', '<', '>', '=')
}
