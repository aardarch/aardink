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
package com.aardarch.aardink.sample

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.ui.CodeEditorLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A stylus over the editor, sent as the platform sends it (`TOOL_TYPE_STYLUS`, no button pressed):
 * a tap places the caret as a finger's does, and from Android 14 a stroke becomes handwriting. It
 * places the caret where it began, focuses the editor and tells the input method the editor takes
 * handwriting. Whether an input method then writes is a device check.
 */
@RunWith(RobolectricTestRunner::class)
class StylusInputTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val text = "one\ntwo\nthree"

    private fun show(): CodeEditorState {
        val state = CodeEditorState(initialText = text, tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
        }
        rule.setContent { CodeEditorLayout(state = state, modifier = Modifier.fillMaxSize()) }
        rule.waitForIdle()
        rule.runOnIdle { state.selection = TextRange(0) }
        rule.waitForIdle()
        return state
    }

    private val editorBounds get() = rule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInWindow

    /** Below the last line: a press there puts the caret at the end of the text. */
    private fun belowTheText(): Offset = with(editorBounds) { Offset(left + width / 2f, bottom - 40f) }

    /** Past the end of the first line: a press there puts the caret after "one". */
    private fun onTheFirstLine(): Offset = with(editorBounds) { Offset(left + width / 2f, top + 4f) }

    /** A stroke from below the text up to the first line, so where it began and where it lifted differ. */
    private fun upwardStroke(): List<Offset> {
        val start = belowTheText()
        val end = onTheFirstLine()
        return (0..4).map { i -> start + (end - start) * (i / 4f) }
    }

    private fun stylus(action: Int, at: Offset, downTime: Long, eventTime: Long): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = at.x
            y = at.y
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
        )
    }

    /** Sends a stylus gesture through [points] in one go, so no clock runs out between its events. */
    private fun stylusGesture(points: List<Offset>) {
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val down = SystemClock.uptimeMillis()
            root.dispatchTouchEvent(stylus(MotionEvent.ACTION_DOWN, points.first(), down, down))
            points.drop(1).forEachIndexed { i, point ->
                root.dispatchTouchEvent(stylus(MotionEvent.ACTION_MOVE, point, down, down + 10L * (i + 1)))
            }
            root.dispatchTouchEvent(stylus(MotionEvent.ACTION_UP, points.last(), down, down + 10L * points.size))
        }
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [34])
    fun `a stylus tap places the caret, as a finger's does`() {
        val state = show()
        stylusGesture(listOf(belowTheText()))
        assertEquals(TextRange(text.length), state.selection)
        rule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    @Config(sdk = [34])
    fun `a stylus stroke writes where it began, and focuses the editor`() {
        val state = show()
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
        stylusGesture(upwardStroke())
        assertEquals("the caret is where the stroke began", TextRange(text.length), state.selection)
        rule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    @Config(sdk = [33])
    fun `before Android 14 a stylus stroke is a finger's gesture`() {
        val state = show()
        // Nothing to scroll, so the stroke ends as a finger's tap would: where it lifted.
        stylusGesture(upwardStroke())
        assertEquals("the caret is where the stroke lifted", TextRange("one".length), state.selection)
    }

    @Test
    @Config(sdk = [34])
    fun `the input method is told the editor takes handwriting`() {
        show()
        rule.onNode(hasSetTextAction()).requestFocus()
        rule.waitForIdle()
        val info = EditorInfo()
        rule.runOnIdle {
            val view = requireNotNull(rule.activity.window.decorView.findFocus()) { "nothing has focus" }
            requireNotNull(view.onCreateInputConnection(info)) { "the focused editor handed out no InputConnection" }
        }
        assertTrue(info.extras?.getBoolean(HANDWRITING_KEY) == true)
    }

    @Test
    @Config(sdk = [33])
    fun `before Android 14 the input method is not offered handwriting`() {
        show()
        rule.onNode(hasSetTextAction()).requestFocus()
        rule.waitForIdle()
        val info = EditorInfo()
        rule.runOnIdle {
            val view = requireNotNull(rule.activity.window.decorView.findFocus()) { "nothing has focus" }
            requireNotNull(view.onCreateInputConnection(info)) { "the focused editor handed out no InputConnection" }
        }
        assertFalse(info.extras?.containsKey(HANDWRITING_KEY) == true)
    }

    private companion object {
        const val HANDWRITING_KEY = "androidx.core.view.inputmethod.EditorInfoCompat.STYLUS_HANDWRITING_ENABLED"
    }
}
