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

import androidx.compose.ui.graphics.vector.ImageVector
import com.aardarch.aardink.core.EditorCommand

/**
 * An item of the host's in the editor's touch menu (over a selection on a touch screen) or its
 * right-click menu: [CodeEditorLayout]'s `touchMenuItems` and `contextMenuItems`. Choosing it runs
 * [command] through the editor's state ([com.aardarch.aardink.core.CodeEditorState.execute]) when
 * there is one, else [onClick]; either way the menu closes first.
 *
 * @property label What the item says.
 * @property enabled Whether it can be chosen; a disabled item shows, greyed. An item with a
 *   [command] is also disabled while the editor cannot run it.
 */
class EditorMenuItem(val label: String, val command: EditorCommand? = null, val enabled: Boolean = true, val onClick: () -> Unit = {})

/**
 * A button of the keyboard toolbar above the soft keyboard: [CodeEditorLayout]'s
 * `keyboardToolbarItems`, shown after the language's own characters.
 */
sealed class KeyboardToolbarItem {
    /** Types [char] as a key would, through the typing rules (a `(` gets its `)`). */
    data class Character(val char: Char) : KeyboardToolbarItem()

    /**
     * A button of the host's: [icon], or [label] as text when there is none. Tapping it runs
     * [command] through the editor's state when there is one, else [onClick]. [label] is also
     * what a screen reader announces.
     */
    class Action(val label: String, val icon: ImageVector? = null, val command: EditorCommand? = null, val onClick: () -> Unit = {}) :
        KeyboardToolbarItem()
}
