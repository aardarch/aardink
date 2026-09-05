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
package com.aardarch.aardink.languages.internal.toml

import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.RegexTokenizer

/** Regex-driven TOML tokenizer. */
object TomlTokenizer : RegexTokenizer() {

    override val multiLineConstructs: Boolean = true

    override val rules: List<Pair<Regex, TokenType>> = listOf(
        // Comments
        Regex("#[^\n]*") to TokenType.Comment,

        // Multiline strings
        Regex("\"\"\"[\\s\\S]*?\"\"\"") to TokenType.StringLiteral,
        Regex("'''[\\s\\S]*?'''") to TokenType.StringLiteral,

        // Basic and literal single-line strings
        Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"") to TokenType.StringLiteral,
        Regex("'[^'\\n]*'") to TokenType.StringLiteral,

        // Array of tables header [[array.table]]
        Regex("\\[\\[[^\\]\n]+\\]\\]") to TokenType.TypeName,

        // Table header [table]
        Regex("\\[[^\\]\n]+\\]") to TokenType.TypeName,

        // Keys before '=' (bare keys, dotted keys, or quoted keys)
        Regex("(?:\"[^\"]+\"|'[^']+'|[A-Za-z0-9_.-]+)(?=\\s*=)") to TokenType.Annotation,

        // Booleans
        Regex("\\b(?:true|false)\\b") to TokenType.Keyword,

        // Datetime literals (ISO-8601 / RFC-3339)
        Regex("\\b\\d{4}-\\d{2}-\\d{2}(?:[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:[Zz]|[+-]\\d{2}:\\d{2})?)?\\b") to TokenType.Number,

        // Hex, octal, binary, floats, integers
        Regex("\\b(?:0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+|[+-]?\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?)\\b") to
            TokenType.Number,

        // Punctuation and operators
        Regex("[=\\[\\]{},.]") to TokenType.Punctuation,
    )

    override fun keyboardToolbarChars(): List<Char> = listOf('=', '[', ']', '"', '\'', '#', '.', '{', '}', ',')
}
