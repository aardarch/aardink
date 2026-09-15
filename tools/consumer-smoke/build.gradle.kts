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

// Deliberately depends on the PUBLISHED Maven coordinates, not project(":editor") /
// project(":languages") — this is the "does com.aardarch:aardink actually resolve and work
// for a real consumer" gate, run via scripts/verify-consumer.ps1 after a
// `publishToMavenLocal`. It is NOT part of any default `assemble`/`build` task chain and is
// not built by CI's normal task lists, because it will fail to resolve on a fresh checkout
// that has never published to mavenLocal.
val jvmVersion: String = libs.versions.jvm.get()
val jvmVersionInt = jvmVersion.toInt()
val aardinkVersion: String = providers.gradleProperty("VERSION_NAME").get()

plugins {
    alias(libs.plugins.plugin.android.application)
    alias(libs.plugins.plugin.kotlin.compose)
    alias(libs.plugins.plugin.spotless)
}

android {
    namespace = "com.aardarch.aardink.consumersmoke"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aardarch.aardink.consumersmoke"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jvmVersion)
        targetCompatibility = JavaVersion.toVersion(jvmVersion)
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(jvmVersionInt)
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_no-empty-file" to "disabled",
            ),
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

dependencies {
    implementation("com.aardarch:aardink:$aardinkVersion")
    implementation("com.aardarch:aardink-languages:$aardinkVersion")

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose.core)
    implementation(libs.androidx.activity.compose)
}
