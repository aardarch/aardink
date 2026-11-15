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

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [RenameDialog] and [GoToLineDialog] on their own, as a host shows them. */
class ChromeDialogsUiTest {

    // ── RenameDialog ─────────────────────────────────────────────────────────

    @Test
    fun `rename shows the current name, in the field and in the question`() = runComposeUiTest {
        setContent { RenameDialog(currentName = "total", onConfirm = {}, onDismiss = {}) }
        onNodeWithText("Rename Symbol").assertExists()
        onNodeWithText("Enter new name for 'total':").assertExists()
        onNode(hasSetTextAction() and hasText("total")).assertExists()
    }

    @Test
    fun `rename is offered only for a new name that is not blank, and trimmed`() = runComposeUiTest {
        val confirmed = ArrayList<String>()
        setContent { RenameDialog(currentName = "total", onConfirm = { confirmed += it }, onDismiss = {}) }
        val rename = onNodeWithText("Rename")
        rename.assertIsNotEnabled()

        val field = onNode(hasSetTextAction())
        field.performTextClearance()
        field.performTextInput("   ")
        rename.assertIsNotEnabled()

        field.performTextClearance()
        field.performTextInput(" sum ")
        rename.assertIsEnabled()
        rename.performClick()
        assertEquals(listOf("sum"), confirmed)
    }

    @Test
    fun `cancel dismisses the rename`() = runComposeUiTest {
        var dismissed = 0
        val confirmed = ArrayList<String>()
        setContent { RenameDialog(currentName = "total", onConfirm = { confirmed += it }, onDismiss = { dismissed++ }) }
        onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
        assertEquals(emptyList(), confirmed)
    }

    // ── GoToLineDialog ───────────────────────────────────────────────────────

    @Test
    fun `go to line shows the lines there are, and goes to the one typed`() = runComposeUiTest {
        val confirmed = ArrayList<Int>()
        setContent { GoToLineDialog(totalLines = 40, onConfirm = { confirmed += it }, onDismiss = {}) }
        onNodeWithText("Go to line").assertExists()
        onNodeWithText("1 – 40").assertExists()
        val go = onNodeWithText("Go")
        go.assertIsNotEnabled()

        onNode(hasSetTextAction()).performTextInput("12")
        go.assertIsEnabled()
        go.performClick()
        assertEquals(listOf(12), confirmed)
    }

    @Test
    fun `go to line takes digits only, at most seven`() = runComposeUiTest {
        val confirmed = ArrayList<Int>()
        setContent { GoToLineDialog(totalLines = 9_999_999, onConfirm = { confirmed += it }, onDismiss = {}) }
        val field = onNode(hasSetTextAction())
        field.performTextInput("4a-2")
        onNode(hasSetTextAction() and hasText("42")).assertExists()

        field.performTextClearance()
        field.performTextInput("123456789")
        onNode(hasSetTextAction() and hasText("1234567")).assertExists()
        onNodeWithText("Go").performClick()
        assertEquals(listOf(1_234_567), confirmed)
    }

    @Test
    fun `a line outside the document cannot be gone to`() = runComposeUiTest {
        setContent { GoToLineDialog(totalLines = 40, onConfirm = {}, onDismiss = {}) }
        val field = onNode(hasSetTextAction())
        for (line in listOf("0", "41", "9000")) {
            field.performTextClearance()
            field.performTextInput(line)
            onNodeWithText("Go").assertIsNotEnabled()
        }
        field.performTextClearance()
        field.performTextInput("40")
        onNodeWithText("Go").assertIsEnabled()
    }

    @Test
    fun `cancel dismisses go to line`() = runComposeUiTest {
        var dismissed = 0
        setContent { GoToLineDialog(totalLines = 40, onConfirm = {}, onDismiss = { dismissed++ }) }
        onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
    }
}
