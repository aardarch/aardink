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
package com.aardarch.editor.languages.internal.typescript

import com.aardarch.editor.core.TokenType
import com.aardarch.editor.languages.internal.RegexTokenizer

/** Regex-driven TypeScript / JavaScript tokenizer. */
object TypeScriptTokenizer : RegexTokenizer() {

    private val keywords = setOf(
        "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch", "class",
        "const", "constructor", "continue", "debugger", "declare", "default", "delete", "do",
        "else", "enum", "export", "extends", "false", "finally", "for", "from", "function", "get",
        "if", "implements", "import", "in", "instanceof", "interface", "is", "keyof", "let", "module",
        "namespace", "never", "new", "null", "number", "of", "private", "protected", "public",
        "readonly", "return", "set", "static", "string", "super", "switch", "symbol", "this", "throw",
        "true", "try", "type", "typeof", "undefined", "unknown", "var", "void", "while", "with",
        "yield",
    )

    override val multiLineConstructs: Boolean = true

    override val rules: List<Pair<Regex, TokenType>> = listOf(
        Regex("/\\*[\\s\\S]*?\\*/") to TokenType.Comment,
        Regex("//[^\\n]*") to TokenType.Comment,
        Regex("`(?:\\\\.|\\$\\{[^}]*\\}|[^`\\\\])*`") to TokenType.StringLiteral,
        Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"") to TokenType.StringLiteral,
        Regex("'(?:\\\\.|[^'\\\\\\n])*'") to TokenType.StringLiteral,
        Regex("@[A-Za-z_][A-Za-z0-9_]*") to TokenType.Annotation,
        Regex("\\b(?:0[xX][\\dA-Fa-f_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?n?)\\b") to TokenType.Number,
        Regex("\\b(?:" + keywords.joinToString("|") + ")\\b") to TokenType.Keyword,
        Regex("\\b[A-Z][A-Za-z0-9_]*\\b") to TokenType.TypeName,
        Regex("\\b[A-Za-z_$][A-Za-z0-9_$]*(?=\\s*\\()") to TokenType.FunctionCall,
        Regex("\\b[A-Za-z_$][A-Za-z0-9_$]*\\b") to TokenType.Identifier,
        Regex("[{}\\[\\]();,.:]") to TokenType.Punctuation,
        Regex("[+\\-*/%=!<>&|^~?]+") to TokenType.Operator,
    )

    override fun keyboardToolbarChars(): List<Char> = listOf('{', '}', '(', ')', '"', '\'', '`', '<', '>', ';', '.', '=')
}
