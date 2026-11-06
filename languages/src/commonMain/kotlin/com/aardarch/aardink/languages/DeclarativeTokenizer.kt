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
 *
 * [embedded] finds the grammars `nextEmbedded` names, by id, and answers null for one it does not
 * know: that text stays on the parent's rules. It is asked each time such a language is entered and
 * for each line read in it, so it should be a lookup, not a parse; and a grammar it comes to know
 * later is found the next time the text is scanned. It can run on the tokenizer's own threads.
 */
class DeclarativeTokenizer(private val grammar: DeclarativeGrammar, private val embedded: (String) -> DeclarativeGrammar? = { null }) :
    IncrementalTokenizer {

    /**
     * The state stack at the start of a line, innermost state first. Shared, never changed.
     * [state] is the name as entered, dotted parts and all, not the state its rules come from:
     * `$S2` reads those parts, so `string.a` and `string.b` are different states to a rescan.
     *
     * [grammar] is the id of the embedded grammar these states are in, null for the tokenizer's
     * own. [embedded], on the innermost state, is the stack of the language that has the text for
     * now, which can hold one of its own: two stacks are equal only if everything inside them is,
     * so a rescan stops only where every language is back in step.
     */
    private class Stack(val state: String, val parent: Stack?, val grammar: String? = null, val embedded: Stack? = null) {
        // Computed once, from the parts' own: a line's end state is compared on every rescanned line.
        private val hash: Int = ((state.hashCode() * 31 + grammar.hashCode()) * 31 + embedded.hashCode()) * 31 + parent.hashCode()

        /** Where `@popall` goes. Not always the start state: `switchTo` can replace the bottom one too. */
        val bottom: Stack
            get() {
                var stack = this
                while (true) stack = stack.parent ?: return stack
            }

        /** This state with [inner] as the language that has the text, or none. */
        fun embedding(inner: Stack?): Stack = if (inner === embedded) this else Stack(state, parent, grammar, inner)

        // A loop, not a call per state: `@push` can make a stack as deep as the document is long.
        override fun equals(other: Any?): Boolean {
            var a = this
            var b = other as? Stack ?: return false
            while (a !== b) {
                if (a.hash != b.hash || a.state != b.state || a.grammar != b.grammar || a.embedded != b.embedded) return false
                a = a.parent ?: return b.parent == null
                b = b.parent ?: return false
            }
            return true
        }

        override fun hashCode(): Int = hash
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
        val builder = LineBuilder()
        val last = Reader(grammar, text.substring(start, end), 0, builder, 0).read(stack)
        return builder.build() to last
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

    /** Where a rule leaves an embedded language: its [match], at the earliest such place in the line. */
    private class Exit(val rule: DeclarativeGrammar.Rule, val match: MatchResult)

    /**
     * Reads [content] with [language]: a whole line, or for an embedded language the part of one its
     * parent leaves it, [offset] columns in. [depth] counts the languages around this one.
     */
    private inner class Reader(
        private val language: DeclarativeGrammar,
        private val content: String,
        private val offset: Int,
        private val builder: LineBuilder,
        private val depth: Int,
    ) {
        /** Reads all of [content] starting in [stack]; returns the stack it ends in. */
        fun read(stack: Stack): Stack {
            var current = stack
            var pos = 0
            var emptySteps = 0
            while (pos <= content.length) {
                val rules = language.states[stateOf(current.state, language.states.keys) ?: language.start].orEmpty()
                var matched: DeclarativeGrammar.Rule? = null
                var match: MatchResult? = null
                if (current.embedded != null) {
                    val exit = exitFrom(pos, rules, current.state)
                    val until = exit?.match?.range?.first ?: content.length
                    // A line that is empty still goes in, as a line is what a language's rules read.
                    // The end of a line it was entered at does not: that is no line of its own.
                    if (until > pos || (exit == null && content.isEmpty())) {
                        current = readEmbedded(current, pos, until)
                        pos = until
                        emptySteps = 0
                    }
                    if (exit == null) return current
                    matched = exit.rule
                    match = exit.match
                } else {
                    for (rule in rules) {
                        val found = rule.regex.matchAt(content, pos) ?: continue
                        matched = rule
                        match = found
                        break
                    }
                }
                if (matched == null || match == null) {
                    if (pos >= content.length) break
                    // Nothing matches here: one character of the default token, as in Monarch.
                    add(pos, pos + 1, language.defaultToken)
                    pos++
                    continue
                }
                val length = match.value.length
                val action = resolve(matched.action, match.value, pos + length == content.length, current.state)
                val before = current
                val next: DeclarativeGrammar.Next?
                var embedding: DeclarativeGrammar.Embedding? = null
                var consumed = length
                when (action) {
                    is DeclarativeGrammar.Action.Token -> {
                        // Never back past where the match began: the line only moves forward, so
                        // this loop ends, and a line's tokens stay in order.
                        consumed = if (action.rematch) 0 else (length - action.goBack).coerceAtLeast(0)
                        if (!action.rematch) add(pos, pos + consumed, action.typeOf(match.value))
                        next = action.next
                        embedding = action.embedding
                    }

                    is DeclarativeGrammar.Action.Groups -> {
                        current = addGroups(match, pos, action.actions, current)
                        next = action.next
                    }

                    is DeclarativeGrammar.Action.Cases -> {
                        // No case applied: the match gets the default token.
                        add(pos, pos + length, language.defaultToken)
                        next = null
                    }
                }
                current = move(current, next, embedding, match.value)
                if (consumed == 0) {
                    // A match that takes no text only counts if it changes the state; and never forever.
                    if (current == before || ++emptySteps > MAX_EMPTY_STEPS) {
                        if (pos >= content.length) break
                        add(pos, pos + 1, language.defaultToken)
                        pos++
                        emptySteps = 0
                    }
                } else {
                    pos += consumed
                    emptySteps = 0
                }
            }
            return current
        }

        private fun add(start: Int, end: Int, type: TokenType) = builder.add(offset + start, offset + end, type)

        /**
         * Where the language embedded in [state] gives the text back: the first match from [from] of
         * one of [rules] that leaves it, the earlier rule on a tie, or null if the line has none.
         * This is a find per line and rule that leaves, never per token: the rules that do not
         * leave are not tried, and text is only searched once more when a language is entered again.
         */
        private fun exitFrom(from: Int, rules: List<DeclarativeGrammar.Rule>, state: String): Exit? {
            var exit: Exit? = null
            for (rule in rules) {
                if (!rule.leavesEmbedded) continue
                var at = from
                while (at <= content.length) {
                    val found = rule.regex.find(content, at) ?: break
                    if (exit != null && found.range.first >= exit.match.range.first) break
                    if (leaves(rule.action, found, state)) {
                        exit = Exit(rule, found)
                        break
                    }
                    // Its cases said no to this one, so the next might still be it. Past all of it:
                    // what follows inside it (`end` in `bend`) is no match the rule would have made.
                    at = maxOf(found.range.last, found.range.first) + 1
                }
            }
            return exit
        }

        /**
         * Whether [action] leaves on [match] in [state]. A group's cases test the group's own text,
         * which is only worked out when the groups are read, so groups that can leave count as
         * leaving; if they then do not, the match is the parent's and the language goes on after it.
         */
        private fun leaves(action: DeclarativeGrammar.Action, match: MatchResult, state: String): Boolean =
            when (val resolved = resolve(action, match.value, match.range.last + 1 == content.length, state)) {
                is DeclarativeGrammar.Action.Token -> resolved.embedding == DeclarativeGrammar.Embedding.Pop
                is DeclarativeGrammar.Action.Groups -> resolved.leavesEmbedded
                is DeclarativeGrammar.Action.Cases -> false
            }

        /** [stack] after its embedded language read columns [from, until) of this text. */
        private fun readEmbedded(stack: Stack, from: Int, until: Int): Stack {
            val inner = stack.embedded ?: return stack
            // Known when it was entered and not now: the parent's rules take over again.
            val found = inner.grammar?.let(embedded) ?: return stack.embedding(null)
            return stack.embedding(Reader(found, content.substring(from, until), offset + from, builder, depth + 1).read(inner))
        }

        /**
         * Each capture group of [match] its own token, by its own action (whose cases test the group's
         * text); text between groups the default token. Returns [stack] after the groups' `next`s.
         */
        private fun addGroups(match: MatchResult, pos: Int, actions: List<DeclarativeGrammar.Action>, stack: Stack): Stack {
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
                val endOfLine = groupStart + value.length == content.length
                val action = resolve(actions[group - 1], value, endOfLine, current.state) as? DeclarativeGrammar.Action.Token
                current = move(current, action?.next, action?.embedding, value)
                if (value.isEmpty()) continue
                if (groupStart > at) add(at, groupStart, language.defaultToken)
                add(groupStart, groupStart + value.length, action?.typeOf(value) ?: language.defaultToken)
                at = groupStart + value.length
            }
            if (at < end) add(at, end, language.defaultToken)
            return current
        }

        /**
         * [stack] after [next] and [embedding]. Leaving comes first and entering last: the language
         * sits on the innermost state, which `next` may be about to pop, or to replace.
         */
        private fun move(stack: Stack, next: DeclarativeGrammar.Next?, embedding: DeclarativeGrammar.Embedding?, matched: String): Stack {
            val left = if (embedding == DeclarativeGrammar.Embedding.Pop) stack.embedding(null) else stack
            val moved = step(left, next, matched)
            if (embedding !is DeclarativeGrammar.Embedding.Enter) return moved
            val id = embedding.id.fill(matched, stack.state)
            val found = if (depth < MAX_EMBEDDING_DEPTH) embedded(id) else null
            return if (found == null) moved else moved.embedding(Stack(found.start, null, id))
        }

        private fun step(stack: Stack, next: DeclarativeGrammar.Next?, matched: String): Stack = when (next) {
            null -> stack
            is DeclarativeGrammar.Next.Enter -> target(next.state, matched, stack)?.let { Stack(it, stack, stack.grammar) } ?: stack
            is DeclarativeGrammar.Next.Switch -> target(next.state, matched, stack)?.let { Stack(it, stack.parent, stack.grammar) } ?: stack
            DeclarativeGrammar.Next.Push -> Stack(stack.state, stack, stack.grammar)
            DeclarativeGrammar.Next.Pop -> stack.parent ?: stack
            DeclarativeGrammar.Next.PopAll -> stack.bottom
        }

        /**
         * The state [name] comes to for [matched] on [stack], or null if the grammar has none. The
         * text being typed can make a name anything, so where Monarch throws, the stack stays put.
         */
        private fun target(name: DeclarativeGrammar.Template, matched: String, stack: Stack): String? =
            name.fill(matched, stack.state).removePrefix("@").takeIf { stateOf(it, language.states.keys) != null }
    }

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

        /**
         * Languages inside languages, past which an id counts as unknown. Each is a call and a search
         * of the line, and a grammar can embed itself, on a match that takes no text if it likes.
         */
        const val MAX_EMBEDDING_DEPTH = 16

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
