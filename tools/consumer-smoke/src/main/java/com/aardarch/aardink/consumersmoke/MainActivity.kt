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

package com.aardarch.aardink.consumersmoke

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.aardarch.aardink.core.rememberCodeEditorState
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.LocalEditorTheme

/**
 * Minimal app that depends on the PUBLISHED `com.aardarch:aardink` / `aardink-languages`
 * Maven coordinates (see build.gradle.kts) rather than `project(":editor")`. Its only job is
 * to prove that a real consumer resolving those coordinates from `mavenLocal()` gets a
 * working editor — this is the target of `scripts/verify-consumer.ps1`.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val registry = LanguageRegistry.withBuiltIns()
                    val language = registry.byId("kotlin") ?: registry.all.first()
                    val state = rememberCodeEditorState(
                        initialText = "fun main() = println(\"Hello, Aardink\")\n",
                        tokenizer = language.tokenizer,
                    )
                    CompositionLocalProvider(LocalEditorTheme provides EditorThemes.VsCodeDark) {
                        CodeEditorLayout(
                            state = state,
                            languageService = language.languageService,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
