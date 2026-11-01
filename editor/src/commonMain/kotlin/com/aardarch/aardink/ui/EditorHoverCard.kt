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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.HoverDoc

/**
 * What a resting mouse (or the touch menu's "Info") shows: the host's decoration [messages] under
 * it, then the [diagnostics] under it, most severe
 * first, each with its source and code, and a Quick Fix button when [onQuickFix] is given; then the
 * symbol's documentation when there is [doc]. Monaco's hover shows markers above documentation the
 * same way.
 */
@Composable
internal fun EditorHoverCard(
    diagnostics: List<Diagnostic>,
    doc: HoverDoc?,
    onQuickFix: (() -> Unit)?,
    modifier: Modifier = Modifier,
    messages: List<String> = emptyList(),
) {
    if (diagnostics.isEmpty() && messages.isEmpty()) {
        if (doc != null) HoverDocCard(doc, modifier)
        return
    }
    val theme = LocalEditorTheme.current
    EditorChromeTheme {
        Surface(
            modifier = modifier
                .widthIn(max = 420.dp)
                .shadow(8.dp, RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The host's decorations first, as Monaco shows decoration hovers above markers.
                for (message in messages) {
                    Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                if (messages.isNotEmpty() && diagnostics.isNotEmpty()) HorizontalDivider(thickness = 0.5.dp)
                for (diagnostic in diagnostics) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(16.dp)
                                .background(severityColor(theme, diagnostic.severity)),
                        )
                        Column {
                            Text(
                                text = diagnostic.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val origin = listOfNotNull(diagnostic.source, diagnostic.code?.let { "($it)" }).joinToString(" ")
                            if (origin.isNotEmpty()) {
                                Text(
                                    text = origin,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (onQuickFix != null) {
                    TextButton(onClick = onQuickFix) {
                        Text(
                            text = "Quick Fix…",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
                if (doc != null) {
                    HorizontalDivider(thickness = 0.5.dp)
                    Text(text = doc.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(text = doc.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (doc.example != null) HoverDocExample(doc.example)
                }
            }
        }
    }
}

private fun severityColor(theme: EditorTheme, severity: DiagnosticSeverity): Color = when (severity) {
    DiagnosticSeverity.Error -> theme.errorColor
    DiagnosticSeverity.Warning -> theme.warningColor
    DiagnosticSeverity.Info, DiagnosticSeverity.Hint -> theme.infoColor
}
