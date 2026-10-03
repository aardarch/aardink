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
package com.aardarch.aardink.languages.internal.xml

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.languages.internal.BaseLanguageService
import com.aardarch.aardink.languages.internal.diagnosticsPacer

/**
 * Structural XML / HTML validator, auto-close provider, completion provider, and formatter.
 *
 * Diagnostics surfaced:
 *   - mismatched closing tag (`</a>` where `<b>` was open)
 *   - unclosed opening tag at end of document
 *   - stray closing tag with no matching open
 *   - unterminated comment / CDATA / processing instruction
 *   - unterminated tag (no `>` before EOF)
 *   - duplicate attribute names on a single element
 *   - unescaped `&` in text nodes
 */
abstract class TagValidator(private val htmlMode: Boolean, private val sourceLabel: String) : BaseLanguageService() {

    override val triggerCharacters: Set<Char> = setOf('<', '/', ' ', ':', '"', '=')

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        val text = document.text
        if (text.isBlank()) return emptyList()
        val diags = mutableListOf<Diagnostic>()
        val stack = ArrayDeque<OpenTag>()
        var i = 0
        val n = text.length
        val pacer = diagnosticsPacer()

        while (i < n) {
            pacer?.onProgress(i)
            val c = text[i]

            // Check for unescaped '&' outside tags/comments/CDATA
            if (c == '&') {
                val entityMatch = ENTITY_REGEX.matchAt(text, i)
                if (entityMatch == null) {
                    val (line, _) = document.offsetToLineCol(i)
                    diags.add(
                        Diagnostic(
                            range = i..(i + 1),
                            lineNumber = line,
                            message = "Unescaped '&' character; use '&amp;' instead",
                            severity = DiagnosticSeverity.Warning,
                            source = sourceLabel,
                        ),
                    )
                } else {
                    i += entityMatch.value.length
                    continue
                }
            }

            if (c != '<') {
                i++
                continue
            }

            // <!-- comment -->
            if (i + 3 < n && text[i + 1] == '!' && text[i + 2] == '-' && text[i + 3] == '-') {
                val end = text.indexOf("-->", i + 4)
                if (end < 0) {
                    diags.add(error(document, i, n, "Unterminated comment"))
                    return diags
                }
                i = end + 3
                continue
            }
            // <![CDATA[ … ]]>
            if (i + 8 < n && text.regionMatches(i + 1, "![CDATA[", 0, 8)) {
                val end = text.indexOf("]]>", i + 9)
                if (end < 0) {
                    diags.add(error(document, i, n, "Unterminated CDATA section"))
                    return diags
                }
                i = end + 3
                continue
            }
            // <!DOCTYPE …>, <! …>
            if (i + 1 < n && text[i + 1] == '!') {
                val end = text.indexOf('>', i + 2)
                if (end < 0) {
                    diags.add(error(document, i, n, "Unterminated declaration"))
                    return diags
                }
                i = end + 1
                continue
            }
            // <? … ?>
            if (i + 1 < n && text[i + 1] == '?') {
                val end = text.indexOf("?>", i + 2)
                if (end < 0) {
                    diags.add(error(document, i, n, "Unterminated processing instruction"))
                    return diags
                }
                i = end + 2
                continue
            }
            val tagEnd = findTagEnd(text, i + 1)
            if (tagEnd == null) {
                diags.add(error(document, i, n, "Unterminated tag"))
                return diags
            }

            // Closing tag </name>
            if (i + 1 < n && text[i + 1] == '/') {
                val rawClose = text.substring(i + 2, tagEnd).trim()
                val name = foldTagCase(rawClose.takeWhile { !it.isWhitespace() })
                if (name.isEmpty()) {
                    diags.add(error(document, i, tagEnd + 1, "Empty closing tag"))
                } else {
                    val matched = stack.lastOrNull()
                    if (matched != null && matched.name == name) {
                        stack.removeLast()
                    } else {
                        diags.add(error(document, i, tagEnd + 1, "Unmatched closing tag </$name>"))
                    }
                }
                i = tagEnd + 1
                continue
            }

            // Opening tag <name …>
            val raw = text.substring(i + 1, tagEnd)
            val selfClosing = raw.trimEnd().endsWith('/')
            val rawNameEnd = raw.indexOfFirst { it.isWhitespace() || it == '/' }
            val name = foldTagCase(if (rawNameEnd < 0) raw else raw.substring(0, rawNameEnd))
            if (name.isEmpty()) {
                diags.add(error(document, i, tagEnd + 1, "Empty tag"))
                i = tagEnd + 1
                continue
            }

            // Check duplicate attributes in raw, and — in XML, where a bare '&' is as invalid in a
            // value as in text — entity references inside attribute values, which the top-level
            // '&' check never sees because the loop jumps past the whole tag. HTML permits an
            // ampersand that does not look like a reference (`href="?a=1&b=2"`), so it is left be.
            checkDuplicateAttributes(document, i + 1, raw, diags)
            if (!htmlMode) checkAttributeValueEntities(document, i + 1, raw, diags)

            val isVoid = htmlMode && name in HTML_VOID_ELEMENTS
            if (!selfClosing && !isVoid) {
                stack.addLast(OpenTag(name, i, tagEnd + 1))
            }
            i = tagEnd + 1
            // The contents of an HTML raw-text element are script or style source, not markup: a
            // '&&' there is an operator, and no '<' in it opens a tag. Skip to its closing tag.
            if (htmlMode && !selfClosing && name in HTML_RAW_TEXT_ELEMENTS) {
                val close = text.indexOf("</$name", i, ignoreCase = true)
                if (close >= 0) i = close
            }
        }

        // Anything still on the stack is unclosed
        for (open in stack) {
            diags.add(error(document, open.start, open.end, "Unclosed tag <${open.name}>"))
        }
        return diags
    }

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? {
        val text = document.text
        if (offset < 0 || offset >= text.length) return null

        if (charTyped == '"') return "\""
        if (charTyped == '\'') return "'"

        if (charTyped == '>') {
            val tagStart = text.lastIndexOf('<', offset - 1)
            // A '>' typed in text content — `<p>1 > 0` — closes nothing: the tag that the last
            // '<' opened was already closed by an earlier '>'.
            if (tagStart >= 0 && text.indexOf('>', tagStart) == offset) {
                val tagText = text.substring(tagStart, offset + 1)
                if (!tagText.startsWith("<!--") &&
                    !tagText.startsWith("<?") &&
                    !tagText.startsWith("<!") &&
                    !tagText.startsWith("</") &&
                    !tagText.endsWith("/>")
                ) {
                    val rawName = tagText.substring(1, tagText.length - 1).trim()
                    val nameEnd = rawName.indexOfFirst { it.isWhitespace() || it == '/' }
                    val name = if (nameEnd < 0) rawName else rawName.substring(0, nameEnd)
                    if (name.isNotEmpty()) {
                        val isVoid = htmlMode && name.lowercase() in HTML_VOID_ELEMENTS
                        if (!isVoid) {
                            return "</$name>"
                        }
                    }
                }
            }
        }

        if (charTyped == '/') {
            if (offset > 0 && text[offset - 1] == '<') {
                val unclosed = findLastUnclosedTag(text, offset - 1)
                if (unclosed != null) {
                    return "$unclosed>"
                }
            }
        }

        return null
    }

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val text = document.text
        val clampedOffset = cursorOffset.coerceIn(0, text.length)
        val textBefore = text.take(clampedOffset)
        // A comment or CDATA section holds text, not markup: nothing there is an element or an
        // attribute, and accepting one would write markup into it.
        if (isInsideCommentOrCData(textBefore)) return emptyList()

        val lastLt = textBefore.lastIndexOf('<')
        val lastGt = textBefore.lastIndexOf('>')

        if (lastLt > lastGt) {
            val tagContent = textBefore.substring(lastLt + 1)
            // A declaration (`<!DOCTYPE …`) or processing instruction (`<?xml …`) has no elements
            // or attributes of the document's to offer.
            if (tagContent.startsWith("!") || tagContent.startsWith("?")) return emptyList()

            // 1. Attribute value completion after '=', while the value is still open. A finished
            // value - `id="x"` - puts the cursor back in attribute-name territory, so the quote
            // has to still be unclosed for this to be a value context.
            val lastEq = textBefore.lastIndexOf('=')
            if (lastEq > lastLt) {
                val afterEq = textBefore.substring(lastEq + 1).trimStart()
                if (afterEq.isEmpty() || isUnclosedQuotedValue(afterEq)) {
                    // The editor's token scan would stop at the '@' in "@string/", so name the
                    // range: everything typed after the opening quote.
                    val valueStart = clampedOffset - afterEq.length + (if (afterEq.isEmpty()) 0 else 1)
                    return COMMON_ATTR_VALUES.map { value ->
                        CompletionItem(
                            label = value,
                            kind = CompletionKind.Value,
                            insertText = if (afterEq.isEmpty()) "\"$value\"" else value,
                            documentation = "Attribute value $value",
                            replaceRange = valueStart until clampedOffset,
                        )
                    }
                }
            }

            // 2. Attribute name completion inside tag (after space)
            if (tagContent.contains(' ')) {
                // ':' and '.' are token boundaries to the editor, so an "android:" already typed
                // would be kept and duplicated — name the range covering the partial name instead.
                val nameStart = startOfAttributeName(text, clampedOffset)
                return COMMON_XML_ATTRIBUTES.map { attr ->
                    CompletionItem(
                        label = attr,
                        kind = CompletionKind.Attribute,
                        // The caret between the quotes, as in Monaco.
                        insertText = "$attr=\"\$1\"",
                        documentation = "Attribute $attr",
                        replaceRange = nameStart until clampedOffset,
                        isSnippet = true,
                    )
                }
            }

            // 3. Tag name completion after '<'
            if (!tagContent.startsWith("/") && !tagContent.startsWith("!") && !tagContent.startsWith("?")) {
                return COMMON_XML_ELEMENTS.map { elem ->
                    CompletionItem(
                        label = elem,
                        kind = CompletionKind.Element,
                        insertText = "$elem>",
                        documentation = "XML element <$elem>",
                    )
                }
            }
        }

        return emptyList()
    }

    override fun smartIndent(document: CodeDocument, lineIndex: Int): Int {
        if (lineIndex <= 0) return 0
        val prevLine = document.lineText(lineIndex - 1)
        val prevIndent = prevLine.takeWhile { it.isWhitespace() }.length
        val trimmedPrev = prevLine.trim()
        val currLine = document.lineText(lineIndex).trim()

        val isClosingTag = currLine.startsWith("</")
        val prevIsOpenTag = trimmedPrev.startsWith("<") &&
            !trimmedPrev.startsWith("</") &&
            !trimmedPrev.startsWith("<!--") &&
            !trimmedPrev.startsWith("<?") &&
            !trimmedPrev.startsWith("<!") &&
            !trimmedPrev.endsWith("/>") &&
            !trimmedPrev.contains("</")

        var indent = prevIndent
        if (prevIsOpenTag) indent += 4
        if (isClosingTag) indent = (indent - 4).coerceAtLeast(0)
        return indent
    }

    /**
     * Re-indents markup lines and leaves content alone.
     *
     * A line that starts a tag and is markup from end to end is re-indented to its depth. A tag
     * spread over several lines is re-indented as a block: its first line moves to its depth and
     * the lines continuing it move by the same amount, keeping their alignment, unless they start
     * inside an attribute value. Whitespace in a text node is content — no schema here says
     * otherwise — and so is the inside of a comment or a CDATA section, so those lines are emitted
     * verbatim. Every line's tags still count toward the depth of the lines after it, whether the
     * line was re-indented or not.
     */
    override suspend fun format(document: CodeDocument): String {
        val text = document.text
        if (text.isBlank()) return text

        val lines = text.lines()
        val states = lineStates(lines)
        val result = mutableListOf<String>()
        // How far the first line of the multi-line tag being continued moved.
        var shift = 0

        for ((index, line) in lines.withIndex()) {
            val state = states[index]
            if (!state.continuesTag) shift = 0
            val indent = " ".repeat(state.indentDepth * 4)
            when {
                state.isStructural -> result.add(if (line.isBlank()) "" else indent + line.trim())

                state.opensTag -> {
                    val content = line.trimStart()
                    shift = indent.length - (line.length - content.length)
                    result.add(indent + content)
                }

                state.continuesTag && !state.startsInValue -> result.add(shifted(line, shift))

                else -> result.add(line)
            }
        }

        return result.joinToString("\n")
    }

    /** [line] moved right by [shift] columns, or left by as much of [shift] as its indent allows. */
    private fun shifted(line: String, shift: Int): String = when {
        line.isBlank() -> ""
        shift >= 0 -> " ".repeat(shift) + line
        else -> line.drop(minOf(-shift, line.length - line.trimStart().length))
    }

    /**
     * @param depth Elements open when the line starts.
     * @param leadingCloses Closing tags the line ends before its first opening tag; they pull the
     *   line itself left, which is what puts `</a>` under its opener.
     * @param isStructural The line is markup the formatter owns: blank, or opening with `<` and
     *   closing its last tag on the same line, with nothing but markup in between. Everything else
     *   — text nodes, mixed content, and continuation lines of a comment, CDATA section or
     *   multi-line tag — is content and must survive verbatim.
     * @param opensTag The line starts with markup and ends inside a tag that continues on the next.
     * @param continuesTag The line starts inside a tag begun on an earlier line.
     * @param startsInValue The line starts inside a quoted attribute value, whose whitespace is
     *   content.
     */
    private data class XmlLineState(
        val depth: Int,
        val leadingCloses: Int,
        val isStructural: Boolean,
        val opensTag: Boolean,
        val continuesTag: Boolean,
        val startsInValue: Boolean,
    ) {
        val indentDepth: Int get() = (depth - leadingCloses).coerceAtLeast(0)
    }

    private enum class TagKind { Open, Close, Other }

    /**
     * Classifies every line in one pass, carrying comment / CDATA / tag / attribute-value state and
     * the element depth across lines, so a tag counts toward the depth wherever it ends.
     *
     * Self-closing tags, void HTML elements, declarations, processing instructions and comments
     * count for nothing. Attribute values are skipped, so a `<` or `>` inside one is not markup.
     */
    private fun lineStates(lines: List<String>): List<XmlLineState> {
        val states = ArrayList<XmlLineState>(lines.size)
        var depth = 0
        var inComment = false
        var inCdata = false
        var inTag = false
        var quote: Char? = null
        var tagKind = TagKind.Other
        var tagName = ""
        // The last non-blank character inside the current tag; `/` there makes it self-closing.
        var lastInTag = ' '

        for (line in lines) {
            val startDepth = depth
            val startedInTag = inTag
            val startedInValue = quote != null
            val startedMidConstruct = inComment || inCdata || inTag
            var leadingCloses = 0
            var seenOpener = false
            var i = 0

            while (i < line.length) {
                when {
                    inComment -> {
                        val close = line.indexOf(COMMENT_CLOSE, i)
                        if (close < 0) {
                            i = line.length
                        } else {
                            inComment = false
                            i = close + COMMENT_CLOSE.length
                        }
                    }

                    inCdata -> {
                        val close = line.indexOf(CDATA_CLOSE, i)
                        if (close < 0) {
                            i = line.length
                        } else {
                            inCdata = false
                            i = close + CDATA_CLOSE.length
                        }
                    }

                    quote != null -> {
                        val close = line.indexOf(quote, i)
                        if (close < 0) {
                            i = line.length
                        } else {
                            quote = null
                            lastInTag = ' '
                            i = close + 1
                        }
                    }

                    inTag -> {
                        val c = line[i]
                        when {
                            c == '"' || c == '\'' -> quote = c

                            c == '>' -> {
                                inTag = false
                                when (tagKind) {
                                    TagKind.Close -> {
                                        if (!seenOpener) leadingCloses++
                                        depth = (depth - 1).coerceAtLeast(0)
                                    }

                                    TagKind.Open -> if (lastInTag != '/' && !isVoidElement(tagName)) depth++

                                    TagKind.Other -> Unit
                                }
                            }

                            !c.isWhitespace() -> lastInTag = c
                        }
                        i++
                    }

                    line.startsWith(COMMENT_OPEN, i) -> {
                        inComment = true
                        i += COMMENT_OPEN.length
                    }

                    line.startsWith(CDATA_OPEN, i) -> {
                        inCdata = true
                        i += CDATA_OPEN.length
                    }

                    line[i] == '<' -> {
                        inTag = true
                        lastInTag = ' '
                        tagKind = when (line.getOrNull(i + 1)) {
                            '/' -> TagKind.Close
                            '!', '?' -> TagKind.Other
                            else -> TagKind.Open
                        }
                        if (tagKind == TagKind.Open) {
                            seenOpener = true
                            tagName = tagNameOf(line.substring(i + 1))
                        }
                        i++
                    }

                    else -> i++
                }
            }

            // Whole-line markup only: opens with a tag and closes one, with every construct it
            // started finished by the end of the line. Text before the first `<` or after the last
            // `>` is a text node, and its surrounding whitespace belongs to the document.
            val trimmed = line.trim()
            val wholeLineMarkup = trimmed.isEmpty() || (trimmed.startsWith("<") && trimmed.endsWith(">"))
            val endsMidConstruct = inComment || inCdata || inTag
            states.add(
                XmlLineState(
                    depth = startDepth,
                    leadingCloses = leadingCloses,
                    isStructural = !startedMidConstruct && !endsMidConstruct && wholeLineMarkup,
                    opensTag = !startedMidConstruct && inTag && trimmed.startsWith("<"),
                    continuesTag = startedInTag,
                    startsInValue = startedInValue,
                ),
            )
        }
        return states
    }

    /** Element name at the start of [tagContent] (the text just inside `<`), without attributes. */
    private fun tagNameOf(tagContent: String): String = tagContent.trimStart().takeWhile { !it.isWhitespace() && it != '/' && it != '>' }

    /**
     * Whether [afterEq] is a quoted attribute value the cursor is still inside.
     *
     * True for `"` and `"partial`, false for `"done"` — after a closed value the cursor has moved
     * on to the next attribute name, and offering values there hides name completion entirely.
     */
    private fun isUnclosedQuotedValue(afterEq: String): Boolean {
        val quote = afterEq.firstOrNull() ?: return false
        if (quote != '"' && quote != '\'') return false
        return afterEq.indexOf(quote, startIndex = 1) < 0
    }

    /**
     * Whether the end of [textBefore] lies inside a comment or a CDATA section that has not ended
     * yet. They are followed in order, each through its own terminator, so a `<!--` inside a
     * CDATA section (or a `<![CDATA[` inside a comment) is only text.
     */
    private fun isInsideCommentOrCData(textBefore: String): Boolean {
        var i = 0
        // The next of each opener, searched again only once the scan has passed it.
        var comment = textBefore.indexOf(COMMENT_OPEN)
        var cdata = textBefore.indexOf(CDATA_OPEN)
        while (true) {
            if (comment in 0 until i) comment = textBefore.indexOf(COMMENT_OPEN, i)
            if (cdata in 0 until i) cdata = textBefore.indexOf(CDATA_OPEN, i)
            if (comment < 0 && cdata < 0) return false
            val isComment = cdata < 0 || comment in 0 until cdata
            val start = if (isComment) comment else cdata
            val open = if (isComment) COMMENT_OPEN else CDATA_OPEN
            val close = if (isComment) COMMENT_CLOSE else CDATA_CLOSE
            val end = textBefore.indexOf(close, start + open.length)
            if (end < 0) return true
            i = end + close.length
        }
    }

    /** Start of the attribute name being typed at [offset] — [offset] itself when none is. */
    private fun startOfAttributeName(text: String, offset: Int): Int {
        var start = offset
        while (start > 0 && isNameChar(text[start - 1])) start--
        return start
    }

    /** XML element names are case-sensitive (`<Foo>` needs `</Foo>`); only HTML folds case. */
    private fun foldTagCase(name: String): String = if (htmlMode) name.lowercase() else name

    /** Whether [name] is an HTML element that never has children — only meaningful in HTML mode. */
    private fun isVoidElement(name: String): Boolean = htmlMode && name.lowercase() in HTML_VOID_ELEMENTS

    private fun findLastUnclosedTag(text: String, beforeOffset: Int): String? {
        val stack = ArrayDeque<String>()
        var i = 0
        while (i < beforeOffset) {
            val c = text[i]
            if (c != '<') {
                i++
                continue
            }
            if (i + 1 < beforeOffset && text[i + 1] == '/') {
                val end = text.indexOf('>', i)
                if (end in (i + 2)..<beforeOffset) {
                    val closeName = text.substring(i + 2, end).trim().takeWhile { !it.isWhitespace() }
                    if (stack.lastOrNull()?.equals(closeName, ignoreCase = htmlMode) == true) {
                        stack.removeLast()
                    }
                    i = end + 1
                    continue
                }
            }
            val end = text.indexOf('>', i)
            if (end in (i + 1)..<beforeOffset) {
                val raw = text.substring(i + 1, end).trim()
                if (!raw.startsWith("!") && !raw.startsWith("?") && !raw.endsWith("/")) {
                    val name = tagNameOf(raw)
                    if (name.isNotEmpty() && !isVoidElement(name)) stack.addLast(name)
                }
                i = end + 1
                continue
            }
            i++
        }
        return stack.lastOrNull()
    }

    private fun checkDuplicateAttributes(
        document: CodeDocument,
        rawStartOffset: Int,
        rawTagContent: String,
        diags: MutableList<Diagnostic>,
    ) {
        val seen = mutableSetOf<String>()
        for ((nameStart, attrName) in attributeNamesIn(rawTagContent)) {
            // XML attribute names are case-sensitive; only HTML folds case.
            if (!seen.add(if (htmlMode) attrName.lowercase() else attrName)) {
                val attrOffset = rawStartOffset + nameStart
                val (line, _) = document.offsetToLineCol(attrOffset)
                diags.add(
                    Diagnostic(
                        range = attrOffset..(attrOffset + attrName.length),
                        lineNumber = line,
                        message = "Duplicate attribute '$attrName'",
                        severity = DiagnosticSeverity.Error,
                        source = sourceLabel,
                    ),
                )
            }
        }
    }

    /**
     * Flags a bare `&` inside a quoted attribute value of [rawTagContent], the same way the
     * document scan flags one in a text node. An `&` must start an entity or character reference
     * in an attribute value just as it must in text.
     */
    private fun checkAttributeValueEntities(
        document: CodeDocument,
        rawStartOffset: Int,
        rawTagContent: String,
        diags: MutableList<Diagnostic>,
    ) {
        var i = 0
        while (i < rawTagContent.length) {
            val c = rawTagContent[i]
            if (c != '"' && c != '\'') {
                i++
                continue
            }
            val close = rawTagContent.indexOf(c, i + 1).let { if (it < 0) rawTagContent.length else it }
            var j = i + 1
            while (j < close) {
                if (rawTagContent[j] != '&') {
                    j++
                    continue
                }
                val entity = ENTITY_REGEX.matchAt(rawTagContent, j)
                if (entity != null) {
                    j += entity.value.length
                    continue
                }
                val offset = rawStartOffset + j
                val (line, _) = document.offsetToLineCol(offset)
                diags.add(
                    Diagnostic(
                        range = offset..(offset + 1),
                        lineNumber = line,
                        message = "Unescaped '&' character; use '&amp;' instead",
                        severity = DiagnosticSeverity.Warning,
                        source = sourceLabel,
                    ),
                )
                j++
            }
            i = close + 1
        }
    }

    /**
     * Attribute names in [rawTagContent] (the text between `<` and `>`) as offset-to-name pairs.
     *
     * Walks the tag tracking quote state rather than pattern-matching the whole string, so
     * `foo=…` appearing inside an attribute *value* — `<a data=" foo=1 foo=2"/>` — is value text,
     * not two attributes.
     */
    private fun attributeNamesIn(rawTagContent: String): List<Pair<Int, String>> {
        val names = mutableListOf<Pair<Int, String>>()
        var i = 0
        // Skip the element name; it is not an attribute.
        while (i < rawTagContent.length && !rawTagContent[i].isWhitespace()) i++

        while (i < rawTagContent.length) {
            val c = rawTagContent[i]
            when {
                c == '"' || c == '\'' -> {
                    val close = rawTagContent.indexOf(c, i + 1)
                    i = if (close < 0) rawTagContent.length else close + 1
                }

                isNameStartChar(c) -> {
                    val start = i
                    while (i < rawTagContent.length && isNameChar(rawTagContent[i])) i++
                    val afterName = i
                    var j = i
                    while (j < rawTagContent.length && rawTagContent[j].isWhitespace()) j++
                    // Only a name followed by '=' is an attribute; a bare word is not.
                    if (j < rawTagContent.length && rawTagContent[j] == '=') {
                        names.add(start to rawTagContent.substring(start, afterName))
                    }
                }

                else -> i++
            }
        }
        return names
    }

    private fun isNameStartChar(c: Char): Boolean = c.isLetter() || c == '_' || c == ':'

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == ':' || c == '.' || c == '-'

    private fun findTagEnd(text: String, from: Int): Int? {
        var i = from
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return null
    }

    private fun error(document: CodeDocument, start: Int, end: Int, message: String): Diagnostic {
        val (line, _) = document.offsetToLineCol(start)
        return Diagnostic(
            range = start..(end - 1).coerceAtLeast(start),
            lineNumber = line,
            message = message,
            severity = DiagnosticSeverity.Error,
            source = sourceLabel,
        )
    }

    private data class OpenTag(val name: String, val start: Int, val end: Int)

    private companion object {
        val ENTITY_REGEX = Regex("&(?:[a-zA-Z0-9]+|#[0-9]+|#x[0-9a-fA-F]+);")

        const val COMMENT_OPEN = "<!--"
        const val COMMENT_CLOSE = "-->"
        const val CDATA_OPEN = "<![CDATA["
        const val CDATA_CLOSE = "]]>"

        val HTML_VOID_ELEMENTS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr",
        )

        /** HTML elements whose content is raw text: no tags, no entity references. */
        val HTML_RAW_TEXT_ELEMENTS = setOf("script", "style")

        val COMMON_XML_ELEMENTS = listOf(
            "manifest", "application", "activity", "service", "receiver", "provider",
            "uses-permission", "uses-sdk", "intent-filter", "action", "category", "data", "meta-data",
            "LinearLayout", "ConstraintLayout", "TextView", "Button", "ImageView", "RecyclerView",
            "FrameLayout", "ScrollView",
        )

        val COMMON_XML_ATTRIBUTES = listOf(
            "android:name", "android:id", "android:layout_width", "android:layout_height",
            "android:exported", "android:theme", "android:icon", "android:label",
            "xmlns:android", "xmlns:app", "xmlns:tools",
        )

        val COMMON_ATTR_VALUES = listOf(
            "match_parent", "wrap_content", "true", "false", "singleTask", "singleTop",
            "@string/", "@color/", "@style/", "@mipmap/", "@drawable/",
        )
    }
}

/** Strict XML well-formedness checker. */
object XmlLanguageService : TagValidator(htmlMode = false, sourceLabel = "xml")

/** Lenient HTML checker — treats void elements (`br`, `img`, …) as self-closing. */
object HtmlLanguageService : TagValidator(htmlMode = true, sourceLabel = "html")
