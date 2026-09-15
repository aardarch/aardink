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
package com.aardarch.aardink.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType

/**
 * Builds an [AnnotatedString] from a token list and the active [EditorTheme].
 *
 * Each token gets a `SpanStyle(color = theme.tokenColors[token.type])`. Gaps between tokens are
 * coloured with `theme.tokenColors[TokenType.Default]`. If [tokens] is empty the whole text is
 * coloured Default.
 *
 * Token ranges are clamped to `[0, text.length)` so a slightly stale token list (one that lags an
 * edit) cannot crash the renderer.
 */
fun annotateTokens(text: String, tokens: List<Token>, theme: EditorTheme): AnnotatedString {
    val defaultColor = theme.tokenColors[TokenType.Default]
    return buildAnnotatedString {
        append(text)
        if (defaultColor != null && text.isNotEmpty()) {
            addStyle(SpanStyle(color = defaultColor), 0, text.length)
        }
        for (token in tokens) {
            val color = theme.tokenColors[token.type] ?: continue
            val start = token.start.coerceIn(0, text.length)
            val end = token.end.coerceIn(start, text.length)
            if (start < end) addStyle(SpanStyle(color = color), start, end)
        }
    }
}
