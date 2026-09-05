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
 * Signature and parameter information shown in a signature help popover.
 *
 * @param signatures List of available function or method signatures.
 * @param activeSignature Index of the active signature in [signatures].
 * @param activeParameter Index of the active parameter in [signatures]'s parameter list.
 */
data class SignatureHelp(val signatures: List<SignatureInformation>, val activeSignature: Int = 0, val activeParameter: Int = 0)

data class SignatureInformation(
    val label: String,
    val documentation: String? = null,
    val parameters: List<ParameterInformation> = emptyList(),
)

data class ParameterInformation(val label: String, val documentation: String? = null)
