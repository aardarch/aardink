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
package com.aardarch.aardink.languages.internal.json

import com.aardarch.aardink.core.DocumentSymbol
import com.aardarch.aardink.core.SymbolKind
import com.aardarch.aardink.languages.internal.CooperativePacer

/**
 * The properties of a JSON document as [DocumentSymbol]s, as VS Code's outline shows them: one per
 * property, named by its key, from the key to the end of its value, of the kind its value is; an
 * object's or array's properties are its children. Array items appear, named by index, only when
 * they are objects or arrays. A top-level object's properties are the top-level symbols.
 *
 * A forgiving scan, not the validating parser: a document with an error, or one being typed,
 * keeps its structure up to and around it. Comments (`//` and `/* */`) are skipped.
 */
internal object JsonSymbols {

    private class Frame(val isObject: Boolean, val start: Int) {
        val children = ArrayList<DocumentSymbol>()
        var key: String? = null
        var keyRange: IntRange = IntRange.EMPTY
        var index = 0
    }

    suspend fun of(text: CharSequence, pacer: CooperativePacer? = null): List<DocumentSymbol> {
        val stack = ArrayList<Frame>()
        var top: List<DocumentSymbol> = emptyList()
        val n = text.length
        var i = 0

        // A value of [kind] from [start] to [last] has ended inside the innermost frame.
        fun value(start: Int, last: Int, kind: SymbolKind, children: List<DocumentSymbol>, isContainer: Boolean) {
            val frame = stack.lastOrNull()
            if (frame == null) {
                if (isContainer && top.isEmpty()) top = children
                return
            }
            if (frame.isObject) {
                val key = frame.key ?: return
                frame.children += DocumentSymbol(key, kind, frame.keyRange.first - 1..last, frame.keyRange, children = children)
                frame.key = null
            } else if (isContainer) {
                frame.children += DocumentSymbol("${frame.index}", kind, start..last, start..start, children = children)
            }
        }

        fun close(frame: Frame, last: Int) {
            value(frame.start, last, if (frame.isObject) SymbolKind.Object else SymbolKind.Array, frame.children, isContainer = true)
        }

        while (i < n) {
            pacer?.onProgress(i)
            val c = text[i]
            when {
                c.isWhitespace() || c == ':' -> i++

                c == ',' -> {
                    stack.lastOrNull()?.let { if (it.isObject) it.key = null else it.index++ }
                    i++
                }

                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }

                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = indexOf(text, "*/", i + 2)
                    i = if (end < 0) n else end + 2
                }

                c == '"' -> {
                    val start = i
                    i++
                    val decoded = StringBuilder()
                    while (i < n && text[i] != '"' && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < n) {
                            // Decoded as the parser decodes them; a malformed escape stays as written.
                            val esc = text[i + 1]
                            val hex = if (esc == 'u' && i + 6 <= n) text.substring(i + 2, i + 6) else null
                            when {
                                hex != null && hex.all { it.digitToIntOrNull(16) != null } -> {
                                    decoded.append(hex.toInt(16).toChar())
                                    i += 6
                                }

                                esc in "\"\\/bfnrt" -> {
                                    decoded.append(jsonUnescape(esc))
                                    i += 2
                                }

                                else -> {
                                    decoded.appendRange(text, i, i + 2)
                                    i += 2
                                }
                            }
                        } else {
                            decoded.append(text[i])
                            i++
                        }
                    }
                    val closed = i < n && text[i] == '"'
                    val last = if (closed) i else i - 1
                    if (closed) i++
                    val frame = stack.lastOrNull()
                    if (frame != null && frame.isObject && frame.key == null && !followedByValueSeparator(text, start)) {
                        frame.key = decoded.toString()
                        frame.keyRange = (start + 1) until (if (closed) last else last + 1)
                    } else {
                        value(start, last, SymbolKind.String, emptyList(), isContainer = false)
                    }
                }

                c == '{' || c == '[' -> {
                    stack += Frame(c == '{', i)
                    i++
                }

                c == '}' || c == ']' -> {
                    val at = stack.indexOfLast { it.isObject == (c == '}') }
                    if (at >= 0) {
                        while (stack.size > at + 1) close(stack.removeAt(stack.size - 1), i - 1)
                        close(stack.removeAt(stack.size - 1), i)
                    }
                    i++
                }

                else -> {
                    val start = i
                    while (i < n && !text[i].isWhitespace() && text[i] !in ",:]}[{\"") i++
                    if (i == start) {
                        i++
                        continue
                    }
                    val word = text.subSequence(start, i)
                    val kind = when {
                        word == "true" || word == "false" -> SymbolKind.Boolean
                        word == "null" -> SymbolKind.Null
                        else -> SymbolKind.Number
                    }
                    value(start, i - 1, kind, emptyList(), isContainer = false)
                }
            }
        }
        while (stack.isNotEmpty()) close(stack.removeAt(stack.size - 1), n - 1)
        return top
    }

    /**
     * Whether the string starting at [start] is a value after its key's colon rather than a key:
     * the last thing before it, past whitespace, is a `:`.
     */
    private fun followedByValueSeparator(text: CharSequence, start: Int): Boolean {
        var j = start - 1
        while (j >= 0 && text[j].isWhitespace()) j--
        return j >= 0 && text[j] == ':'
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
