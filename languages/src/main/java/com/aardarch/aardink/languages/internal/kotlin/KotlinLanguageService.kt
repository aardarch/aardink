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
package com.aardarch.aardink.languages.internal.kotlin

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.languages.internal.BaseLanguageService

/**
 * In-process language service for Kotlin.
 * Provides lexical completions (keywords, stdlib types/methods, Compose annotations, snippets),
 * syntax error diagnostics, auto-closing, smart indentation, and formatting.
 */
object KotlinLanguageService : BaseLanguageService() {

    override val triggerCharacters: Set<Char> = setOf('.', '(', ':', '@', '<', ' ', '"')

    private val stdlibMethods = listOf(
        "map { }" to "Transforms elements in collection",
        "filter { }" to "Filters elements matching predicate",
        "forEach { }" to "Executes action on each element",
        "let { }" to "Executes block with it as receiver",
        "apply { }" to "Executes block with this as receiver",
        "also { }" to "Executes side-effect block with it",
        "run { }" to "Executes block in scope",
        "firstOrNull()" to "Returns first element or null",
        "count()" to "Returns number of elements",
        "isEmpty()" to "Returns true if empty",
        "isNotEmpty()" to "Returns true if not empty",
    )

    private val commonAnnotations = listOf(
        "Composable",
        "OptIn",
        "Stable",
        "Immutable",
        "Test",
        "Preview",
        "Keep",
        "Deprecated",
    )

    private val keywords = listOf(
        "fun", "val", "var", "class", "sealed interface", "data class", "when", "if", "else",
        "try", "catch", "return", "suspend", "override", "companion object", "private", "public",
    )

    private val types = listOf(
        "String", "Int", "Boolean", "Long", "Float", "Double", "List", "Map", "Set", "Unit", "Any",
    )

    private val snippets = listOf(
        CompletionItem(
            label = "@Composable fun ...",
            kind = CompletionKind.Snippet,
            insertText = "@Composable\nfun MyComposable() {\n    \n}",
            documentation = "Jetpack Compose Composable function snippet",
        ),
        CompletionItem(
            label = "fun ...() { ... }",
            kind = CompletionKind.Snippet,
            insertText = "fun myFunction() {\n    \n}",
            documentation = "Function snippet",
        ),
        CompletionItem(
            label = "when (...) { ... }",
            kind = CompletionKind.Snippet,
            insertText = "when (val result = x) {\n    is String -> {}\n    else -> {}\n}",
            documentation = "When expression snippet",
        ),
    )

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        val text = document.text
        if (text.isBlank()) return emptyList()

        val diags = mutableListOf<Diagnostic>()
        val stack = ArrayDeque<Pair<Char, Int>>()
        var i = 0
        val n = text.length

        while (i < n) {
            val c = text[i]

            // Line comment //
            if (c == '/' && i + 1 < n && text[i + 1] == '/') {
                val nl = text.indexOf('\n', i)
                i = if (nl < 0) n else nl
                continue
            }

            // Block comment /* */ — Kotlin's nest, so the first "*/" need not be the end of one.
            if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                val end = endOfBlockComment(text, i)
                if (end < 0) {
                    val (line, _) = document.offsetToLineCol(i)
                    diags.add(
                        Diagnostic(
                            range = i..n,
                            lineNumber = line,
                            message = "Unterminated block comment /* ... */",
                            severity = DiagnosticSeverity.Error,
                            source = "kotlin",
                        ),
                    )
                    return diags
                }
                i = end
                continue
            }

            // Triple-quoted string """..."""
            if (c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                val end = text.indexOf("\"\"\"", i + 3)
                if (end < 0) {
                    val (line, _) = document.offsetToLineCol(i)
                    diags.add(
                        Diagnostic(
                            range = i..n,
                            lineNumber = line,
                            message = "Unterminated raw string \"\"\" ... \"\"\"",
                            severity = DiagnosticSeverity.Error,
                            source = "kotlin",
                        ),
                    )
                    return diags
                }
                i = end + 3
                continue
            }

            // Single-line string "..."
            if (c == '"') {
                val strStart = i
                i++
                var closed = false
                while (i < n && text[i] != '\n') {
                    if (text[i] == '\\') {
                        i += 2
                        continue
                    }
                    if (text[i] == '"') {
                        closed = true
                        i++
                        break
                    }
                    i++
                }
                if (!closed) {
                    val (line, _) = document.offsetToLineCol(strStart)
                    diags.add(
                        Diagnostic(
                            range = strStart..i,
                            lineNumber = line,
                            message = "Unterminated string literal",
                            severity = DiagnosticSeverity.Error,
                            source = "kotlin",
                        ),
                    )
                }
                continue
            }

            // Character literal '.' — delimiters inside one are data, not brackets ('}' is valid Kotlin).
            if (c == '\'') {
                val end = endOfCharLiteral(text, i)
                if (end > i) {
                    i = end
                    continue
                }
                // Not a literal (an unterminated quote): treat it as an ordinary character rather
                // than reporting an error the delimiter checker has no business reporting.
            }

            // Brackets / Parentheses / Braces matching
            if (c == '{' || c == '(' || c == '[') {
                stack.addLast(c to i)
            } else if (c == '}' || c == ')' || c == ']') {
                val expectedOpen = when (c) {
                    '}' -> '{'
                    ')' -> '('
                    ']' -> '['
                    else -> ' '
                }
                if (stack.isEmpty() || stack.last().first != expectedOpen) {
                    val (line, _) = document.offsetToLineCol(i)
                    diags.add(
                        Diagnostic(
                            range = i..(i + 1),
                            lineNumber = line,
                            message = "Unmatched closing delimiter '$c'",
                            severity = DiagnosticSeverity.Error,
                            source = "kotlin",
                        ),
                    )
                } else {
                    stack.removeLast()
                }
            }
            i++
        }

        // Unclosed delimiters
        for ((openChar, offset) in stack) {
            val (line, _) = document.offsetToLineCol(offset)
            diags.add(
                Diagnostic(
                    range = offset..(offset + 1),
                    lineNumber = line,
                    message = "Unclosed delimiter '$openChar'",
                    severity = DiagnosticSeverity.Error,
                    source = "kotlin",
                ),
            )
        }

        return diags
    }

    /**
     * Index just past the character literal opening at [start], or [start] itself when the quote
     * doesn't open one (an unterminated or empty pair, or a literal running past the line).
     */
    private fun endOfCharLiteral(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length && text[i] != '\n') {
            when (text[i]) {
                '\\' -> i += 2
                '\'' -> return if (i > start + 1) i + 1 else start
                else -> i++
            }
        }
        return start
    }

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val text = document.text
        val clampedOffset = cursorOffset.coerceIn(0, text.length)
        val textBefore = text.take(clampedOffset).trimEnd()

        // 1. Dot completions
        if (textBefore.endsWith(".")) {
            return stdlibMethods.map { (method, doc) ->
                CompletionItem(
                    label = method,
                    kind = CompletionKind.Transform,
                    insertText = method,
                    documentation = doc,
                )
            }
        }

        // 2. Annotation completions
        if (textBefore.endsWith("@")) {
            return commonAnnotations.map { ann ->
                CompletionItem(
                    label = ann,
                    kind = CompletionKind.Element,
                    insertText = ann,
                    documentation = "@$ann annotation",
                )
            }
        }

        // 3. Keywords & stdlib types
        val items = mutableListOf<CompletionItem>()
        items.addAll(
            keywords.map { kw ->
                CompletionItem(
                    label = kw,
                    kind = CompletionKind.Value,
                    insertText = kw,
                )
            },
        )
        items.addAll(
            types.map { type ->
                CompletionItem(
                    label = type,
                    kind = CompletionKind.Element,
                    insertText = type,
                )
            },
        )
        items.addAll(snippets)

        return items
    }

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = when (charTyped) {
        '{' -> "}"
        '(' -> ")"
        '[' -> "]"
        '"' -> "\""
        else -> null
    }

    override fun smartIndent(document: CodeDocument, lineIndex: Int): Int {
        if (lineIndex <= 0) return 0
        val prevLine = document.lineText(lineIndex - 1)
        val prevIndent = prevLine.takeWhile { it.isWhitespace() }.length
        val trimmedPrev = prevLine.trim()
        val currLine = document.lineText(lineIndex).trim()

        var indent = prevIndent
        if (trimmedPrev.endsWith("{") || trimmedPrev.endsWith("(") || trimmedPrev.endsWith("=") || trimmedPrev.endsWith("->") ||
            trimmedPrev == "when"
        ) {
            indent += 4
        }
        if (currLine.startsWith("}") || currLine.startsWith(")")) {
            indent = (indent - 4).coerceAtLeast(0)
        }
        return indent
    }

    override suspend fun format(document: CodeDocument): String {
        val text = document.text
        if (text.isBlank()) return text

        val lines = text.lines()
        val states = lineStates(lines)
        val formatted = mutableListOf<String>()
        var depth = 0

        for ((index, line) in lines.withIndex()) {
            val state = states[index]
            // Whitespace inside a raw string is data — re-indenting it changes the program.
            if (state.touchesRawString) {
                formatted.add(line)
                continue
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                formatted.add("")
                continue
            }

            // Structure comes from the code on the line, so a brace in a comment or a string
            // literal ("// {", "val s = \"{\"") no longer shifts everything below it.
            if (state.code.startsWith("}") || state.code.startsWith(")")) {
                depth = (depth - 1).coerceAtLeast(0)
            }

            formatted.add(" ".repeat(depth * 4) + trimmed)

            if (state.code.endsWith("{") || state.code.endsWith("(")) {
                depth++
            }
        }

        return formatted.joinToString("\n")
    }

    /**
     * How a line relates to the literals and comments around it.
     *
     * @param touchesRawString Any part of the line lies inside a `"""` string, whose leading and
     *   trailing whitespace is data and must survive formatting verbatim.
     * @param code The line with comments and string contents removed, trimmed — what the indenter
     *   may draw structure from.
     */
    private data class KotlinLineState(val touchesRawString: Boolean, val code: String)

    /**
     * Classifies every line of the document in one pass, tracking raw strings, block comments,
     * line comments and single-line strings across line boundaries.
     */
    private fun lineStates(lines: List<String>): List<KotlinLineState> {
        val states = ArrayList<KotlinLineState>(lines.size)
        var inRawString = false
        var blockCommentDepth = 0

        for (line in lines) {
            val startedInRawString = inRawString
            var openedRawString = false
            val code = StringBuilder()
            var i = 0

            while (i < line.length) {
                when {
                    inRawString -> {
                        val close = line.indexOf(RAW_STRING_QUOTE, i)
                        if (close < 0) {
                            i = line.length
                        } else {
                            inRawString = false
                            i = close + RAW_STRING_QUOTE.length
                        }
                    }

                    // Depth, not a flag: Kotlin block comments nest, so an inner "*/" closes only
                    // the comment it opened and the text after it is still comment.
                    blockCommentDepth > 0 -> when {
                        line.startsWith("/*", i) -> {
                            blockCommentDepth++
                            i += 2
                        }

                        line.startsWith("*/", i) -> {
                            blockCommentDepth--
                            i += 2
                        }

                        else -> i++
                    }

                    line.startsWith("//", i) -> i = line.length

                    line.startsWith("/*", i) -> {
                        blockCommentDepth++
                        i += 2
                    }

                    line.startsWith(RAW_STRING_QUOTE, i) -> {
                        inRawString = true
                        openedRawString = true
                        i += RAW_STRING_QUOTE.length
                    }

                    line[i] == '"' || line[i] == '\'' -> {
                        // Keep the quotes, drop the contents: the literal is a token to the
                        // indenter but its braces are not structure.
                        val quote = line[i]
                        code.append(quote)
                        val end = endOfSingleLineLiteral(line, i)
                        if (end > i + 1) code.append(quote)
                        i = end
                    }

                    else -> {
                        code.append(line[i])
                        i++
                    }
                }
            }

            states.add(KotlinLineState(startedInRawString || openedRawString, code.toString().trim()))
        }
        return states
    }

    /**
     * Index just past the block comment opening at [start] in [text], or -1 when it never closes.
     *
     * Kotlin block comments nest: an opener inside one starts a second comment that the next
     * terminator closes, leaving the outer one still open. The scan therefore counts depth
     * rather than stopping at the first terminator, which would hand the rest of the outer
     * comment to the delimiter checker as if it were code.
     */
    private fun endOfBlockComment(text: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }

                text.startsWith("*/", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                }

                else -> i++
            }
        }
        return -1
    }

    /** Index just past the single-line string or char literal opening at [start], or the line end. */
    private fun endOfSingleLineLiteral(line: String, start: Int): Int {
        val quote = line[start]
        var i = start + 1
        while (i < line.length) {
            when {
                line[i] == '\\' -> i += 2
                line[i] == quote -> return i + 1
                else -> i++
            }
        }
        return line.length
    }

    private const val RAW_STRING_QUOTE = "\"\"\""
}
