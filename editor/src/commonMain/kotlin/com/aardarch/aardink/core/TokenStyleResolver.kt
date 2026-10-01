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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * How [theme] draws each token type, found as Monaco's token themes find it: the type's own
 * entry, else the entry of the longest dotted prefix of its [TokenType.scope] that has one, so
 * `comment.doc.kotlin` takes `comment.doc`'s colour, or else `comment`'s. Monaco's standard names
 * that are not a built-in type's scope (`tag`, `attribute.value`, `regexp`, …) fall back to the
 * built-in type that colours them in the built-in themes. Colour and font style are found
 * separately, so a more specific entry with only a colour keeps a general entry's italics.
 *
 * Answers are cached per type; make a new resolver for a new theme.
 */
internal class TokenStyleResolver(private val theme: EditorTheme) {

    private val colorsByScope = byScope(theme.tokenColors)
    private val stylesByScope = byScope(theme.tokenFontStyles)
    private val spanStyles = HashMap<TokenType, SpanStyle?>()

    /** The colour of [type], or null for the default text colour. */
    fun color(type: TokenType): Color? = resolve(type, theme.tokenColors, colorsByScope)

    /** The font style of [type], or null for upright and regular. */
    fun fontStyle(type: TokenType): TokenFontStyle? = resolve(type, theme.tokenFontStyles, stylesByScope)

    /** What a [type] token's text is drawn with, or null when it is plain text. */
    fun spanStyle(type: TokenType): SpanStyle? {
        if (spanStyles.containsKey(type)) return spanStyles[type]
        val color = color(type)
        val font = fontStyle(type)?.takeIf { it != TokenFontStyle.None }
        val style = if (color == null && font == null) {
            null
        } else {
            val decorations = listOfNotNull(
                TextDecoration.Underline.takeIf { font?.underline == true },
                TextDecoration.LineThrough.takeIf { font?.strikethrough == true },
            )
            SpanStyle(
                color = color ?: Color.Unspecified,
                fontWeight = if (font?.bold == true) FontWeight.Bold else null,
                fontStyle = if (font?.italic == true) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            )
        }
        spanStyles[type] = style
        return style
    }

    private fun <T> resolve(type: TokenType, exact: Map<TokenType, T>, byScope: Map<String, T>): T? {
        exact[type]?.let { return it }
        var candidate = type.scope
        while (candidate.isNotEmpty()) {
            byScope[candidate]?.let { return it }
            MONACO_ALIASES[candidate]?.let { alias -> byScope[alias]?.let { return it } }
            val dot = candidate.lastIndexOf('.')
            if (dot < 0) break
            candidate = candidate.substring(0, dot)
        }
        return null
    }

    internal companion object {
        /**
         * The built-in scope that colours Monaco's standard name [name] (or its first part) when it
         * is not a built-in type's own: `tag` and `tag.aardflex` are coloured as `type`.
         */
        fun aliasOf(name: String): String? = MONACO_ALIASES[name] ?: MONACO_ALIASES[name.substringBefore('.')]

        /** Entries by scope: the built-in types', then named ones, which win for the same name. */
        private fun <T> byScope(entries: Map<TokenType, T>): Map<String, T> {
            val result = HashMap<String, T>()
            for ((type, value) in entries) if (type !is NamedTokenType && type.scope.isNotEmpty()) result[type.scope] = value
            for ((type, value) in entries) if (type is NamedTokenType) result[type.scope] = value
            return result
        }

        /** Monaco's standard names that are not a built-in type's, and the scope that colours them. */
        private val MONACO_ALIASES: Map<String, String> = mapOf(
            "constant" to "number",
            "variable" to "identifier",
            "predefined" to "function",
            "metatag" to "annotation",
            "regexp" to "string",
            "regex" to "string",
            // As the built-in XML highlighting colours them.
            "tag" to "type",
            "attribute.name" to "identifier",
            "attribute.value" to "string",
            "entity" to "number",
        )
    }
}
