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
package com.aardarch.aardink.languages.internal.css

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.ColorInfo
import com.aardarch.aardink.core.ColorPresentation
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.languages.internal.BaseLanguageService
import com.aardarch.aardink.languages.internal.HexColors

/**
 * The hex colours in a style sheet's declarations, for the editor's colour swatches and picker:
 * CSS's, and SCSS's and LESS's, which have no formatter of their own (CSS's would misread their
 * `//` comments and nesting).
 */
internal open class CssColorService : BaseLanguageService() {

    override suspend fun documentColors(document: CodeDocument): List<ColorInfo> = HexColors.cssTokens(document.text)

    override suspend fun colorPresentations(document: CodeDocument, info: ColorInfo, color: Color): List<ColorPresentation> =
        HexColors.presentation(document, info, color, alphaFirst = false)
}

/** SCSS's and LESS's service: the colours alone. */
internal object StyleSheetColorService : CssColorService()

/** CSS's service: its colours, and [CssFormatter]. */
internal object CssLanguageService : CssColorService() {

    override suspend fun format(document: CodeDocument, options: FormattingOptions): String = CssFormatter.format(document.text, options)
}
