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
@file:OptIn(InternalDokkaGradlePluginApi::class)

import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.dokka.gradle.formats.DokkaFormatPlugin
import org.jetbrains.dokka.gradle.internal.InternalDokkaGradlePluginApi

// Registers a Dokka v2 output format named "markdown" that emits
// GitHub-Flavored Markdown via the official `gfm-plugin`. Once applied,
// Dokka exposes a `dokkaGenerateMarkdown` task on the project.
//
// See: https://github.com/Kotlin/dokka/blob/master/dokka-subprojects/plugin-gfm/README.md
abstract class DokkaMarkdownPlugin : DokkaFormatPlugin(formatName = "markdown") {
    override fun DokkaFormatPluginContext.configure() {
        project.dependencies {
            dokkaPlugin(dokka("gfm-plugin"))
            formatDependencies.dokkaPublicationPluginClasspathApiOnly
                .dependencies
                .addLater(dokka("gfm-template-processing-plugin"))
        }
    }
}
