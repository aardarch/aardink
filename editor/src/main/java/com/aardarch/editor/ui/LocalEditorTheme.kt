package com.aardarch.editor.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import com.aardarch.editor.core.EditorTheme

/**
 * Composition local carrying the active [EditorTheme].
 *
 * Defaults to [EditorThemes.VsCodeDark]. Override for a specific subtree:
 * ```kotlin
 * CompositionLocalProvider(LocalEditorTheme provides EditorThemes.MidnightOcean) {
 *     CodeEditorLayout(state)
 * }
 * ```
 */
val LocalEditorTheme: ProvidableCompositionLocal<EditorTheme> =
    compositionLocalOf { EditorThemes.VsCodeDark }
