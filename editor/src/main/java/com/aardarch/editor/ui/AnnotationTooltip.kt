package com.aardarch.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aardarch.editor.core.DiagnosticSeverity

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
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "✕",
            color = fg.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
