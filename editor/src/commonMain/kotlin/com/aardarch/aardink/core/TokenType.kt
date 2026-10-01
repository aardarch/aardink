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
 * What a token is, for its colour and for what the editor does inside it. Language packages add
 * their own `object` or `data class` implementations, including across module boundaries, or use
 * [NamedTokenType] for a dotted name.
 *
 * Each type has a [scope]: its name as Monaco names tokens (`keyword`, `comment.doc`,
 * `string.escape`), most general part first. A theme colours a type by its own entry in
 * [EditorTheme.tokenColors], or else by the longest dotted prefix of its scope that has one, as
 * Monaco's themes do: `comment.doc` takes `comment`'s colour unless the theme gives it its own.
 */
interface TokenType {
    /**
     * The type's dotted name, as Monaco's (`keyword.flow`, `comment.doc`), for theme colours by
     * prefix. Empty for a type that is coloured only by its own entry.
     */
    val scope: String get() = ""

    /** Unstyled text (default color). */
    data object Default : TokenType

    /** Language keywords. */
    data object Keyword : TokenType {
        override val scope: String get() = "keyword"
    }

    /** Operators (+, -, ==, …). */
    data object Operator : TokenType {
        override val scope: String get() = "operator"
    }

    /** Structural punctuation (braces, brackets, angle brackets, …). */
    data object Punctuation : TokenType {
        override val scope: String get() = "delimiter"
    }

    /** String or character literals. */
    data object StringLiteral : TokenType {
        override val scope: String get() = "string"
    }

    /** Line or block comments. */
    data object Comment : TokenType {
        override val scope: String get() = "comment"
    }

    /** Numeric literals. */
    data object Number : TokenType {
        override val scope: String get() = "number"
    }

    /** Identifiers (variable names, function names, …). */
    data object Identifier : TokenType {
        override val scope: String get() = "identifier"
    }

    /** Type names / class names. */
    data object TypeName : TokenType {
        override val scope: String get() = "type"
    }

    /** Function or method names at call sites. */
    data object FunctionCall : TokenType {
        override val scope: String get() = "function"
    }

    /** Annotation or decorator tokens (@Something). */
    data object Annotation : TokenType {
        override val scope: String get() = "annotation"
    }

    /** Error / invalid token — used to underline unrecognised content. */
    data object Invalid : TokenType {
        override val scope: String get() = "invalid"
    }
}

/**
 * A token type by name: `keyword.flow`, `comment.doc`, `tag.aardflex`, `attribute.name`. Grammars
 * ([com.aardarch.aardink.languages.DeclarativeGrammar]) and the built-in languages use it for what
 * the built-in types do not name. A theme colours it by its own entry in
 * [EditorTheme.tokenColors], or by the longest dotted prefix of [name] that has one ([scope]).
 *
 * A name with a `comment` or `string` part (`comment.doc`, `string.escape.json`) is a comment or a
 * string to the editor, as in Monaco: no bracket colours or matching inside it.
 */
data class NamedTokenType(val name: String) : TokenType {
    override val scope: String get() = name

    /** Whether one of the name's parts says comment, string or regular expression. */
    internal val isCommentOrString: Boolean = name.split('.').any { it in COMMENT_OR_STRING_PARTS }

    override fun toString(): String = "NamedTokenType($name)"
}

/** The name parts Monaco treats as a comment, a string, or a regular expression (a kind of string). */
private val COMMENT_OR_STRING_PARTS = setOf("comment", "string", "regex", "regexp")

/** A comment or a string (Monaco's standard token types): bracket colours and matching skip it. */
internal val TokenType.isCommentOrString: Boolean
    get() = when (this) {
        TokenType.Comment, TokenType.StringLiteral -> true
        is NamedTokenType -> isCommentOrString
        else -> scope.isNotEmpty() && scope.split('.').any { it in COMMENT_OR_STRING_PARTS }
    }

/**
 * How a token's text is set besides its colour: Monaco's `fontStyle` (`"italic bold"`). [None]
 * is an explicit "none of them", which a theme gives to stop a more general entry's style.
 */
data class TokenFontStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
) {
    companion object {
        val None: TokenFontStyle = TokenFontStyle()

        /** A theme rule's `fontStyle`: words `bold`, `italic`, `underline`, `strikethrough`, space-separated. */
        fun parse(value: String): TokenFontStyle {
            val words = value.split(' ', ',').filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()
            return TokenFontStyle(
                bold = "bold" in words,
                italic = "italic" in words,
                underline = "underline" in words,
                strikethrough = "strikethrough" in words,
            )
        }
    }
}
