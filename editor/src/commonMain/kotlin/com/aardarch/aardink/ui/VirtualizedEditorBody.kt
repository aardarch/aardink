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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.platform.EditorViewScrollbars
import com.aardarch.aardink.ui.view.EditorBody
import com.aardarch.aardink.ui.view.EditorColors
import com.aardarch.aardink.ui.view.EditorDecorations
import com.aardarch.aardink.ui.view.EditorGutterView
import com.aardarch.aardink.ui.view.EditorMetrics
import com.aardarch.aardink.ui.view.EditorPointerHandler
import com.aardarch.aardink.ui.view.EditorView
import com.aardarch.aardink.ui.view.EditorViewport
import com.aardarch.aardink.ui.view.GutterContent
import com.aardarch.aardink.ui.view.ViewStyle

/**
 * The gutter, text area and scrollbars of the editor's own renderer: what the `BasicTextField`
 * and its gutter are in the text-field one. Only the lines on screen are laid out and drawn.
 *
 * @param gutter what the gutter shows, or null for no gutter.
 * @param composition the input method's composing text, underlined.
 * @param inputModifier focus, keys and text input for the text area.
 */
@Composable
internal fun VirtualizedEditorBody(
    state: CodeEditorState,
    view: EditorView,
    pointer: EditorPointerHandler,
    foldState: FoldState?,
    diagnostics: List<Diagnostic>,
    findReplaceState: FindReplaceState?,
    gutter: GutterContent?,
    softWrap: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    composition: () -> TextRange? = { null },
    showCarets: () -> Boolean = { true },
    inputModifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val typography = LocalEditorTypography.current
    val theme = LocalEditorTheme.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val textStyle = remember(typography, textColor) {
        TextStyle(
            fontFamily = typography.fontFamily,
            fontSize = typography.fontSize,
            lineHeight = typography.lineHeight,
            color = textColor,
            // Every line is its own paragraph: none may lose the leading a whole-document
            // paragraph trims only from its first and last line.
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
        )
    }
    val metrics = remember(density, textStyle, measurer, typography) {
        val sample = measurer.measure(CHAR_WIDTH_SAMPLE, textStyle)
        with(density) {
            EditorMetrics(
                lineHeight = typography.lineHeight.toPx(),
                charWidth = sample.getLineRight(0) / CHAR_WIDTH_SAMPLE.length,
                paddingTop = EditorDefaults.contentPaddingTop.toPx(),
                paddingBottom = 0f,
                paddingStart = EditorDefaults.contentPaddingHorizontal.toPx(),
                paddingEnd = EditorDefaults.contentPaddingHorizontal.toPx(),
            )
        }
    }
    val tokenStyles = remember(theme) { theme.tokenColors.mapValues { SpanStyle(color = it.value) } }
    val placeholderStyle = remember(textColor) { SpanStyle(color = textColor.copy(alpha = 0.4f), fontStyle = FontStyle.Italic) }
    val style = ViewStyle(measurer, textStyle, tokenStyles, placeholderStyle, metrics, softWrap)
    SideEffect {
        view.style = style
        view.foldState = foldState
        pointer.foldState = foldState
    }

    val tertiary = MaterialTheme.colorScheme.tertiary
    val colors = EditorColors(
        selection = theme.selectionColor,
        findMatch = tertiary.copy(alpha = 0.35f),
        currentFindMatch = tertiary.copy(alpha = 0.7f),
        error = theme.errorColor,
        warning = theme.warningColor,
        info = theme.infoColor,
        caret = theme.cursorColor,
        text = textColor,
    )
    val lineCount = remember(state.textVersion) { state.document.lineCount }

    EditorBody(
        view = view,
        modifier = modifier,
        gutter = {
            if (gutter != null) {
                EditorGutterView(view, lineCount, theme.gutterBackground, theme.gutterForeground, gutter)
            }
        },
        viewport = {
            EditorViewport(
                view = view,
                pointer = pointer,
                decorations = {
                    EditorDecorations(
                        selections = state.selections,
                        findMatches = findReplaceState?.matches.orEmpty(),
                        currentFindMatch = findReplaceState?.currentMatchIndex ?: -1,
                        diagnostics = diagnostics,
                        composition = composition(),
                    )
                },
                carets = { if (showCarets()) state.selections else emptyList() },
                colors = colors,
                softWrap = softWrap,
                modifier = Modifier.fillMaxSize().testTag(EditorTestTags.EDITOR),
                inputModifier = inputModifier,
            )
        },
        scrollbars = { EditorViewScrollbars(view.scroll, horizontal = !softWrap, modifier = Modifier.fillMaxSize()) },
    )
}

/** Measured to find the advance of one character of the (monospace) editor font. */
private const val CHAR_WIDTH_SAMPLE = "0000000000000000000000000000000000000000000000000000000000000000"
