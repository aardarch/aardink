import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Set before the plugins block below applies com.vanniktech.maven.publish — see the note in
// editor/build.gradle.kts for why.
group = "com.aardarch"
version = providers.gradleProperty("VERSION_NAME").get()

val jvmVersionInt =
    libs.versions.jvm
        .get()
        .toInt()

plugins {
    alias(libs.plugins.plugin.kotlin.multiplatform)
    alias(libs.plugins.plugin.android.kmp.library)
    // Not for any @Composable code of its own — see the matching comment in
    // languages/build.gradle.kts; this module's wasmJs test binary transitively links
    // :editor's Compose/Skiko runtime through api(project(":editor")).
    alias(libs.plugins.plugin.compose.multiplatform)
    alias(libs.plugins.plugin.kotlin.compose)
    alias(libs.plugins.plugin.kotlin.serialization)
    id("aardink.dokka-gfm")
    alias(libs.plugins.plugin.spotless)
    alias(libs.plugins.plugin.vanniktech.maven.publish)
    signing
}

kotlin {
    jvmToolchain(jvmVersionInt)

    androidLibrary {
        namespace = "com.aardarch.aardink.languages.lsp"
        compileSdk = 37
        minSdk = 26
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        // See editor/build.gradle.kts's matching comment.
        binaries.executable()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // StreamLspTransport (java.io Input/OutputStream) and its test are JVM/Android only;
        // the default hierarchy template doesn't create a shared set for exactly that pair.
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        val jvmAndAndroidTest by creating { dependsOn(commonTest.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmTest.get().dependsOn(jvmAndAndroidTest)

        commonMain.dependencies {
            // JsonElement is part of LspClient's public API, hence `api` rather than
            // `implementation`. This module needs no Compose of its own: every editor `core`
            // type it bridges to is plain Kotlin.
            api(project(":editor"))
            api(libs.kotlinx.serialization.json)
            // CoroutineScope is in LspClient's constructor signature — likewise `api`.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit5)
                runtimeOnly(libs.junit.jupiter.engine)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
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

// ── Publishing ────────────────────────────────────────────────────────────────

if (providers.gradleProperty("signingInMemoryKey").orNull == null) {
    signing {
        useGpgCmd()
    }
}

mavenPublishing {
    coordinates(artifactId = "aardink-languages-lsp")

    configure(
        KotlinMultiplatform(
            // TODO(KMP Dokka): see the matching TODO in editor/build.gradle.kts.
            javadocJar = JavadocJar.Empty(),
            sourcesJar = true,
        ),
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("Aardink Languages LSP")
        description.set(
            "Language Server Protocol bridge module for Aardink code editor: " +
                "provides LspClient and LspLanguageService adapter to connect to standard LSP servers.",
        )
        url.set("https://github.com/aardarch/aardink")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("aardarch")
                name.set("Aardarch")
                email.set("editor@aardarch.com")
                url.set("https://aardarch.com")
            }
        }
        scm {
            connection.set("scm:git:github.com/aardarch/aardink.git")
            developerConnection.set("scm:git:ssh://github.com/aardarch/aardink.git")
            url.set("https://github.com/aardarch/aardink/tree/main")
        }
    }
}

// ── API compatibility tracking ────────────────────────────────────────────────
// TODO: Wire up Kotlin 2.4's built-in `kotlin.abiValidation` now that this module is
// multiplatform. See the note in the root build.gradle.kts for context.
