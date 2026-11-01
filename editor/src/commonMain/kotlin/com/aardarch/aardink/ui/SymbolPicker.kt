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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CompletionFilter
import com.aardarch.aardink.core.DocumentSymbol
import com.aardarch.aardink.core.SymbolKind
import com.aardarch.aardink.core.flattenSymbols

/** One row of the symbol picker: a symbol and how deep it is in the tree. */
internal class SymbolRow(val symbol: DocumentSymbol, val depth: Int)

/**
 * The rows the picker shows for [query]: every symbol, depth first, while the query is empty;
 * otherwise those whose name has the query's characters in order from a word start, as the
 * completion list matches, flat.
 */
internal fun symbolRows(symbols: List<DocumentSymbol>, query: String): List<SymbolRow> {
    val all = flattenSymbols(symbols)
    if (query.isBlank()) return all.map { (symbol, depth) -> SymbolRow(symbol, depth) }
    val typed = query.trim()
    return all.filter { (symbol, _) -> CompletionFilter.matches(typed, symbol.name) }.map { (symbol, _) -> SymbolRow(symbol, 0) }
}

/** A short label for a symbol's kind, beside its name. */
internal fun SymbolKind.label(): String = when (this) {
    SymbolKind.Class -> "class"
    SymbolKind.Method -> "method"
    SymbolKind.Function -> "fun"
    SymbolKind.Constructor -> "ctor"
    SymbolKind.Property -> "prop"
    SymbolKind.Field -> "field"
    SymbolKind.Variable -> "var"
    SymbolKind.Constant -> "const"
    SymbolKind.Interface -> "interface"
    SymbolKind.Enum -> "enum"
    SymbolKind.EnumMember -> "member"
    SymbolKind.Object -> "object"
    SymbolKind.Array -> "array"
    SymbolKind.Key -> "key"
    SymbolKind.Module -> "module"
    SymbolKind.Namespace -> "namespace"
    SymbolKind.Package -> "package"
    else -> name.lowercase()
}

/**
 * Go to Symbol (Ctrl/Cmd+Shift+O), as VS Code's: a field to filter the document's [symbols] by
 * name and the list of them. Up and Down move the selected row, Enter or a click picks it
 * ([onPick]), Escape closes ([onDismiss]). It takes the focus while it is up.
 */
@Composable
internal fun SymbolPickerCard(
    symbols: List<DocumentSymbol>,
    onPick: (DocumentSymbol) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val rows = remember(symbols, query) { symbolRows(symbols, query) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(selected, rows) {
        if (selected in rows.indices && listState.layoutInfo.visibleItemsInfo.none { it.index == selected && it.offset >= 0 }) {
            listState.scrollToItem(selected)
        }
    }
    EditorChromeTheme {
        Surface(
            modifier = modifier
                .widthIn(min = 260.dp, max = 520.dp)
                .heightIn(max = 320.dp)
                .shadow(8.dp, RoundedCornerShape(8.dp))
                .testTag(EditorTestTags.SYMBOL_PICKER),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column {
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        selected = 0
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .focusRequester(focus)
                        .testTag(EditorTestTags.SYMBOL_PICKER_FIELD)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> true.also { if (rows.isNotEmpty()) selected = (selected + 1) % rows.size }
                                Key.DirectionUp -> true.also { if (rows.isNotEmpty()) selected = (selected - 1 + rows.size) % rows.size }
                                Key.Enter, Key.NumPadEnter -> true.also { rows.getOrNull(selected)?.let { onPick(it.symbol) } }
                                Key.Escape -> true.also { onDismiss() }
                                else -> false
                            }
                        },
                    decorationBox = { field ->
                        if (query.isEmpty()) {
                            Text(
                                text = "Go to symbol",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        field()
                    },
                )
                HorizontalDivider()
                if (rows.isEmpty()) {
                    Text(
                        text = if (symbols.isEmpty()) "No symbols" else "No matching symbols",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                LazyColumn(state = listState) {
                    itemsIndexed(rows) { index, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (index == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .clickable { onPick(row.symbol) }
                                .padding(start = (8 + 12 * row.depth).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = row.symbol.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = row.symbol.detail ?: row.symbol.kind.label(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The breadcrumbs row ([EditorOptions.breadcrumbs]): the symbols the caret is in, outermost first,
 * separated by `›`. A tap on one picks it ([onPick]). It scrolls sideways when the path is long.
 */
@Composable
internal fun EditorBreadcrumbs(path: List<DocumentSymbol>, onPick: (DocumentSymbol) -> Unit, modifier: Modifier = Modifier) {
    EditorChromeTheme {
        Row(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag(EditorTestTags.BREADCRUMBS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (path.isEmpty()) {
                Text(text = " ", style = MaterialTheme.typography.labelMedium)
            }
            path.forEachIndexed { index, symbol ->
                if (index > 0) {
                    Text(text = "›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = symbol.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index ==
                        path.lastIndex
                    ) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = Modifier.clickable { onPick(symbol) }.padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
        }
    }
}
