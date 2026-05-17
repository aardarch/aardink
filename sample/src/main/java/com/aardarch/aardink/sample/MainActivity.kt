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
package com.aardarch.aardink.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.rememberCodeEditorState
import com.aardarch.aardink.languages.LanguageDefinition
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.sample.samples.SampleAssets
import com.aardarch.aardink.sample.theme.AardinkSampleTheme
import com.aardarch.aardink.sample.ui.LanguageCard
import com.aardarch.aardink.sample.ui.SampleThemeChoice
import com.aardarch.aardink.sample.ui.ThemePicker
import com.aardarch.aardink.sample.ui.WelcomeCard
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.KeyboardToolbarPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val themeChoices: List<SampleThemeChoice> = listOf(
    SampleThemeChoice("midnight-ocean", "Midnight Ocean", EditorThemes.MidnightOcean),
    SampleThemeChoice("vscode-dark", "VS Code Dark", EditorThemes.VsCodeDark),
    SampleThemeChoice("vscode-light", "VS Code Light", EditorThemes.VsCodeLight),
    SampleThemeChoice("material-dark", "Material Dark", EditorThemes.MaterialDark),
    SampleThemeChoice("material-light", "Material Light", EditorThemes.MaterialLight),
    SampleThemeChoice("solarized-dark", "Solarized Dark", EditorThemes.SolarizedDark),
)

private val defaultThemeId = themeChoices.first().id

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SampleApp() }
    }
}

@Composable
private fun SampleApp() {
    val registry = remember { LanguageRegistry.withBuiltIns() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var themeId by rememberSaveable { mutableStateOf(defaultThemeId) }
    val active = themeChoices.firstOrNull { it.id == themeId } ?: themeChoices.first()
    val selected = selectedId?.let(registry::byId)

    AardinkSampleTheme(editorTheme = active.theme) {
        if (selected == null) {
            StartScreen(
                languages = registry.all,
                currentTheme = active,
                onThemeChange = { themeId = it.id },
                onSelect = { selectedId = it.id },
            )
        } else {
            EditorScreen(
                language = selected,
                onBack = { selectedId = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartScreen(
    languages: List<LanguageDefinition>,
    currentTheme: SampleThemeChoice,
    onThemeChange: (SampleThemeChoice) -> Unit,
    onSelect: (LanguageDefinition) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Aardink samples") })
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("welcome") {
                WelcomeCard()
            }
            item("theme-picker") {
                ThemePicker(
                    choices = themeChoices,
                    current = currentTheme,
                    onChange = onThemeChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val (advanced, basic) = languages.partition { it.languageService != null }

            if (advanced.isNotEmpty()) {
                item("section-advanced") {
                    SectionHeader(
                        title = "Full language support",
                        subtitle = "Diagnostics, completions, hover, formatting",
                    )
                }
                items(items = advanced, key = { it.id }) { def ->
                    LanguageCard(language = def, onClick = { onSelect(def) })
                }
            }

            if (basic.isNotEmpty()) {
                item("section-basic") {
                    SectionHeader(
                        title = "Syntax highlighting",
                        subtitle = "Tokenization and folding",
                    )
                }
                items(items = basic, key = { it.id }) { def ->
                    LanguageCard(language = def, onClick = { onSelect(def) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(language: LanguageDefinition, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val initialText = remember(language.id) { SampleAssets.forId(language.id) }
    val state = rememberCodeEditorState(
        initialText = initialText,
        tokenizer = language.tokenizer,
    )
    val foldState = remember(language.id) { FoldState() }
    val findReplaceState = remember(language.id) { FindReplaceState() }

    var toolbarPlacementName by rememberSaveable {
        mutableStateOf(KeyboardToolbarPlacement.BottomHover.name)
    }
    val toolbarPlacement = KeyboardToolbarPlacement.valueOf(toolbarPlacementName)

    var showGutter by rememberSaveable { mutableStateOf(true) }
    var showLineNumbers by rememberSaveable { mutableStateOf(true) }
    var showFoldMarkers by rememberSaveable { mutableStateOf(true) }
    var showDiagnosticDots by rememberSaveable { mutableStateOf(true) }
    var showDiffMarkers by rememberSaveable { mutableStateOf(true) }
    var softWrap by rememberSaveable { mutableStateOf(false) }

    var diagnostics by remember(language.id) { mutableStateOf<List<Diagnostic>>(emptyList()) }
    val service = language.languageService
    if (service != null) {
        LaunchedEffect(state, service) {
            snapshotFlow { state.textVersion }.collect {
                delay(400)
                diagnostics = withContext(Dispatchers.Default) { service.diagnostics(state.document) }
            }
        }
    }

    val subtitleText = language.fileExtensions.joinToString("  ·  ") { ".$it" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = language.displayName,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (subtitleText.isNotEmpty()) {
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (findReplaceState.visible) findReplaceState.hide() else findReplaceState.show()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Find and replace",
                        )
                    }
                    SampleOptionsMenu(
                        placement = toolbarPlacement,
                        onPlacementChange = { toolbarPlacementName = it.name },
                        showGutter = showGutter,
                        onShowGutterChange = { showGutter = it },
                        showLineNumbers = showLineNumbers,
                        onShowLineNumbersChange = { showLineNumbers = it },
                        showFoldMarkers = showFoldMarkers,
                        onShowFoldMarkersChange = { showFoldMarkers = it },
                        showDiagnosticDots = showDiagnosticDots,
                        onShowDiagnosticDotsChange = { showDiagnosticDots = it },
                        showDiffMarkers = showDiffMarkers,
                        onShowDiffMarkersChange = { showDiffMarkers = it },
                        softWrap = softWrap,
                        onSoftWrapChange = { softWrap = it },
                    )
                },
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        CodeEditorLayout(
            state = state,
            foldState = foldState,
            findReplaceState = findReplaceState,
            foldingProvider = language.foldingProvider,
            languageService = language.languageService,
            diagnostics = diagnostics,
            keyboardToolbarPlacement = toolbarPlacement,
            showGutter = showGutter,
            showLineNumbers = showLineNumbers,
            showFoldMarkers = showFoldMarkers,
            showDiagnosticAnnotations = showDiagnosticDots,
            showDiffMarkers = showDiffMarkers,
            softWrap = softWrap,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

@Composable
private fun SampleOptionsMenu(
    placement: KeyboardToolbarPlacement,
    onPlacementChange: (KeyboardToolbarPlacement) -> Unit,
    showGutter: Boolean,
    onShowGutterChange: (Boolean) -> Unit,
    showLineNumbers: Boolean,
    onShowLineNumbersChange: (Boolean) -> Unit,
    showFoldMarkers: Boolean,
    onShowFoldMarkersChange: (Boolean) -> Unit,
    showDiagnosticDots: Boolean,
    onShowDiagnosticDotsChange: (Boolean) -> Unit,
    showDiffMarkers: Boolean,
    onShowDiffMarkersChange: (Boolean) -> Unit,
    softWrap: Boolean,
    onSoftWrapChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(imageVector = Icons.Filled.Menu, contentDescription = "Sample options")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        SectionLabel("Keyboard toolbar")
        ToolbarPlacementItem(
            label = "Hover above keyboard",
            target = KeyboardToolbarPlacement.BottomHover,
            current = placement,
            onSelect = {
                onPlacementChange(it)
                expanded = false
            },
        )
        ToolbarPlacementItem(
            label = "Top of editor",
            target = KeyboardToolbarPlacement.Top,
            current = placement,
            onSelect = {
                onPlacementChange(it)
                expanded = false
            },
        )
        ToolbarPlacementItem(
            label = "Bottom of editor",
            target = KeyboardToolbarPlacement.BottomFixed,
            current = placement,
            onSelect = {
                onPlacementChange(it)
                expanded = false
            },
        )
        HorizontalDivider()
        ToolbarPlacementItem(
            label = "Hidden",
            target = KeyboardToolbarPlacement.Hidden,
            current = placement,
            onSelect = {
                onPlacementChange(it)
                expanded = false
            },
        )

        HorizontalDivider()
        SectionLabel("Editor")
        ToggleItem(
            label = "Soft wrap",
            checked = softWrap,
            onChange = onSoftWrapChange,
        )

        HorizontalDivider()
        SectionLabel("Gutter")
        ToggleItem(
            label = "Show gutter",
            checked = showGutter,
            onChange = onShowGutterChange,
        )
        ToggleItem(
            label = "Line numbers",
            checked = showLineNumbers,
            enabled = showGutter,
            onChange = onShowLineNumbersChange,
        )
        ToggleItem(
            label = "Fold markers",
            checked = showFoldMarkers,
            enabled = showGutter,
            onChange = onShowFoldMarkersChange,
        )
        ToggleItem(
            label = "Diagnostic dots",
            checked = showDiagnosticDots,
            enabled = showGutter,
            onChange = onShowDiagnosticDotsChange,
        )
        ToggleItem(
            label = "Diff markers",
            checked = showDiffMarkers,
            enabled = showGutter,
            onChange = onShowDiffMarkersChange,
        )
    }
}

@Composable
private fun ToggleItem(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (checked) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = null)
            }
        },
        enabled = enabled,
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ToolbarPlacementItem(
    label: String,
    target: KeyboardToolbarPlacement,
    current: KeyboardToolbarPlacement,
    onSelect: (KeyboardToolbarPlacement) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (current == target) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = null)
            }
        },
        onClick = { onSelect(target) },
    )
}
