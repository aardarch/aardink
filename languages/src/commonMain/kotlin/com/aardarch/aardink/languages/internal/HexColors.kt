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
package com.aardarch.aardink.languages.internal

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.ColorInfo
import com.aardarch.aardink.core.ColorPresentation
import com.aardarch.aardink.core.TextEdit
import kotlin.math.roundToInt

/**
 * Hex colours as the built-in services read and write them: `#rgb`, `#rrggbb` and, with alpha,
 * `#rgba` and `#rrggbbaa` as CSS writes them, or `#argb` and `#aarrggbb` with [alphaFirst], as
 * Android resources do.
 */
internal object HexColors {

    /** A hex colour token in CSS. */
    private val TOKEN = Regex("""#([0-9a-fA-F]{3,8})(?![0-9a-zA-Z_-])""")

    /** [hex] (without its `#`) as a colour, or null when it is not 3, 4, 6 or 8 hex digits. */
    fun parse(hex: String, alphaFirst: Boolean): Color? {
        if (hex.any { it.digitToIntOrNull(16) == null }) return null
        val full = when (hex.length) {
            3, 4 -> hex.map { "$it$it" }.joinToString("")
            6, 8 -> hex
            else -> return null
        }
        val (alpha, rgb) = when {
            full.length == 6 -> "ff" to full
            alphaFirst -> full.substring(0, 2) to full.substring(2)
            else -> full.substring(6) to full.substring(0, 6)
        }
        return Color((alpha.toLong(16) shl 24 or rgb.toLong(16)).toInt())
    }

    /**
     * [color] written as [original] (with its `#`) was: the same case, three digits where they
     * suffice and it had three, and alpha only when the colour is not opaque, in the order of
     * [alphaFirst].
     */
    fun format(color: Color, original: String, alphaFirst: Boolean): String {
        fun byte(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255)
        val r = byte(color.red)
        val g = byte(color.green)
        val b = byte(color.blue)
        val a = byte(color.alpha)
        val digits = original.removePrefix("#")
        val short = (digits.length == 3 || digits.length == 4) && listOf(r, g, b, a).all { it % 17 == 0 }
        val text = buildString {
            append('#')
            fun put(value: Int) = if (short) append((value / 17).toString(16)) else append(value.toString(16).padStart(2, '0'))
            if (a != 255 && alphaFirst) put(a)
            put(r)
            put(g)
            put(b)
            if (a != 255 && !alphaFirst) put(a)
        }
        return if (digits.any { it.isUpperCase() }) text.uppercase() else text
    }

    /** The colour presentation of [color] in place of [info] in [document]: written as its text was. */
    fun presentation(document: CodeDocument, info: ColorInfo, color: Color, alphaFirst: Boolean): List<ColorPresentation> {
        val original = document.subSequence(info.range.first, info.range.last + 1).toString()
        if (!original.startsWith("#")) return emptyList()
        val label = format(color, original, alphaFirst)
        return listOf(ColorPresentation(label, TextEdit(info.range, label)))
    }

    /**
     * The string values in [text] that hold nothing but a hex colour, as JSON's ([json]) and TOML's
     * values do. Comments are skipped, and so are keys (a string followed by `:` in JSON, by `=` or
     * a dotted key's `.` in TOML) and TOML's multi-line strings.
     */
    fun quoted(text: CharSequence, json: Boolean): List<ColorInfo> {
        val colors = ArrayList<ColorInfo>()
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                json && c == '/' && i + 1 < n && text[i + 1] == '/' -> i = lineEnd(text, i)

                json && c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = indexOf(text, "*/", i + 2)
                    i = if (end < 0) n else end + 2
                }

                !json && c == '#' -> i = lineEnd(text, i)

                !json && (startsWith(text, i, "\"\"\"") || startsWith(text, i, "'''")) -> {
                    val end = indexOf(text, text.substring(i, i + 3), i + 3)
                    i = if (end < 0) n else end + 3
                }

                c == '"' || (!json && c == '\'') -> {
                    // A basic string has escapes; a TOML literal string ('...') has none.
                    var j = i + 1
                    while (j < n && text[j] != c && text[j] != '\n') j += if (c == '"' && text[j] == '\\') 2 else 1
                    if (j >= n || text[j] != c) {
                        i = j
                        continue
                    }
                    val isKey = when (nextNonBlank(text, j + 1)) {
                        ':' -> json
                        '=', '.' -> !json
                        else -> false
                    }
                    if (!isKey && text[i + 1] == '#') {
                        val color = parse(text.substring(i + 2, j), alphaFirst = false)
                        if (color != null) colors += ColorInfo((i + 1) until j, color)
                    }
                    i = j + 1
                }

                else -> i++
            }
        }
        return colors
    }

    /**
     * The hex colour tokens in [text] (CSS) in declaration values: after a declaration's `:`, inside
     * a `{ }` block. A statement that ends in `{` is a selector (a nested rule's, or an at-rule's
     * prelude), so its tokens (`#id`) are not colours; nor are those in comments, strings or `url()`.
     */
    fun cssTokens(text: CharSequence): List<ColorInfo> {
        val colors = ArrayList<ColorInfo>()
        // The colours of the statement being read, kept once it ends as a declaration.
        val pending = ArrayList<ColorInfo>()
        var inValue = false
        var depth = 0
        val n = text.length
        var i = 0

        fun endStatement(isDeclaration: Boolean) {
            if (isDeclaration) colors += pending
            pending.clear()
            inValue = false
        }

        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = indexOf(text, "*/", i + 2)
                    i = if (end < 0) n else end + 2
                    continue
                }

                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && text[j] != c && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    i = j + 1
                    continue
                }

                (c == 'u' || c == 'U') && startsWith(text, i, "url(", ignoreCase = true) &&
                    (i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '-' || text[i - 1] == '_')) -> {
                    val end = indexOf(text, ")", i + 4)
                    i = if (end < 0) n else end + 1
                    continue
                }

                c == '{' -> {
                    endStatement(isDeclaration = false)
                    depth++
                }

                c == '}' -> {
                    endStatement(isDeclaration = depth > 0)
                    depth = (depth - 1).coerceAtLeast(0)
                }

                c == ';' -> endStatement(isDeclaration = depth > 0)

                c == ':' -> inValue = true

                c == '#' && depth > 0 && inValue -> {
                    val match = TOKEN.matchAt(text, i)
                    val color = match?.let { parse(it.groupValues[1], alphaFirst = false) }
                    if (match != null && color != null) {
                        pending += ColorInfo(match.range, color)
                        i = match.range.last + 1
                        continue
                    }
                }
            }
            i++
        }
        return colors
    }

    private fun lineEnd(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    /** The first character from [from] on that is not a space or tab, or null at the end of [text]. */
    private fun nextNonBlank(text: CharSequence, from: Int): Char? {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return if (i < text.length) text[i] else null
    }

    private fun startsWith(text: CharSequence, at: Int, prefix: String, ignoreCase: Boolean = false): Boolean =
        at + prefix.length <= text.length && prefix.indices.all { text[at + it].equals(prefix[it], ignoreCase) }

    private fun indexOf(text: CharSequence, needle: String, from: Int): Int {
        var i = from
        while (i + needle.length <= text.length) {
            if (needle.indices.all { text[i + it] == needle[it] }) return i
            i++
        }
        return -1
    }
}
