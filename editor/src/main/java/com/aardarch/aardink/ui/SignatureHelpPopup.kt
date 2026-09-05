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
package com.aardarch.aardink.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.aardarch.aardink.core.SignatureHelp

/**
 * Parameter hint popover shown above the cursor when typing function calls.
 */
@Composable
fun SignatureHelpPopup(help: SignatureHelp, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (help.signatures.isEmpty()) return

    val activeSigIndex = help.activeSignature.coerceIn(0, help.signatures.size - 1)
    val sig = help.signatures[activeSigIndex]
    val activeParamIndex = help.activeParameter.coerceIn(0, (sig.parameters.size - 1).coerceAtLeast(0))

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            modifier = modifier
                .widthIn(max = 380.dp)
                .shadow(6.dp, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Annotated signature label highlighting the active parameter
                val primaryColor = MaterialTheme.colorScheme.primary
                val annotatedLabel = buildAnnotatedString {
                    val activeParam = sig.parameters.getOrNull(activeParamIndex)
                    if (activeParam != null && sig.label.contains(activeParam.label)) {
                        val paramStart = sig.label.indexOf(activeParam.label)
                        val paramEnd = paramStart + activeParam.label.length
                        append(sig.label.take(paramStart))
                        withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold)) {
                            append(activeParam.label)
                        }
                        append(sig.label.substring(paramEnd))
                    } else {
                        append(sig.label)
                    }
                }

                Text(
                    text = annotatedLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Parameter documentation if present
                val activeParamDoc = sig.parameters.getOrNull(activeParamIndex)?.documentation ?: sig.documentation
                if (activeParamDoc != null) {
                    Text(
                        text = activeParamDoc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
