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
package com.aardarch.aardink.core.edit

/**
 * A completion's snippet, expanded: the [text] to insert and its tab stops, with ranges relative to
 * the start of [text]. [stops] are in the order Tab visits them: 1, 2, … and last the final stop
 * (`$0`, or the end of the text when the snippet has none).
 */
internal class ExpandedSnippet(val text: String, val stops: List<SnippetStop>)

/**
 * One tab stop: every place it occurs (`$1` twice is one stop, edited at both places at once), as
 * half-open `[start, end)` pairs, and the [choices] of a `${1|a,b|}` stop.
 */
internal class SnippetStop(val index: Int, val ranges: List<IntRange>, val choices: List<String>? = null) {
    val isFinal: Boolean get() = index == 0
}

/**
 * VS Code's (TextMate's) snippet syntax, as Monaco's `InsertAsSnippet` reads it:
 *
 * - `$1`, `${1}`: a tab stop; `$0` is where the caret ends up.
 * - `${1:text}`: a placeholder, selected when Tab reaches it. Placeholders nest.
 * - `${1|one,two|}`: a choice; the first is inserted and the rest offered.
 * - `$NAME`, `${NAME}`, `${NAME:default}`: a variable, from [variable]. One it does not know
 *   becomes a placeholder with its own name, as in VS Code.
 * - `\$`, `\}` and `\\` are the characters themselves. Anything that does not parse is inserted as
 *   written.
 *
 * Transforms (`${1/regex/format/}`) are not supported and are inserted as written.
 */
internal object SnippetParser {

    /**
     * Expands [snippet]. Each line after the first gets [indent] in front of it, and a tab in a
     * line's leading whitespace becomes [indentUnit], so a snippet written with `\n\t` follows the
     * indentation of the line it lands on (VS Code's `adjustWhitespace`); pass null for both to
     * insert the whitespace as written.
     */
    fun expand(
        snippet: String,
        variable: (String) -> String? = { null },
        indent: String? = null,
        indentUnit: String? = null,
    ): ExpandedSnippet {
        val nodes = Parser(snippet).parse()
        // Unknown variables become placeholders numbered after every real stop.
        var nextIndex = maxIndex(nodes) + 1
        val resolved = resolveVariables(nodes, variable) { nextIndex++ }
        val defaults = HashMap<Int, List<Node>>()
        collectDefaults(resolved, defaults)
        val writer = Writer(indent, indentUnit)
        writer.write(resolved, defaults, mirror = false)
        val ranges = writer.ranges
        val choices = writer.choices
        val ordered = ranges.keys.filter { it > 0 }.sorted()
        val stops = ordered.map { SnippetStop(it, ranges.getValue(it), choices[it]) }
        val final = ranges[0]?.let { SnippetStop(0, it) } ?: SnippetStop(0, listOf(writer.length until writer.length))
        return ExpandedSnippet(writer.text, stops + final)
    }

    // ── Syntax tree ──────────────────────────────────────────────────────────

    private sealed interface Node
    private class Text(val value: String) : Node
    private class TabStop(val index: Int, val children: List<Node>?, val choices: List<String>? = null) : Node
    private class Variable(val name: String, val default: List<Node>?) : Node

    /** A variable's default, inlined where the variable was. */
    private class Group(val children: List<Node>) : Node

    private fun maxIndex(nodes: List<Node>): Int = nodes.maxOfOrNull { node ->
        when (node) {
            is TabStop -> maxOf(node.index, node.children?.let(::maxIndex) ?: 0)
            is Variable -> node.default?.let(::maxIndex) ?: 0
            is Group -> maxIndex(node.children)
            is Text -> 0
        }
    } ?: 0

    private fun resolveVariables(nodes: List<Node>, variable: (String) -> String?, newIndex: () -> Int): List<Node> = nodes.map { node ->
        when (node) {
            is Text -> node

            is TabStop -> node.children?.let { TabStop(node.index, resolveVariables(it, variable, newIndex), node.choices) } ?: node

            is Variable -> {
                val value = variable(node.name)
                when {
                    value != null -> Text(value)
                    node.default != null -> Group(resolveVariables(node.default, variable, newIndex))
                    else -> TabStop(newIndex(), listOf(Text(node.name)))
                }
            }

            is Group -> node
        }
    }

    /** The first placeholder text of each stop: a bare `$1` elsewhere mirrors it. */
    private fun collectDefaults(nodes: List<Node>, into: MutableMap<Int, List<Node>>) {
        for (node in nodes) {
            when (node) {
                is TabStop -> {
                    val children = node.children ?: node.choices?.firstOrNull()?.let { listOf(Text(it)) }
                    if (children != null && children.isNotEmpty() && node.index !in into) into[node.index] = children
                    node.children?.let { collectDefaults(it, into) }
                }

                is Group -> collectDefaults(node.children, into)

                else -> Unit
            }
        }
    }

    private class Writer(private val indent: String?, private val indentUnit: String?) {
        private val out = StringBuilder()
        private var atLineStart = false
        val ranges = LinkedHashMap<Int, MutableList<IntRange>>()
        val choices = HashMap<Int, List<String>>()
        val text: String get() = out.toString()
        val length: Int get() = out.length

        fun write(nodes: List<Node>, defaults: Map<Int, List<Node>>, mirror: Boolean) {
            for (node in nodes) {
                when (node) {
                    is Text -> append(node.value)

                    is Group -> write(node.children, defaults, mirror)

                    is Variable -> Unit

                    // resolved before writing
                    is TabStop -> {
                        val start = out.length
                        val children = node.children ?: node.choices?.firstOrNull()?.let { listOf(Text(it)) } ?: defaults[node.index]
                        // A mirror's text is a copy: its nested stops are edited at the original.
                        if (children != null) write(children, defaults, mirror = mirror || node.children == null)
                        if (!mirror) {
                            ranges.getOrPut(node.index) { ArrayList() } += start until out.length
                            node.choices?.let { if (node.index !in choices) choices[node.index] = it }
                        }
                    }
                }
            }
        }

        private fun append(value: String) {
            for (c in value) {
                when {
                    c == '\n' -> {
                        out.append('\n')
                        if (indent != null) out.append(indent)
                        atLineStart = true
                    }

                    atLineStart && c == '\t' && indentUnit != null -> out.append(indentUnit)

                    else -> {
                        if (c != ' ' && c != '\t') atLineStart = false
                        out.append(c)
                    }
                }
            }
        }
    }

    // ── Parser ───────────────────────────────────────────────────────────────

    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): List<Node> = parseUntil(closing = false)

        /** Nodes up to the end, or up to an unescaped `}` when [closing] (which is left in place). */
        private fun parseUntil(closing: Boolean): List<Node> {
            val nodes = ArrayList<Node>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) {
                    nodes += Text(text.toString())
                    text.clear()
                }
            }
            while (pos < src.length) {
                val c = src[pos]
                when {
                    c == '\\' && pos + 1 < src.length && src[pos + 1] in "\$}\\" -> {
                        text.append(src[pos + 1])
                        pos += 2
                    }

                    c == '}' && closing -> break

                    c == '$' -> {
                        val node = parseDollar()
                        if (node == null) {
                            text.append('$')
                            pos++
                        } else {
                            flush()
                            nodes += node
                        }
                    }

                    else -> {
                        text.append(c)
                        pos++
                    }
                }
            }
            flush()
            return nodes
        }

        /** A `$…` construct at [pos], consumed; or null, with [pos] unchanged, when it is not one. */
        private fun parseDollar(): Node? {
            val start = pos
            pos++ // $
            if (pos >= src.length) return reset(start)
            val c = src[pos]
            if (c.isDigit()) return TabStop(readInt(), null)
            if (c.isNameStart()) return Variable(readName(), null)
            if (c != '{') return reset(start)
            pos++ // {
            if (pos >= src.length) return reset(start)
            if (src[pos].isDigit()) {
                val index = readInt()
                if (pos >= src.length) return reset(start)
                when (src[pos]) {
                    '}' -> {
                        pos++
                        return TabStop(index, null)
                    }

                    ':' -> {
                        pos++
                        val children = parseUntil(closing = true)
                        if (pos >= src.length) return reset(start)
                        pos++ // }
                        return TabStop(index, children)
                    }

                    '|' -> {
                        pos++
                        val options = readChoices() ?: return reset(start)
                        return TabStop(index, null, options)
                    }

                    else -> return reset(start)
                }
            }
            if (src[pos].isNameStart()) {
                val name = readName()
                if (pos >= src.length) return reset(start)
                when (src[pos]) {
                    '}' -> {
                        pos++
                        return Variable(name, null)
                    }

                    ':' -> {
                        pos++
                        val children = parseUntil(closing = true)
                        if (pos >= src.length) return reset(start)
                        pos++
                        return Variable(name, children)
                    }

                    else -> return reset(start)
                }
            }
            return reset(start)
        }

        /** `one,two|}` after `${1|`: the options, with `\,` `\|` `\\` escapes; null when unterminated. */
        private fun readChoices(): List<String>? {
            val options = ArrayList<String>()
            val current = StringBuilder()
            while (pos < src.length) {
                val c = src[pos]
                when {
                    c == '\\' && pos + 1 < src.length && src[pos + 1] in ",|\\\$}" -> {
                        current.append(src[pos + 1])
                        pos += 2
                    }

                    c == ',' -> {
                        options += current.toString()
                        current.clear()
                        pos++
                    }

                    c == '|' && pos + 1 < src.length && src[pos + 1] == '}' -> {
                        options += current.toString()
                        pos += 2
                        return options
                    }

                    else -> {
                        current.append(c)
                        pos++
                    }
                }
            }
            return null
        }

        private fun reset(start: Int): Node? {
            pos = start
            return null
        }

        private fun readInt(): Int {
            val begin = pos
            while (pos < src.length && src[pos].isDigit()) pos++
            return src.substring(begin, pos).toIntOrNull() ?: 0
        }

        private fun readName(): String {
            val begin = pos
            while (pos < src.length && (src[pos].isNameStart() || src[pos].isDigit())) pos++
            return src.substring(begin, pos)
        }

        private fun Char.isNameStart(): Boolean = this == '_' || this in 'a'..'z' || this in 'A'..'Z'
    }
}
