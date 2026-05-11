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
package com.aardarch.editor.languages.internal.xml

import com.aardarch.editor.core.CodeDocument
import com.aardarch.editor.core.Diagnostic
import com.aardarch.editor.core.DiagnosticSeverity
import com.aardarch.editor.languages.internal.BaseLanguageService

/**
 * Lightweight XML well-formedness checker.
 *
 * Diagnostics surfaced:
 *   - mismatched closing tag (`</a>` where `<b>` was open)
 *   - unclosed opening tag at end of document
 *   - stray closing tag with no matching open
 *   - unterminated comment / CDATA / processing instruction
 *   - unterminated tag (no `>` before EOF)
 *
 * Validation is structural only — DTD / schema checks are out of scope.
 */
abstract class TagValidator(private val htmlMode: Boolean, private val sourceLabel: String) : BaseLanguageService() {

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        val text = document.text
        if (text.isBlank()) return emptyList()
        val diags = mutableListOf<Diagnostic>()
        val stack = ArrayDeque<OpenTag>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
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
        val HTML_VOID_ELEMENTS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr",
        )
    }
}

/** Strict XML well-formedness checker. */
object XmlLanguageService : TagValidator(htmlMode = false, sourceLabel = "xml")

/** Lenient HTML checker — treats void elements (`br`, `img`, …) as self-closing. */
object HtmlLanguageService : TagValidator(htmlMode = true, sourceLabel = "html")
