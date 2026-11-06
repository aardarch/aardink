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
package com.aardarch.aardink.ui

import com.aardarch.aardink.core.UrlDetector

/** Documentation longer than this is cut, with an ellipsis: it comes from servers and schemas, and is parsed while composing. */
internal const val MARKDOWN_MAX_LENGTH = 10_000

/** How deep lists nest. A marker further in stays text, so no input can recurse without end. */
private const val MAX_LIST_DEPTH = 8

/** How deep emphasis nests. Deeper marks are dropped, as drawing recurses and a server chooses the input. */
private const val MAX_INLINE_DEPTH = 16

/** The most parentheses a link's target may leave open, as CommonMark's reference parser. */
private const val MAX_TARGET_PARENS = 32

/** How far past its `&` an entity's `;` may be, so a run of `&` does not search the text from each one. */
private const val MAX_ENTITY_LENGTH = 10

/** A block of documentation: what [parseMarkdown] finds, and [MarkdownDoc] draws. */
internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: List<MarkdownInline>) : MarkdownBlock

    data class Paragraph(val text: List<MarkdownInline>) : MarkdownBlock

    /** A fenced or indented code block; [language] is the first word of a fence's info string. */
    data class Code(val language: String?, val code: String) : MarkdownBlock

    /** A list. [start] is the first item's number; each item is its own blocks. */
    data class Items(val ordered: Boolean, val start: Int, val items: List<List<MarkdownBlock>>) : MarkdownBlock

    /** A thematic break, which language servers use to set a signature off from its documentation. */
    data object Rule : MarkdownBlock
}

/** A run of text inside a [MarkdownBlock]. */
internal sealed interface MarkdownInline {
    data class Plain(val text: String) : MarkdownInline

    data class Code(val text: String) : MarkdownInline

    data class Emphasis(val children: List<MarkdownInline>) : MarkdownInline

    data class Strong(val children: List<MarkdownInline>) : MarkdownInline

    /** A link, which only ever opens through the host's `onOpenLink`. */
    data class Link(val target: String, val children: List<MarkdownInline>) : MarkdownInline

    /** A hard line break. A soft one (a plain newline) is a space, as in Monaco's hover. */
    data object Break : MarkdownInline
}

/**
 * The CommonMark subset hover and signature help show: ATX and setext headings, fenced and indented
 * code, nested lists, thematic breaks, emphasis, inline code, links and backslash escapes. Raw HTML
 * stays literal text, an image shows its alt text and fetches nothing, and block quotes, tables and
 * reference links stay as written. Input past [MARKDOWN_MAX_LENGTH] is cut before parsing, so what
 * this costs has a bound whatever a server sends.
 */
internal fun parseMarkdown(text: String): List<MarkdownBlock> {
    val capped = text.length > MARKDOWN_MAX_LENGTH
    val source = if (capped) text.substring(0, cutPoint(text)) else text
    val blocks = BlockReader(source.lines().map(::expandLeadingTabs), depth = 0).read()
    return if (capped) blocks + MarkdownBlock.Paragraph(listOf(MarkdownInline.Plain("…"))) else blocks
}

/** Where to cut [text]: at a line end when one is near the limit, and never inside a surrogate pair. */
private fun cutPoint(text: String): Int {
    val line = text.lastIndexOf('\n', MARKDOWN_MAX_LENGTH)
    if (line > MARKDOWN_MAX_LENGTH / 2) return line
    return if (text[MARKDOWN_MAX_LENGTH - 1].isHighSurrogate()) MARKDOWN_MAX_LENGTH - 1 else MARKDOWN_MAX_LENGTH
}

private fun expandLeadingTabs(line: String): String {
    if ('\t' !in line) return line
    val out = StringBuilder()
    var at = 0
    while (at < line.length && (line[at] == ' ' || line[at] == '\t')) {
        if (line[at] == ' ') out.append(' ') else repeat(4 - out.length % 4) { out.append(' ') }
        at++
    }
    return out.append(line, at, line.length).toString()
}

private fun indentOf(line: String): Int {
    var at = 0
    while (at < line.length && line[at] == ' ') at++
    return at
}

// ── Blocks ───────────────────────────────────────────────────────────────────

private class Fence(val marker: Char, val length: Int, val indent: Int, val info: String)

private class ListMarker(val ordered: Boolean, val delimiter: Char, val start: Int, val contentIndent: Int, val empty: Boolean) {
    fun continues(other: ListMarker) = ordered == other.ordered && delimiter == other.delimiter
}

private fun fenceAt(line: String): Fence? {
    val indent = indentOf(line)
    val marker = line.getOrNull(indent)
    if (indent > 3 || (marker != '`' && marker != '~')) return null
    var end = indent
    while (end < line.length && line[end] == marker) end++
    val info = line.substring(end).trim()
    if (end - indent < 3 || (marker == '`' && '`' in info)) return null
    return Fence(marker, end - indent, indent, info)
}

/** The level and text of an ATX heading, or null. `#define` is not one: the marks need a space after them. */
private fun headingAt(line: String): Pair<Int, String>? {
    val indent = indentOf(line)
    if (indent > 3) return null
    var end = indent
    while (end < line.length && line[end] == '#') end++
    val level = end - indent
    if (level !in 1..6 || (end < line.length && line[end] != ' ')) return null
    val body = line.substring(end).trim()
    val closed = body.trimEnd('#')
    return level to if (closed.isEmpty() || closed.last() == ' ') closed.trimEnd() else body
}

private fun isRule(line: String): Boolean {
    if (indentOf(line) > 3) return false
    val marks = line.filterNot { it == ' ' }
    return marks.length >= 3 && marks[0] in "-*_" && marks.all { it == marks[0] }
}

/** 1 or 2 for the line under a setext heading, or 0. */
private fun setextLevel(line: String): Int {
    if (indentOf(line) > 3) return 0
    val marks = line.trim()
    return when {
        marks.isEmpty() -> 0
        marks.all { it == '=' } -> 1
        marks.all { it == '-' } -> 2
        else -> 0
    }
}

private fun markerAt(line: String): ListMarker? {
    val indent = indentOf(line)
    val first = line.getOrNull(indent)
    if (indent > 3 || first == null) return null
    var end = indent
    val ordered = first in '0'..'9'
    if (ordered) {
        while (end < line.length && line[end] in '0'..'9' && end - indent < 9) end++
    } else if (first !in "-+*") {
        return null
    }
    val start = if (ordered) line.substring(indent, end).toInt() else 1
    val delimiter = line.getOrNull(end) ?: return null
    if (ordered && delimiter != '.' && delimiter != ')') return null
    end++
    if (line.substring(end).isBlank()) return ListMarker(ordered, delimiter, start, end + 1, empty = true)
    if (line[end] != ' ') return null
    val spaces = indentOf(line.substring(end))
    return ListMarker(ordered, delimiter, start, end + if (spaces > 4) 1 else spaces, empty = false)
}

private class BlockReader(private val lines: List<String>, private val depth: Int) {
    private var at = 0

    fun read(): List<MarkdownBlock> {
        val blocks = ArrayList<MarkdownBlock>()
        while (at < lines.size) {
            val line = lines[at]
            val fence = fenceAt(line)
            val heading = headingAt(line)
            val marker = listMarkerAt(line)
            when {
                line.isBlank() -> at++

                indentOf(line) >= 4 -> blocks += indentedCode()

                fence != null -> blocks += fencedCode(fence)

                heading != null -> {
                    blocks += MarkdownBlock.Heading(heading.first, parseInlines(heading.second))
                    at++
                }

                isRule(line) -> {
                    blocks += MarkdownBlock.Rule
                    at++
                }

                marker != null -> blocks += list(marker)

                else -> blocks += paragraph()
            }
        }
        return blocks
    }

    private fun listMarkerAt(line: String): ListMarker? = if (depth >= MAX_LIST_DEPTH || isRule(line)) null else markerAt(line)

    private fun indentedCode(): MarkdownBlock {
        val code = ArrayList<String>()
        while (at < lines.size && (lines[at].isBlank() || indentOf(lines[at]) >= 4)) code += lines[at++].drop(4)
        return MarkdownBlock.Code(null, code.dropLastWhile { it.isBlank() }.joinToString("\n"))
    }

    private fun fencedCode(fence: Fence): MarkdownBlock {
        at++
        val code = ArrayList<String>()
        while (at < lines.size) {
            val line = lines[at++]
            val close = fenceAt(line)
            if (close != null && close.marker == fence.marker && close.length >= fence.length && close.info.isEmpty()) break
            code += line.drop(minOf(fence.indent, indentOf(line)))
        }
        val language = fence.info.substringBefore(' ').takeIf { it.isNotEmpty() }
        return MarkdownBlock.Code(language, code.joinToString("\n"))
    }

    private fun list(first: ListMarker): MarkdownBlock {
        val items = ArrayList<List<MarkdownBlock>>()
        var marker: ListMarker? = first
        while (marker != null) {
            items += BlockReader(itemLines(marker), depth + 1).read()
            var next = at
            while (next < lines.size && lines[next].isBlank()) next++
            marker = lines.getOrNull(next)?.let(::listMarkerAt)?.takeIf(first::continues)
            if (marker != null) at = next
        }
        return MarkdownBlock.Items(first.ordered, first.start, items)
    }

    /** The current item's lines, its indentation taken off, leaving [at] on the line after them. */
    private fun itemLines(marker: ListMarker): List<String> {
        val indent = marker.contentIndent
        val body = arrayListOf(lines[at++].drop(indent))
        while (at < lines.size) {
            val line = lines[at]
            if (line.isBlank()) {
                // A blank line belongs to the item only when an indented line comes after it.
                var next = at
                while (next < lines.size && lines[next].isBlank()) next++
                if (next == lines.size || indentOf(lines[next]) < indent) break
                repeat(next - at) { body += "" }
                at = next
            } else if (indentOf(line) >= indent) {
                body += line.drop(indent)
                at++
            } else if (body.last().isNotBlank() && !startsBlock(line)) {
                // A lazy continuation: the paragraph carries on without its indentation.
                body += line.trimStart()
                at++
            } else {
                break
            }
        }
        return body
    }

    private fun startsBlock(line: String) = fenceAt(line) != null || headingAt(line) != null || isRule(line) || listMarkerAt(line) != null

    /** Whether [line] ends a paragraph. A list only does so with something in it, and an ordered one only from 1. */
    private fun interruptsParagraph(line: String): Boolean {
        if (fenceAt(line) != null || headingAt(line) != null || isRule(line)) return true
        val marker = listMarkerAt(line) ?: return false
        return !marker.empty && (!marker.ordered || marker.start == 1)
    }

    private fun paragraph(): MarkdownBlock {
        val text = StringBuilder(lines[at++].trimStart())
        while (at < lines.size) {
            val line = lines[at]
            if (line.isBlank()) break
            val level = setextLevel(line)
            if (level != 0) {
                at++
                return MarkdownBlock.Heading(level, parseInlines(text.toString().trimEnd()))
            }
            if (interruptsParagraph(line)) break
            text.append('\n').append(line.trimStart())
            at++
        }
        return MarkdownBlock.Paragraph(parseInlines(text.toString().trimEnd()))
    }
}

// ── Inlines ──────────────────────────────────────────────────────────────────

/** What the inline parser holds while it reads: finished runs, and marks that may yet pair up. */
private sealed interface Piece

private class Finished(val node: MarkdownInline) : Piece

/** A run of `*` or `_`, by CommonMark's flanking rules. */
private class DelimiterRun(val char: Char, var count: Int, val canOpen: Boolean, val canClose: Boolean) : Piece {
    val original = count
}

private class OpenBracket(val image: Boolean) : Piece {
    var active = true
}

private class LinkTail(val target: String, val end: Int)

private const val ASCII_PUNCTUATION = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

/** What a bare URL does not end on in Markdown. `_` stays: `https://a.dev/_x_` is a real path. */
private const val URL_TRAILING_MARKS = "*.,;:!?"

private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

/** Schemes that run instead of open, whatever a host does with a link: such a link shows as its text. */
private val SCRIPT_SCHEMES = listOf("javascript:", "vbscript:", "data:")

private fun isOpenable(target: String): Boolean {
    val compact = target.filterNot { it <= ' ' }.lowercase()
    return compact.isNotEmpty() && SCRIPT_SCHEMES.none(compact::startsWith)
}

private fun Char.isMarkdownPunctuation() = !isLetterOrDigit() && !isWhitespace()

/** The text of the entity at [start] and the index after it, or null. Only the common ones: others stay as written. */
private fun entityAt(text: String, start: Int): Pair<String, Int>? {
    var end = start + 1
    while (end < text.length && end - start <= MAX_ENTITY_LENGTH && text[end] != ';') end++
    if (text.getOrNull(end) != ';' || end - start > MAX_ENTITY_LENGTH) return null
    val name = text.substring(start + 1, end)
    val decoded = if (name.startsWith("#")) {
        val code = if (name.getOrNull(1)?.lowercaseChar() == 'x') name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
        code?.takeIf { it in 1..0xFFFF && it.toChar().category != CharCategory.SURROGATE }?.toChar()?.toString()
    } else {
        ENTITIES[name]
    }
    return decoded?.let { it to end + 1 }
}

/** [raw] with its backslash escapes and entities resolved, for a link's target. */
private fun unescaped(raw: String): String {
    val out = StringBuilder()
    var at = 0
    while (at < raw.length) {
        val c = raw[at]
        val entity = if (c == '&') entityAt(raw, at) else null
        when {
            c == '\\' && raw.getOrNull(at + 1)?.let { it in ASCII_PUNCTUATION } == true -> {
                out.append(raw[at + 1])
                at += 2
            }

            entity != null -> {
                out.append(entity.first)
                at = entity.second
            }

            else -> {
                out.append(c)
                at++
            }
        }
    }
    return out.toString()
}

internal fun parseInlines(text: String): List<MarkdownInline> = InlineParser(text).parse()

/**
 * CommonMark's inline algorithm: one pass that finishes code spans, escapes and links as it meets
 * them and keeps runs of `*` and `_` aside, then pairs those up, innermost first, so `snake_case`
 * and `2 * 3 * 4` stay as written.
 */
private class InlineParser(private val text: String) {
    private var at = 0
    private val pieces = ArrayList<Piece>()
    private val brackets = ArrayList<Int>()
    private val plain = StringBuilder()

    /** Lengths of backtick run with no closer ahead, so unmatched runs do not rescan the text each time. */
    private val noCloser = HashSet<Int>()

    fun parse(): List<MarkdownInline> {
        while (at < text.length) {
            when (text[at]) {
                '\\' -> escape()
                '`' -> codeSpan()
                '*', '_' -> delimiterRun()
                '[' -> openBracket(image = false)
                '!' -> if (text.getOrNull(at + 1) == '[') openBracket(image = true) else literal()
                ']' -> closeBracket()
                '<' -> angle()
                '&' -> entity()
                '\n' -> lineEnd()
                'h' -> bareUrl()
                else -> literal()
            }
        }
        flush()
        return resolve(pieces)
    }

    private fun literal() {
        plain.append(text[at++])
    }

    private fun flush() {
        if (plain.isEmpty()) return
        pieces += Finished(MarkdownInline.Plain(plain.toString()))
        plain.clear()
    }

    private fun finish(node: MarkdownInline) {
        flush()
        pieces += Finished(node)
    }

    private fun escape() {
        val next = text.getOrNull(at + 1)
        when {
            next == '\n' -> {
                finish(MarkdownInline.Break)
                at += 2
            }

            next != null && next in ASCII_PUNCTUATION -> {
                plain.append(next)
                at += 2
            }

            else -> literal()
        }
    }

    private fun entity() {
        val found = entityAt(text, at) ?: return literal()
        plain.append(found.first)
        at = found.second
    }

    private fun lineEnd() {
        var spaces = 0
        while (spaces < plain.length && plain[plain.length - 1 - spaces] == ' ') spaces++
        plain.setLength(plain.length - spaces)
        if (spaces >= 2) finish(MarkdownInline.Break) else plain.append(' ')
        at++
    }

    private fun bareUrl() {
        val boundary = at == 0 || !text[at - 1].isLetterOrDigit()
        var end = (if (boundary && text.startsWith("http", at)) UrlDetector.urlEnd(text, at) else null) ?: return literal()
        // A closing `**` is the emphasis around the URL, and may have punctuation inside it.
        while (text[end - 1] in URL_TRAILING_MARKS) end--
        val url = text.substring(at, end)
        finish(MarkdownInline.Link(url, listOf(MarkdownInline.Plain(url))))
        at = end
    }

    private fun codeSpan() {
        val start = at
        while (at < text.length && text[at] == '`') at++
        val length = at - start
        if (length !in noCloser) {
            var search = at
            while (true) {
                val closer = text.indexOf('`', search)
                if (closer < 0) break
                var end = closer
                while (end < text.length && text[end] == '`') end++
                if (end - closer == length) {
                    finish(MarkdownInline.Code(codeSpanText(text.substring(at, closer))))
                    at = end
                    return
                }
                search = end
            }
            noCloser += length
        }
        plain.append(text, start, at)
    }

    private fun codeSpanText(raw: String): String {
        val code = raw.replace('\n', ' ')
        val padded = code.length > 1 && code.first() == ' ' && code.last() == ' ' && code.isNotBlank()
        return if (padded) code.substring(1, code.length - 1) else code
    }

    private fun delimiterRun() {
        val char = text[at]
        val start = at
        while (at < text.length && text[at] == char) at++
        val before = text.getOrElse(start - 1) { ' ' }
        val after = text.getOrElse(at) { ' ' }
        val leftFlanking = !after.isWhitespace() &&
            (!after.isMarkdownPunctuation() || before.isWhitespace() || before.isMarkdownPunctuation())
        val rightFlanking = !before.isWhitespace() &&
            (!before.isMarkdownPunctuation() || after.isWhitespace() || after.isMarkdownPunctuation())
        val canOpen = leftFlanking && (char == '*' || !rightFlanking || before.isMarkdownPunctuation())
        val canClose = rightFlanking && (char == '*' || !leftFlanking || after.isMarkdownPunctuation())
        flush()
        pieces += DelimiterRun(char, at - start, canOpen, canClose)
    }

    private fun openBracket(image: Boolean) {
        flush()
        brackets += pieces.size
        pieces += OpenBracket(image)
        at += if (image) 2 else 1
    }

    private fun closeBracket() {
        flush()
        val index = brackets.lastOrNull()
        if (index == null) return literal()
        brackets.removeAt(brackets.lastIndex)
        val opener = pieces[index] as OpenBracket
        val tail = if (opener.active) linkTail(at + 1) else null
        if (tail == null) {
            pieces[index] = Finished(MarkdownInline.Plain(if (opener.image) "![" else "["))
            return literal()
        }
        at = tail.end
        val inside = pieces.subList(index + 1, pieces.size)
        val children = resolve(inside.toList())
        inside.clear()
        pieces.removeAt(index)
        when {
            opener.image -> pieces += Finished(MarkdownInline.Plain(plainText(children)))

            !isOpenable(tail.target) -> children.mapTo(pieces, ::Finished)

            else -> {
                pieces += Finished(MarkdownInline.Link(tail.target, finished(withoutLinks(children).map(::Finished))))
                // A link cannot hold a link: the brackets still open around this one are text.
                for (open in brackets.asReversed()) {
                    val outer = pieces[open] as OpenBracket
                    if (outer.image) continue
                    if (!outer.active) break
                    outer.active = false
                }
            }
        }
    }

    /** The `(target "title")` from [start], or null. The title is read past: nothing shows it. */
    private fun linkTail(start: Int): LinkTail? {
        if (text.getOrNull(start) != '(') return null
        var position = skipSpace(start + 1)
        val targetStart = position
        val target: String
        if (text.getOrNull(position) == '<') {
            position++
            while (true) {
                val c = text.getOrNull(position) ?: return null
                if (c == '\n' || c == '<') return null
                if (c == '>') break
                position += if (c == '\\') 2 else 1
            }
            target = text.substring(targetStart + 1, position)
            position++
        } else {
            var open = 0
            while (position < text.length) {
                val c = text[position]
                if (c <= ' ' || (c == ')' && open == 0)) break
                if (c == '(' && ++open > MAX_TARGET_PARENS) return null
                if (c == ')') open--
                position += if (c == '\\') 2 else 1
            }
            if (open != 0 || position > text.length) return null
            target = text.substring(targetStart, position)
        }
        val afterTarget = position
        position = skipSpace(position)
        if (position > afterTarget) position = skipSpace(titleEnd(position) ?: return null)
        if (text.getOrNull(position) != ')') return null
        return LinkTail(unescaped(target), position + 1)
    }

    /** The index after the title at [start]; [start] when no title starts there; null when one never ends. */
    private fun titleEnd(start: Int): Int? {
        val close = when (text.getOrNull(start)) {
            '"' -> '"'
            '\'' -> '\''
            '(' -> ')'
            else -> return start
        }
        var position = start + 1
        while (position < text.length) {
            val c = text[position]
            if (c == close) return position + 1
            if (c == '(' && close == ')') return null
            position += if (c == '\\') 2 else 1
        }
        return null
    }

    private fun skipSpace(start: Int): Int {
        var position = start
        while (position < text.length && text[position].isWhitespace()) position++
        return position
    }

    /** An autolink; else a tag or comment, kept whole so what is inside it stays literal; else a plain `<`. */
    private fun angle() {
        val end = tagEnd() ?: return literal()
        val body = text.substring(at + 1, end)
        val lower = body.lowercase()
        val autolink = (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) &&
            body.none { it <= ' ' } &&
            isOpenable(body)
        if (autolink) {
            finish(MarkdownInline.Link(body, listOf(MarkdownInline.Plain(body.removePrefix("mailto:")))))
        } else {
            plain.append(text, at, end + 1)
        }
        at = end + 1
    }

    /** The `>` of the tag opening at [at], or null: `a < b` and `List<*` are not tags. */
    private fun tagEnd(): Int? {
        val first = text.getOrNull(at + 1) ?: return null
        if (!first.isLetter() && first != '/' && first != '!' && first != '?') return null
        var position = at + 1
        while (position < text.length) {
            when (text[position]) {
                '>' -> return position
                '<' -> return null
                else -> position++
            }
        }
        return null
    }
}

/** Pairs the delimiter runs in [pieces]: each closer, left to right, with the nearest opener that fits. */
private fun resolve(pieces: List<Piece>): List<MarkdownInline> {
    val stack = ArrayList<Piece>()
    val openers = ArrayList<Int>()
    for (piece in pieces) {
        if (piece !is DelimiterRun) {
            stack += piece
            continue
        }
        while (piece.count > 0 && piece.canClose) {
            val match = openers.indexOfLast { pairs(stack[it] as DelimiterRun, piece) }
            if (match < 0) break
            val position = openers[match]
            val opener = stack[position] as DelimiterRun
            val used = if (opener.count >= 2 && piece.count >= 2) 2 else 1
            val inside = stack.subList(position + 1, stack.size)
            val children = finished(inside)
            inside.clear()
            while (openers.size > match + 1) openers.removeAt(openers.lastIndex)
            opener.count -= used
            piece.count -= used
            if (opener.count == 0) {
                stack.removeAt(position)
                openers.removeAt(match)
            }
            when {
                inlineDepth(children) >= MAX_INLINE_DEPTH -> children.mapTo(stack, ::Finished)
                used == 2 -> stack += Finished(MarkdownInline.Strong(children))
                else -> stack += Finished(MarkdownInline.Emphasis(children))
            }
        }
        if (piece.count > 0) {
            if (piece.canOpen) openers += stack.size
            stack += piece
        }
    }
    return finished(stack)
}

/** Same character, and CommonMark's rule of three for a run that can both open and close. */
private fun pairs(opener: DelimiterRun, closer: DelimiterRun): Boolean {
    if (opener.char != closer.char) return false
    val either = opener.canClose || closer.canOpen
    val sum = opener.original + closer.original
    return !(either && sum % 3 == 0 && (opener.original % 3 != 0 || closer.original % 3 != 0))
}

/** [pieces] as inlines: what never paired is text, and neighbouring text is one run. */
private fun finished(pieces: List<Piece>): List<MarkdownInline> {
    val out = ArrayList<MarkdownInline>()
    val text = StringBuilder()
    fun flush() {
        if (text.isEmpty()) return
        out += MarkdownInline.Plain(text.toString())
        text.clear()
    }
    for (piece in pieces) {
        when (piece) {
            is DelimiterRun -> repeat(piece.count) { text.append(piece.char) }

            is OpenBracket -> text.append(if (piece.image) "![" else "[")

            is Finished -> {
                val node = piece.node
                if (node is MarkdownInline.Plain) {
                    text.append(node.text)
                } else {
                    flush()
                    out += node
                }
            }
        }
    }
    flush()
    return out
}

private fun inlineDepth(nodes: List<MarkdownInline>): Int = nodes.maxOfOrNull { node ->
    when (node) {
        is MarkdownInline.Emphasis -> 1 + inlineDepth(node.children)
        is MarkdownInline.Strong -> 1 + inlineDepth(node.children)
        is MarkdownInline.Link -> 1 + inlineDepth(node.children)
        else -> 0
    }
} ?: 0

private fun plainText(nodes: List<MarkdownInline>): String = nodes.joinToString("") { node ->
    when (node) {
        is MarkdownInline.Plain -> node.text
        is MarkdownInline.Code -> node.text
        is MarkdownInline.Emphasis -> plainText(node.children)
        is MarkdownInline.Strong -> plainText(node.children)
        is MarkdownInline.Link -> plainText(node.children)
        MarkdownInline.Break -> " "
    }
}

/** [nodes] with any link inside them reduced to its text. */
private fun withoutLinks(nodes: List<MarkdownInline>): List<MarkdownInline> = nodes.flatMap { node ->
    when (node) {
        is MarkdownInline.Link -> withoutLinks(node.children)
        is MarkdownInline.Emphasis -> listOf(MarkdownInline.Emphasis(withoutLinks(node.children)))
        is MarkdownInline.Strong -> listOf(MarkdownInline.Strong(withoutLinks(node.children)))
        else -> listOf(node)
    }
}
