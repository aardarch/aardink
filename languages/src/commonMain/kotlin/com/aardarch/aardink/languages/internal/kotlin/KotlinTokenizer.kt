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

import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.RegexTokenizer

/** Regex-driven Kotlin tokenizer used by [com.aardarch.aardink.languages.BuiltInLanguages.Kotlin]. */
object KotlinTokenizer : RegexTokenizer() {

    private val keywords = setOf(
        "as", "break", "by", "class", "companion", "const", "continue", "data", "do", "dynamic",
        "else", "enum", "external", "false", "final", "for", "fun", "get", "if", "import", "in",
        "init", "inline", "inner", "interface", "internal", "is", "lateinit", "let", "noinline",
        "null", "object", "open", "operator", "out", "override", "package", "private", "protected",
        "public", "reified", "return", "sealed", "set", "super", "suspend", "tailrec", "this",
        "throw", "true", "try", "typealias", "val", "var", "when", "where", "while", "with",
        "yield",
    )

    override val multiLineConstructs: Boolean = true

    override val rules: List<Pair<Regex, TokenType>> = listOf(
        Regex("/\\*[\\s\\S]*?\\*/") to TokenType.Comment,
        Regex("//[^\\n]*") to TokenType.Comment,
        Regex("\"\"\"[\\s\\S]*?\"\"\"") to TokenType.StringLiteral,
        Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"") to TokenType.StringLiteral,
        // Char literal: any single character, escape sequence, or 4-digit unicode escape
        Regex("'(?:\\\\u[0-9A-Fa-f]{4}|\\\\.|[^'\\\\\\n])'") to TokenType.StringLiteral,
        // Declaration names (`fun name`, `class Name`, ...) are typed in refine() below rather
        // than by lookbehind rules here -- see refine() for why.
        Regex("@[A-Za-z_][A-Za-z0-9_]*") to TokenType.Annotation,
        Regex("\\b(?:0[xX][\\dA-Fa-f_]+|0[bB][01_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?[fFlLuU]*)\\b") to TokenType.Number,
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

    override fun keyboardToolbarChars(): List<Char> = listOf('{', '}', '(', ')', '"', '$', '.', ':', '<', '>', '=')
}
