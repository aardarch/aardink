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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.aardarch.aardink.sample.samples.SampleAssets
import com.aardarch.aardink.sample.theme.AardInkSampleTheme
import com.aardarch.aardink.sample.ui.LanguageCard
import com.aardarch.aardink.sample.ui.SampleThemeChoice
import com.aardarch.aardink.sample.ui.ThemePicker
import com.aardarch.aardink.sample.ui.WelcomeCard
import com.aardarch.editor.core.Diagnostic
import com.aardarch.editor.core.FoldState
import com.aardarch.editor.core.rememberCodeEditorState
import com.aardarch.editor.languages.LanguageDefinition
import com.aardarch.editor.languages.LanguageRegistry
import com.aardarch.editor.ui.CodeEditorLayout
import com.aardarch.editor.ui.EditorThemes
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

    AardInkSampleTheme(editorTheme = active.theme) {
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
            TopAppBar(title = { Text("AardInk samples") })
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
            items(items = languages, key = { it.id }) { def ->
                LanguageCard(language = def, onClick = { onSelect(def) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    language: LanguageDefinition,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val initialText = remember(language.id) { SampleAssets.forId(language.id) }
    val state = rememberCodeEditorState(
        initialText = initialText,
        tokenizer = language.tokenizer,
    )
    val foldState = remember(language.id) { FoldState() }

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
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        CodeEditorLayout(
            state = state,
            foldState = foldState,
            foldingProvider = language.foldingProvider,
            languageService = language.languageService,
            diagnostics = diagnostics,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}
