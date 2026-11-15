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
package com.aardarch.aardink.languages.internal.css

import com.aardarch.aardink.core.FormattingOptions

/**
 * CSS formatted as VS Code formats it by default: a rule's selectors one to a line, then ` {`; a
 * declaration to a line as `name: value;`; a block's contents one level in; a blank line after a
 * top-level rule. A blank line between statements in the source stays, as one. Strings and
 * comments keep their text, and a comment that followed something on its line still does. Other
 * runs of whitespace become one space.
 */
internal object CssFormatter {

    fun format(text: String, options: FormattingOptions): String {
        if (text.isBlank()) return text
        val unit = options.indentUnit
        val out = StringBuilder()
        var depth = 0
        var i = 0
        // A top-level rule just closed: what comes next, other than the end, gets a blank line.
        var ruleClosed = false
        while (true) {
            var newlines = 0
            while (i < text.length && text[i].isWhitespace()) {
                if (text[i] == '\n') newlines++
                i++
            }
            if (i >= text.length) break
            val blank = out.isNotEmpty() && (newlines >= 2 || ruleClosed)
            val closedBefore = ruleClosed
            ruleClosed = false
            when {
                text[i] == '}' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    out.line(unit, depth, "}")
                    i++
                    ruleClosed = depth == 0
                }

                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    val comment = text.substring(i, end)
                    if (newlines == 0 && out.isNotEmpty()) {
                        // After something on the same line: it stays there.
                        out.setLength(out.length - 1)
                        out.append(' ').append(comment).append('\n')
                        ruleClosed = closedBefore
                    } else {
                        if (blank) out.append('\n')
                        out.line(unit, depth, comment)
                    }
                    i = end
                }

                else -> {
                    val end = statementEnd(text, i)
                    val body = collapse(text, i, end)
                    when (if (end < text.length) text[end] else null) {
                        '{' -> {
                            if (blank) out.append('\n')
                            out.line(unit, depth, prelude(body, unit.repeat(depth)) + " {")
                            depth++
                            i = end + 1
                        }

                        ';' -> {
                            if (body.isNotEmpty()) {
                                if (blank) out.append('\n')
                                out.line(unit, depth, declaration(body) + ";")
                            }
                            i = end + 1
                        }

                        // The last declaration of a block may leave out its `;`; it gets one.
                        '}' -> {
                            if (blank) out.append('\n')
                            out.line(unit, depth, declaration(body) + ";")
                            i = end
                        }

                        else -> {
                            if (blank) out.append('\n')
                            out.line(unit, depth, declaration(body))
                            i = end
                        }
                    }
                }
            }
        }
        val formatted = out.trimEnd('\n').toString().let { if (text.endsWith('\n')) "$it\n" else it }
        // A comment keeps its own line breaks; a CR LF document gets CR LF throughout.
        return if ("\r\n" in text) formatted.replace("\r\n", "\n").replace("\n", "\r\n") else formatted
    }

    private fun StringBuilder.line(unit: String, depth: Int, text: String) {
        repeat(depth) { append(unit) }
        append(text).append('\n')
    }

    /** Where the statement from [start] ends: its `{`, `;` or `}` outside strings, comments and brackets, or the end. */
    private fun statementEnd(text: String, start: Int): Int {
        var i = start
        var nesting = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' || c == '\'' -> i = stringEnd(text, i)

                text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }

                c == '(' || c == '[' -> {
                    nesting++
                    i++
                }

                c == ')' || c == ']' -> {
                    nesting = (nesting - 1).coerceAtLeast(0)
                    i++
                }

                nesting == 0 && (c == '{' || c == ';' || c == '}') -> return i

                else -> i++
            }
        }
        return text.length
    }

    /** Just past the string opened at [start], or the end of its line when it is not closed. */
    private fun stringEnd(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return text.length
    }

    /** [text] from [start] to [end], trimmed, with each run of whitespace outside strings and comments as one space. */
    private fun collapse(text: String, start: Int, end: Int): String {
        val out = StringBuilder()
        var i = start
        while (i < end) {
            val c = text[i]
            when {
                c == '"' || c == '\'' -> {
                    val close = stringEnd(text, i).coerceAtMost(end)
                    out.append(text, i, close)
                    i = close
                }

                text.startsWith("/*", i) -> {
                    val close = text.indexOf("*/", i + 2).let { if (it < 0 || it + 2 > end) end else it + 2 }
                    out.append(text, i, close)
                    i = close
                }

                c.isWhitespace() -> {
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.trimEnd().toString()
    }

    /** A rule's selectors one to a line, at [indent]; an at-rule's prelude as it is. */
    private fun prelude(body: String, indent: String): String {
        if (body.startsWith('@')) return body
        return topLevelSplit(body, ',').joinToString(",\n$indent") { it.trim() }
    }

    /** `name: value`, with one space after the colon; a statement with no colon outside brackets as it is. */
    private fun declaration(body: String): String {
        if (body.startsWith('@')) return body
        val colon = topLevelSplit(body, ':', limit = 2)
        if (colon.size < 2) return body
        return colon[0].trim() + ": " + colon[1].trim()
    }

    /** [body] split at each [separator] outside strings, comments and brackets, at most into [limit] parts. */
    private fun topLevelSplit(body: String, separator: Char, limit: Int = Int.MAX_VALUE): List<String> {
        val parts = ArrayList<String>()
        var nesting = 0
        var from = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '"' || c == '\'' -> i = stringEnd(body, i)

                body.startsWith("/*", i) -> i = body.indexOf("*/", i + 2).let { if (it < 0) body.length else it + 2 }

                c == '(' || c == '[' -> {
                    nesting++
                    i++
                }

                c == ')' || c == ']' -> {
                    nesting = (nesting - 1).coerceAtLeast(0)
                    i++
                }

                c == separator && nesting == 0 && parts.size < limit - 1 -> {
                    parts += body.substring(from, i)
                    from = i + 1
                    i++
                }

                else -> i++
            }
        }
        parts += body.substring(from)
        return parts
    }
}
