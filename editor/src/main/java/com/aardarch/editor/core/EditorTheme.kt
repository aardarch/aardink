package com.aardarch.editor.core

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Complete visual specification for the code editor.
 *
 * Obtain pre-built instances from [com.aardarch.editor.ui.EditorThemes], or build your own.
 * Override for a specific editor instance via [com.aardarch.editor.ui.LocalEditorTheme].
 */
@Immutable
data class EditorTheme(
    // ── Chrome ────────────────────────────────────────────────────────────────
    val background: Color,
    val gutterBackground: Color,
    val gutterForeground: Color,
    val lineHighlight: Color,
    val selectionColor: Color,
    val findMatchColor: Color,
    val cursorColor: Color,

    // ── Tokens ────────────────────────────────────────────────────────────────
    val tokenColors: Map<TokenType, Color>,

    // ── Diagnostics ───────────────────────────────────────────────────────────
    val errorColor: Color,
    val warningColor: Color,
    val infoColor: Color,

    // ── Typography ────────────────────────────────────────────────────────────
    val fontFamily: FontFamily = FontFamily.Monospace,
    val fontSize: TextUnit = 14.sp,
    val lineHeight: TextUnit = 20.sp,
)
