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
package com.aardarch.aardink.ui.view

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.edit.SelectionSet
import com.aardarch.aardink.platform.PlatformInfo
import com.aardarch.aardink.ui.EditorAnchoredPopup
import com.aardarch.aardink.ui.EditorChromeTheme
import com.aardarch.aardink.ui.EditorTestTags
import kotlin.math.roundToInt

/** Which selection handle is being dragged. */
internal enum class SelectionHandle { Start, End, Insertion }

/**
 * The teardrop handles at the ends of the selection, or under the caret, after a touch: dragging
 * one moves that end (or the caret), with Android's magnifier over the text beneath the finger.
 * Tapping the caret's handle opens the text toolbar, for pasting.
 */
@Composable
internal fun BoxScope.EditorSelectionHandles(controller: EditorController, color: Color) {
    if (!controller.touchMode || !controller.focused) return
    val state = controller.state
    val selection = state.selection
    val frame = controller.view.frame
    if (selection.collapsed) {
        if (!controller.readOnly) SelectionHandleBox(controller, SelectionHandle.Insertion, selection.end, frame, color)
    } else {
        SelectionHandleBox(controller, SelectionHandle.Start, selection.min, frame, color)
        SelectionHandleBox(controller, SelectionHandle.End, selection.max, frame, color)
    }
}

@Composable
private fun SelectionHandleBox(controller: EditorController, handle: SelectionHandle, offset: Int, frame: ViewFrame, color: Color) {
    val density = LocalDensity.current
    val size = with(density) { HANDLE_SIZE.toPx() }
    // Only while the caret it marks is on screen.
    val caret = controller.view.caretRect(offset)
    if (caret.bottom < 0f || caret.top > frame.height || caret.left < 0f || caret.left > frame.width) return
    val left = when (handle) {
        SelectionHandle.Start -> caret.left - size
        SelectionHandle.End -> caret.left
        SelectionHandle.Insertion -> caret.left - size / 2f
    }
    var drag by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), caret.bottom.roundToInt()) }
            .size(HANDLE_SIZE)
            .testTag(EditorTestTags.HANDLE + handle.name)
            .pointerInput(controller, handle) {
                detectTapGestures { if (handle == SelectionHandle.Insertion) controller.showToolbarAtCaret = true }
            }
            .pointerInput(controller, handle) {
                detectDragGestures(
                    onDragStart = {
                        val start = controller.view.caretRect(
                            if (handle ==
                                SelectionHandle.Start
                            ) {
                                controller.state.selection.min
                            } else {
                                controller.state.selection.max
                            },
                        )
                        // The finger holds the handle below the text: aim at the middle of the line above it.
                        drag = Offset(start.left, start.center.y)
                        controller.draggingHandle = handle
                        controller.magnifierCenter = drag
                    },
                    onDragEnd = {
                        controller.draggingHandle = null
                        controller.magnifierCenter = null
                    },
                    onDragCancel = {
                        controller.draggingHandle = null
                        controller.magnifierCenter = null
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag += amount
                        controller.magnifierCenter = drag
                        controller.dragHandle(handle, drag)
                    },
                )
            }
            .drawBehind { drawHandle(handle, color) },
    )
}

/** A circle with one square corner, pointing at the text: up-right for the start, up-left for the end, up for the caret. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(handle: SelectionHandle, color: Color) {
    val radius = size.minDimension / 2f
    drawCircle(color, radius, Offset(radius, radius))
    when (handle) {
        SelectionHandle.Start -> drawRect(color, Offset(radius, 0f), Size(radius, radius))
        SelectionHandle.End -> drawRect(color, Offset.Zero, Size(radius, radius))
        SelectionHandle.Insertion -> rotateSquareCorner(color, radius)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.rotateSquareCorner(color: Color, radius: Float) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(radius, 0f)
        lineTo(radius + radius * 0.7f, radius * 0.7f)
        lineTo(radius - radius * 0.7f, radius * 0.7f)
        close()
    }
    drawPath(path, color)
}

/**
 * The menu over a touch selection (and over the caret after its handle is tapped): cut, copy,
 * paste, select all, and "Info" (the documentation of the symbol there) when the host offers it.
 * The editor's own rather than the platform's text toolbar, which takes no items of an app's own.
 * Hidden while a handle is dragged. Its colours follow the active editor theme, as the keyboard
 * toolbar's do.
 */
@Composable
internal fun EditorTouchMenu(controller: EditorController) {
    val state = controller.state
    val selection = state.selection
    val wanted = controller.touchMode && controller.focused && controller.draggingHandle == null &&
        (!selection.collapsed || controller.showToolbarAtCaret)
    if (!wanted) return
    controller.view.scroll.scrollY
    val start = controller.view.caretRect(selection.min)
    val end = controller.view.caretRect(selection.max)
    // Down to the bottom of the handles: when there is no room above, the menu goes below them.
    val handles = with(LocalDensity.current) { HANDLE_SIZE.toPx() }
    val anchor = Rect(minOf(start.left, end.left), start.top, maxOf(start.right, end.right, start.left + 1f), end.bottom + handles)
    fun done() {
        controller.showToolbarAtCaret = false
    }
    EditorAnchoredPopup(anchor = anchor, preferAbove = true, onDismiss = ::done) {
        EditorChromeTheme { TouchMenuContent(controller, ::done) }
    }
}

@Composable
private fun TouchMenuContent(controller: EditorController, done: () -> Unit) {
    val state = controller.state
    val selection = state.selection
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag(EditorTestTags.TOUCH_MENU),
    ) {
        Row {
            val editable = !controller.readOnly
            val hasSelection = !selection.collapsed
            if (editable && hasSelection) {
                TextButton(onClick = {
                    done()
                    controller.cutToClipboard()
                }) { Text("Cut") }
            }
            if (hasSelection) {
                TextButton(onClick = {
                    done()
                    controller.copyToClipboard()
                }) { Text("Copy") }
            }
            if (editable) {
                TextButton(onClick = {
                    done()
                    controller.pasteFromClipboard()
                }) { Text("Paste") }
            }
            TextButton(onClick = {
                done()
                state.selectAll()
            }) { Text("Select all") }
            val showInfo = controller.actions.onShowInfo
            if (showInfo != null) {
                TextButton(onClick = {
                    done()
                    showInfo(selection.min)
                }) { Text("Info") }
            }
        }
    }
}

/** The right-click menu: cut, copy, paste and select all, at the pointer. */
@Composable
internal fun BoxScope.EditorContextMenu(controller: EditorController) {
    val at = controller.contextMenuAt ?: return
    fun close() {
        controller.contextMenuAt = null
    }
    Box(modifier = Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.size(0.dp)) {
        EditorChromeTheme { ContextMenuContent(controller, ::close) }
    }
}

@Composable
private fun ContextMenuContent(controller: EditorController, close: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = close) {
        val editable = !controller.readOnly
        DropdownMenuItem(text = { Text("Cut") }, enabled = editable, onClick = {
            close()
            controller.cutToClipboard()
        })
        DropdownMenuItem(text = { Text("Copy") }, onClick = {
            close()
            controller.copyToClipboard()
        })
        DropdownMenuItem(text = { Text("Paste") }, enabled = editable, onClick = {
            close()
            controller.pasteFromClipboard()
        })
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Select All") }, onClick = {
            close()
            controller.state.replaceSelections(SelectionSet.single(TextRange(0, controller.state.document.length)))
        })
        if (controller.languageService() != null) {
            val mac = PlatformInfo.isMacOs
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Go to Definition") }, trailingIcon = { Shortcut("F12") }, onClick = {
                close()
                controller.actions.onGoToDefinition()
            })
            DropdownMenuItem(text = {
                Text("Find References")
            }, trailingIcon = { Shortcut(if (mac) "⇧F12" else "Shift+F12") }, onClick = {
                close()
                controller.actions.onFindReferences()
            })
            val selection = controller.state.selection
            DropdownMenuItem(
                text = { Text(if (selection.collapsed) "Format Document" else "Format Selection") },
                trailingIcon = { Shortcut(if (mac) "⇧⌥F" else "Shift+Alt+F") },
                enabled = editable,
                onClick = {
                    close()
                    controller.actions.onFormat()
                },
            )
        }
    }
}

/** A menu item's key, as the platform writes it. */
@Composable
private fun Shortcut(keys: String) {
    Text(keys, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val HANDLE_SIZE = 22.dp
