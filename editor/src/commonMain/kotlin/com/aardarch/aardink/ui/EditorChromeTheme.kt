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

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.TokenType
import kotlin.math.max
import kotlin.math.min

/**
 * The colours of the chrome the editor draws itself (the keyboard toolbar, the completion strip
 * and list, the menus, the hover, signature, references and code action cards, the find panel,
 * the diagnostic banner, the dialogs), taken from an [EditorTheme] rather than the host's
 * [MaterialTheme]: a dark editor theme gets dark chrome even where the host has no Material theme
 * at all, as on the web, whose default Material colours are light.
 *
 * Surfaces are the editor's background moved towards its text colour, as VS Code's widgets sit a
 * shade off the editor; the accent is the theme's keyword colour. A colour drawn as text is moved
 * towards white or black where it must be, until it reaches [MIN_TEXT_CONTRAST] on every surface
 * and on the selection, so a theme with dim text (Solarized) still gets readable chrome.
 */
internal class EditorChromeColors(private val theme: EditorTheme) {
    /** The editor's background, opaque. */
    val background: Color = theme.background.compositeOver(Color.Black)

    /** Whether the theme is a dark one, by its background. */
    val isDark: Boolean = background.luminance() < 0.5f

    /** The theme's own text colour, which the surfaces step towards. */
    private val themeText: Color =
        (theme.tokenColors[TokenType.Default] ?: if (isDark) Color(0xFFD4D4D4) else Color(0xFF1F1F1F)).compositeOver(background)

    /** A surface [level] steps (0 to 4) off the background, towards the text colour. */
    fun surface(level: Int): Color = lerp(background, themeText, SURFACE_STEP * level)

    /** A selected row or chip: the theme's selection colour. */
    val selection: Color = theme.selectionColor.compositeOver(background)

    /** Every surface level, which accents and borders are drawn on. */
    val surfaces: List<Color> = List(SURFACE_LEVELS + 1, ::surface)

    /** What the chrome's text is drawn on: every surface, and the selection under a selected row. */
    val textBackgrounds: List<Color> = surfaces + selection

    /** The text colour: the theme's default token colour, readable on every surface and the selection. */
    val foreground: Color = readable(themeText, textBackgrounds)

    /** Secondary text: the foreground faded towards the background, as far as it stays readable. */
    val foregroundVariant: Color = readable(lerp(foreground, background, 0.3f), textBackgrounds)

    /** The accent for controls and highlighted text: the keyword colour, else the text colour, readable on every surface. */
    val accent: Color = theme.tokenColors[TokenType.Keyword]?.let { readable(it.compositeOver(background), surfaces) } ?: foreground

    /** Errors as text (a field's error label, a badge): the theme's error colour, readable on every surface. */
    val error: Color = readable(theme.errorColor.compositeOver(background), surfaces)

    /** A text field's border at rest: visible as a control's edge (3:1) on every surface. */
    val outline: Color = readable(lerp(background, themeText, 0.4f), surfaces, MIN_UI_CONTRAST)

    /** A banner's background for a diagnostic of [severity]: a surface tinted with its colour. */
    fun severityContainer(severity: DiagnosticSeverity): Color {
        val color = when (severity) {
            DiagnosticSeverity.Error -> theme.errorColor
            DiagnosticSeverity.Warning -> theme.warningColor
            DiagnosticSeverity.Info -> theme.infoColor
        }
        return lerp(surface(1), color.compositeOver(background), SEVERITY_TINT)
    }

    /** Text on [container]: the foreground, moved until it reads there. */
    fun onColor(container: Color): Color = readable(foreground, listOf(container))

    /**
     * [color] as it is if it reaches [minContrast] on all of [backgrounds], else moved towards white
     * or black, whichever reads better on them, until it does.
     */
    private fun readable(color: Color, backgrounds: List<Color>, minContrast: Float = MIN_TEXT_CONTRAST): Color {
        fun worst(c: Color) = backgrounds.minOf { contrastRatio(c, it) }
        if (worst(color) >= minContrast) return color
        val target = if (worst(Color.White) >= worst(Color.Black)) Color.White else Color.Black
        for (step in 1..CONTRAST_STEPS) {
            val moved = lerp(color, target, step.toFloat() / CONTRAST_STEPS)
            if (worst(moved) >= minContrast) return moved
        }
        return target
    }

    /** A Material colour scheme from these colours, for the Material components in the chrome. */
    fun colorScheme(): ColorScheme {
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        val errorContainer = severityContainer(DiagnosticSeverity.Error)
        return base.copy(
            primary = accent,
            onPrimary = onColor(accent),
            primaryContainer = surface(3),
            onPrimaryContainer = foreground,
            inversePrimary = background,
            secondary = theme.tokenColors[TokenType.TypeName]?.let { readable(it.compositeOver(background), surfaces) } ?: accent,
            secondaryContainer = selection,
            onSecondaryContainer = foreground,
            tertiary = theme.tokenColors[TokenType.StringLiteral]?.let { readable(it.compositeOver(background), surfaces) } ?: accent,
            error = error,
            onError = onColor(error),
            errorContainer = errorContainer,
            onErrorContainer = onColor(errorContainer),
            background = background,
            onBackground = foreground,
            surface = background,
            onSurface = foreground,
            surfaceVariant = surface(2),
            onSurfaceVariant = foregroundVariant,
            surfaceTint = accent,
            inverseSurface = foreground,
            inverseOnSurface = background,
            surfaceBright = surface(2),
            surfaceDim = background,
            surfaceContainerLowest = background,
            surfaceContainerLow = surface(1),
            surfaceContainer = surface(1),
            surfaceContainerHigh = surface(2),
            surfaceContainerHighest = surface(3),
            outline = outline,
            outlineVariant = surface(4),
        )
    }

    companion object {
        private const val SURFACE_STEP = 0.04f
        private const val SURFACE_LEVELS = 4
        private const val SEVERITY_TINT = 0.22f
        private const val CONTRAST_STEPS = 20

        /** WCAG AA for normal text. */
        const val MIN_TEXT_CONTRAST = 4.5f

        /** WCAG AA for a control's boundary. */
        const val MIN_UI_CONTRAST = 3f
    }
}

/** The WCAG contrast ratio between two opaque colours, from 1 to 21. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * Runs [content] under a [MaterialTheme] whose colours follow the active [LocalEditorTheme] (see
 * [EditorChromeColors]); the host's typography and shapes are kept. The Material components inside
 * (surfaces, text fields, buttons, chips, dialogs) take their default colours from it.
 */
@Composable
internal fun EditorChromeTheme(content: @Composable () -> Unit) {
    val theme = LocalEditorTheme.current
    val colorScheme = remember(theme) { EditorChromeColors(theme).colorScheme() }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
