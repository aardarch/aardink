package com.aardarch.editor.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared constants for the editor UI. All layout math should derive from these. */
internal object EditorDefaults {
    val fontSize = 14.sp
    val lineHeight = 20.sp

    /** Horizontal padding inside the text area. */
    val contentPaddingHorizontal = 8.dp

    /** Top padding inside the text area and gutter. */
    val contentPaddingTop = 8.dp

    /** Horizontal padding inside the gutter (each side). */
    val gutterPaddingHorizontal = 6.dp

    /** Minimum number of digits shown in the gutter (prevents column-width jitter). */
    const val GUTTER_MIN_DIGITS = 2
}
