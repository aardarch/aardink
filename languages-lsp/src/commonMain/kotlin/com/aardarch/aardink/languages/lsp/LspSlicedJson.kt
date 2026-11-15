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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * [text] as a JSON tree, as [LspJson]'s `parseToJsonElement` reads it, in slices: [pacer] gets a turn
 * every [CHECK_EVERY_CHARS] characters. On wasm the compute dispatcher is the event loop that also
 * paints, and an answer can be as long as the document, so parsing a large one in one go is a long
 * frame (docs/AARDINK_ROADMAP.md §15.1: about 73 ms for 272 KB of semantic tokens).
 *
 * Unquoted values are taken as `parseToJsonElement` takes them, up to the next delimiter, so the
 * same text gives an equal tree. Nesting deeper than [MAX_DEPTH] is parsed by `parseToJsonElement`
 * in one go instead, which no server's answer comes near.
 *
 * @throws SerializationException for what is not JSON, as `parseToJsonElement` throws.
 */
internal suspend fun parseJsonInSlices(text: String, pacer: LspDecodePacer = LspDecodePacer()): JsonElement {
    val parser = SlicedJsonParser(text, pacer)
    return try {
        parser.document()
    } catch (_: TooDeep) {
        LspJson.parseToJsonElement(text)
    }
}

/** The payloads [parseJsonInSlices] is for: shorter ones parse in less than a slice. */
internal const val SLICED_PARSE_MIN_CHARS = 64 * 1024

private const val CHECK_EVERY_CHARS = 16 * 1024
private const val MAX_DEPTH = 200

private class TooDeep : RuntimeException()

private class SlicedJsonParser(private val text: String, private val pacer: LspDecodePacer) {
    private var at = 0
    private var nextCheck = CHECK_EVERY_CHARS
    private val builder = StringBuilder()

    suspend fun document(): JsonElement {
        val value = value(0)
        skipSpace()
        if (at != text.length) fail("unexpected text after the value")
        return value
    }

    private suspend fun value(depth: Int): JsonElement {
        if (at >= nextCheck) {
            pacer.check()
            nextCheck = at + CHECK_EVERY_CHARS
        }
        skipSpace()
        if (at >= text.length) fail("unexpected end of input")
        return when (text[at]) {
            '{' -> objectAt(depth + 1)
            '[' -> arrayAt(depth + 1)
            '"' -> JsonPrimitive(string())
            else -> literal()
        }
    }

    private suspend fun objectAt(depth: Int): JsonObject {
        if (depth > MAX_DEPTH) throw TooDeep()
        at++
        val members = LinkedHashMap<String, JsonElement>()
        skipSpace()
        if (peek() == '}') {
            at++
            return JsonObject(members)
        }
        while (true) {
            skipSpace()
            if (peek() != '"') fail("expected a member name")
            val name = string()
            skipSpace()
            if (peek() != ':') fail("expected ':'")
            at++
            members[name] = value(depth)
            skipSpace()
            when (peek()) {
                ',' -> at++

                '}' -> {
                    at++
                    return JsonObject(members)
                }

                else -> fail("expected ',' or '}'")
            }
        }
    }

    private suspend fun arrayAt(depth: Int): JsonArray {
        if (depth > MAX_DEPTH) throw TooDeep()
        at++
        val items = ArrayList<JsonElement>()
        skipSpace()
        if (peek() == ']') {
            at++
            return JsonArray(items)
        }
        while (true) {
            items += value(depth)
            skipSpace()
            when (peek()) {
                ',' -> at++

                ']' -> {
                    at++
                    return JsonArray(items)
                }

                else -> fail("expected ',' or ']'")
            }
        }
    }

    /** The string starting at [at], its quotes and escapes read. */
    private fun string(): String {
        at++
        val start = at
        // Most strings have no escape: one substring, no builder.
        while (at < text.length) {
            val char = text[at]
            if (char == '"') return text.substring(start, at++)
            if (char == '\\') break
            at++
        }
        builder.setLength(0)
        builder.append(text, start, at)
        while (at < text.length) {
            val char = text[at++]
            when (char) {
                '"' -> return builder.toString()

                '\\' -> {
                    if (at >= text.length) fail("unexpected end of input in a string")
                    when (val escaped = text[at++]) {
                        '"', '\\', '/' -> builder.append(escaped)

                        'b' -> builder.append('\b')

                        'f' -> builder.append('\u000C')

                        'n' -> builder.append('\n')

                        'r' -> builder.append('\r')

                        't' -> builder.append('\t')

                        'u' -> {
                            if (at + 4 > text.length) fail("unexpected end of input in an escape")
                            val code = text.substring(at, at + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                            builder.append(code.toChar())
                            at += 4
                        }

                        else -> fail("bad escape '\\$escaped'")
                    }
                }

                else -> builder.append(char)
            }
        }
        fail("unterminated string")
    }

    /** A number, `true`, `false` or `null`: up to the next delimiter, as `parseToJsonElement` reads one. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun literal(): JsonElement {
        val start = at
        while (at < text.length && text[at] !in DELIMITERS) at++
        if (at == start) fail("unexpected '${text[at]}'")
        val content = text.substring(start, at)
        return if (content == "null") JsonNull else JsonUnquotedLiteral(content)
    }

    private fun peek(): Char = if (at < text.length) text[at] else fail("unexpected end of input")

    private fun skipSpace() {
        while (at < text.length) {
            val char = text[at]
            if (char != ' ' && char != '\n' && char != '\r' && char != '\t') return
            at++
        }
    }

    private fun fail(message: String): Nothing = throw SerializationException("Unexpected JSON token at offset $at: $message")

    private companion object {
        const val DELIMITERS = " \n\r\t,:]}[{\""
    }
}
