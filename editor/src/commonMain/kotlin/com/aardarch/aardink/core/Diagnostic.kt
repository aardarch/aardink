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
 * A diagnostic annotation produced by a [LanguageService] or the host: LSP's `Diagnostic`.
 *
 * Carries a document-absolute [IntRange] so the renderer can draw precise squiggle underlines
 * beneath the exact offending text, which may run over several lines.
 *
 * @property range Document-absolute character range of the problematic text, inclusive of its
 *   last character.
 * @property message What is wrong, shown when the diagnostic is hovered, tapped or reached with F8.
 * @property severity How bad it is: its colour, and whether it gets a gutter dot ([DiagnosticSeverity.Hint] does not).
 * @property source Human-readable label of what produced it (e.g. "xml", "expression").
 * @property code The producer's identifier for this kind of problem, shown after [source].
 * @property codeDescription Where [code] is explained.
 * @property tags How the text is drawn besides the underline: faded or struck through.
 * @property relatedInformation Other places that bear on it, such as a first declaration.
 */
data class Diagnostic(
    val range: IntRange,
    val message: String,
    val severity: DiagnosticSeverity,
    val source: String? = null,
    val code: String? = null,
    val codeDescription: CodeDescription? = null,
    val tags: Set<DiagnosticTag> = emptySet(),
    val relatedInformation: List<DiagnosticRelatedInformation> = emptyList(),
) {
    /** The 0.6 constructor, with the line [range] starts on, which the editor now works out itself. */
    @Deprecated(
        "The line comes from the range; read it with lineIn(document).",
        ReplaceWith("Diagnostic(range, message, severity, source)"),
    )
    constructor(
        range: IntRange,
        @Suppress("UNUSED_PARAMETER") lineNumber: Int,
        message: String,
        severity: DiagnosticSeverity,
        source: String? = null,
    ) : this(range, message, severity, source)

    /** The 0-based line of [document] that [range] starts on. */
    fun lineIn(document: CodeDocument): Int = document.offsetToLineCol(range.first.coerceIn(0, document.length)).first
}

/** How bad a [Diagnostic] is, most severe first. */
enum class DiagnosticSeverity {
    Error,
    Warning,
    Info,

    /** A suggestion: a short dotted underline, no gutter dot. */
    Hint,
}

/** How a [Diagnostic]'s text is drawn besides its underline, as LSP's `DiagnosticTag`. */
enum class DiagnosticTag {
    /** Unused or unreachable code: drawn faded rather than underlined. */
    Unnecessary,

    /** Deprecated or obsolete: struck through. */
    Deprecated,
}

/** Where a [Diagnostic]'s [Diagnostic.code] is explained: LSP's `CodeDescription`. */
data class CodeDescription(val href: String)

/** Another place that bears on a [Diagnostic], with what it has to do with it: LSP's `DiagnosticRelatedInformation`. */
data class DiagnosticRelatedInformation(val location: Location, val message: String)
