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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.FindReplaceState

/**
 * Slide-down find/replace panel anchored to the top of the editor.
 *
 * The panel mutates [state] directly. The host (typically [CodeEditorLayout]) is responsible for
 * watching the state and re-running [com.aardarch.aardink.core.FindEngine.findAll] on changes.
 *
 * It takes the keyboard focus when it opens and on every [FindReplaceState.show]: the find field,
 * or the replace field when opened for replacing with something already to find. In its fields,
 * Enter goes to the next match (Shift+Enter to the previous one, and Enter in the replace field
 * replaces the current match), and Escape closes the panel.
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
    AnimatedVisibility(
        visible = state.visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val findFocus = remember { FocusRequester() }
        val replaceFocus = remember { FocusRequester() }
        // On opening, and again on each show() while open (Ctrl+F pressed in the editor).
        LaunchedEffect(state.showRequests) {
            if (!state.visible) return@LaunchedEffect
            // The fields (the replace row may be new) are attached from the next frame.
            withFrameNanos { }
            val target = if (state.replaceMode && state.query.isNotEmpty()) replaceFocus else findFocus
            runCatching { target.requestFocus() }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                        value = state.query,
                        onValueChange = { state.query = it },
                        placeholder = { Text("Find") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = LocalEditorTypography.current.fontFamily,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(findFocus)
                            .testTag(EditorTestTags.FIND_FIELD)
                            .onEnterKey { shift -> if (shift) onPrev() else onNext() },
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
                }

                // Options row
                Row(

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
                .onEnterKey { onReplace() },
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
