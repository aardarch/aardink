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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.DiagnosticSeverity

/**
 * Inline dismissible banner shown when the user taps a gutter annotation dot.
 * Appears as a colored strip between the find panel and the editor body.
 */
@Composable
fun AnnotationTooltip(
    message: String,
    severity: DiagnosticSeverity,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onQuickFix: (() -> Unit)? = null,
) {
    val (bg, fg) = when (severity) {
        DiagnosticSeverity.Error ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer

        DiagnosticSeverity.Warning ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer

        DiagnosticSeverity.Info ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            // The buttons bring their own 48 dp minimum height; keep the strip to that.
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tapping the message itself also dismisses; say so rather than leaving an unnamed target.
        Text(
            text = message,
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = DISMISS_LABEL, onClick = onDismiss),
        )
        if (onQuickFix != null) {
            // A real button: it carries the button role and the minimum touch target, which a
            // clickable Text does not.
            TextButton(
                onClick = onQuickFix,
                colors = ButtonDefaults.textButtonColors(contentColor = fg),
            ) {
                Text(
                    text = "💡 Quick Fix",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = EditorIcons.Close,
                contentDescription = DISMISS_LABEL,
                tint = fg.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private const val DISMISS_LABEL = "Dismiss diagnostic"
