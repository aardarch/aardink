package com.aardarch.editor.ui

import androidx.compose.ui.graphics.Color
import com.aardarch.editor.core.EditorTheme
import com.aardarch.editor.core.TokenType
import org.json.JSONException
import org.json.JSONObject

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
 */
object EditorThemeParser {

    /**
     * Parses [json] as a VS Code theme. Returns null if the JSON is malformed or
     * required keys are missing; the caller should fall back to a built-in theme.
     */
    fun fromJson(json: String): EditorTheme? = try {
        parseTheme(JSONObject(json))
    } catch (_: JSONException) {
        null
    }

    private fun parseTheme(root: JSONObject): EditorTheme {
        val colors = root.optJSONObject("colors") ?: JSONObject()
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

        // ── Token colors ──────────────────────────────────────────────────────
        val tokenColors = buildTokenColors(root, foreground, fallback.tokenColors)

        return EditorTheme(
            background = background,
            gutterBackground = gutterBg,
            gutterForeground = gutterFg,
            lineHighlight = lineHighlight,
            selectionColor = selection,
            findMatchColor = findMatch,
            cursorColor = cursor,
            tokenColors = tokenColors,
            errorColor = fallback.errorColor,
            warningColor = fallback.warningColor,
            infoColor = fallback.infoColor,
        )
    }

    private fun buildTokenColors(
        root: JSONObject,
        defaultFg: Color,
        fallback: Map<TokenType, Color>,
    ): Map<TokenType, Color> {
        val result = fallback.toMutableMap()
        result[TokenType.Default] = defaultFg

        val tokenColorsArray = root.optJSONArray("tokenColors") ?: return result

        for (i in 0 until tokenColorsArray.length()) {
            val entry = tokenColorsArray.optJSONObject(i) ?: continue
            val settings = entry.optJSONObject("settings") ?: continue
            val fg = settings.hexColor("foreground") ?: continue

            val scopes: List<String> = when {
                entry.has("scope") -> {
                    val raw = entry.get("scope")
                    when (raw) {
                        is String -> raw.split(",").map { it.trim() }

                        else -> {
                            val arr = entry.optJSONArray("scope") ?: continue
                            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                        }
                    }
                }

                else -> continue
            }

            for (scope in scopes) {
                mapScope(scope)?.let { type -> result[type] = fg }
            }
        }

        return result
    }

    private fun mapScope(scope: String): TokenType? = when {
        scope.startsWith("keyword") && !scope.startsWith("keyword.operator") -> TokenType.Keyword

        scope.startsWith("storage") -> TokenType.Keyword

        scope.startsWith("keyword.operator") -> TokenType.Operator

        scope.startsWith("punctuation") -> TokenType.Punctuation

        scope.startsWith("string") -> TokenType.StringLiteral

        scope.startsWith("comment") -> TokenType.Comment

        scope.startsWith("constant.numeric") || scope == "constant.language" -> TokenType.Number

        scope.startsWith("entity.name.function") || scope.startsWith("support.function") -> TokenType.FunctionCall

        scope.startsWith("entity.name.type") || scope.startsWith("entity.name.class") ||
            scope.startsWith("support.type") || scope.startsWith("support.class") -> TokenType.TypeName

        scope.startsWith("variable") || scope.startsWith("support.variable") -> TokenType.Identifier

        scope.startsWith("entity.other.attribute") || scope.startsWith("meta.tag") -> TokenType.Annotation

        scope.startsWith("invalid") -> TokenType.Invalid

        else -> null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun JSONObject.hexColor(key: String): Color? {
        val hex = optString(key).takeIf { it.startsWith("#") } ?: return null
        return parseHex(hex)
    }

    private fun parseHex(hex: String): Color? {
        val clean = hex.removePrefix("#")
        return when (clean.length) {
            6 -> {
                val r = clean.substring(0, 2).toIntOrNull(16) ?: return null
                val g = clean.substring(2, 4).toIntOrNull(16) ?: return null
                val b = clean.substring(4, 6).toIntOrNull(16) ?: return null
                Color(r, g, b)
            }

            8 -> {
                val a = clean.substring(0, 2).toIntOrNull(16) ?: return null
                val r = clean.substring(2, 4).toIntOrNull(16) ?: return null
                val g = clean.substring(4, 6).toIntOrNull(16) ?: return null
                val b = clean.substring(6, 8).toIntOrNull(16) ?: return null
                Color(r, g, b, a)
            }

            else -> null
        }
    }

    private fun Color.darken(fraction: Float): Color = Color(
        red = (red - fraction).coerceAtLeast(0f),
        green = (green - fraction).coerceAtLeast(0f),
        blue = (blue - fraction).coerceAtLeast(0f),
        alpha = alpha,
    )
}
