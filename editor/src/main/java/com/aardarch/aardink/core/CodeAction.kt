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
 * A quick fix or refactoring action produced by a [LanguageService].
 *
 * @param title Human-readable label shown in the quick fix menu.
 * @param kind Category of action (quick fix, refactoring, organise imports).
 * @param edits List of text edits to apply atomically to the document.
 * @param isPreferred True if this action is the primary/default fix.
 */
data class CodeAction(
    val title: String,
    val kind: CodeActionKind = CodeActionKind.QuickFix,
    val edits: List<TextEdit> = emptyList(),
    val isPreferred: Boolean = false,
)

enum class CodeActionKind {
    QuickFix,
    Refactor,
    SourceOrganizeImports,
}
