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

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Marks the switch between the text-field renderer and the editor's own, virtualised one, which
 * 0.6.0 makes the only renderer. The switch exists while both do, to compare them; it is removed
 * before 0.6.0 is released.
 */
@RequiresOptIn(
    message = "A temporary switch while the editor's own renderer is built; it is removed before 0.6.0, " +
        "which renders only that way.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class ExperimentalAardinkRenderer

/** How [CodeEditorLayout] draws and edits the document. */
@ExperimentalAardinkRenderer
enum class EditorRenderer {
    /** The whole document in one `BasicTextField`, as in 0.5. */
    TextField,

    /** The editor's own renderer: only the lines on screen are laid out and drawn. */
    Virtualized,
}

/** The [EditorRenderer] used by every [CodeEditorLayout] below this point. */
@ExperimentalAardinkRenderer
val LocalEditorRenderer: ProvidableCompositionLocal<EditorRenderer> = staticCompositionLocalOf { EditorRenderer.TextField }
