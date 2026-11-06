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
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
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
 * What highlights a code fence in documentation (hover, signature help, a completion's detail
 * pane), by the language the fence is tagged with, such as the `kotlin` of ` ```kotlin `. Answer
 * null for a language you do not know, and the fence shows plain; a fence with no tag takes the
 * tokenizer of the [CodeEditorState][com.aardarch.aardink.core.CodeEditorState] it belongs to.
 * Popups inherit it, so provide it around [CodeEditorLayout]:
 * ```kotlin
 * CompositionLocalProvider(LocalCodeFenceTokenizers provides { registry.byId(it)?.tokenizer }) {
 *     CodeEditorLayout(state)
 * }
 * ```
 * The default knows no language.
 */
val LocalCodeFenceTokenizers: ProvidableCompositionLocal<(languageId: String) -> IncrementalTokenizer?> =
    compositionLocalOf { { null } }

/**
 * [text] highlighted by [tokenizer] in the colours of [LocalEditorTheme] and the font of
 * [LocalEditorTypography], with no input, no gutter and no caret: for showing code in a
 * reference screen or a help page. It scrolls sideways when a line is wide, and is as tall as the
 * text. Meant for short snippets: the whole text is tokenized and laid out at once, unlike
 * [CodeEditorLayout], which lays out only the lines on screen. It shows the text plain until the
 * tokenizer, run off the main thread, has coloured it. [tokenizer] can be one a document is using:
 * it is asked with [IncrementalTokenizer.tokenizeLines] and no previous tokens, the pass that keeps
 * nothing.
 */
@Composable
fun CodeBlock(text: String, tokenizer: IncrementalTokenizer, modifier: Modifier = Modifier) {
    val theme = LocalEditorTheme.current
    val typography = LocalEditorTypography.current
    var tokens by remember(text, tokenizer) { mutableStateOf<List<Token>?>(null) }
    LaunchedEffect(text, tokenizer) {
        tokens = withContext(EditorDispatchers.compute) { tokensOf(text, tokenizer) }
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

/**
 * [text]'s tokens, by the pass that keeps nothing. A full pass would take a place among the few
 * scans a tokenizer keeps for incremental passes, and [tokenizer] is often the object a document
 * is using: the built-in languages are singletons. A handful of snippets would then push out the
 * document's scan, and its next edit would rescan the whole of it, on the web on the main thread.
 *
 * Not in slices: documentation is cut at [MARKDOWN_MAX_LENGTH], far under the size at which the
 * editor starts slicing a document's own passes.
 */
internal fun tokensOf(text: String, tokenizer: IncrementalTokenizer): List<Token> =
    tokenizer.tokenizeLines(text, 0..text.count { it == '\n' }, emptyList())

/** [text] with each token's colour and font style, as the editor draws them. */
internal fun highlighted(text: String, tokens: List<Token>, resolver: TokenStyleResolver): AnnotatedString = buildAnnotatedString {
    append(text)
    for (token in tokens) {
        if (token.end > text.length || token.end <= token.start) continue
        val style = resolver.spanStyle(token.type) ?: continue
        addStyle(style, token.start, token.end)
    }
}
