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

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager

/**
 * A stylus over the editor, sent as the platform sends it (`TOOL_TYPE_STYLUS`, no button pressed):
 * a tap places the caret as a finger's does, and from Android 14, where the input method writes, a
 * stroke becomes handwriting. It places the caret where it began, focuses the editor, hands the
 * stroke to the input method while the stylus is still down, and tells the input method the editor
 * takes handwriting. Whether an input method then writes is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [HandwritingInputMethodManager::class])
class StylusInputTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val text = "one\ntwo\nthree"

    @Before
    fun handwritingAvailable() {
        HandwritingInputMethodManager.available = true
    }

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

    private fun focus() {
        rule.onNode(hasSetTextAction()).requestFocus()
        rule.waitForIdle()
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

    /** How many times the input method has been asked to take a stroke as handwriting. */
    private fun handwritingStarts(): Int {
        val imm = rule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return shadowOf(imm).stylusHandwritingViews.size
    }

    private fun stylus(action: Int, at: Offset, downTime: Long, eventTime: Long, buttons: Int = 0): MotionEvent {
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
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
        )
    }

    /**
     * Puts the stylus down and moves it through [points] in one go, so no clock runs out between
     * its events, and leaves it down; returns its down time, for [stylusUp].
     */
    private fun stylusDown(points: List<Offset>, buttons: Int = 0): Long {
        var down = 0L
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            down = SystemClock.uptimeMillis()
            root.dispatchTouchEvent(stylus(MotionEvent.ACTION_DOWN, points.first(), down, down, buttons))
            points.drop(1).forEachIndexed { i, point ->
                root.dispatchTouchEvent(stylus(MotionEvent.ACTION_MOVE, point, down, down + 10L * (i + 1), buttons))
            }
        }
        rule.waitForIdle()
        return down
    }

    private fun stylusUp(at: Offset, down: Long, buttons: Int = 0) {
        rule.runOnIdle {
            rule.activity.window.decorView.dispatchTouchEvent(stylus(MotionEvent.ACTION_UP, at, down, SystemClock.uptimeMillis(), buttons))
        }
        rule.waitForIdle()
    }

    /** Sends a stylus gesture through [points] in one go, so no clock runs out between its events. */
    private fun stylusGesture(points: List<Offset>, buttons: Int = 0) {
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            val down = SystemClock.uptimeMillis()
            root.dispatchTouchEvent(stylus(MotionEvent.ACTION_DOWN, points.first(), down, down, buttons))
            points.drop(1).forEachIndexed { i, point ->
                root.dispatchTouchEvent(stylus(MotionEvent.ACTION_MOVE, point, down, down + 10L * (i + 1), buttons))
            }
            root.dispatchTouchEvent(stylus(MotionEvent.ACTION_UP, points.last(), down, down + 10L * points.size, buttons))
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
    @Config(sdk = [34])
    fun `a stroke on the focused editor is handed to the input method while the stylus is down`() {
        show()
        focus()
        val starts = handwritingStarts()
        val stroke = upwardStroke()
        val down = stylusDown(stroke)
        assertEquals("one handwriting request, the stylus still down", starts + 1, handwritingStarts())
        stylusUp(stroke.last(), down)
        assertEquals("none more when it lifts", starts + 1, handwritingStarts())
    }

    @Test
    @Config(sdk = [34])
    fun `a stroke on an unfocused editor starts its input session, then hands the stroke over`() {
        val state = show()
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
        val starts = handwritingStarts()
        val stroke = upwardStroke()
        val down = stylusDown(stroke)
        rule.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals("the caret is where the stroke began", TextRange(text.length), state.selection)
        assertEquals("one handwriting request, the stylus still down", starts + 1, handwritingStarts())
        stylusUp(stroke.last(), down)
        assertEquals("none more when it lifts", starts + 1, handwritingStarts())
    }

    @Test
    @Config(sdk = [34])
    fun `where the input method does not write, a stylus stroke is a finger's gesture`() {
        HandwritingInputMethodManager.available = false
        val state = show()
        val starts = handwritingStarts()
        // Nothing to scroll, so the stroke ends as a finger's tap would: where it lifted.
        stylusGesture(upwardStroke())
        assertEquals("the caret is where the stroke lifted", TextRange("one".length), state.selection)
        assertEquals("no handwriting request", starts, handwritingStarts())
    }

    @Test
    @Config(sdk = [34])
    fun `a stylus drag with its barrel button held selects, as a mouse drag does`() {
        val state = show()
        val starts = handwritingStarts()
        stylusGesture(upwardStroke(), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertEquals(
            "from where the drag began to where it lifted",
            TextRange("one".length, text.length),
            TextRange(state.selection.min, state.selection.max),
        )
        assertEquals("no handwriting request", starts, handwritingStarts())
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
        focus()
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
        focus()
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

/**
 * Robolectric's input method manager, which records handwriting requests, with a choice of whether
 * the current input method writes: the real one asks a system service Robolectric does not have.
 */
@Implements(InputMethodManager::class)
class HandwritingInputMethodManager : ShadowInputMethodManager() {

    @Implementation(minSdk = 34)
    protected fun isStylusHandwritingAvailable(): Boolean = available

    companion object {
        @JvmStatic
        var available = true
    }
}
