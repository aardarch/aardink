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

    // ── Typography (deprecated) ────────────────────────────────────────
    // These three have never been read by the editor: every call site hardcoded
    // FontFamily.Monospace and EditorDefaults' metrics instead, so setting them here had no
    // effect. Typography now lives in its own type so it can be provided independently of
    // colours (a web host swaps the font but keeps the theme). Kept as constructor parameters
    // with their original defaults rather than removed, so existing named-argument call sites
    // and the binary signature still work.
    @Deprecated(
        "Superseded by EditorTypography; provide LocalEditorTypography instead. " +
            "This value is not read by the editor.",
    )
    val fontFamily: FontFamily = FontFamily.Monospace,
    @Deprecated(
        "Superseded by EditorTypography; provide LocalEditorTypography instead. " +
            "This value is not read by the editor.",
    )
    val fontSize: TextUnit = 14.sp,
    @Deprecated(
        "Superseded by EditorTypography; provide LocalEditorTypography instead. " +
            "This value is not read by the editor.",
    )
    val lineHeight: TextUnit = 20.sp,
)
