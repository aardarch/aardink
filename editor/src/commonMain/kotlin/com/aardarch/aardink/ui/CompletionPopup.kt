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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.aardarch.aardink.core.CompletionItem

/** As tall as [CompletionList], so the two read as one thing. */
private val DETAIL_MAX_HEIGHT = 240.dp
private val DETAIL_MAX_WIDTH = 360.dp

/** A signature can be long; the documentation under it still needs room. */
private const val DETAIL_MAX_LINES = 4

/**
 * The completion list at [anchor], and the pane beside it about the [selected] item, for a hardware
 * keyboard: [CodeEditorLayout] composes this where it would not draw [CompletionDropdown]'s strip,
 * so a touch device has neither. [resolve] gives the item the pane shows, which can take a moment;
 * the pane starts with the item as it is. A link in the pane goes to [onOpenLink].
 */
@Composable
internal fun CompletionPopup(
    anchor: Rect,
    items: List<CompletionItem>,
    selected: Int,
    onAccept: (CompletionItem) -> Unit,
    onDismiss: () -> Unit,
    resolve: suspend (CompletionItem) -> CompletionItem,
    onOpenLink: ((String) -> Unit)?,
) {
    var listSize by remember { mutableStateOf(IntSize.Zero) }
    EditorAnchoredPopup(anchor = anchor, onDismiss = onDismiss) {
        CompletionList(items, selected, onAccept, Modifier.onSizeChanged { listSize = it })
    }
    val item = items.getOrNull(selected)
    // Waiting for the list to measure is also what puts the pane's layer above the list's, wherever
    // this call sits: a press on the pane then does not count as one outside the list, which would
    // close them both.
    if (item != null && listSize != IntSize.Zero) CompletionDetailPopup(item, anchor, listSize, resolve, onOpenLink)
}

@Composable
private fun CompletionDetailPopup(
    item: CompletionItem,
    anchor: Rect,
    listSize: IntSize,
    resolve: suspend (CompletionItem) -> CompletionItem,
    onOpenLink: ((String) -> Unit)?,
) {
    val currentResolve by rememberUpdatedState(resolve)
    // A keystroke re-addresses the item (shiftedForInsert), which makes it a new value but the same
    // completion: the pane keeps its place and its resolved answer through that.
    val identity = item.copy(replaceRange = null, additionalEdits = emptyList())
    var shown by remember(identity) { mutableStateOf(item) }
    LaunchedEffect(identity) { shown = currentResolve(item) }
    if (shown.detail.isNullOrBlank() && shown.documentation.isNullOrBlank()) return
    val gap = with(LocalDensity.current) { POPUP_GAP.roundToPx() }
    val provider = remember(anchor, listSize, gap) { BesidePopupPositionProvider(anchor, listSize, gap) }
    // No dismissal of its own: it goes when the list does.
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
        // Each item's documentation starts at its top, however far the last one was scrolled.
        key(identity) { CompletionDetailCard(shown, onOpenLink) }
    }
}

/** [item]'s [detail][CompletionItem.detail] and, as Markdown, its [documentation][CompletionItem.documentation]. */
@Composable
internal fun CompletionDetailCard(item: CompletionItem, onOpenLink: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val detail = item.detail?.takeIf { it.isNotBlank() }
    val documentation = item.documentation?.takeIf { it.isNotBlank() }
    EditorChromeTheme {
        Surface(
            modifier = modifier
                .widthIn(max = DETAIL_MAX_WIDTH)
                .heightIn(max = DETAIL_MAX_HEIGHT)
                .shadow(8.dp, RoundedCornerShape(8.dp))
                .testTag(EditorTestTags.COMPLETION_DETAIL),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalEditorTypography.current.fontFamily),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = DETAIL_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (detail != null && documentation != null) HorizontalDivider(thickness = 0.5.dp)
                if (documentation != null) {
                    MarkdownDoc(
                        text = documentation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        onOpenLink = onOpenLink,
                    )
                }
            }
        }
    }
}
