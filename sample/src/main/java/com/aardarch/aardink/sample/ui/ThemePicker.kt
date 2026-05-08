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
package com.aardarch.aardink.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aardarch.editor.core.EditorTheme
import com.aardarch.editor.core.TokenType

/**
 * One [EditorTheme] choice, with its display name and stable id.
 */
data class SampleThemeChoice(val id: String, val displayName: String, val theme: EditorTheme)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePicker(
    choices: List<SampleThemeChoice>,
    current: SampleThemeChoice,
    onChange: (SampleThemeChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = current.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Editor theme") },
            leadingIcon = { ThemeSwatch(current.theme) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    leadingIcon = { ThemeSwatch(choice.theme) },
                    text = { Text(choice.displayName) },
                    onClick = {
                        onChange(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeSwatch(theme: EditorTheme) {
    val keyword = theme.tokenColors[TokenType.Keyword] ?: theme.cursorColor
    val string = theme.tokenColors[TokenType.StringLiteral] ?: keyword
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(theme.background),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            Modifier
                .size(width = 14.dp, height = 14.dp)
                .clip(CircleShape)
                .background(keyword),
        )
        Spacer(Modifier.width(2.dp))
        ColorDot(string)
    }
}

@Composable
private fun ColorDot(color: Color) {
    Spacer(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}
