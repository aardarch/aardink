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
package com.aardarch.aardink.languages.internal

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.languages.LanguageConfiguration
import com.aardarch.aardink.languages.LanguageConfiguration.AutoClosingPair
import com.aardarch.aardink.languages.LanguageConfiguration.CharacterPair
import com.aardarch.aardink.languages.LanguageConfiguration.EnterAction
import com.aardarch.aardink.languages.LanguageConfiguration.IndentAction
import com.aardarch.aardink.languages.LanguageConfiguration.IndentationRules

/** [base] with the [commentSyntax] a [LanguageConfiguration] gave, as comments live on the tokenizer. */
internal class CommentedTokenizer(private val base: IncrementalTokenizer, override val commentSyntax: CommentSyntax) :
    IncrementalTokenizer by base

/** [service] with [configuration] over it, or the configuration alone without one. */
internal fun configuredService(configuration: LanguageConfiguration, comments: CommentSyntax?, service: LanguageService?): LanguageService {
    val configured = ConfigurationLanguageService(configuration, comments)
    return if (service == null) configured else ConfiguredLanguageService(configured, service)
}

/**
 * What a [LanguageConfiguration] says about typing. Everything here runs on the main thread at a
 * keystroke, so it reads the line at hand and at most [LOOK_BACK] lines above it. [comments] tell
 * [AutoClosingPair]'s line scan where comments are.
 */
internal class ConfigurationLanguageService(private val configuration: LanguageConfiguration, private val comments: CommentSyntax?) :
    BaseLanguageService() {

    private val closingPairs = configuration.autoClosingPairs.filter { it.open.isNotEmpty() && it.close.isNotEmpty() }

    /** The pairs by the character that completes their opening, the longest opening first. */
    private val opened: Map<Char, List<AutoClosingPair>> = closingPairs.sortedByDescending { it.open.length }.groupBy { it.open.last() }

    /** What delimits a string, for the line scan: the pairs that open and close with one same character. */
    private val stringQuotes: Set<Char> = closingPairs.filter {
        it.open == it.close && it.open.length == 1
    }.mapTo(HashSet()) { it.open.single() }

    /** What the language closes with, besides quotes, which open as much as they close. */
    private val closingStarts: Set<Char> = closingPairs.filter { it.open != it.close }.mapTo(HashSet()) { it.close.first() }

    private val surrounds: Map<Char, String> =
        configuration.surroundingPairs.filter { it.open.length == 1 && it.close.isNotEmpty() }.associate { it.open.single() to it.close }

    /** The pair whose opening [charTyped] at [offset] (already in [document]) completes, if any. */
    fun pairAt(document: CodeDocument, offset: Int, charTyped: Char): AutoClosingPair? {
        val candidates = opened[charTyped] ?: return null
        val (line, column) = document.offsetToLineCol(offset)
        val text = lineOf(document, line)
        return candidates.firstOrNull { column + 1 >= it.open.length && text.startsWith(it.open, column + 1 - it.open.length) }
    }

    /** What closes [pair], just opened at [offset], or null where it stays open. */
    fun closing(document: CodeDocument, offset: Int, pair: AutoClosingPair): String? {
        val (line, column) = document.offsetToLineCol(offset)
        val text = lineOf(document, line)
        val caret = column + 1
        val openedAt = caret - pair.open.length
        // `/**` typed inside the `/* */` that `/*` closed already has its end.
        if (pair.open.length > 1 && text.startsWith(pair.close, caret)) return null
        val quote = pair.open.length == 1 && pair.open[0] in QUOTES
        // Not the apostrophe in `don't`.
        if (quote && openedAt > 0 && text[openedAt - 1].isWordCharacter()) return null
        val next = text.getOrNull(caret)
        if (next != null && next !in closingStarts && next !in (if (quote) CLOSE_BEFORE_QUOTE else CLOSE_BEFORE_BRACKET)) return null
        if (pair.notInString || pair.notInComment) {
            val place = placeIn(text, openedAt)
            if ((pair.notInString && place == Place.String) || (pair.notInComment && place == Place.Comment)) return null
        }
        return pair.close
    }

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? =
        pairAt(document, offset, charTyped)?.let { closing(document, offset, it) }

    override fun surroundingClose(typed: Char): String? = surrounds[typed]

    /** Alone, a line keeps the indentation of the one above when nothing here says otherwise. */
    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = when {
        lineIndex <= 0 -> ""
        else -> enterIndent(document, lineIndex, options) ?: options.indentAfter(lineOf(document, lineIndex - 1))
    }

    /** What new line [lineIndex] starts with by the rules, or null when none of them decides. */
    fun enterIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String? {
        if (lineIndex <= 0) return null
        val before = lineOf(document, lineIndex - 1)
        val after = lineOf(document, lineIndex)
        val previous = if (lineIndex >= 2) lineOf(document, lineIndex - 2) else ""
        val action = configuration.onEnterRules.firstOrNull {
            it.beforeText.finds(before) && it.afterText.finds(after) && it.previousLineText.finds(previous)
        }?.action ?: bracketAction(before, after)
        if (action != null) return applied(action, before, options)
        return configuration.indentationRules?.let { indentByRules(it, document, lineIndex, after, options) }
    }

    /** Between a pair the closer stays put; after an opening, the line goes in. Line ends and starts only. */
    private fun bracketAction(before: String, after: String): EnterAction? {
        val opening = configuration.brackets.filter { before.endsWithOpening(it) }
        if (opening.isEmpty()) return null
        val between = opening.any { after.startsWithClosing(it) }
        return EnterAction(if (between) IndentAction.IndentOutdent else IndentAction.Indent)
    }

    private fun applied(action: EnterAction, before: String, options: FormattingOptions): String {
        val indentation = before.takeWhile { it == ' ' || it == '\t' }.dropLast(action.removeText.coerceAtLeast(0))
        val levels = when (action.indent) {
            IndentAction.Indent -> 1
            IndentAction.Outdent -> -1
            IndentAction.None, IndentAction.IndentOutdent -> 0
        }
        val appended = if (action.indent == IndentAction.IndentOutdent) "" else action.appendText
        return options.indentAfter(indentation, levels) + appended
    }

    /** VS Code's reading of `indentationRules`, for the new line only, or null when none applies. */
    private fun indentByRules(
        rules: IndentationRules,
        document: CodeDocument,
        lineIndex: Int,
        after: String,
        options: FormattingOptions,
    ): String? {
        val oldest = (lineIndex - 1 - LOOK_BACK).coerceAtLeast(0)
        var reference = lineIndex - 1
        while (reference > oldest && rules.unIndentedLine.matches(lineOf(document, reference))) reference--
        val text = lineOf(document, reference)
        val goesIn = rules.increase.matches(text) || rules.indentNextLine.matches(text)
        var anchor = reference
        // The line after `if (x)` on its own: back to where that started.
        if (!goesIn && !rules.decrease.matches(text)) {
            while (anchor > oldest && rules.isOneLineIndenter(lineOf(document, anchor - 1))) anchor--
        }
        val comesBack = rules.decrease.matches(after)
        if (!goesIn && !comesBack && anchor == lineIndex - 1) return null
        return options.indentAfter(lineOf(document, anchor), (if (goesIn) 1 else 0) - (if (comesBack) 1 else 0))
    }

    private fun IndentationRules.isOneLineIndenter(line: String): Boolean =
        indentNextLine.matches(line) && !increase.matches(line) && !decrease.matches(line)

    /** Where [end] on [line] is, by a scan of the line: an approximation, with no tokens to go on. */
    private fun placeIn(line: String, end: Int): Place {
        val lineComment = comments?.line
        val blockStart = comments?.blockStart
        val blockEnd = comments?.blockEnd
        var i = 0
        while (i < end) {
            val c = line[i]
            when {
                lineComment != null && line.startsWith(lineComment, i) -> return Place.Comment

                blockStart != null && blockEnd != null && line.startsWith(blockStart, i) -> {
                    val close = line.indexOf(blockEnd, i + blockStart.length)
                    if (close < 0 || close + blockEnd.length > end) return Place.Comment
                    i = close + blockEnd.length
                }

                c in stringQuotes -> {
                    i++
                    while (i < end && line[i] != c) i += if (line[i] == '\\') 2 else 1
                    if (i >= end) return Place.String
                    i++
                }

                else -> i++
            }
        }
        return Place.Code
    }

    private enum class Place { Code, String, Comment }

    private fun String.endsWithOpening(pair: CharacterPair): Boolean {
        val text = trimEnd()
        if (!text.endsWith(pair.open)) return false
        val before = text.getOrNull(text.length - pair.open.length - 1)
        return !(pair.open.first().isWordCharacter() && before?.isWordCharacter() == true)
    }

    private fun String.startsWithClosing(pair: CharacterPair): Boolean {
        val text = trimStart()
        if (!text.startsWith(pair.close)) return false
        val next = text.getOrNull(pair.close.length)
        return !(pair.close.last().isWordCharacter() && next?.isWordCharacter() == true)
    }

    private fun Char.isWordCharacter(): Boolean = isLetterOrDigit() || this == '_'

    /** No regex means no condition. */
    private fun Regex?.finds(text: String): Boolean = this == null || containsMatchIn(text)

    /** No regex means no match. */
    private fun Regex?.matches(text: String): Boolean = this != null && containsMatchIn(text)

    /** Line [line], without the carriage return a CR LF document keeps on it. */
    private fun lineOf(document: CodeDocument, line: Int): String = document.lineText(line).removeSuffix("\r")

    private companion object {
        /** How many lines [indentByRules] reads above the new one, at most. */
        const val LOOK_BACK = 32

        const val QUOTES = "'\"`"

        /** VS Code's `autoCloseBefore` default for quotes: a quote closes before these or at the line's end. */
        const val CLOSE_BEFORE_QUOTE = ";:.,=}])> \t"

        /** VS Code's `autoCloseBefore` default for brackets. */
        const val CLOSE_BEFORE_BRACKET = "'\"`;:.,=}])> \t"
    }
}

/**
 * A language's service under its configuration: the configuration decides what it has a rule for,
 * including deciding against ("no closing here" and "no indent here" are answers), and [fallback]
 * answers the rest. It leaves completions alone, so [fallback]'s `completionsAreIncomplete` holds.
 */
internal class ConfiguredLanguageService(private val configured: ConfigurationLanguageService, base: LanguageService) :
    CompositeLanguageService(configured, base) {

    override val completionsAreIncomplete: Boolean
        get() = fallback.completionsAreIncomplete

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> =
        fallback.completions(document, cursorOffset)

    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String =
        configured.enterIndent(document, lineIndex, options) ?: fallback.smartIndent(document, lineIndex, options)

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? {
        val pair = configured.pairAt(document, offset, charTyped) ?: return fallback.autoClose(document, offset, charTyped)
        return configured.closing(document, offset, pair)
    }
}
