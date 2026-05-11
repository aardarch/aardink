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
package com.aardarch.aardink.core

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Complete visual specification for the code editor.
 *
 * Obtain pre-built instances from [com.aardarch.aardink.ui.EditorThemes], or build your own.
 * Override for a specific editor instance via [com.aardarch.aardink.ui.LocalEditorTheme].
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
