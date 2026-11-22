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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.rememberCodeEditorState
import com.aardarch.aardink.core.rememberDiffEditorState
import com.aardarch.aardink.languages.LanguageDefinition
import com.aardarch.aardink.sample.samples.DiffSampleAssets
import com.aardarch.aardink.ui.CodeDiffEditorLayout
import com.aardarch.aardink.ui.DiffEditorOptions
import kotlinx.coroutines.Dispatchers

/** The id the diff editor page has in place of a language's, for the sample's navigation. */
internal const val DIFF_SAMPLE_ID = "diff-editor"

/**
 * The diff editor page: a Kotlin file and an edited version of it in [CodeDiffEditorLayout], with
 * chips to switch between side by side and inline and to fold the unchanged regions. The modified
 * side is editable, so a change can be reverted from the gutter or the context menu.
 *
 * [initialSideBySide] and [initialHideUnchanged] are what the chips start as; the screenshot tests set them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiffSampleScreen(
    kotlin: LanguageDefinition,
    onBack: () -> Unit,
    tokenizeDebounceMs: Long = 150L,
    initialSideBySide: Boolean = true,
    initialHideUnchanged: Boolean = false,
) {
    BackHandler(onBack = onBack)
    var sideBySide by rememberSaveable { mutableStateOf(initialSideBySide) }
    var hideUnchanged by rememberSaveable { mutableStateOf(initialHideUnchanged) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "Diff editor", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Cart.kt  ·  original and edited",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = sideBySide,
                    onClick = { sideBySide = true },
                    label = { Text("Side by side") },
                )
                FilterChip(
                    selected = !sideBySide,
                    onClick = { sideBySide = false },
                    label = { Text("Inline") },
                )
                FilterChip(
                    selected = hideUnchanged,
                    onClick = { hideUnchanged = !hideUnchanged },
                    label = { Text("Hide unchanged regions") },
                )
            }
            DiffSampleEditor(
                kotlin = kotlin,
                sideBySide = sideBySide,
                hideUnchanged = hideUnchanged,
                tokenizeDebounceMs = tokenizeDebounceMs,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/**
 * The comparison of [DiffSampleAssets] without the page around it, which the screenshot tests also
 * show. A phone is narrower than the layout's inline breakpoint, so side by side lowers it to 0.
 */
@Composable
internal fun DiffSampleEditor(
    kotlin: LanguageDefinition,
    sideBySide: Boolean,
    hideUnchanged: Boolean,
    modifier: Modifier = Modifier,
    tokenizeDebounceMs: Long = 150L,
    original: String = DiffSampleAssets.ORIGINAL,
    modified: String = DiffSampleAssets.MODIFIED,
) {
    val originalState = rememberCodeEditorState(
        initialText = original,
        tokenizer = kotlin.tokenizer,
        tokenizeDebounceMs = tokenizeDebounceMs,
    )
    val modifiedState = rememberCodeEditorState(
        initialText = modified,
        tokenizer = kotlin.tokenizer,
        tokenizeDebounceMs = tokenizeDebounceMs,
    )
    if (tokenizeDebounceMs == 0L) {
        // As the editor page does: no background thread hop, so the test's waitForIdle sees the result.
        originalState.computeDispatcher = Dispatchers.Unconfined
        modifiedState.computeDispatcher = Dispatchers.Unconfined
    }
    val diffState = rememberDiffEditorState(originalState, modifiedState)
    val options = remember(sideBySide, hideUnchanged) {
        DiffEditorOptions(
            renderSideBySide = sideBySide,
            renderSideBySideInlineBreakpoint = 0.dp,
            hideUnchangedRegions = hideUnchanged,
        )
    }
    CodeDiffEditorLayout(
        state = diffState,
        options = options,
        languageService = kotlin.languageService,
        modifier = modifier,
    )
}

/** The start screen's entry for the diff editor page. */
@Composable
internal fun DiffSampleCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer, contentColor = scheme.onSurface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(scheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "±", style = MaterialTheme.typography.titleLarge, color = scheme.onTertiaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Diff editor", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Spacer(Modifier.size(2.dp))
                Text(
                    text = "Side by side  ·  inline  ·  hide unchanged",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}
