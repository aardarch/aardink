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
package com.aardarch.aardink.languages.internal.typescript

import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.BACKSLASH_ESCAPE
import com.aardarch.aardink.languages.internal.COMMENT_DOC
import com.aardarch.aardink.languages.internal.KEYWORD_FLOW
import com.aardarch.aardink.languages.internal.NUMBER_BINARY
import com.aardarch.aardink.languages.internal.NUMBER_FLOAT
import com.aardarch.aardink.languages.internal.NUMBER_HEX
import com.aardarch.aardink.languages.internal.RegexTokenizer

/** Regex-driven TypeScript / JavaScript tokenizer. */
object TypeScriptTokenizer : RegexTokenizer() {

    private val keywords = setOf(
        "abstract", "any", "as", "async", "boolean", "class",
        "const", "constructor", "debugger", "declare", "delete",
        "enum", "export", "extends", "false", "from", "function", "get",
        "implements", "import", "in", "instanceof", "interface", "is", "keyof", "let", "module",
        "namespace", "never", "new", "null", "number", "of", "private", "protected", "public",
        "readonly", "set", "static", "string", "super", "symbol", "this",
        "true", "type", "typeof", "undefined", "unknown", "var", "void", "with",
    )

    /** Keywords that change where execution goes: Monaco's `keyword.flow`, VS Code's `keyword.control`. */
    private val flowKeywords = setOf(
        "await", "break", "case", "catch", "continue", "default", "do", "else", "finally", "for", "if",
        "return", "switch", "throw", "try", "while", "yield",
    )

    override val multiLineConstructs: Boolean = true

    override val rules: List<Pair<Regex, TokenType>> = listOf(
        // JSDoc before other block comments (`/**/` is an empty comment, not JSDoc).
        Regex("/\\*\\*(?!/)[\\s\\S]*?\\*/") to COMMENT_DOC,
        Regex("/\\*[\\s\\S]*?\\*/") to TokenType.Comment,
        Regex("//[^\\n]*") to TokenType.Comment,
        Regex("`(?:\\\\.|\\$\\{[^}]*\\}|[^`\\\\])*`") to TokenType.StringLiteral,
        Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"") to TokenType.StringLiteral,
        Regex("'(?:\\\\.|[^'\\\\\\n])*'") to TokenType.StringLiteral,
        Regex("@[A-Za-z_][A-Za-z0-9_]*") to TokenType.Annotation,
        Regex("\\b0[xX][\\dA-Fa-f_]+n?\\b") to NUMBER_HEX,
        Regex("\\b0[bB][01_]+n?\\b") to NUMBER_BINARY,
        Regex("\\b\\d[\\d_]*(?:\\.\\d[\\d_]*(?:[eE][+-]?\\d+)?|[eE][+-]?\\d+)\\b") to NUMBER_FLOAT,
        Regex("\\b\\d[\\d_]*n?\\b") to TokenType.Number,
        Regex("\\b(?:" + flowKeywords.joinToString("|") + ")\\b") to KEYWORD_FLOW,
        Regex("\\b(?:" + keywords.joinToString("|") + ")\\b") to TokenType.Keyword,
        Regex("\\b[A-Z][A-Za-z0-9_]*\\b") to TokenType.TypeName,
        Regex("\\b[A-Za-z_$][A-Za-z0-9_$]*(?=\\s*\\()") to TokenType.FunctionCall,
        Regex("\\b[A-Za-z_$][A-Za-z0-9_$]*\\b") to TokenType.Identifier,
        Regex("[{}\\[\\]();,.:]") to TokenType.Punctuation,
        Regex("[+\\-*/%=!<>&|^~?]+") to TokenType.Operator,
    )

    override val stringEscapes: Regex = BACKSLASH_ESCAPE

    override val commentSyntax: CommentSyntax = CommentSyntax(line = "//", blockStart = "/*", blockEnd = "*/")

    override fun keyboardToolbarChars(): List<Char> = listOf('{', '}', '(', ')', '"', '\'', '`', '<', '>', ';', '.', '=')
}
