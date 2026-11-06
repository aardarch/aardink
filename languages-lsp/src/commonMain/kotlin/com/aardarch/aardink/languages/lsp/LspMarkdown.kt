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

/** A URL, kept whole, or what Markdown could read as markup in the middle of a line of text. */
private val INLINE_MARKS = Regex("""https?://[^\s"'<>()\[\]{}`\\]+|[\\`*_\[\]<&]""")

/** What Markdown could read as a heading, a list, a fence or an underline at the start of a line. */
private const val BLOCK_MARKS = "#=+-~"

private val ORDERED_MARKER = Regex("""^(\d+)([.)])""")

/** A hover's `MarkedString | MarkedString[] | MarkupContent` as Markdown: a bare string already is. */
internal fun hoverMarkdown(element: JsonElement?): String? = markdownOf(element, plainStrings = false)

/** A signature's, parameter's or completion's `string | MarkupContent` as Markdown: a bare string here is plain text. */
internal fun documentationMarkdown(element: JsonElement?): String? = markdownOf(element, plainStrings = true)

private fun markdownOf(element: JsonElement?, plainStrings: Boolean): String? = when (element) {
    null -> null

    is JsonPrimitive -> element.contentOrNull?.let { if (plainStrings) plainAsMarkdown(it) else it }

    is JsonObject -> objectMarkdown(element)

    is JsonArray -> element.mapNotNull { markdownOf(it, plainStrings) }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
        .takeIf { it.isNotEmpty() }
}

/** A `MarkupContent`, or a `MarkedString`'s `{ language, value }`, which is code: none at all is no answer. */
private fun objectMarkdown(element: JsonObject): String? {
    val value = (element["value"] as? JsonPrimitive)?.contentOrNull ?: return null
    val language = (element["language"] as? JsonPrimitive)?.contentOrNull
    val kind = (element["kind"] as? JsonPrimitive)?.contentOrNull
    return when {
        language != null -> value.takeIf { it.isNotBlank() }?.let { fenced(language, it) }
        kind == "plaintext" -> plainAsMarkdown(value)
        else -> value
    }
}

/** [code] in a fence longer than any run of backticks inside it. */
private fun fenced(language: String, code: String): String {
    val longest = Regex("`+").findAll(code).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longest + 1))
    return "$fence$language\n$code\n$fence"
}

/** [text] as Markdown that shows it to the letter and line for line. Only each line's indentation is lost. */
private fun plainAsMarkdown(text: String): String {
    val lines = text.trim().lines().map(::plainLine)
    return buildString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append(if (line.isEmpty() || lines[index - 1].isEmpty()) "\n" else "\\\n")
            append(line)
        }
    }
}

private fun plainLine(line: String): String {
    val text = INLINE_MARKS.replace(line.trim()) { if (it.value.length > 1) it.value else "\\" + it.value }
    val first = text.firstOrNull() ?: return ""
    if (first in BLOCK_MARKS) return "\\$text"
    return ORDERED_MARKER.replace(text) { it.groupValues[1] + "\\" + it.groupValues[2] }
}
