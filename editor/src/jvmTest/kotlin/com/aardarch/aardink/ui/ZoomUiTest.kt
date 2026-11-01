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
@file:OptIn(ExperimentalTestApi::class)

package com.aardarch.aardink.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenStyleResolver
import com.aardarch.aardink.core.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Zoom, typography options, the Material theme and the code block. */
class ZoomUiTest {

    private fun ComposeUiTest.zoomKey(key: Key) {
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
        waitForIdle()
    }

    @Test
    fun `ctrl plus and minus ask the host for a new size`() = runComposeUiTest {
        val state = CodeEditorState("text")
        val asked = mutableListOf<TextUnit>()
        setContent {
            CodeEditorLayout(
                state = state,
                options = EditorOptions(fontSize = 16.sp),
                onFontSizeChange = { asked += it },
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
        zoomKey(Key.Equals)
        zoomKey(Key.Minus)
        assertEquals(listOf(17.sp, 15.sp), asked)
        assertTrue(state.canExecute(EditorCommand.ZoomIn))
    }

    @Test
    fun `without zoom the keys are not the editor's`() = runComposeUiTest {
        val state = CodeEditorState("text")
        setContent { CodeEditorLayout(state = state, modifier = Modifier.size(600.dp, 400.dp)) }
        waitForIdle()
        assertFalse(state.canExecute(EditorCommand.ZoomIn))
        zoomKey(Key.Equals)
        assertEquals("text", state.text)
    }

    @Test
    fun `the editor keeps the size itself with pinch to zoom and no callback`() = runComposeUiTest {
        val state = CodeEditorState("text")
        var seen: TextUnit = TextUnit.Unspecified
        setContent {
            CodeEditorLayout(state = state, options = EditorOptions(pinchToZoom = true), modifier = Modifier.size(600.dp, 400.dp))
            seen = LocalEditorTypography.current.fontSize
        }
        waitForIdle()
        assertTrue(state.execute(EditorCommand.ZoomIn))
        assertTrue(state.execute(EditorCommand.ZoomIn))
        assertTrue(state.execute(EditorCommand.ZoomReset))
        // The default typography outside the layout is untouched.
        assertEquals(EditorTypography().fontSize, seen)
    }

    @Test
    fun `a material scheme gives the editor its colours`() {
        val dark = darkColorScheme(primary = Color(0xFF123456), surface = Color(0xFF101010), error = Color(0xFFFF0000))
        val theme = EditorTheme.fromColorScheme(dark, dark = true)
        assertEquals(Color(0xFF101010), theme.background)
        assertEquals(Color(0xFF123456), theme.cursorColor)
        assertEquals(Color(0xFF123456), theme.tokenColors[TokenType.Keyword])
        assertEquals(Color(0xFFFF0000), theme.errorColor)
        val light = EditorTheme.fromColorScheme(lightColorScheme(), dark = false)
        assertEquals(lightColorScheme().surface, light.background)
    }

    @Test
    fun `a code block colours its tokens as the editor does`() {
        val theme = EditorThemes.VsCodeDark
        val styled = highlighted("val x", listOf(Token(0, 3, TokenType.Keyword), Token(9, 12, TokenType.Number)), TokenStyleResolver(theme))
        assertEquals("val x", styled.text)
        assertEquals(theme.tokenColors[TokenType.Keyword], styled.spanStyles.single().item.color)
        assertNull(styled.spanStyles.firstOrNull { it.start >= 4 })
    }
}
