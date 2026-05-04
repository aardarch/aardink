package com.aardarch.editor.core

/**
 * A diagnostic annotation produced by a [LanguageService].
 *
 * Unlike the legacy [com.aardarch.aardflex.service.ValidationDiagnostic] which carries only a
 * line number, this type carries a document-absolute [IntRange] so the renderer can draw precise
 * squiggle underlines beneath the exact offending token.
 */
data class Diagnostic(
    /** Document-absolute character range of the problematic text (exclusive end). */
    val range: IntRange,
    /** 0-based line number (derived from [range] for gutter display). */
    val lineNumber: Int,
    val message: String,
    val severity: DiagnosticSeverity,
    /** Optional human-readable source label (e.g. "xml", "expression"). */
    val source: String? = null,
)

enum class DiagnosticSeverity { Error, Warning, Info }
