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

/**
 * Marker interface for token classification. Language packages extend this by adding their own
 * `object` or `data class` implementations, including across module boundaries.
 *
 * Built-in implementations cover common programming constructs. Language-specific token types
 * are defined in their own language package — e.g. the bundled `aardink-languages` module
 * extends this interface for XML attribute-name/value distinctions, JSON property keys, and so on.
 */
interface TokenType {
    /** Unstyled text (default color). */
    data object Default : TokenType

    /** Language keywords. */
    data object Keyword : TokenType

    /** Operators (+, -, ==, …). */
    data object Operator : TokenType

    /** Structural punctuation (braces, brackets, angle brackets, …). */
    data object Punctuation : TokenType

    /** String or character literals. */
    data object StringLiteral : TokenType

    /** Line or block comments. */
    data object Comment : TokenType

    /** Numeric literals. */
    data object Number : TokenType

    /** Identifiers (variable names, function names, …). */
    data object Identifier : TokenType

    /** Type names / class names. */
    data object TypeName : TokenType

    /** Function or method names at call sites. */
    data object FunctionCall : TokenType

    /** Annotation or decorator tokens (@Something). */
    data object Annotation : TokenType

    /** Error / invalid token — used to underline unrecognised content. */
    data object Invalid : TokenType
}
