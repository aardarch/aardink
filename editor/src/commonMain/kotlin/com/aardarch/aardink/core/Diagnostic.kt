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
 * A diagnostic annotation produced by a [LanguageService].
 *
 * Carries a document-absolute [IntRange] so the renderer can draw precise squiggle underlines
 * beneath the exact offending token, rather than just a line number.
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
