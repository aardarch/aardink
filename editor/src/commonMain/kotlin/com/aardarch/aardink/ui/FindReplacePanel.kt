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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.SearchHistory

/**
 * Slide-down find/replace panel anchored to the top of the editor.
 *
 * The panel mutates [state] directly. The host (typically [CodeEditorLayout]) is responsible for
 * watching the state and re-running [com.aardarch.aardink.core.FindEngine.findAll] on changes.
 *
 * It takes the keyboard focus when it opens and on every [FindReplaceState.show]: the find field,
 * or the replace field when opened for replacing with something already to find. In its fields,
 * Enter goes to the next match (Shift+Enter to the previous one, and Enter in the replace field
 * replaces the current match), Up and Down step through what was searched for (or replaced with)
 * before, and Escape closes the panel. Its options are VS Code's: match case, whole word, regular
 * expression, find in selection, and in the replace row, preserve case.
 *
 * @param state The state holder backing this panel.
 * @param onNext User pressed the "next match" arrow — host should scroll/select that match.
 * @param onPrev User pressed the "previous match" arrow.
 * @param onReplace Replace the current match with [FindReplaceState.replacement].
 * @param onReplaceAll Replace every match in the document.
 * @param onClose Close the panel (typically calls [FindReplaceState.hide]).
 */
@Composable
fun FindReplacePanel(
    state: FindReplaceState,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FindReplacePanel(state, onNext, onPrev, onReplace, onReplaceAll, onClose, modifier, onSearchWorkspace = null, replacePreview = null)
}

/**
 * [FindReplacePanel] as [CodeEditorLayout] shows it: with a button that hands the search to the
 * host's [onSearchWorkspace], and under the replace row, the current match's line with the
 * replacement made ([replacePreview]).
 */
@Composable
internal fun FindReplacePanel(
    state: FindReplaceState,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    onSearchWorkspace: (() -> Unit)?,
    replacePreview: AnnotatedString?,
) {
    EditorChromeTheme {
        AnimatedVisibility(
            visible = state.visible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = modifier,
        ) {
            val findFocus = remember { FocusRequester() }
            val replaceFocus = remember { FocusRequester() }
            // The find field's own value, so its text can be selected; the query stays the truth.
            var queryField by remember { mutableStateOf(TextFieldValue(state.query)) }
            if (queryField.text != state.query) queryField = TextFieldValue(state.query, TextRange(state.query.length))
            // On opening, and again on each show() while open (Ctrl+F pressed in the editor): the
            // query selected, as VS Code does, so what is typed next replaces it.
            LaunchedEffect(state.showRequests) {
                if (!state.visible) return@LaunchedEffect
                queryField = TextFieldValue(state.query, TextRange(0, state.query.length))
                // The fields (the replace row may be new) are attached from the next frame.
                withFrameNanos { }
                val target = if (state.replaceMode && state.query.isNotEmpty()) replaceFocus else findFocus
                runCatching { target.requestFocus() }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = editorWidgetBorder(),
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(EditorTestTags.FIND_PANEL)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Find row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Shows or hides the replace row, like the chevron in front of VS Code's find box.
                        IconButton(
                            onClick = { state.replaceMode = !state.replaceMode },
                            modifier = Modifier.semantics {
                                contentDescription = if (state.replaceMode) "Hide replace" else "Show replace"
                            },
                        ) {
                            Text(if (state.replaceMode) "▾" else "▸", modifier = Modifier.clearAndSetSemantics { })
                        }
                        OutlinedTextField(
                            value = queryField,
                            onValueChange = {
                                queryField = it
                                state.query = it.text
                            },
                            placeholder = { Text("Find") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = LocalEditorTypography.current.fontFamily,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(findFocus)
                                .testTag(EditorTestTags.FIND_FIELD)
                                .onEnterKey { shift -> if (shift) onPrev() else onNext() }
                                .onHistoryKeys(state.queryHistory, { state.query }, { state.query = it }),
                        )
                        Text(
                            text = matchLabel(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(64.dp),
                        )
                        // The glyphs mean nothing to a screen reader; the buttons carry the labels.
                        IconButton(
                            onClick = onPrev,
                            enabled = state.matches.isNotEmpty(),
                            modifier = Modifier.semantics { contentDescription = "Previous match" },
                        ) {
                            Text("▲", modifier = Modifier.clearAndSetSemantics { })
                        }
                        IconButton(
                            onClick = onNext,
                            enabled = state.matches.isNotEmpty(),
                            modifier = Modifier.semantics { contentDescription = "Next match" },
                        ) {
                            Text("▼", modifier = Modifier.clearAndSetSemantics { })
                        }
                        IconButton(onClick = onClose) {
                            Icon(EditorIcons.Close, contentDescription = "Close find and replace")
                        }
                    }

                    // Replace row, in replace mode only (Ctrl+H, or the toggle above)
                    if (state.replaceMode) {
                        ReplaceRow(state, onReplace, onReplaceAll, replaceFocus)
                        if (replacePreview != null) {
                            Text(
                                text = replacePreview,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalEditorTypography.current.fontFamily),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag(EditorTestTags.REPLACE_PREVIEW),
                            )
                        }
                    }

                    // Options row: scrolls sideways where the panel is narrower than its options.
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = state.caseSensitive,
                            onClick = { state.caseSensitive = !state.caseSensitive },
                            label = { Text("Aa") },
                        )
                        FilterChip(
                            selected = state.wholeWord,
                            onClick = { state.wholeWord = !state.wholeWord },
                            label = { Text("ab") },
                        )
                        FilterChip(
                            selected = state.useRegex,
                            onClick = { state.useRegex = !state.useRegex },
                            label = { Text(".*") },
                        )
                        FilterChip(
                            selected = state.inSelection,
                            onClick = { state.inSelection = !state.inSelection },
                            label = { Text("In selection", maxLines = 1, softWrap = false) },
                        )
                        if (state.replaceMode) {
                            FilterChip(
                                selected = state.preserveCase,
                                onClick = { state.preserveCase = !state.preserveCase },
                                label = { Text("Preserve case", maxLines = 1, softWrap = false) },
                            )
                        }
                        if (onSearchWorkspace != null) {
                            TextButton(onClick = onSearchWorkspace) { Text("Search all files", maxLines = 1, softWrap = false) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplaceRow(state: FindReplaceState, onReplace: () -> Unit, onReplaceAll: () -> Unit, focus: FocusRequester) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.replacement,
            onValueChange = { state.replacement = it },
            placeholder = { Text("Replace") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = LocalEditorTypography.current.fontFamily,
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .testTag(EditorTestTags.REPLACE_FIELD)
                .onEnterKey { onReplace() }
                .onHistoryKeys(state.replaceHistory, { state.replacement }, { state.replacement = it }),
        )
        TextButton(
            onClick = onReplace,
            enabled = state.matches.isNotEmpty() && state.currentMatchIndex >= 0,
        ) { Text("Replace") }
        TextButton(
            onClick = onReplaceAll,
            enabled = state.matches.isNotEmpty(),
        ) { Text("All") }
    }
}

/** Up and Down step through [history], from what [current] holds, into [set]. */
private fun Modifier.onHistoryKeys(history: SearchHistory, current: () -> String, set: (String) -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionUp -> true.also { history.older(current())?.let(set) }
            Key.DirectionDown -> true.also { history.newer()?.let(set) }
            else -> false
        }
    }

/** Runs [action] on Enter, with whether Shift is down, and keeps the key from the field. */
private fun Modifier.onEnterKey(action: (shift: Boolean) -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
        action(event.isShiftPressed)
        true
    } else {
        false
    }
}

private fun matchLabel(state: FindReplaceState): String = when {
    state.matches.isEmpty() -> if (state.query.isEmpty()) "" else "0"

    // Past the cap the total is unknown: "3/10000+".
    state.matchesCapped -> "${state.currentMatchIndex + 1}/${state.matches.size}+"

    else -> "${state.currentMatchIndex + 1}/${state.matches.size}"
}
