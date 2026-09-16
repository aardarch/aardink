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
package com.aardarch.aardink.sampledesktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.GoToLineDialog
import com.aardarch.aardink.ui.KeyboardToolbarPlacement
import com.aardarch.aardink.ui.LocalEditorTheme

private val SAMPLE = """
    fun main() {
        val greeting = "Hello, Aardink"
        repeat(3) { index ->
            println("${'$'}greeting (${'$'}index)")
        }
    }
""".trimIndent()

/**
 * Desktop harness. Deliberately small: its job is to exercise the things that only exist off
 * Android — hardware-keyboard shortcuts, scrollbars, and
 * [KeyboardToolbarPlacement.platformDefault] resolving to `Hidden` because a desktop always has
 * a real keyboard.
 *
 * Try: Ctrl/Cmd+Z and +Y (undo/redo through EditorUndoManager, not the field's own stack),
 * Ctrl/Cmd+F (find), Ctrl/Cmd+G (go to line), Tab and Shift+Tab on a multi-line selection,
 * Escape to dismiss, and the mouse wheel plus the scrollbar on the right.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Aardink — desktop sample",
        state = rememberWindowState(width = 1100.dp, height = 800.dp),
    ) {
        MaterialTheme {
            SampleWindow()
        }
    }
}

@Composable
private fun SampleWindow() {
    val registry = remember { LanguageRegistry.withBuiltIns() }
    var language by remember { mutableStateOf(registry.byId("kotlin") ?: registry.all.first()) }
    var themeName by remember { mutableStateOf("VS Code Dark") }
    var showGoToLine by remember { mutableStateOf(false) }

    val themes = remember {
        linkedMapOf(
            "VS Code Dark" to EditorThemes.VsCodeDark,
            "VS Code Light" to EditorThemes.VsCodeLight,
            "Midnight Ocean" to EditorThemes.MidnightOcean,
            "Solarized Dark" to EditorThemes.SolarizedDark,
        )
    }

    // Keyed on the language so switching it rebuilds the state with the right tokenizer.
    val state = remember(language) { CodeEditorState(SAMPLE, tokenizer = language.tokenizer) }
    val findReplaceState = remember { FindReplaceState() }
    val foldState = remember { FoldState() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PickerButton(
                label = "Language: ${language.displayName}",
                options = registry.all.map { it.displayName },
            ) { index -> language = registry.all[index] }

            PickerButton(label = "Theme: $themeName", options = themes.keys.toList()) { index ->
                themeName = themes.keys.toList()[index]
            }

            Text(
                text = "Ctrl/Cmd+Z/Y undo/redo · +F find · +G go to line · Tab / Shift+Tab indent",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        CompositionLocalProvider(LocalEditorTheme provides (themes[themeName] ?: EditorThemes.VsCodeDark)) {
            CodeEditorLayout(
                state = state,
                languageService = language.languageService,
                findReplaceState = findReplaceState,
                foldState = foldState,
                foldingProvider = language.foldingProvider,
                keyboardToolbarPlacement = KeyboardToolbarPlacement.platformDefault,
                onRequestGoToLine = { showGoToLine = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showGoToLine) {
        GoToLineDialog(
            totalLines = state.document.lineCount,
            onConfirm = { line ->
                showGoToLine = false
                // onConfirm reports a 1-based line number.
                state.navigateTo(state.document.lineStart(line - 1))
            },
            onDismiss = { showGoToLine = false },
        )
    }
}

@Composable
private fun PickerButton(label: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) { Text(label) }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEachIndexed { index, option ->
            DropdownMenuItem(
                text = { Text(option) },
                onClick = {
                    expanded = false
                    onSelect(index)
                },
            )
        }
    }
}
