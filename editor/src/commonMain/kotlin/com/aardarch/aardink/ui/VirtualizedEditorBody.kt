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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.platform.EditorClipboardEvents
import com.aardarch.aardink.platform.EditorViewScrollbars
import com.aardarch.aardink.platform.editorMagnifier
import com.aardarch.aardink.ui.view.EditorBody
import com.aardarch.aardink.ui.view.EditorColors
import com.aardarch.aardink.ui.view.EditorContextMenu
import com.aardarch.aardink.ui.view.EditorController
import com.aardarch.aardink.ui.view.EditorDecorations
import com.aardarch.aardink.ui.view.EditorGutterView
import com.aardarch.aardink.ui.view.EditorInputElement
import com.aardarch.aardink.ui.view.EditorMetrics
import com.aardarch.aardink.ui.view.EditorSelectionHandles
import com.aardarch.aardink.ui.view.EditorTextToolbar
import com.aardarch.aardink.ui.view.EditorViewport
import com.aardarch.aardink.ui.view.GutterContent
import com.aardarch.aardink.ui.view.ViewStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The gutter, text area and scrollbars of the editor's own renderer: what the `BasicTextField`
 * and its gutter are in the text-field one. Only the lines on screen are laid out and drawn.
 *
 * [controller] handles the input: focus, keys, the input method, the pointer, the clipboard.
 *
 * @param gutter what the gutter shows, or null for no gutter.
 */
@Composable
internal fun VirtualizedEditorBody(
    state: CodeEditorState,
    controller: EditorController,
    foldState: FoldState?,
    diagnostics: List<Diagnostic>,
    findReplaceState: FindReplaceState?,
    gutter: GutterContent?,
    softWrap: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val view = controller.view
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
    val focusRequester = remember { FocusRequester() }
    val clipboard = LocalClipboard.current
    SideEffect {
        view.style = style
        view.foldState = foldState
        controller.pointer.foldState = foldState
        controller.clipboard = clipboard
        controller.requestFocus = { runCatching { focusRequester.requestFocus() } }
    }

    // The carets blink while the editor has focus, and show solid again after every change.
    var caretOn by remember { mutableStateOf(true) }
    LaunchedEffect(controller) {
        snapshotFlow { Triple(controller.focused, state.selections, state.textVersion) }.collectLatest { (focused) ->
            caretOn = true
            while (focused) {
                delay(CARET_BLINK_MS)
                caretOn = !caretOn
            }
        }
    }
    // A tap in the text asks for the on-screen keyboard. On the web this also gives Compose's hidden
    // text area the page's focus back, which a touch on the canvas takes away.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(controller, keyboard) {
        var seen = controller.ime.keyboardRequests
        snapshotFlow { controller.ime.keyboardRequests }.collect {
            if (it != seen) keyboard?.show()
            seen = it
        }
    }
    EditorClipboardEvents(
        active = controller.focused,
        onCopy = { controller.copyText() },
        onCut = { controller.cutText() },
        onPaste = { controller.pasteText(it) },
    )
    EditorTextToolbar(controller)

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
                pointer = controller.pointer,
                decorations = {
                    EditorDecorations(
                        selections = state.selections,
                        findMatches = findReplaceState?.matches.orEmpty(),
                        currentFindMatch = findReplaceState?.currentMatchIndex ?: -1,
                        diagnostics = diagnostics,
                        composition = controller.ime.composition,
                    )
                },
                carets = { if (controller.focused && caretOn) state.selections else emptyList() },
                colors = colors,
                softWrap = softWrap,
                modifier = Modifier.fillMaxSize().testTag(EditorTestTags.EDITOR),
                inputModifier = Modifier
                    .then(EditorInputElement(controller))
                    .focusRequester(focusRequester)
                    .focusTarget()
                    .editorMagnifier { controller.magnifierCenter },
                overlay = {
                    EditorSelectionHandles(controller, theme.cursorColor)
                    EditorContextMenu(controller)
                },
            )
        },
        scrollbars = { EditorViewScrollbars(view.scroll, horizontal = !softWrap, modifier = Modifier.fillMaxSize()) },
    )
}

/** Half a caret blink: VS Code's default, 1.06 s a cycle. */
private const val CARET_BLINK_MS = 530L

/** Measured to find the advance of one character of the (monospace) editor font. */
private const val CHAR_WIDTH_SAMPLE = "0000000000000000000000000000000000000000000000000000000000000000"
