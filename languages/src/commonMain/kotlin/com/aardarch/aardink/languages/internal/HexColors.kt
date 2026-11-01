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

    /** A string literal (in double or single quotes) holding nothing but a hex colour. */
    private val QUOTED = Regex("""(["'])#([0-9a-fA-F]{3,8})\1""")

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

    /** The quoted strings in [text] that hold nothing but a hex colour, as JSON and TOML values do. */
    fun quoted(text: CharSequence, alphaFirst: Boolean = false): List<ColorInfo> = QUOTED.findAll(text).mapNotNull { match ->
        val color = parse(match.groupValues[2], alphaFirst) ?: return@mapNotNull null
        ColorInfo((match.range.first + 1) until match.range.last, color)
    }.toList()

    /** The hex colour tokens in [text] (CSS), outside comments and inside a `{ }` block. */
    fun cssTokens(text: CharSequence): List<ColorInfo> {
        val colors = ArrayList<ColorInfo>()
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = indexOf(text, "*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                    continue
                }

                c == '{' -> depth++

                c == '}' -> depth = (depth - 1).coerceAtLeast(0)

                c == '#' && depth > 0 -> {
                    val match = TOKEN.matchAt(text, i)
                    val color = match?.let { parse(it.groupValues[1], alphaFirst = false) }
                    if (match != null && color != null) {
                        colors += ColorInfo(match.range, color)
                        i = match.range.last + 1
                        continue
                    }
                }
            }
            i++
        }
        return colors
    }

    private fun indexOf(text: CharSequence, needle: String, from: Int): Int {
        var i = from
        while (i + needle.length <= text.length) {
            if (needle.indices.all { text[i + it] == needle[it] }) return i
            i++
        }
        return -1
    }
}
