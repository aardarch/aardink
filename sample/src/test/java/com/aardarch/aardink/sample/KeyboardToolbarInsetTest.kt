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

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.ui.CodeEditorLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the keyboard toolbar lands once the IME is open: right on top of the keyboard, whether
 * the editor fills the window or the host puts its own bars below it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardToolbarInsetTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val imeHeight = 300.dp

    private fun stateOf(text: String) =
        CodeEditorState(initialText = text, tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
        }

    /** Shows the editor with [below] of host content under it, opens a fake IME and returns the toolbar's gap to it. */
    private fun gapAboveKeyboard(below: Dp): Dp {
        lateinit var composeView: View
        rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false) }
        rule.setContent {
            composeView = LocalView.current
            Column(Modifier.fillMaxSize()) {
                CodeEditorLayout(state = stateOf("<a>\n</a>\n"), modifier = Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(below).fillMaxWidth())
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            val imePx = (imeHeight.value * composeView.resources.displayMetrics.density).toInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, insets)
        }
        rule.waitForIdle()
        val rootBottom = rule.onRoot().getBoundsInRoot().bottom
        val undoBottom = rule.onNode(UNDO_BUTTON).getBoundsInRoot().bottom
        return rootBottom - imeHeight - undoBottom
    }

    @Test
    fun `toolbar sits on the keyboard when the editor fills the window`() {
        val gap = gapAboveKeyboard(below = 0.dp)
        assertTrue("toolbar is $gap above the keyboard", gap in 0.dp..TOOLBAR_SLACK)
    }

    @Test
    fun `toolbar sits on the keyboard when the host has bars below the editor`() {
        // Before the fix the toolbar floated the host's 120 dp above the keyboard.
        val gap = gapAboveKeyboard(below = 120.dp)
        assertTrue("toolbar is $gap above the keyboard", gap in 0.dp..TOOLBAR_SLACK)
    }

    private companion object {
        /** The toolbar's buttons carry their names as click labels. */
        val UNDO_BUTTON = SemanticsMatcher("toolbar Undo button") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "Undo"
        }

        /** The toolbar's own padding below its buttons. */
        val TOOLBAR_SLACK = 24.dp
    }
}
