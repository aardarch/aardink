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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.Location

/** One reference as the peek list shows it: where it is, and the text of its line when that is this document's. */
@Immutable
internal class ReferenceItem(val location: Location, val place: String, val preview: String) {
    companion object {
        /** [location] described against [document], the editor's own. */
        fun of(location: Location, document: CodeDocument): ReferenceItem {
            if (location.isIn(document)) {
                val line = document.offsetToLineCol(location.range.first).first
                return ReferenceItem(location, "${line + 1}", document.lineText(line).trim().take(MAX_PREVIEW_CHARS))
            }
            val file = location.uri.substringAfterLast('/')
            val line = location.line?.let { ":${it + 1}" }.orEmpty()
            val column = location.column?.takeIf { location.line != null }?.let { ":${it + 1}" }.orEmpty()
            return ReferenceItem(location, "$file$line$column", "")
        }
    }
}

/**
 * Whether this location is in [document], the editor's own: a [Location] carries a range only for
 * the document it was asked about, and [IntRange.EMPTY] for any other file.
 */
internal fun Location.isIn(document: CodeDocument): Boolean = range != IntRange.EMPTY && range.first in 0..document.length

/**
 * The references to a symbol as a list at the caret (Shift+F12), as VS Code's peek: each row is
 * where a reference is and the text around it. The [selected] row is what Enter opens, and Up and
 * Down move it; a click opens a row too.
 */
@Composable
internal fun ReferencesPeekCard(
    items: List<ReferenceItem>,
    selected: Int,
    onOpen: (ReferenceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected, items) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (selected in items.indices && visible.none { it.index == selected && it.offset >= 0 }) {
            listState.scrollToItem(selected)
        }
    }
    Surface(
        modifier = modifier
            .widthIn(min = 260.dp, max = 520.dp)
            .heightIn(max = 280.dp)
            .shadow(8.dp, RoundedCornerShape(8.dp))
            .testTag(EditorTestTags.REFERENCES),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Text(
                text = if (items.size == 1) "1 reference" else "${items.size} references",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            HorizontalDivider()
            LazyColumn(state = listState) {
                itemsIndexed(items) { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (index == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onOpen(item) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.place,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            text = item.preview,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalEditorTypography.current.fontFamily),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Characters of a reference's line the list shows. */
private const val MAX_PREVIEW_CHARS = 160
