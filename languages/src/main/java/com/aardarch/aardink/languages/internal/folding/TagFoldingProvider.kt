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
package com.aardarch.editor.languages.internal.folding

import com.aardarch.editor.core.CodeDocument
import com.aardarch.editor.core.FoldRange
import com.aardarch.editor.core.FoldingProvider

/**
 * Pairs `<tag>` open/close tokens to fold XML / HTML markup.
 *
 * Skips:
 *   - `<!-- … -->` comments
 *   - `<![CDATA[ … ]]>` blocks
 *   - `<? … ?>` processing instructions
 *   - `<!DOCTYPE …>` and other `<!FOO …>` declarations
 *   - self-closing tags (`<br/>`)
 *
 * The matcher is intentionally lenient — it does not validate well-formedness; mismatched closes
 * pop the stack until a match is found, and unclosed opens at EOF are silently dropped.
 */
class TagFoldingProvider(
    /** When true, treat HTML void elements (`br`, `img`, `input`, …) as self-closing. */
    private val htmlMode: Boolean = false,
    private val minLines: Int = 1,
) : FoldingProvider {

    override fun foldableRanges(document: CodeDocument): List<FoldRange> {
        val text = document.text
        if (text.isEmpty()) return emptyList()
        val stack = ArrayDeque<OpenTag>()
        val ranges = mutableListOf<FoldRange>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c != '<') {
                i++
                continue
            }
            // <!-- ... -->
            if (i + 3 < n && text[i + 1] == '!' && text[i + 2] == '-' && text[i + 3] == '-') {
                val end = text.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            // <![CDATA[ ... ]]>
            if (i + 8 < n && text.regionMatches(i + 1, "![CDATA[", 0, 8)) {
                val end = text.indexOf("]]>", i + 9)
                i = if (end < 0) n else end + 3
                continue
            }
            // <!DOCTYPE …> / other <!… …>
            if (i + 1 < n && text[i + 1] == '!') {
                val end = text.indexOf('>', i + 2)
                i = if (end < 0) n else end + 1
                continue
            }
            // <? … ?>
            if (i + 1 < n && text[i + 1] == '?') {
                val end = text.indexOf("?>", i + 2)
                i = if (end < 0) n else end + 2
                continue
            }
            // Closing tag: </name>
            if (i + 1 < n && text[i + 1] == '/') {
                val gt = findTagEnd(text, i + 2) ?: break
                val rawClose = text.substring(i + 2, gt).trim()
                val closeNameEnd = rawClose.indexOfFirst { it.isWhitespace() }
                val name = (if (closeNameEnd < 0) rawClose else rawClose.substring(0, closeNameEnd)).lowercase()
                // pop until match
                while (stack.isNotEmpty()) {
                    val top = stack.removeLast()
                    if (top.name == name) {
                        val (sLine, _) = document.offsetToLineCol(top.offset)
                        val (eLine, _) = document.offsetToLineCol(i)
                        if (eLine - sLine >= minLines) {
                            ranges.add(FoldRange(sLine, eLine))
                        }
                        break
                    }
                }
                i = gt + 1
                continue
            }
            // Opening tag: <name … > or <name … />
            val tagEnd = findTagEnd(text, i + 1) ?: break
            val raw = text.substring(i + 1, tagEnd)
            val selfClosing = raw.trimEnd().endsWith('/')
            val rawNameEnd = raw.indexOfFirst { it.isWhitespace() || it == '/' }
            val name = (if (rawNameEnd < 0) raw else raw.substring(0, rawNameEnd)).lowercase()
            if (name.isEmpty()) {
                i = tagEnd + 1
                continue
            }
            val isVoid = htmlMode && name in HTML_VOID_ELEMENTS
            if (!selfClosing && !isVoid) {
                stack.addLast(OpenTag(name, i))
            }
            i = tagEnd + 1
        }
        return ranges.sortedBy { it.startLine }
    }

    /** Index of the next `>` outside of any quoted attribute value, or null if none. */
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

    private data class OpenTag(val name: String, val offset: Int)

    private companion object {
        val HTML_VOID_ELEMENTS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr",
        )
    }
}
