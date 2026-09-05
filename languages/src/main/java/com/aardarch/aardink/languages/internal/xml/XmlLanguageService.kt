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

        while (i < n) {
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
                val name = rawClose.takeWhile { !it.isWhitespace() }.lowercase()
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
            val name = (if (rawNameEnd < 0) raw else raw.substring(0, rawNameEnd)).lowercase()
            if (name.isEmpty()) {
                diags.add(error(document, i, tagEnd + 1, "Empty tag"))
                i = tagEnd + 1
                continue
            }

            // Check duplicate attributes in raw
            checkDuplicateAttributes(document, i + 1, raw, diags)

            val isVoid = htmlMode && name in HTML_VOID_ELEMENTS
            if (!selfClosing && !isVoid) {
                stack.addLast(OpenTag(name, i, tagEnd + 1))
            }
            i = tagEnd + 1
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
            if (tagStart >= 0) {
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

        val lastLt = textBefore.lastIndexOf('<')
        val lastGt = textBefore.lastIndexOf('>')

        if (lastLt > lastGt) {
            val tagContent = textBefore.substring(lastLt + 1)

            // 1. Attribute value completion after '='
            val lastEq = textBefore.lastIndexOf('=')
            if (lastEq > lastLt) {
                val afterEq = textBefore.substring(lastEq + 1).trimStart()
                if (afterEq.isEmpty() || afterEq.startsWith("\"") || afterEq.startsWith("'")) {
                    return COMMON_ATTR_VALUES.map { value ->
                        CompletionItem(
                            label = value,
                            kind = CompletionKind.Value,
                            insertText = if (afterEq.isEmpty()) "\"$value\"" else value,
                            documentation = "Attribute value $value",
                        )
                    }
                }
            }

            // 2. Attribute name completion inside tag (after space)
            if (tagContent.contains(' ')) {
                return COMMON_XML_ATTRIBUTES.map { attr ->
                    CompletionItem(
                        label = attr,
                        kind = CompletionKind.Attribute,
                        insertText = "$attr=\"\"",
                        documentation = "Attribute $attr",
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

    override suspend fun format(document: CodeDocument): String {
        val text = document.text
        if (text.isBlank()) return text

        val lines = text.lines()
        val result = mutableListOf<String>()
        var depth = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                result.add("")
                continue
            }

            if (trimmed.startsWith("<?") || trimmed.startsWith("<!--") || trimmed.startsWith("<!")) {
                result.add(" ".repeat(depth * 4) + trimmed)
                continue
            }

            if (trimmed.startsWith("</")) {
                depth = (depth - 1).coerceAtLeast(0)
                result.add(" ".repeat(depth * 4) + trimmed)
                continue
            }

            if (trimmed.startsWith("<") && (trimmed.endsWith("/>") || (trimmed.endsWith(">") && trimmed.contains("</")))) {
                result.add(" ".repeat(depth * 4) + trimmed)
                continue
            }

            if (trimmed.startsWith("<")) {
                result.add(" ".repeat(depth * 4) + trimmed)
                // Whole-name match: <input-group> is not the void element <input>.
                if (!isVoidElement(tagNameOf(trimmed.removePrefix("<")))) depth++
                continue
            }

            result.add(" ".repeat(depth * 4) + trimmed)
        }

        return result.joinToString("\n")
    }

    /** Element name at the start of [tagContent] (the text just inside `<`), without attributes. */
    private fun tagNameOf(tagContent: String): String = tagContent.trimStart().takeWhile { !it.isWhitespace() && it != '/' && it != '>' }

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
                    if (stack.lastOrNull()?.equals(closeName, ignoreCase = true) == true) {
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
        val matches = ATTR_NAME_REGEX.findAll(rawTagContent)
        for (m in matches) {
            val attrName = m.value
            // XML attribute names are case-sensitive; only HTML folds case.
            if (!seen.add(if (htmlMode) attrName.lowercase() else attrName)) {
                val attrOffset = rawStartOffset + m.range.first
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
        val ATTR_NAME_REGEX = Regex("(?<=\\s)[a-zA-Z_:][a-zA-Z0-9_.:-]*(?=\\s*=)")

        val HTML_VOID_ELEMENTS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr",
        )

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
