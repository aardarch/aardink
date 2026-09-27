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
package com.aardarch.aardink.core

/** Finds the bracket that matches the one at a caret. */
internal object BracketMatcher {

    /** How far from the caret the search looks before giving up. */
    const val LIMIT = 50_000

    /** A bracket at [open] and its partner at [close] (document offsets of the characters). */
    data class Match(val open: Int, val close: Int)

    /**
     * The pair the caret at [offset] touches: the bracket just before it, else the one just after
     * it, and its partner, found by counting brackets of the same kind outside strings and
     * comments ([isCode]) within [LIMIT] characters. Null when the caret touches no bracket, or
     * its partner is not found.
     */
    fun find(text: CharSequence, offset: Int, isCode: (Int) -> Boolean): Match? {
        for (at in intArrayOf(offset - 1, offset)) {
            if (at < 0 || at >= text.length) continue
            val c = text[at]
            if (!isBracket(c) || !isCode(at)) continue
            return partner(text, at, isCode)?.let { if (it > at) Match(at, it) else Match(it, at) }
        }
        return null
    }

    private fun partner(text: CharSequence, at: Int, isCode: (Int) -> Boolean): Int? {
        val c = text[at]
        val forward = c == '(' || c == '[' || c == '{'
        val open = if (forward) c else openerOf(c)
        val close = closerOf(open)
        var depth = 0
        var i = at
        val stop = if (forward) minOf(text.length, at + LIMIT) else maxOf(-1, at - LIMIT)
        while (i != stop) {
            val ch = text[i]
            if ((ch == open || ch == close) && isCode(i)) {
                depth += if ((ch == open) == forward) 1 else -1
                if (depth == 0) return i
            }
            i += if (forward) 1 else -1
        }
        return null
    }

    fun isBracket(c: Char): Boolean = c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'

    fun isOpening(c: Char): Boolean = c == '(' || c == '[' || c == '{'

    private fun openerOf(close: Char): Char = when (close) {
        ')' -> '('
        ']' -> '['
        else -> '{'
    }

    private fun closerOf(open: Char): Char = when (open) {
        '(' -> ')'
        '[' -> ']'
        else -> '}'
    }
}
