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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CompletionItem

/**
 * Completions as a list at the caret, where there is a hardware keyboard (desktop, the web): the
 * [selected] row is what Enter or Tab accepts, and Up and Down move it. A tap or click accepts a
 * row too. Touch devices keep [CompletionDropdown]'s strip above the keyboard instead.
 */
@Composable
internal fun CompletionList(
    items: List<CompletionItem>,
    selected: Int,
    onAccept: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Keep the selected row in view as the arrows move it.
    LaunchedEffect(selected, items) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (selected in items.indices && visible.none { it.index == selected && it.offset >= 0 }) {
            listState.scrollToItem(selected)
        }
    }
    EditorChromeTheme {
        Surface(
            modifier = modifier
                .widthIn(min = 220.dp, max = 420.dp)
                .heightIn(max = 240.dp)
                .shadow(8.dp, RoundedCornerShape(8.dp))
                .testTag(EditorTestTags.COMPLETION_LIST),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            LazyColumn(state = listState) {
                itemsIndexed(items) { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (index == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onAccept(item) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompletionKindBadge(item.kind)
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalEditorTypography.current.fontFamily),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        item.documentation?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
        }
    }
}
