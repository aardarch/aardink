package com.aardarch.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Modal dialog prompting the user for a target line number.
 *
 * Confirming with a valid line number invokes [onConfirm] with the 1-based line number;
 * the host should scroll/place-cursor at that line.
 *
 * @param totalLines Total document line count, used to validate the input.
 */
@Composable
fun GoToLineDialog(totalLines: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val parsed = input.toIntOrNull()
    val valid = parsed != null && parsed in 1..totalLines

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to line") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { new -> input = new.filter { it.isDigit() }.take(7) },
                    label = { Text("Line number") },
                    singleLine = true,
                    isError = input.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier,
                )
                Text(
                    text = "1 – $totalLines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.takeIf { valid }?.let(onConfirm) },
                enabled = valid,
            ) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
