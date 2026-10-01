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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionDropdownUiTest {

    @Test
    fun `items with the same kind and label both show`() = runComposeUiTest {
        // A language's own `true` and the one of the language it extends.
        val items = listOf(
            CompletionItem("true", CompletionKind.Value, "true"),
            CompletionItem("true", CompletionKind.Value, "true", documentation = "from the base"),
        )

        setContent { CompletionDropdown(items = items, visible = true, onAccept = {}) }
        waitForIdle()

        assertEquals(2, onAllNodesWithText("true").fetchSemanticsNodes().size)
    }
}
