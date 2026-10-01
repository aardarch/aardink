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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aardarch.aardink.core.DefaultBracketPairColors
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.LightBracketPairColors
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenFontStyle
import com.aardarch.aardink.core.TokenStyleResolver
import com.aardarch.aardink.core.TokenType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Parses a VS Code theme JSON string into an [EditorTheme].
 *
 * Supports the standard VS Code theme format with `colors` and `tokenColors` sections.
 * Any VS Code theme from the marketplace can be dropped in as-is.
 *
 * Usage:
 * ```kotlin
 * val json = assets.open("my_theme.json").bufferedReader().readText()
 * val theme = EditorThemeParser.fromJson(json) ?: EditorThemes.VsCodeDark
 * ```
 *
 * Backed by `kotlinx.serialization.json` rather than the Android-only `org.json` — this is the
 * one runtime dependency `:editor` needs beyond Compose, added specifically so this parser (and
 * everything else in the module) compiles as common Kotlin. See AGENTS.md's dependency policy.
 */
object EditorThemeParser {

    /**
     * Parses [json] as a VS Code theme. Returns null if the JSON is malformed or
     * required keys are missing; the caller should fall back to a built-in theme.
     */
    fun fromJson(json: String): EditorTheme? = try {
        parseTheme(Json.parseToJsonElement(json).jsonObject)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseTheme(root: JsonObject): EditorTheme {
        val colors = root["colors"]?.jsonObject ?: JsonObject(emptyMap())
        val fallback = EditorThemes.VsCodeDark

        val background = colors.hexColor("editor.background") ?: fallback.background
        val foreground = colors.hexColor("editor.foreground") ?: fallback.tokenColors[TokenType.Default]!!
        val gutterFg = colors.hexColor("editorLineNumber.foreground") ?: fallback.gutterForeground
        val gutterBg = colors.hexColor("editorGutter.background")
            ?: colors.hexColor("editor.background")?.darken(0.04f)
            ?: fallback.gutterBackground
        val lineHighlight = colors.hexColor("editor.lineHighlightBackground") ?: fallback.lineHighlight
        val selection = colors.hexColor("editor.selectionBackground") ?: fallback.selectionColor
        val findMatch = colors.hexColor("editor.findMatchHighlightBackground") ?: fallback.findMatchColor
        val cursor = colors.hexColor("editorCursor.foreground") ?: fallback.cursorColor
        // editorBracketHighlight.foreground1..6, as many as the theme sets, in order.
        val bracketColors = (1..6).mapNotNull { colors.hexColor("editorBracketHighlight.foreground$it") }
            .ifEmpty { if (background.luminance() > 0.5f) LightBracketPairColors else DefaultBracketPairColors }

        // ── Token colors ──────────────────────────────────────────────────────
        val tokenStyles = buildTokenStyles(root, foreground, fallback)

        return EditorTheme(
            background = background,
            gutterBackground = gutterBg,
            gutterForeground = gutterFg,
            lineHighlight = lineHighlight,
            selectionColor = selection,
            findMatchColor = findMatch,
            cursorColor = cursor,
            tokenColors = tokenStyles.colors,
            errorColor = fallback.errorColor,
            warningColor = fallback.warningColor,
            infoColor = fallback.infoColor,
            bracketPairColors = bracketColors,
            whitespaceColor = colors.hexColor("editorWhitespace.foreground") ?: Color.Unspecified,
            tokenFontStyles = tokenStyles.fontStyles,
        )
    }

    /** A theme's token colours and font styles, as [buildTokenStyles] reads them. */
    private class TokenStyles(val colors: Map<TokenType, Color>, val fontStyles: Map<TokenType, TokenFontStyle>)

    /**
     * The theme's `tokenColors` rules as colours and font styles by token type:
     *
     * - Each built-in type takes the rule that best fits the scopes it stands for
     *   ([BASE_SCOPES]): a rule for one of them or for a prefix of it (the longest), else the most
     *   general rule under one of them. So a theme with both `keyword` and `keyword.control` gives
     *   keywords the first, not whichever comes last.
     * - Monaco's sub-names ([MONACO_SUB_SCOPES]) take the rule for the TextMate scope they stand
     *   for: `keyword.flow` the theme's `keyword.control`, `comment.doc` its documentation comments.
     * - Every rule's own scope is kept too, as a [NamedTokenType], so a grammar token or a Monaco
     *   rule by that name (`tag.aardflex`) is coloured by it.
     *
     * What the theme leaves out comes from [fallback], except a fallback sub-name under a type the
     * theme sets itself: a theme with its own keyword colour does not keep the fallback's
     * `keyword.flow`.
     */
    private fun buildTokenStyles(root: JsonObject, defaultFg: Color, fallback: EditorTheme): TokenStyles {
        val colorRules = LinkedHashMap<String, Color>()
        val fontRules = LinkedHashMap<String, TokenFontStyle>()
        for (entry in (root["tokenColors"] as? JsonArray).orEmpty()) {
            val entryObj = entry as? JsonObject ?: continue
            val settings = entryObj["settings"] as? JsonObject ?: continue
            val fg = settings.hexColor("foreground")
            val font = (settings["fontStyle"] as? JsonPrimitive)?.contentOrNull?.let { TokenFontStyle.parse(it) }
            if (fg == null && font == null) continue
            val scopes: List<String> = when (val scopeElement = entryObj["scope"]) {
                is JsonArray -> scopeElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> scopeElement.content.split(",")
                else -> continue
            }
            for (raw in scopes) {
                val scope = raw.trim()
                if (scope.isEmpty()) continue
                // A later rule for the same scope wins, as in VS Code.
                if (fg != null) colorRules.remove(scope).also { colorRules[scope] = fg }
                if (font != null) fontRules.remove(scope).also { fontRules[scope] = font }
            }
        }

        val colors = fallback.tokenColors.toMutableMap()
        colors[TokenType.Default] = defaultFg
        val fontStyles = fallback.tokenFontStyles.toMutableMap()
        val ownColors = HashSet<TokenType>()
        val ownFonts = HashSet<TokenType>()
        for ((type, scopes) in BASE_SCOPES) {
            bestRule(scopes, colorRules, type)?.let {
                colors[type] = it
                ownColors += type
            }
            bestRule(scopes, fontRules, type)?.let {
                fontStyles[type] = it
                ownFonts += type
            }
        }
        dropInheritedSubNames(colors, fallback.tokenColors, ownColors)
        dropInheritedSubNames(fontStyles, fallback.tokenFontStyles, ownFonts)
        for ((name, textMate) in MONACO_SUB_SCOPES) {
            specificRule(textMate, colorRules)?.let { colors[NamedTokenType(name)] = it }
            specificRule(textMate, fontRules)?.let { fontStyles[NamedTokenType(name)] = it }
        }
        for ((scope, color) in colorRules) colors[NamedTokenType(scope)] = color
        for ((scope, font) in fontRules) fontStyles[NamedTokenType(scope)] = font
        return TokenStyles(colors, fontStyles)
    }

    /**
     * The value of the rule that best fits [scopes] (in order of preference): the longest rule
     * that is one of them or a prefix of one, else the shortest rule under one of them.
     */
    private fun <T> bestRule(scopes: List<String>, rules: Map<String, T>, type: TokenType): T? {
        for (scope in scopes) {
            var candidate = scope
            while (true) {
                rules[candidate]?.let { return it }
                val dot = candidate.lastIndexOf('.')
                if (dot < 0) break
                candidate = candidate.substring(0, dot)
            }
        }
        for (scope in scopes) {
            val under = rules.keys
                .filter { it.startsWith("$scope.") && !(type == TokenType.Keyword && it.startsWith("keyword.operator")) }
                .minByOrNull { it.length }
            if (under != null) return rules.getValue(under)
        }
        return null
    }

    /** The rule for [textMate] exactly, else the most general one under it; never a more general one. */
    private fun <T> specificRule(textMate: String, rules: Map<String, T>): T? {
        rules[textMate]?.let { return it }
        val under = rules.keys.filter { it.startsWith("$textMate.") }.minByOrNull { it.length } ?: return null
        return rules.getValue(under)
    }

    /** Removes the [fallback]'s sub-names under the built-in types the theme set itself ([own]). */
    private fun <T> dropInheritedSubNames(values: MutableMap<TokenType, T>, fallback: Map<TokenType, T>, own: Set<TokenType>) {
        if (own.isEmpty()) return
        for (key in fallback.keys) {
            if (key !is NamedTokenType) continue
            if (own.any { key.name.startsWith(it.scope + ".") || TokenStyleResolver.aliasOf(key.name) == it.scope }) values.remove(key)
        }
    }

    /**
     * What each built-in type stands for, most telling first: TextMate scopes, as VS Code themes
     * name them, and Monaco's standard name, as Monaco themes do.
     */
    private val BASE_SCOPES: List<Pair<TokenType, List<String>>> = listOf(
        TokenType.Keyword to listOf("keyword", "storage.type", "storage"),
        TokenType.Operator to listOf("keyword.operator", "operator"),
        TokenType.Punctuation to listOf("punctuation", "delimiter"),
        TokenType.StringLiteral to listOf("string"),
        TokenType.Comment to listOf("comment"),
        TokenType.Number to listOf("constant.numeric", "number", "constant.language"),
        TokenType.FunctionCall to listOf("entity.name.function", "support.function", "function"),
        TokenType.TypeName to listOf("entity.name.type", "entity.name.class", "support.type", "support.class", "type"),
        TokenType.Identifier to listOf("variable", "support.variable", "identifier"),
        TokenType.Annotation to listOf("entity.other.attribute-name", "meta.tag", "annotation"),
        TokenType.Invalid to listOf("invalid"),
    )

    /** Monaco's sub-names the built-in languages use, and the TextMate scope VS Code themes colour them by. */
    private val MONACO_SUB_SCOPES: List<Pair<String, String>> = listOf(
        "comment.doc" to "comment.block.documentation",
        "string.escape" to "constant.character.escape",
        "regexp" to "string.regexp",
        "keyword.flow" to "keyword.control",
        "number.hex" to "constant.numeric.hex",
        "number.float" to "constant.numeric.float",
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun JsonObject.hexColor(key: String): Color? {
        val hex = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("#") } ?: return null
        return parseHex(hex)
    }

    /** VS Code's colour forms: `#RGB`, `#RGBA`, `#RRGGBB` and `#RRGGBBAA` (alpha last). */
    internal fun parseHex(hex: String): Color? {
        val clean = hex.removePrefix("#")
        val full = when (clean.length) {
            3, 4 -> clean.map { "$it$it" }.joinToString("")
            6, 8 -> clean
            else -> return null
        }
        val r = full.substring(0, 2).toIntOrNull(16) ?: return null
        val g = full.substring(2, 4).toIntOrNull(16) ?: return null
        val b = full.substring(4, 6).toIntOrNull(16) ?: return null
        val a = if (full.length == 8) full.substring(6, 8).toIntOrNull(16) ?: return null else 255
        return Color(r, g, b, a)
    }

    private fun Color.darken(fraction: Float): Color = Color(
        red = (red - fraction).coerceAtLeast(0f),
        green = (green - fraction).coerceAtLeast(0f),
        blue = (blue - fraction).coerceAtLeast(0f),
        alpha = alpha,
    )
}
