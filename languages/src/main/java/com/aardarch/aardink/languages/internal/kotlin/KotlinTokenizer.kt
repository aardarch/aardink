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
package com.aardarch.editor.languages.internal.kotlin

import com.aardarch.editor.core.TokenType
import com.aardarch.editor.languages.internal.RegexTokenizer

/** Regex-driven Kotlin tokenizer used by [com.aardarch.editor.languages.BuiltInLanguages.Kotlin]. */
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
        // Function declaration: highlight the name immediately following `fun`
        Regex("(?<=\\bfun\\s)[A-Za-z_][A-Za-z0-9_]*") to TokenType.FunctionCall,
        // Class / object / interface declaration name
        Regex("(?<=\\b(?:class|object|interface|enum)\\s)[A-Za-z_][A-Za-z0-9_]*") to TokenType.TypeName,
        Regex("@[A-Za-z_][A-Za-z0-9_]*") to TokenType.Annotation,
        Regex("\\b(?:0[xX][\\dA-Fa-f_]+|0[bB][01_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?[fFlLuU]*)\\b") to TokenType.Number,
        Regex("\\b(?:" + keywords.joinToString("|") + ")\\b") to TokenType.Keyword,
        Regex("\\b[A-Z][A-Za-z0-9_]*\\b") to TokenType.TypeName,
        Regex("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()") to TokenType.FunctionCall,
        Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b") to TokenType.Identifier,
        Regex("[{}\\[\\]();,.:]") to TokenType.Punctuation,
        Regex("[+\\-*/%=!<>&|^~?]+") to TokenType.Operator,
    )

    override fun keyboardToolbarChars(): List<Char> = listOf('{', '}', '(', ')', '"', '$', '.', ':', '<', '>', '=')
}
