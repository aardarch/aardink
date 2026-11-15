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

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.EditorCommand
import kotlin.test.Test
import kotlin.test.assertEquals

/** [KeyboardToolbarRow] on its own, shown as a host above the keyboard shows it. */
class KeyboardToolbarRowUiTest {

    private val taps = ArrayList<String>()

    /** A button by the label its click has: what a screen reader says it does. */
    private fun clickLabelled(label: String) = SemanticsMatcher("click label '$label'") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun ComposeUiTest.show(
        canUndo: Boolean = true,
        canRedo: Boolean = true,
        quickChars: List<Char> = listOf('{', '('),
        items: List<KeyboardToolbarItem> = emptyList(),
        alwaysVisible: Boolean = true,
    ) {
        setContent {
            KeyboardToolbarRow(
                quickChars = quickChars,
                canUndo = canUndo,
                canRedo = canRedo,
                onInsertChar = { taps += "insert $it" },
                onMoveCursorLeft = { taps += "left" },
                onMoveCursorRight = { taps += "right" },
                onUndo = { taps += "undo" },
                onRedo = { taps += "redo" },
                modifier = Modifier.width(1200.dp),
                alwaysVisible = alwaysVisible,
                items = items,
                onAction = { taps += "action ${it.label}" },
            )
        }
        waitForIdle()
    }

    @Test
    fun `each button does what it says`() = runComposeUiTest {
        show()
        for (label in listOf("Undo", "Redo", "Move cursor left", "Move cursor right", "Insert {", "Insert (")) {
            onNode(clickLabelled(label)).performClick()
        }
        assertEquals(listOf("undo", "redo", "left", "right", "insert {", "insert ("), taps)
    }

    @Test
    fun `undo and redo are offered only when there is something to undo or redo`() = runComposeUiTest {
        var canUndo by mutableStateOf(false)
        setContent {
            KeyboardToolbarRow(
                quickChars = emptyList(),
                canUndo = canUndo,
                canRedo = false,
                onInsertChar = {},
                onMoveCursorLeft = {},
                onMoveCursorRight = {},
                onUndo = { taps += "undo" },
                onRedo = { taps += "redo" },
                alwaysVisible = true,
            )
        }
        onNode(clickLabelled("Undo")).assertIsNotEnabled()
        onNode(clickLabelled("Redo")).assertIsNotEnabled()
        canUndo = true
        waitForIdle()
        onNode(clickLabelled("Undo")).assertIsEnabled()
        onNode(clickLabelled("Redo")).assertIsNotEnabled()
    }

    @Test
    fun `the host's items come after the quick characters, characters typed and actions handed back`() = runComposeUiTest {
        show(
            items = listOf(
                KeyboardToolbarItem.Character(';'),
                KeyboardToolbarItem.Action("Format", command = EditorCommand.Format),
            ),
        )
        onNode(clickLabelled("Insert ;")).performClick()
        onNodeWithText("Format").performClick()
        assertEquals(listOf("insert ;", "action Format"), taps)
    }

    @Test
    fun `with no keyboard open it shows only when told to always show`() = runComposeUiTest {
        show(alwaysVisible = false)
        onNode(clickLabelled("Undo")).assertDoesNotExist()
    }
}
