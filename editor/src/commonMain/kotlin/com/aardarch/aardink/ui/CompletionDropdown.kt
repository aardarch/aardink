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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind

/**
 * Horizontal strip of completion chips shown above the keyboard toolbar when completions
 * are available. Each chip shows a kind badge + label; tapping accepts the item.
 *
 * This strip approach works well on mobile: no cursor-relative positioning complexity, and
 * it stays visible while the keyboard is up. Its colours follow the active [LocalEditorTheme].
 */
@Composable
fun CompletionDropdown(items: List<CompletionItem>, visible: Boolean, onAccept: (CompletionItem) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible && items.isNotEmpty(),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        val keys = remember(items) { completionKeys(items) }
        EditorChromeTheme {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(items = items, key = { index, _ -> keys[index] }) { _, item ->
                    CompletionChip(item = item, onClick = { onAccept(item) })
                }
            }
        }
    }
}

/**
 * A unique key per item: its kind and label, numbered when they repeat. A list can hold two items
 * with the same kind and label (a language and the one it extends both offer `true`, say), and
 * a lazy list fails on a duplicate key.
 */
internal fun completionKeys(items: List<CompletionItem>): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val key = "${item.kind.name}:${item.label}"
        val count = seen[key] ?: 0
        seen[key] = count + 1
        if (count == 0) key else "$key#$count"
    }
}

@Composable
private fun CompletionChip(item: CompletionItem, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompletionKindBadge(item.kind)
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalEditorTypography.current.fontFamily),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A completion's kind, as a letter in a tinted square. */
@Composable
internal fun CompletionKindBadge(kind: CompletionKind) {
    val (badgeLabel, badgeColor) = when (kind) {
        CompletionKind.Element -> "E" to MaterialTheme.colorScheme.primary
        CompletionKind.Attribute -> "A" to MaterialTheme.colorScheme.secondary
        CompletionKind.Value -> "V" to MaterialTheme.colorScheme.tertiary
        CompletionKind.Snippet -> "S" to MaterialTheme.colorScheme.error
        CompletionKind.Module -> "M" to MaterialTheme.colorScheme.primary
        CompletionKind.Property -> "P" to MaterialTheme.colorScheme.secondary
        CompletionKind.Transform -> "T" to MaterialTheme.colorScheme.tertiary
        CompletionKind.ColorRef -> "C" to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = badgeColor.copy(alpha = 0.15f),
        modifier = Modifier.size(18.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = badgeLabel,
                fontSize = 9.sp,
                color = badgeColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
