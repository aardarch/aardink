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
package com.aardarch.aardink.languages.internal.json

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.languages.internal.BaseLanguageService

/**
 * Hand-written JSON validator. Reports the first syntax error as a [Diagnostic] with character-
 * range precision. Validation only — no completions, no formatting in v1.
 */
object JsonLanguageService : BaseLanguageService() {

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        val text = document.text
        if (text.isBlank()) return emptyList()
        val parser = JsonParser(text)
        val diag = parser.parse() ?: return emptyList()
        return listOf(diag.toDiagnostic(document))
    }

    private data class ParseError(val start: Int, val end: Int, val message: String) {
        fun toDiagnostic(document: CodeDocument): Diagnostic {
            val (line, _) = document.offsetToLineCol(start)
            return Diagnostic(
                range = start..end,
                lineNumber = line,
                message = message,
                severity = DiagnosticSeverity.Error,
                source = "json",
            )
        }
    }

    private class JsonParser(val text: String) {
        var pos = 0

        fun parse(): ParseError? {
            return try {
                skipWs()
                if (pos >= text.length) return ParseError(0, 0, "Empty JSON document")
                parseValue()
                skipWs()
                if (pos < text.length) {
                    return ParseError(pos, (pos + 1).coerceAtMost(text.length), "Unexpected trailing content")
                }
                null
            } catch (e: ParseFail) {
                ParseError(e.start, e.end, e.description)
            }
        }

        private fun parseValue() {
            skipWs()
            if (pos >= text.length) fail("Expected a JSON value")
            when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true")
                'f' -> parseLiteral("false")
                'n' -> parseLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> fail("Unexpected character '$c'")
            }
        }

        private fun parseObject() {
            expect('{')
            skipWs()
            if (peek() == '}') {
                pos++
                return
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("Expected a string key")
                parseString()
                skipWs()
                if (peek() != ':') fail("Expected ':' after object key")
                pos++
                parseValue()
                skipWs()
                when (peek()) {
                    ',' -> {
                        pos++
                        skipWs()
                        if (peek() == '}') fail("Trailing comma in object")
                    }

                    '}' -> {
                        pos++
                        return
                    }

                    null -> fail("Unterminated object")

                    else -> fail("Expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray() {
            expect('[')
            skipWs()
            if (peek() == ']') {
                pos++
                return
            }
            while (true) {
                parseValue()
                skipWs()
                when (peek()) {
                    ',' -> {
                        pos++
                        skipWs()
                        if (peek() == ']') fail("Trailing comma in array")
                    }

                    ']' -> {
                        pos++
                        return
                    }

                    null -> fail("Unterminated array")

                    else -> fail("Expected ',' or ']' in array")
                }
            }
        }

        private fun parseString() {
            val start = pos
            expect('"')
            while (pos < text.length) {
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return
                    }

                    '\\' -> {
                        pos++
                        if (pos >= text.length) fail("Unterminated escape sequence", start)
                        val esc = text[pos]
                        if (esc == 'u') {
                            if (pos + 4 >= text.length) fail("Invalid \\u escape", start)
                            for (k in 1..4) {
                                if (!isHex(text[pos + k])) fail("Invalid \\u escape", start)
                            }
                            pos += 5
                        } else {
                            if (esc !in "\"\\/bfnrt") fail("Invalid escape \\$esc", pos - 1)
                            pos++
                        }
                    }

                    '\n' -> fail("Unterminated string", start)

                    else -> if (c.code < 0x20) {
                        fail("Unescaped control character in string", pos)
                    } else {
                        pos++
                    }
                }
            }
            fail("Unterminated string", start)
        }

        private fun parseNumber() {
            val start = pos
            if (peek() == '-') pos++
            if (pos >= text.length || !text[pos].isDigit()) fail("Invalid number", start)
            if (text[pos] == '0') {
                pos++
            } else {
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                if (pos >= text.length || !text[pos].isDigit()) fail("Invalid number (digit expected after '.')", start)
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || !text[pos].isDigit()) fail("Invalid number (exponent expected)", start)
                while (pos < text.length && text[pos].isDigit()) pos++
            }
        }

        private fun parseLiteral(literal: String) {
            val start = pos
            if (pos + literal.length > text.length || text.substring(pos, pos + literal.length) != literal) {
                fail("Expected '$literal'", start, start + 1)
            }
            pos += literal.length
        }

        private fun expect(c: Char) {
            if (pos >= text.length || text[pos] != c) fail("Expected '$c'")
            pos++
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun isHex(c: Char): Boolean = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

        private fun fail(message: String, start: Int = pos, end: Int = (start + 1).coerceAtMost(text.length)): Nothing =
            throw ParseFail(start, end, message)

        private class ParseFail(val start: Int, val end: Int, val description: String) : RuntimeException()
    }
}
