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
package com.aardarch.editor.languages.internal.markdown

import com.aardarch.editor.core.TokenType
import com.aardarch.editor.languages.internal.RegexTokenizer

/**
 * Regex-driven Markdown tokenizer. The editor doesn't ship a "Heading" token type, so token types
 * are mapped onto visually-distinct existing categories:
 *   - headings → [TokenType.Annotation]
 *   - bold / italic emphasis markers → [TokenType.Keyword]
 *   - inline code / fenced code → [TokenType.StringLiteral]
 *   - links and images → [TokenType.FunctionCall]
 *   - blockquote markers, list bullets → [TokenType.Punctuation]
 */
object MarkdownTokenizer : RegexTokenizer() {

    override val multiLineConstructs: Boolean = true

    override val rules: List<Pair<Regex, TokenType>> = listOf(
        // Fenced code block (```lang ... ```)
        Regex("```[\\s\\S]*?```") to TokenType.StringLiteral,
        // ATX headings
        Regex("(?m)^#{1,6}[^\\n]*") to TokenType.Annotation,
        // Setext headings (text underlined by === or ---)
        Regex("(?m)^[^\\n]+\\n=+\\s*$") to TokenType.Annotation,
        Regex("(?m)^[^\\n]+\\n-{2,}\\s*$") to TokenType.Annotation,
        // Inline code
        Regex("`[^`\\n]+`") to TokenType.StringLiteral,
        // Images and links
        Regex("!?\\[[^\\]\\n]*\\]\\([^)\\n]*\\)") to TokenType.FunctionCall,
        // Reference-style link definitions: [label]: url "title"
        Regex("(?m)^\\s*\\[[^\\]\\n]+\\]:\\s+\\S+(?:\\s+\"[^\"\\n]*\")?") to TokenType.FunctionCall,
        // Bold (**...**, __...__) and italic (*...*, _..._)
        Regex("\\*\\*[^*\\n]+\\*\\*") to TokenType.Keyword,
        Regex("__[^_\\n]+__") to TokenType.Keyword,
        Regex("\\*[^*\\n]+\\*") to TokenType.Keyword,
        Regex("(?<![A-Za-z0-9])_[^_\\n]+_(?![A-Za-z0-9])") to TokenType.Keyword,
        // Strikethrough — visually struck-out via Comment colour (typically muted)
        Regex("~~[^~\\n]+~~") to TokenType.Comment,
        // HTML entities (&amp;, &#10003;, …)
        Regex("&(?:#\\d+|#[xX][0-9a-fA-F]+|[A-Za-z]+);") to TokenType.Number,
        // Blockquote markers
        Regex("(?m)^>+\\s") to TokenType.Punctuation,
        // Task-list checkbox: - [ ] / - [x]
        Regex("(?m)^\\s*[-*+]\\s+\\[[ xX]\\]") to TokenType.Punctuation,
        // List bullets
        Regex("(?m)^\\s*[-*+]\\s") to TokenType.Punctuation,
        // Numbered list
        Regex("(?m)^\\s*\\d+\\.\\s") to TokenType.Punctuation,
        // Horizontal rule
        Regex("(?m)^\\s*(?:---|\\*\\*\\*|___)\\s*$") to TokenType.Punctuation,
    )

    override fun keyboardToolbarChars(): List<Char> = listOf('#', '*', '_', '`', '-', '>', '[', ']', '(', ')')
}
