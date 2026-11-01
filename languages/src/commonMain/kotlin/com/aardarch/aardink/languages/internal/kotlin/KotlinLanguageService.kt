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
import com.aardarch.aardink.core.DocumentSymbol
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.SymbolKind
import com.aardarch.aardink.languages.internal.BaseLanguageService
import com.aardarch.aardink.languages.internal.diagnosticsPacer

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
            insertText = "@Composable\nfun \${1:MyComposable}(\$2) {\n\t\$0\n}",
            documentation = "Jetpack Compose Composable function snippet",
            isSnippet = true,
        ),
        CompletionItem(
            label = "fun ...() { ... }",
            kind = CompletionKind.Snippet,
            insertText = "fun \${1:myFunction}(\$2) {\n\t\$0\n}",
            documentation = "Function snippet",
            isSnippet = true,
        ),
        CompletionItem(
            label = "when (...) { ... }",
            kind = CompletionKind.Snippet,
            insertText = "when (\${1:val result = x}) {\n\tis \${2:String} -> {\$3}\n\telse -> {\$0}\n}",
            documentation = "When expression snippet",
            isSnippet = true,
        ),
    )

    /**
     * Classes, interfaces, objects, functions and properties, from the declarations at the start
     * of a line, nested by the braces that follow them. Local declarations inside a function body
     * are left out, as VS Code's outline leaves them out.
     */
    override suspend fun documentSymbols(document: CodeDocument): List<DocumentSymbol> {
        val lineCount = document.lineCount
        if (lineCount == 0 || document.length == 0) return emptyList()
        val lines = (0 until lineCount).map(document::lineText)
        val states = lineStates(lines)
        val pacer = diagnosticsPacer()

        class Open(val name: String, val kind: SymbolKind, val start: Int, val nameRange: IntRange, val depth: Int, val detail: String?) {
            val children = ArrayList<DocumentSymbol>()

            fun build(last: Int) = DocumentSymbol(name, kind, start..maxOf(start, last), nameRange, detail, children)
        }
        val top = ArrayList<DocumentSymbol>()
        val stack = ArrayList<Open>()
        var depth = 0

        fun add(symbol: DocumentSymbol) {
            (stack.lastOrNull()?.children ?: top) += symbol
        }

        // The declaration whose header is being read: until its parameter list's parens close, and
        // then its body's brace (if it has one) makes it a container, it is neither on the stack nor
        // done. A multi-line constructor or signature keeps it here across lines.
        var pending: Open? = null
        var parens = 0

        fun settle(last: Int) {
            pending?.let { add(it.build(last)) }
            pending = null
        }

        for (i in 0 until lineCount) {
            pacer?.onProgress(i)
            val state = states[i]
            val code = state.code
            val lineStart = document.lineStart(i)
            val lineLast = document.lineEnd(i) - 1
            val parent = stack.lastOrNull()
            val atDeclarationLevel = depth == (parent?.depth?.plus(1) ?: 0) &&
                parent?.kind != SymbolKind.Function && parent?.kind != SymbolKind.Method
            var match = if (atDeclarationLevel && code.isNotEmpty()) DECLARATION.find(code) else null
            if (match != null && pending != null) {
                // Only a `val` or `var` starts a line of a parameter list. Anything else means the
                // header's parens were never closed (it is being typed): it ends on the line before.
                if (match.groupValues[2] == "val" || match.groupValues[2] == "var") match = null else settle(document.lineEnd(i - 1) - 1)
            }
            if (match != null) {
                val keyword = match.groupValues[2]
                val written = match.groupValues[3]
                val name = written.trim('`').ifEmpty { if (keyword == "object") "Companion" else "" }
                if (name.isNotEmpty()) {
                    val modifiers = match.groupValues[1]
                    val inType = parent?.kind in TYPE_KINDS
                    val kind = when {
                        keyword == "fun" -> if (inType) SymbolKind.Method else SymbolKind.Function
                        keyword == "val" && "const" in modifiers -> SymbolKind.Constant
                        keyword == "val" || keyword == "var" -> if (inType) SymbolKind.Property else SymbolKind.Variable
                        keyword == "interface" -> SymbolKind.Interface
                        keyword == "object" -> SymbolKind.Object
                        "enum" in modifiers -> SymbolKind.Enum
                        else -> SymbolKind.Class
                    }
                    // The name is the match's last group, so it ends where the match does; a nameless
                    // companion is selected by its keyword. Mapped back to the line's own columns.
                    val matchEnd = match.range.last + 1
                    val nameAt = if (written.isNotEmpty()) matchEnd - written.length else code.lastIndexOf(keyword, matchEnd - 1)
                    val nameLength = if (written.isNotEmpty()) written.length else keyword.length
                    val nameRange = (lineStart + state.columns[nameAt]) until (lineStart + state.columns[nameAt + nameLength - 1] + 1)
                    pending = Open(name, kind, lineStart + state.columns[0], nameRange, depth, null)
                    parens = 0
                }
            }
            // The brackets on the line, after its comments and strings are gone: a block opened by a
            // declaration's header makes it a container until the block closes.
            for (c in code) {
                when (c) {
                    '(' -> parens++

                    ')' -> parens--

                    '{' -> {
                        val header = pending
                        if (header != null && parens <= 0 && depth == header.depth) {
                            stack += header
                            pending = null
                        }
                        depth++
                    }

                    '}' -> {
                        depth = (depth - 1).coerceAtLeast(0)
                        // Its parent's block closed around an unfinished header.
                        pending?.let { if (depth < it.depth) settle(lineLast) }
                        while (stack.isNotEmpty() && stack.last().depth >= depth) {
                            val closed = stack.removeAt(stack.size - 1)
                            add(closed.build(lineLast))
                        }
                    }
                }
            }
            // A header that ends on this line without a body is a declaration of this line alone.
            if (parens <= 0) settle(lineLast)
        }
        settle(document.length - 1)
        while (stack.isNotEmpty()) add(stack.removeAt(stack.size - 1).build(document.length - 1))
        return top
    }

    private val TYPE_KINDS = setOf(SymbolKind.Class, SymbolKind.Interface, SymbolKind.Object, SymbolKind.Enum)

    /**
     * A declaration at the start of a line: its modifiers and annotations, its keyword, and its name
     * (after any type parameters and receiver). A `companion object` may have no name.
     */
    private val DECLARATION = Regex(
        """^((?:(?:@[\w.]+(?:\([^)]*\))?|public|private|protected|internal|open|abstract|sealed|data|enum|annotation|inner|""" +
            """override|suspend|inline|operator|infix|tailrec|external|const|lateinit|value|companion|actual|expect|""" +
            """fun(?=\s+interface))\s+)*)""" +
            """(class|interface|object|fun|val|var|typealias)\b\s*(?:<[^>]*>\s*)?(?:[\w<>?, ]+\.)?(`[^`]+`|\w*)""",
    )

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        val text = document.text
        if (text.isBlank()) return emptyList()

        val diags = mutableListOf<Diagnostic>()
        val stack = ArrayDeque<Pair<Char, Int>>()
        var i = 0
        val n = text.length
        val pacer = diagnosticsPacer()

        while (i < n) {
            pacer?.onProgress(i)
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

    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String {
        if (lineIndex <= 0) return ""
        val prevLine = document.lineText(lineIndex - 1)
        val trimmedPrev = prevLine.trim()
        val currLine = document.lineText(lineIndex).trim()

        var levels = 0
        if (trimmedPrev.endsWith("{") || trimmedPrev.endsWith("(") || trimmedPrev.endsWith("=") || trimmedPrev.endsWith("->") ||
            trimmedPrev == "when"
        ) {
            levels++
        }
        if (currLine.startsWith("}") || currLine.startsWith(")")) levels--
        return options.indentAfter(prevLine, levels)
    }

    override suspend fun format(document: CodeDocument, options: FormattingOptions): String {
        val text = document.text
        if (text.isBlank()) return text

        val lines = text.lines()
        val states = lineStates(lines)
        val formatted = mutableListOf<String>()
        var depth = 0

        for ((index, line) in lines.withIndex()) {
            val state = states[index]
            // Whitespace inside a raw string is data — re-indenting it changes the program. The
            // line's own delimiters still count, though: `fun f() { val s = """` opens a block, and
            // skipping the balance left everything after the closing quotes at column 0.
            // `state.code` already excludes the string's contents, so it is safe to read here.
            if (state.touchesRawString) {
                formatted.add(line)
                val (rawNet, rawLeadingCloses) = delimiterBalance(state.code)
                depth = (depth - rawLeadingCloses).coerceAtLeast(0) + rawNet + rawLeadingCloses
                depth = depth.coerceAtLeast(0)
                continue
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                formatted.add("")
                continue
            }

            // Structure comes from the code on the line, so a brace in a comment or a string
            // literal ("// {", "val s = \"{\"") no longer shifts everything below it. Every
            // delimiter counts, not just the first and last: `fun f() { if (x) {` opens two
            // blocks, and a line starting `})` closes two.
            val (net, leadingCloses) = delimiterBalance(state.code)
            val lineDepth = (depth - leadingCloses).coerceAtLeast(0)
            formatted.add(options.indentOf(lineDepth * options.tabSize) + trimmed)
            depth = (lineDepth + net + leadingCloses).coerceAtLeast(0)
        }

        return formatted.joinToString("\n")
    }

    /**
     * The indentation effect of the delimiters in [code], which is a line with its comments and
     * string contents already stripped.
     *
     * [net] is openers minus closers across the line; [leadingCloses] counts only the closers
     * before the first opener, which is how far the line itself is outdented. Brackets count
     * alongside braces and parentheses, so a multi-line list literal indents like a block.
     */
    private data class DelimiterBalance(val net: Int, val leadingCloses: Int)

    private fun delimiterBalance(code: String): DelimiterBalance {
        var net = 0
        var leadingCloses = 0
        var seenOpener = false
        for (c in code) {
            when (c) {
                '{', '(', '[' -> {
                    net++
                    seenOpener = true
                }

                '}', ')', ']' -> {
                    net--
                    if (!seenOpener) leadingCloses++
                }
            }
        }
        return DelimiterBalance(net, leadingCloses)
    }

    /**
     * How a line relates to the literals and comments around it.
     *
     * @param touchesRawString Any part of the line lies inside a `"""` string, whose leading and
     *   trailing whitespace is data and must survive formatting verbatim.
     * @param code The line with comments and string contents removed, trimmed — what the indenter
     *   may draw structure from.
     * @param columns The column in the line each character of [code] came from.
     */
    private class KotlinLineState(val touchesRawString: Boolean, val code: String, val columns: IntArray)

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
            val columns = IntArray(line.length)
            var i = 0

            // Each character kept comes from one column; a literal keeps no more than it spans.
            fun keep(c: Char, column: Int) {
                columns[code.length] = column
                code.append(c)
            }

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

                    line[i] == '\'' -> {
                        // The same rule as diagnostics(): a lone apostrophe — `it's` in a
                        // backtick-quoted name — opens no literal, so it must not swallow the
                        // braces on the rest of the line.
                        val end = endOfCharLiteral(line, i)
                        if (end == i) {
                            keep('\'', i)
                            i++
                        } else {
                            keep('\'', i)
                            keep('\'', end - 1)
                            i = end
                        }
                    }

                    line[i] == '"' -> {
                        // Keep the quotes, drop the contents: the literal is a token to the
                        // indenter but its braces are not structure.
                        keep('"', i)
                        val end = endOfSingleLineLiteral(line, i)
                        if (end > i + 1) keep('"', end - 1)
                        i = end
                    }

                    else -> {
                        keep(line[i], i)
                        i++
                    }
                }
            }

            // Trimmed as the indenter wants it, the columns along with it.
            val first = code.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) code.length else it }
            val end = maxOf(first, code.indexOfLast { !it.isWhitespace() } + 1)
            states.add(KotlinLineState(startedInRawString || openedRawString, code.substring(first, end), columns.copyOfRange(first, end)))
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
