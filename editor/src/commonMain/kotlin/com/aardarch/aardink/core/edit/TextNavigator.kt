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

import com.aardarch.aardink.core.CodeDocument

/**
 * Where caret movements land, on logical lines. Pure functions of the document and an offset; the
 * view layer adds movement by wrapped rows on top.
 *
 * Words follow VS Code's default `editor.wordSeparators`: a run of separator characters is a word
 * of its own, a run of anything else that is not whitespace is a word, and whitespace is skipped.
 */
internal object TextNavigator {

    private const val WORD_SEPARATORS = "`~!@#\$%^&*()-=+[{]}\\|;:'\",.<>/?"

    enum class CharClass { Whitespace, Separator, Regular }

    fun classOf(c: Char): CharClass = when {
        c.isWhitespace() -> CharClass.Whitespace
        c in WORD_SEPARATORS -> CharClass.Separator
        else -> CharClass.Regular
    }

    /** One character left, never splitting a surrogate pair. */
    fun charLeft(document: CharSequence, offset: Int): Int {
        if (offset <= 0) return 0
        val previous = offset - 1
        return if (previous > 0 && document[previous].isLowSurrogate() &&
            document[previous - 1].isHighSurrogate()
        ) {
            previous - 1
        } else {
            previous
        }
    }

    /** One character right, never splitting a surrogate pair. */
    fun charRight(document: CharSequence, offset: Int): Int {
        if (offset >= document.length) return document.length
        val next = offset + 1
        return if (next < document.length && document[offset].isHighSurrogate() && document[next].isLowSurrogate()) next + 1 else next
    }

    /** Ctrl+Left: to the start of the word before [offset], skipping whitespace first. */
    fun wordLeft(document: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && classOf(document[i - 1]) == CharClass.Whitespace && document[i - 1] != '\n') i--
        if (i > 0 && document[i - 1] == '\n') return if (i == offset) i - 1 else i
        if (i == 0) return 0
        val cls = classOf(document[i - 1])
        while (i > 0 && classOf(document[i - 1]) == cls) i--
        return i
    }

    /** Ctrl+Right: to the end of the word after [offset], skipping whitespace first. */
    fun wordRight(document: CharSequence, offset: Int): Int {
        var i = offset
        val length = document.length
        while (i < length && classOf(document[i]) == CharClass.Whitespace && document[i] != '\n') i++
        if (i < length && document[i] == '\n') return if (i == offset) i + 1 else i
        if (i == length) return length
        val cls = classOf(document[i])
        while (i < length && classOf(document[i]) == cls) i++
        return i
    }

    /**
     * The word touching [offset] (double-click, Ctrl+D): the run of regular characters around it,
     * preferring the one after the caret. An empty range at [offset] when there is none.
     */
    fun wordAt(document: CharSequence, offset: Int): IntRange {
        val length = document.length
        val at = when {
            offset < length && classOf(document[offset]) == CharClass.Regular -> offset
            offset > 0 && classOf(document[offset - 1]) == CharClass.Regular -> offset - 1
            else -> return offset until offset
        }
        var start = at
        var end = at + 1
        while (start > 0 && classOf(document[start - 1]) == CharClass.Regular) start--
        while (end < length && classOf(document[end]) == CharClass.Regular) end++
        return start until end
    }

    /** Home: to the first non-blank character of the line, or to column 0 when already there. */
    fun smartHome(document: CodeDocument, offset: Int): Int {
        val line = document.offsetToLineCol(offset).first
        val start = document.lineStart(line)
        val end = document.lineEnd(line)
        var firstNonBlank = start
        while (firstNonBlank < end && document[firstNonBlank].isWhitespace()) firstNonBlank++
        return if (offset == firstNonBlank) start else firstNonBlank
    }

    fun lineEnd(document: CodeDocument, offset: Int): Int = document.lineEnd(document.offsetToLineCol(offset).first)

    /**
     * [lines] lines up (negative) or down from [offset], at [column] when given, else at the
     * current column; clamped to the target line's length. Past the first or last line it goes to
     * the start or end of the document, as editors do.
     */
    fun verticalMove(document: CodeDocument, offset: Int, lines: Int, column: Int? = null): Int {
        val (line, currentColumn) = document.offsetToLineCol(offset)
        val target = line + lines
        if (target < 0) return 0
        if (target >= document.lineCount) return document.length
        return document.lineColToOffset(target, column ?: currentColumn)
    }
}
