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
package com.aardarch.editor.languages.internal.css

import com.aardarch.editor.core.TokenType
import com.aardarch.editor.languages.internal.RegexTokenizer

/** Regex-driven CSS tokenizer. */
object CssTokenizer : RegexTokenizer() {

    override val multiLineConstructs: Boolean = true

    override val rules: List<Pair<Regex, TokenType>> = listOf(
        Regex("/\\*[\\s\\S]*?\\*/") to TokenType.Comment,
        Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"") to TokenType.StringLiteral,
        Regex("'(?:\\\\.|[^'\\\\\\n])*'") to TokenType.StringLiteral,
        // !important
        Regex("!important\\b") to TokenType.Annotation,
        // At-rules: @media, @keyframes, …
        Regex("@[A-Za-z-]+") to TokenType.Annotation,
        // url(...) function — value, not invocation
        Regex("\\burl\\(") to TokenType.FunctionCall,
        // CSS variable use: var(--name)
        Regex("--[A-Za-z_][A-Za-z0-9_-]*") to TokenType.TypeName,
        // Hex colours
        Regex("#[0-9A-Fa-f]{3,8}\\b") to TokenType.Number,
        // Numeric values + units
        Regex("-?\\d+(?:\\.\\d+)?(?:%|[a-zA-Z]+)?") to TokenType.Number,
        // Selectors: .class
        Regex("\\.[A-Za-z_][A-Za-z0-9_-]*") to TokenType.TypeName,
        // ID selectors: #id (when followed by alpha — distinguishes from hex colours which were
        // already consumed above)
        Regex("#[A-Za-z_][A-Za-z0-9_-]*") to TokenType.TypeName,
        // Property names — identifier followed by `:`
        Regex("[A-Za-z-]+(?=\\s*:)") to TokenType.Keyword,
        // Pseudo-classes / pseudo-elements
        Regex("::?[A-Za-z-]+(?:\\([^)]*\\))?") to TokenType.FunctionCall,
        Regex("\\b[A-Za-z_][A-Za-z0-9_-]*\\b") to TokenType.Identifier,
        Regex("[{};,()]") to TokenType.Punctuation,
        Regex("[+\\-*/=>~]") to TokenType.Operator,
    )

    override fun keyboardToolbarChars(): List<Char> = listOf('{', '}', ':', ';', '#', '.', '%', '(', ')')
}
