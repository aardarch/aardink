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
package com.aardarch.aardink.languages

import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.CooperativePacer
import com.aardarch.aardink.languages.internal.ScanCache
import com.aardarch.aardink.languages.internal.diffTexts

/**
 * Tokenizes with a [DeclarativeGrammar], a line at a time: each line starts in the state stack the
 * line before it ended in, so after an edit it rescans from the edited line only until a line ends
 * in the state it ended in before, and returns the tokens of the lines it rescanned.
 */
class DeclarativeTokenizer(private val grammar: DeclarativeGrammar) : IncrementalTokenizer {

    /**
     * The state stack at the start of a line, innermost state first. Shared, never changed.
     * [state] is the name as entered, dotted parts and all, not the state its rules come from:
     * `$S2` reads those parts, so `string.a` and `string.b` are different states to a rescan.
     */
    private class Stack(val state: String, val parent: Stack?) {
        /** Where `@popall` goes. Not always the start state: `switchTo` can replace the bottom one too. */
        val bottom: Stack
            get() {
                var stack = this
                while (true) stack = stack.parent ?: return stack
            }

        override fun equals(other: Any?): Boolean = this === other || (other is Stack && state == other.state && parent == other.parent)

        override fun hashCode(): Int = state.hashCode() * 31 + (parent?.hashCode() ?: 0)
    }

    /** One scan: the text, where each line starts, the stack each starts in, and each line's tokens (columns). */
    private class Scan(val text: String, val lineStarts: IntArray, val stacks: Array<Stack>, val lines: Array<LineTokens>)

    /** A line's tokens: run `i` covers columns [starts[i], ends[i]). */
    private class LineTokens(val starts: IntArray, val ends: IntArray, val types: Array<TokenType>) {
        companion object {
            val EMPTY = LineTokens(IntArray(0), IntArray(0), emptyArray())
        }
    }

    private val scans = ScanCache<Scan>()

    private val root = Stack(grammar.start, null)

    override fun tokenizeFull(text: String): List<Token> {
        val starts = lineStartsOf(text)
        val stacks = arrayOfNulls<Stack>(starts.size + 1)
        val lines = arrayOfNulls<LineTokens>(starts.size)
        var stack = root
        for (line in starts.indices) {
            stacks[line] = stack
            val (tokens, end) = tokenizeLine(text, starts, line, stack)
            lines[line] = tokens
            stack = end
        }
        stacks[starts.size] = stack
        return remembered(Scan(text, starts, stacks.requireNoNulls(), lines.requireNoNulls()), 0, starts.size, full = true)
    }

    override suspend fun tokenizeFullCooperative(text: String): List<Token> {
        val pacer = CooperativePacer()
        val starts = lineStartsOf(text)
        val stacks = arrayOfNulls<Stack>(starts.size + 1)
        val lines = arrayOfNulls<LineTokens>(starts.size)
        var stack = root
        var tokenCount = 0
        for (line in starts.indices) {
            pacer.onProgress(tokenCount)
            stacks[line] = stack
            val (tokens, end) = tokenizeLine(text, starts, line, stack)
            lines[line] = tokens
            tokenCount += tokens.types.size + 1
            stack = end
        }
        stacks[starts.size] = stack
        return remembered(Scan(text, starts, stacks.requireNoNulls(), lines.requireNoNulls()), 0, starts.size, full = true)
    }

    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> {
        if (previousTokens.isEmpty()) return provisional(text, dirtyRange)
        val previous = scans.find(previousTokens) ?: return tokenizeFull(text)
        val diff = diffTexts(previous.text, text)
        val starts = lineStartsOf(text)
        val oldCount = previous.lineStarts.size
        val lineDelta = starts.size - oldCount
        // The first line the edit touched: every line before it is the same text, in the same state.
        val first = minOf(lineOf(previous.lineStarts, diff.start), dirtyRange.first.coerceAtLeast(0)).coerceAtMost(starts.size - 1)
        val stacks = arrayOfNulls<Stack>(starts.size + 1)
        val lines = arrayOfNulls<LineTokens>(starts.size)
        previous.stacks.copyInto(stacks, 0, 0, first + 1)
        previous.lines.copyInto(lines, 0, 0, first)
        // The last line the edit touched, in the new text; later lines are old lines moved by lineDelta.
        val lastChanged = lineOf(starts, diff.newEnd)
        val mustReach = maxOf(lastChanged, dirtyRange.last.coerceAtMost(starts.size - 1))
        var stack = previous.stacks[first]
        var line = first
        while (line < starts.size) {
            val (tokens, end) = tokenizeLine(text, starts, line, stack)
            lines[line] = tokens
            stack = end
            line++
            stacks[line] = stack
            // Past the edit and ending as the same old line did: the rest is as it was.
            val old = line - lineDelta
            if (line > mustReach && old in 1..oldCount && previous.stacks[old] == stack) {
                previous.stacks.copyInto(stacks, line, old, oldCount + 1)
                previous.lines.copyInto(lines, line, old, oldCount)
                break
            }
        }
        return remembered(Scan(text, starts, stacks.requireNoNulls(), lines.requireNoNulls()), first, line, full = false)
    }

    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false

    /** From the grammar's `comments`, for toggling comments. */
    override val commentSyntax: CommentSyntax? get() = grammar.commentSyntax

    /** Just [lines], from the start state at the first of them, for a quick first colouring; remembers nothing. */
    private fun provisional(text: String, lines: IntRange): List<Token> {
        val starts = lineStartsOf(text)
        val result = ArrayList<Token>()
        var stack = root
        for (line in lines.first..lines.last.coerceAtMost(starts.size - 1)) {
            val (tokens, end) = tokenizeLine(text, starts, line, stack)
            tokens.appendTo(result, starts[line])
            stack = end
        }
        return result
    }

    /**
     * Remembers [scan] for the next incremental pass and returns the tokens of lines [from, to):
     * all of them for a full scan; for a partial one, with a zero-length token at each end of the
     * span, so the editor clears lines whose tokens all went away.
     */
    private fun remembered(scan: Scan, from: Int, to: Int, full: Boolean): List<Token> {
        val result = ArrayList<Token>()
        val spanStart = if (from < scan.lineStarts.size) scan.lineStarts[from] else scan.text.length
        val spanEnd = if (to < scan.lineStarts.size) scan.lineStarts[to] - 1 else scan.text.length
        if (!full) result += Token(spanStart, spanStart, TokenType.Default)
        for (line in from until to) scan.lines[line].appendTo(result, scan.lineStarts[line])
        if (!full) result += Token(spanEnd.coerceAtLeast(spanStart), spanEnd.coerceAtLeast(spanStart), TokenType.Default)
        scans.remember(result, scan)
        return result
    }

    private fun LineTokens.appendTo(result: MutableList<Token>, lineStart: Int) {
        for (i in types.indices) result += Token(lineStart + starts[i], lineStart + ends[i], types[i])
    }

    /** Tokenizes line [line] of [text] starting in [stack]; returns its tokens and the stack it ends in. */
    private fun tokenizeLine(text: String, lineStarts: IntArray, line: Int, stack: Stack): Pair<LineTokens, Stack> {
        val start = lineStarts[line]
        val end = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length
        val content = text.substring(start, end)
        val builder = LineBuilder()
        var current = stack
        var pos = 0
        var emptySteps = 0
        while (pos <= content.length) {
            val rules = grammar.states[stateOf(current.state, grammar.states.keys) ?: grammar.start].orEmpty()
            var matched: DeclarativeGrammar.Rule? = null
            var match: MatchResult? = null
            for (rule in rules) {
                val found = rule.regex.matchAt(content, pos) ?: continue
                matched = rule
                match = found
                break
            }
            if (matched == null || match == null) {
                if (pos >= content.length) break
                // Nothing matches here: one character of the default token, as in Monarch.
                builder.add(pos, pos + 1, grammar.defaultToken)
                pos++
                continue
            }
            val length = match.value.length
            val action = resolve(matched.action, match.value, pos + length == content.length, current.state)
            val before = current
            val next: DeclarativeGrammar.Next?
            var consumed = length
            when (action) {
                is DeclarativeGrammar.Action.Token -> {
                    // Never back past where the match began: the line only moves forward, so
                    // this loop ends, and a line's tokens stay in order.
                    consumed = if (action.rematch) 0 else (length - action.goBack).coerceAtLeast(0)
                    if (!action.rematch) builder.add(pos, pos + consumed, action.typeOf(match.value))
                    next = action.next
                }

                is DeclarativeGrammar.Action.Groups -> {
                    current = addGroups(builder, match, pos, action.actions, current, content.length)
                    next = action.next
                }

                is DeclarativeGrammar.Action.Cases -> {
                    // No case applied: the match gets the default token.
                    builder.add(pos, pos + length, grammar.defaultToken)
                    next = null
                }
            }
            current = step(current, next, match.value)
            if (consumed == 0) {
                // A match that takes no text only counts if it changes the state; and never forever.
                if (current == before || ++emptySteps > MAX_EMPTY_STEPS) {
                    if (pos >= content.length) break
                    builder.add(pos, pos + 1, grammar.defaultToken)
                    pos++
                    emptySteps = 0
                }
            } else {
                pos += consumed
                emptySteps = 0
            }
        }
        return builder.build() to current
    }

    /** The action a `cases` resolves to for [matched] in [state], or [action] itself. */
    private fun resolve(action: DeclarativeGrammar.Action, matched: String, endOfLine: Boolean, state: String): DeclarativeGrammar.Action {
        if (action !is DeclarativeGrammar.Action.Cases) return action
        for ((guard, candidate) in action.cases) {
            val applies = when (guard) {
                DeclarativeGrammar.Guard.Default -> true
                DeclarativeGrammar.Guard.EndOfLine -> endOfLine
                is DeclarativeGrammar.Guard.Test -> guard.negated != holds(guard, matched, state)
            }
            if (applies) return resolve(candidate, matched, endOfLine, state)
        }
        return action
    }

    private fun holds(test: DeclarativeGrammar.Guard.Test, matched: String, state: String): Boolean {
        val subject = test.subject.textIn(matched, state)
        return when (val condition = test.condition) {
            is DeclarativeGrammar.Condition.InArray -> (if (condition.ignoreCase) subject.lowercase() else subject) in condition.words
            is DeclarativeGrammar.Condition.Equal -> subject.equals(condition.text.fill(matched, state), condition.ignoreCase)
            is DeclarativeGrammar.Condition.Matches -> condition.regex.matches(subject)
            is DeclarativeGrammar.Condition.MatchesTemplate -> condition.regex(matched, state)?.matches(subject) ?: false
        }
    }

    /**
     * Each capture group of [match] its own token, by its own action (whose cases test the group's
     * text); text between groups the default token. Returns [stack] after the groups' `next`s.
     */
    private fun addGroups(
        builder: LineBuilder,
        match: MatchResult,
        pos: Int,
        actions: List<DeclarativeGrammar.Action>,
        stack: Stack,
        lineLength: Int,
    ): Stack {
        var at = pos
        var current = stack
        val end = pos + match.value.length
        for (group in 1..minOf(actions.size, match.groups.size - 1)) {
            val value = match.groups[group]?.value ?: continue
            // Groups follow each other in the match: find this one from where the last ended.
            val index = match.value.indexOf(value, at - pos)
            if (index < 0) continue
            val groupStart = pos + index
            // A group's case that does not apply leaves it the default token, as for a whole match.
            val endOfLine = groupStart + value.length == lineLength
            val action = resolve(actions[group - 1], value, endOfLine, current.state) as? DeclarativeGrammar.Action.Token
            current = step(current, action?.next, value)
            if (value.isEmpty()) continue
            if (groupStart > at) builder.add(at, groupStart, grammar.defaultToken)
            builder.add(groupStart, groupStart + value.length, action?.typeOf(value) ?: grammar.defaultToken)
            at = groupStart + value.length
        }
        if (at < end) builder.add(at, end, grammar.defaultToken)
        return current
    }

    private fun step(stack: Stack, next: DeclarativeGrammar.Next?, matched: String): Stack = when (next) {
        null -> stack
        is DeclarativeGrammar.Next.Enter -> target(next.state, matched, stack)?.let { Stack(it, stack) } ?: stack
        is DeclarativeGrammar.Next.Switch -> target(next.state, matched, stack)?.let { Stack(it, stack.parent) } ?: stack
        DeclarativeGrammar.Next.Push -> Stack(stack.state, stack)
        DeclarativeGrammar.Next.Pop -> stack.parent ?: stack
        DeclarativeGrammar.Next.PopAll -> stack.bottom
    }

    /**
     * The state [name] comes to for [matched] on [stack], or null if the grammar has none. The
     * text being typed can make a name anything, so where Monarch throws, the stack stays put.
     */
    private fun target(name: DeclarativeGrammar.Template, matched: String, stack: Stack): String? =
        name.fill(matched, stack.state).removePrefix("@").takeIf { stateOf(it, grammar.states.keys) != null }

    /** Collects a line's tokens, merging neighbours of one type and leaving out default ones. */
    private class LineBuilder {
        private var starts = IntArray(8)
        private var ends = IntArray(8)
        private val types = ArrayList<TokenType>()

        fun add(start: Int, end: Int, type: TokenType) {
            if (end <= start || type == TokenType.Default) return
            val last = types.size - 1
            if (last >= 0 && types[last] == type && ends[last] == start) {
                ends[last] = end
                return
            }
            if (types.size == starts.size) {
                starts = starts.copyOf(starts.size * 2)
                ends = ends.copyOf(ends.size * 2)
            }
            starts[types.size] = start
            ends[types.size] = end
            types += type
        }

        fun build(): LineTokens =
            if (types.isEmpty()) LineTokens.EMPTY else LineTokens(starts.copyOf(types.size), ends.copyOf(types.size), types.toTypedArray())
    }

    private companion object {
        /** Matches in a row that take no text before the tokenizer moves on by a character. */
        const val MAX_EMPTY_STEPS = 16

        fun lineStartsOf(text: String): IntArray {
            var count = 1
            for (c in text) if (c == '\n') count++
            val starts = IntArray(count)
            var line = 1
            for (i in text.indices) if (text[i] == '\n') starts[line++] = i + 1
            return starts
        }

        /** The line of [lineStarts] that [offset] is on. */
        fun lineOf(lineStarts: IntArray, offset: Int): Int {
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}
