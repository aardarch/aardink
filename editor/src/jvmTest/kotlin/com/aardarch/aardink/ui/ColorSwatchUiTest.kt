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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.ColorInfo
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import kotlin.test.Test
import kotlin.test.assertEquals

/** The colour swatch shown before a colour, as inline content: its room, and a click on it. */
class ColorSwatchUiTest {

    private val colors = object : LanguageService {
        override suspend fun completions(document: CodeDocument, cursorOffset: Int) = emptyList<CompletionItem>()

        override suspend fun diagnostics(document: CodeDocument) = emptyList<Diagnostic>()

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions) = ""

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions) = document.text

        override suspend fun documentColors(document: CodeDocument) = listOf(ColorInfo(0..6, Color.Red))
    }

    @Test
    fun `a click on the swatch opens the picker for its colour, and the text is untouched`() = runComposeUiTest {
        val state = CodeEditorState("#ff0000 is red")
        setContent {
            CodeEditorLayout(
                state = state,
                languageService = colors,
                options = EditorOptions(colorDecorators = true),
                modifier = Modifier.size(600.dp, 300.dp),
            )
        }
        mainClock.advanceTimeBy(1_000)
        waitUntil { state.documentColors.isNotEmpty() }
        waitForIdle()
        onAllNodesWithTag(EditorTestTags.COLOR_PICKER).assertCountEquals(0)

        // The swatch's room is the first two columns of the first line, the text starting 8 dp in.
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput {
            click(with(density) { Offset(8.dp.toPx() + 6.dp.toPx(), 8.dp.toPx() + 10.dp.toPx()) })
        }
        waitForIdle()

        onAllNodesWithTag(EditorTestTags.COLOR_PICKER).assertCountEquals(1)
        assertEquals("#ff0000 is red", state.document.text)
    }

    @Test
    fun `without colour decorators there is no swatch`() = runComposeUiTest {
        val state = CodeEditorState("#ff0000 is red")
        setContent {
            CodeEditorLayout(
                state = state,
                languageService = colors,
                options = EditorOptions(colorDecorators = false),
                modifier = Modifier.size(600.dp, 300.dp),
            )
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(emptyList(), state.documentColors)
    }
}
