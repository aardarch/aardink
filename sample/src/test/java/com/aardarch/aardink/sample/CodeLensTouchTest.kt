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
// The test tag and what the state shows are internal to :editor, and :editor has no Android test target.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.aardarch.aardink.sample

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CodeLens
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.languages.BuiltInLanguages
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.EditorTestTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A code lens on Android, under Robolectric with its native graphics: a real text engine and the
 * real gesture code, and still not a phone. Nothing here has run on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CodeLensTouchTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var tapped = 0

    private val service = object : LanguageService by BuiltInLanguages.Kotlin.languageService!! {
        override suspend fun codeLenses(document: CodeDocument) = listOf(CodeLens(0, "3 references") { tapped++ })
    }

    private fun show(text: String): CodeEditorState {
        val state = CodeEditorState(initialText = text, tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
        }
        rule.setContent {
            CodeEditorLayout(
                state = state,
                languageService = service,
                options = EditorOptions(codeLens = true),
                modifier = Modifier.size(320.dp, 300.dp),
            )
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(listOf("3 references"), state.codeLenses.map { it.title })
        return state
    }

    private fun tapRow(row: Int, x: Float = 6f) {
        rule.onNodeWithTag(EditorTestTags.EDITOR).performTouchInput {
            click(Offset(8.dp.toPx() + x, 8.dp.toPx() + (20 * row + 10).dp.toPx()))
        }
        rule.waitForIdle()
    }

    private val keyboardShown: Boolean
        get() {
            val manager = rule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            return shadowOf(manager).isSoftInputVisible
        }

    @Test
    fun `a tap on a lens runs it, and asks for no keyboard`() {
        val state = show("fun main() {}\nsecond")
        state.selection = TextRange(16)
        rule.waitForIdle()

        tapRow(0)

        assertEquals(1, tapped)
        assertEquals(TextRange(16), state.selection)
        assertFalse(keyboardShown)
    }

    @Test
    fun `a tap on the text under it places the caret, and asks for the keyboard`() {
        val state = show("fun main() {}\nsecond")

        tapRow(1, x = 1f)

        assertEquals(0, tapped)
        assertEquals(TextRange(0), state.selection)
        assertTrue(keyboardShown)
    }
}
