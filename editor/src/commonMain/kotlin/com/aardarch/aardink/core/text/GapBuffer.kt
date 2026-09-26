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
package com.aardarch.aardink.core.text

/**
 * Text storage with a gap at the last edit position.
 *
 * Edits near the previous one (typing, backspacing) move no text: the gap is already there, so an
 * insert or delete costs only its own length. Moving the edit point costs the distance moved, once.
 * A [StringBuilder] instead shifts everything after the edit on every keystroke.
 */
internal class GapBuffer(initial: CharSequence = "") {

    private var chars = CharArray(maxOf(MIN_CAPACITY, initial.length + initial.length / 2))
    private var gapStart = initial.length
    private var gapEnd = chars.size

    init {
        for (i in initial.indices) chars[i] = initial[i]
    }

    val length: Int get() = chars.size - (gapEnd - gapStart)

    operator fun get(index: Int): Char {
        if (index < 0 || index >= length) throw IndexOutOfBoundsException("index $index, length $length")
        return if (index < gapStart) chars[index] else chars[index + (gapEnd - gapStart)]
    }

    fun insert(offset: Int, text: CharSequence) {
        require(offset in 0..length) { "offset $offset outside 0..$length" }
        if (text.isEmpty()) return
        ensureGap(text.length)
        moveGap(offset)
        for (i in text.indices) chars[gapStart + i] = text[i]
        gapStart += text.length
    }

    /** Deletes the characters in [start, end). */
    fun delete(start: Int, end: Int) {
        require(start in 0..end && end <= length) { "range $start..$end outside 0..$length" }
        if (start == end) return
        moveGap(start)
        gapEnd += end - start
    }

    fun clear() {
        gapStart = 0
        gapEnd = chars.size
    }

    /** The characters in [start, end), copied into a new String. */
    fun substring(start: Int, end: Int): String {
        require(start in 0..end && end <= length) { "range $start..$end outside 0..$length" }
        val gap = gapEnd - gapStart
        return when {
            end <= gapStart -> chars.concatToString(start, end)

            start >= gapStart -> chars.concatToString(start + gap, end + gap)

            else -> buildString(end - start) {
                appendRange(chars, start, gapStart)
                appendRange(chars, gapEnd, end + gap)
            }
        }
    }

    override fun toString(): String = substring(0, length)

    private fun ensureGap(needed: Int) {
        val gap = gapEnd - gapStart
        if (gap >= needed) return
        val newSize = maxOf(chars.size * 2, length + needed + MIN_CAPACITY)
        val grown = CharArray(newSize)
        chars.copyInto(grown, 0, 0, gapStart)
        val tail = chars.size - gapEnd
        chars.copyInto(grown, newSize - tail, gapEnd, chars.size)
        gapEnd = newSize - tail
        chars = grown
    }

    private fun moveGap(offset: Int) {
        if (offset == gapStart) return
        val gap = gapEnd - gapStart
        if (offset < gapStart) {
            // Text in [offset, gapStart) moves to just before gapEnd.
            chars.copyInto(chars, offset + gap, offset, gapStart)
        } else {
            // Text in [gapEnd, offset + gap) moves to gapStart.
            chars.copyInto(chars, gapStart, gapEnd, offset + gap)
        }
        gapStart = offset
        gapEnd = offset + gap
    }

    private companion object {
        const val MIN_CAPACITY = 64
    }
}
