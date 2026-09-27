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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.edit.SelectionSet
import com.aardarch.aardink.core.edit.TextNavigator

/**
 * What pressing, dragging and tapping in the text do to the selections.
 *
 * Mouse: a click places the caret, Shift+click extends the selection, Alt+click adds a caret; a
 * double click selects a word and a triple click a line, and dragging after either extends the
 * selection by words or lines. Touch: a tap places the caret, a double tap selects a word, and a
 * long press selects one and extends by words as the finger moves. Clicking a fold's placeholder
 * opens the fold.
 */
internal class EditorPointerHandler(private val view: EditorView) {

    /** The host's folds, for opening one from its placeholder. */
    var foldState: FoldState? = null

    /** Called on every press in the text, before it moves the selection: focus and the keyboard. */
    var onPress: (touch: Boolean) -> Unit = {}

    private enum class SelectionUnit { Character, Word, Line }

    private var unit = SelectionUnit.Character

    /** What the gesture selected first: dragging extends from it. */
    private var origin: TextRange? = null

    private val state get() = view.state

    fun press(position: Offset, clicks: Int, shift: Boolean, alt: Boolean, touch: Boolean = false) {
        onPress(touch)
        val hit = view.offsetAt(position)
        if (hit.onFoldPlaceholder && clicks == 1 && !shift) {
            foldState?.toggle(hit.line)
            origin = null
            return
        }
        val current = state.currentSelections()
        when {
            clicks == 1 && shift -> {
                unit = SelectionUnit.Character
                val anchor = current.primary.start
                origin = TextRange(anchor)
                state.replaceSelections(SelectionSet.single(TextRange(anchor, hit.offset)))
            }

            clicks == 1 && alt -> {
                unit = SelectionUnit.Character
                origin = null
                state.replaceSelections(SelectionSet.of(current.ranges + TextRange(hit.offset)))
            }

            clicks == 1 -> {
                unit = SelectionUnit.Character
                origin = TextRange(hit.offset)
                state.replaceSelections(SelectionSet.caret(hit.offset))
            }

            clicks == 2 -> {
                unit = SelectionUnit.Word
                val word = wordRange(hit.offset)
                origin = word
                state.replaceSelections(SelectionSet.single(word))
            }

            else -> {
                unit = SelectionUnit.Line
                val line = lineRange(hit.line)
                origin = line
                state.replaceSelections(SelectionSet.single(line))
            }
        }
    }

    /** The pointer moved to [position] with the button held (or the finger down after a long press). */
    fun drag(position: Offset) {
        val from = origin ?: return
        val hit = view.offsetAt(position)
        val target = when (unit) {
            SelectionUnit.Character -> TextRange(hit.offset)
            SelectionUnit.Word -> wordRange(hit.offset)
            SelectionUnit.Line -> lineRange(hit.line)
        }
        // The anchor is the end of the first unit that is furthest from the pointer.
        val selection = if (target.min < from.min) TextRange(from.max, target.min) else TextRange(from.min, target.max)
        state.replaceSelections(SelectionSet.single(selection))
        // Dragging past the top or bottom scrolls the text along.
        if (position.y < 0f || position.y > view.scroll.viewportHeight || position.x < 0f || position.x > view.scroll.viewportWidth) {
            view.requestReveal(hit.offset)
        }
    }

    fun release() {
        origin = null
    }

    /** A long press on touch: select the word there, then extend by words while the finger moves. */
    fun longPress(position: Offset) {
        press(position, clicks = 2, shift = false, alt = false, touch = true)
    }

    private fun wordRange(offset: Int): TextRange {
        val word = TextNavigator.wordAt(state.document, offset)
        return if (word.isEmpty()) TextRange(offset) else TextRange(word.first, word.last + 1)
    }

    /** The whole of [line], with its line break, as a triple click selects it. */
    private fun lineRange(line: Int): TextRange {
        val document = state.document
        val start = document.lineStart(line)
        val end = if (line + 1 < document.lineCount) document.lineStart(line + 1) else document.length
        return TextRange(start, end)
    }
}

/**
 * The pointer gestures of the text area, feeding [handler]. Mouse presses are consumed (a mouse
 * drag selects, never scrolls); touch moves are left alone until a long press, so the scrollable
 * below still scrolls on a swipe.
 */
internal suspend fun PointerInputScope.editorPointerInput(handler: EditorPointerHandler) {
    var lastClickTime = 0L
    var lastClickPosition = Offset.Zero
    var clickCount = 0
    awaitEachGesture {
        val down = awaitFirstDown()
        val touch = down.type == PointerType.Touch
        val repeated = down.uptimeMillis - lastClickTime <= viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastClickPosition).getDistance() <= viewConfiguration.touchSlop * 2
        if (!touch) {
            if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            clickCount = if (repeated) clickCount % 3 + 1 else 1
            lastClickTime = down.uptimeMillis
            lastClickPosition = down.position
            val modifiers = currentEvent.keyboardModifiers
            handler.press(down.position, clickCount, modifiers.isShiftPressed, modifiers.isAltPressed)
            down.consume()
            drag(down.id) { change ->
                handler.drag(change.position)
                change.consume()
            }
            handler.release()
            return@awaitEachGesture
        }
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
        when {
            // Held still: a long press.
            up == null && currentEvent.changes.any { it.id == down.id && it.pressed && !it.isConsumed } -> {
                handler.longPress(down.position)
                clickCount = 0
                drag(down.id) { change ->
                    handler.drag(change.position)
                    change.consume()
                }
                handler.release()
            }

            up != null -> {
                clickCount = if (repeated && clickCount == 1) 2 else 1
                lastClickTime = up.uptimeMillis
                lastClickPosition = up.position
                handler.press(up.position, clickCount, shift = false, alt = false, touch = true)
                handler.release()
                up.consume()
            }
        }
    }
}
