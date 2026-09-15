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
package com.aardarch.aardink.languages.internal.folding

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldingProvider

/**
 * Pairs `{` with `}` (or any other open/close pair) across the document, ignoring braces that
 * appear inside strings and comments.
 *
 * The string/comment dialect is configurable via the constructor flags so this single provider
 * works for Kotlin, TypeScript, JSON (no comments at all), CSS (no `'…'` chars, no `//`), etc.
 */
class BraceFoldingProvider(
    private val openChar: Char = '{',
    private val closeChar: Char = '}',
    private val supportsLineComments: Boolean = true,
    private val supportsBlockComments: Boolean = true,
    private val supportsDoubleQuoted: Boolean = true,
    private val supportsSingleQuoted: Boolean = true,
    private val supportsTripleQuoted: Boolean = false,
    private val minLines: Int = 1,
) : FoldingProvider {

    override fun foldableRanges(document: CodeDocument): List<FoldRange> {
        val text = document.text
        if (text.isEmpty()) return emptyList()
        val stack = ArrayDeque<Int>()
        val ranges = mutableListOf<FoldRange>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            // Triple-quoted string (Kotlin / TS template literals start with `, handled separately
            // by language tokenizers — we only handle `"""…"""` here).
            if (supportsTripleQuoted && c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                val end = text.indexOf("\"\"\"", i + 3)
                i = if (end < 0) n else end + 3
                continue
            }
            if (supportsDoubleQuoted && c == '"') {
                i = skipString(text, i, '"')
                continue
            }
            if (supportsSingleQuoted && c == '\'') {
                i = skipString(text, i, '\'')
                continue
            }
            if (supportsLineComments && c == '/' && i + 1 < n && text[i + 1] == '/') {
                val nl = text.indexOf('\n', i)
                i = if (nl < 0) n else nl
                continue
            }
            if (supportsBlockComments && c == '/' && i + 1 < n && text[i + 1] == '*') {
                val end = text.indexOf("*/", i + 2)
                i = if (end < 0) n else end + 2
                continue
            }
            if (c == openChar) {
                stack.addLast(i)
            } else if (c == closeChar && stack.isNotEmpty()) {
                val openOffset = stack.removeLast()
                val (sLine, _) = document.offsetToLineCol(openOffset)
                val (eLine, _) = document.offsetToLineCol(i)
                if (eLine - sLine >= minLines) {
                    ranges.add(FoldRange(sLine, eLine))
                }
            }
            i++
        }
        return ranges.sortedBy { it.startLine }
    }

    private fun skipString(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\\' && i + 1 < n) {
                i += 2
                continue
            }
            if (c == quote || c == '\n') return i + 1
            i++
        }
        return n
    }
}
