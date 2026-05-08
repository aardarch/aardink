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
package com.aardarch.editor.languages.internal.json

import com.aardarch.editor.core.TokenType
import com.aardarch.editor.languages.internal.RegexTokenizer

/** Regex-driven JSON tokenizer. Object keys are highlighted as [TokenType.Annotation]. */
object JsonTokenizer : RegexTokenizer() {

    override val rules: List<Pair<Regex, TokenType>> = listOf(
        // Key strings — a string immediately followed by colon. Highlighted as Annotation so they
        // visually pop against value strings.
        Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)") to TokenType.Annotation,
        Regex("\"(?:\\\\.|[^\"\\\\])*\"") to TokenType.StringLiteral,
        Regex("\\b(?:true|false|null)\\b") to TokenType.Keyword,
        Regex("-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b") to TokenType.Number,
        Regex("[{}\\[\\],:]") to TokenType.Punctuation,
    )

    override fun keyboardToolbarChars(): List<Char> = listOf('{', '}', '[', ']', '"', ':', ',')
}
