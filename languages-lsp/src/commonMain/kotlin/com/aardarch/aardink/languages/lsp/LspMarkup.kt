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
package com.aardarch.aardink.languages.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What is inline Markdown wherever it stands in a line. */
private const val INLINE_MARKS = "\\`*_[]<&"

/** What is Markdown only as the first thing on a line. `~` opens a fence. */
private const val LINE_START_MARKS = "#>+-=~"

private val ListNumber = Regex("""^\d+[.)]""")

private val BacktickRun = Regex("`+")

/**
 * A hover's `MarkedString | MarkedString[] | MarkupContent` as Markdown, or null. A bare string is
 * a `MarkedString`, which the protocol says is Markdown already.
 */
internal fun hoverMarkdown(contents: JsonElement?): String? = markdownOf(contents) { it }

/**
 * A `documentation`'s `string | MarkupContent` as Markdown, or null. Here a bare string is plain
 * text, as it is not for a hover.
 */
internal fun documentationMarkdown(documentation: JsonElement?): String? = markdownOf(documentation, ::plainTextAsMarkdown)

private fun markdownOf(element: JsonElement?, bareString: (String) -> String): String? = when (element) {
    null -> null

    is JsonPrimitive -> element.contentOrNull?.let(bareString)

    is JsonObject -> markedText(element)

    is JsonArray ->
        element
            .mapNotNull { markdownOf(it, bareString) }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotEmpty() }
}

/** A `MarkupContent` by its `kind`, or a `MarkedString`'s `{ language, value }` as a code block. */
private fun markedText(element: JsonObject): String? {
    fun member(name: String) = (element[name] as? JsonPrimitive)?.contentOrNull
    val value = member("value") ?: return null
    val language = member("language")
    return when {
        language != null -> codeFence(language, value)
        member("kind") == "markdown" -> value
        else -> plainTextAsMarkdown(value)
    }
}

/** Longer than any run of backticks in [code], which would otherwise end it early. */
private fun codeFence(language: String, code: String): String {
    if (code.isBlank()) return ""
    val fence = "`".repeat(maxOf(3, (BacktickRun.findAll(code).maxOfOrNull { it.value.length } ?: 0) + 1))
    val info = language.takeWhile { !it.isWhitespace() && it != '`' }
    return "$fence$info\n${code.trimEnd('\n', '\r')}\n$fence"
}

/** [text] as Markdown that reads to the letter: its marks escaped, and each of its lines kept. */
internal fun plainTextAsMarkdown(text: String): String {
    val lines = text.lines()
    return buildString {
        lines.forEachIndexed { index, line ->
            appendEscaped(line)
            if (index < lines.lastIndex) {
                // A plain newline would run the two lines together; a blank line ends a paragraph anyway.
                if (line.isNotBlank() && lines[index + 1].isNotBlank()) append('\\')
                append('\n')
            }
        }
    }
}

private fun StringBuilder.appendEscaped(line: String) {
    val indent = line.indexOfFirst { it != ' ' && it != '\t' }
    if (indent < 0) return
    // Four spaces would start a code block, and only an entity keeps them as they are.
    for (i in 0 until indent) append(if (line[i] == '\t') "&nbsp;&nbsp;&nbsp;&nbsp;" else "&nbsp;")
    val text = line.substring(indent)
    val listNumberEnd = ListNumber.find(text)?.range?.last
    text.forEachIndexed { i, c ->
        if (c in INLINE_MARKS || i == listNumberEnd || (i == 0 && c in LINE_START_MARKS)) append('\\')
        append(c)
    }
}
