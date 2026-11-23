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
package com.aardarch.aardink.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.TokenType

/**
 * A theme's diff colours, each [com.aardarch.aardink.core.ChromeColors] field that is unspecified
 * filled in as its documentation says: VS Code's colours for inserted and removed lines, and the
 * lane's green, blue and red as the editor has always drawn them.
 */
@Immutable
internal data class DiffColors(
    val insertedLine: Color,
    val removedLine: Color,
    val insertedText: Color,
    val removedText: Color,
    val filler: Color,
    val gutterAdded: Color,
    val gutterModified: Color,
    val gutterDeleted: Color,
) {
    companion object {
        /** The colours with nothing from a theme, for a gutter built without one. */
        val Default = DiffColors(
            insertedLine = Color(0x339BB955),
            removedLine = Color(0x33FF0000),
            insertedText = Color(0x4D9CCC2C),
            removedText = Color(0x4DFF0000),
            filler = Color(0x14808080),
            gutterAdded = Color(0xFF4CAF50),
            gutterModified = Color(0xFF2196F3),
            gutterDeleted = Color(0xFFF44336),
        )

        fun of(theme: EditorTheme): DiffColors {
            val chrome = theme.chromeColors
            val text = theme.tokenColors[TokenType.Default] ?: Color(0xFF808080)
            return DiffColors(
                insertedLine = chrome.diffInsertedLine.takeOrElse { Default.insertedLine },
                removedLine = chrome.diffRemovedLine.takeOrElse { Default.removedLine },
                insertedText = chrome.diffInsertedText.takeOrElse { Default.insertedText },
                removedText = chrome.diffRemovedText.takeOrElse { Default.removedText },
                filler = chrome.diffFiller.takeOrElse { text.copy(alpha = 0.08f) },
                gutterAdded = chrome.gutterAdded.takeOrElse { Default.gutterAdded },
                gutterModified = chrome.gutterModified.takeOrElse { Default.gutterModified },
                gutterDeleted = chrome.gutterDeleted.takeOrElse { Default.gutterDeleted },
            )
        }
    }
}
