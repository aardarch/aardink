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

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenStyleResolver
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.platform.EditorDispatchers
import kotlinx.coroutines.withContext

/**
 * [text] highlighted by [tokenizer] in the colours of [LocalEditorTheme] and the font of
 * [LocalEditorTypography], with no input, no gutter and no caret: for showing code in a
 * reference screen or a help page. It scrolls sideways when a line is wide, and is as tall as the
 * text. Meant for short snippets: the whole text is laid out at once, unlike [CodeEditorLayout],
 * which lays out only the lines on screen. It shows the text plain until the tokenizer, run off
 * the main thread, has coloured it.
 */
@Composable
fun CodeBlock(text: String, tokenizer: IncrementalTokenizer, modifier: Modifier = Modifier) {
    val theme = LocalEditorTheme.current
    val typography = LocalEditorTypography.current
    var tokens by remember(text, tokenizer) { mutableStateOf<List<Token>?>(null) }
    LaunchedEffect(text, tokenizer) {
        tokens = withContext(EditorDispatchers.compute) { tokenizer.tokenizeFull(text) }
    }
    val resolver = remember(theme) { TokenStyleResolver(theme) }
    val styled = remember(text, tokens, resolver) { highlighted(text, tokens.orEmpty(), resolver) }
    val foreground = theme.tokenColors[TokenType.Default] ?: EditorChromeColors(theme).foreground
    Box(
        modifier = modifier
            .background(theme.background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BasicText(
            text = styled,
            style = TextStyle(
                fontFamily = typography.fontFamily,
                fontSize = typography.fontSize,
                lineHeight = typography.lineHeight,
                color = foreground,
            ),
            softWrap = false,
        )
    }
}

/** [text] with each token's colour and font style, as the editor draws them. */
internal fun highlighted(text: String, tokens: List<Token>, resolver: TokenStyleResolver): AnnotatedString = buildAnnotatedString {
    append(text)
    for (token in tokens) {
        if (token.end > text.length || token.end <= token.start) continue
        val style = resolver.spanStyle(token.type) ?: continue
        addStyle(style, token.start, token.end)
    }
}
