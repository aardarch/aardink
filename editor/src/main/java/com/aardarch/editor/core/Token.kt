package com.aardarch.editor.core

/**
 * An immutable, document-absolute span with a classification.
 *
 * [start] and [end] are character offsets into the full document text (not line-relative).
 * [end] is exclusive — the token covers `text[start, end)`.
 */
data class Token(val start: Int, val end: Int, val type: TokenType) {
    init {
        require(start >= 0) { "Token start must be ≥ 0, got $start" }
        require(end >= start) { "Token end must be ≥ start, got end=$end start=$start" }
    }

    val length: Int get() = end - start
    val isEmpty: Boolean get() = start == end
}
