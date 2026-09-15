import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Set before the plugins block below applies com.vanniktech.maven.publish, which auto-detects
// and locks group/version as soon as it sees org.jetbrains.kotlin.multiplatform applied — see
// the note in editor/build.gradle.kts for how this was discovered.
group = "com.aardarch"
version = providers.gradleProperty("VERSION_NAME").get()

val jvmVersionInt =
    libs.versions.jvm
        .get()
        .toInt()

plugins {
    alias(libs.plugins.plugin.kotlin.multiplatform)
    alias(libs.plugins.plugin.android.kmp.library)
    // Not for any @Composable code of its own (there is none) — this module's wasmJs test
    // binary transitively links :editor's Compose/Skiko runtime, and without this plugin
    // applied here too, the wasmJs test webpack bundle doesn't know to bring skiko.mjs along
    // (fails with "Module not found: Error: Can't resolve './skiko.mjs'").
    alias(libs.plugins.plugin.compose.multiplatform)
    alias(libs.plugins.plugin.kotlin.compose)
    id("aardink.dokka-gfm")
    alias(libs.plugins.plugin.spotless)
    alias(libs.plugins.plugin.vanniktech.maven.publish)
    signing
}

kotlin {
    jvmToolchain(jvmVersionInt)

    androidLibrary {
        namespace = "com.aardarch.aardink.languages"
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

    sourceSets {
        commonMain.dependencies {
            // :languages has zero Compose imports (verified — its tokenizers and language
            // services are pure Kotlin), so unlike :editor it applies no Compose plugin here.
            // The `api` dependency still gives consumers Compose transitively via :editor.
            api(project(":editor"))
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
    coordinates(artifactId = "aardink-languages")

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
        name.set("Aardink Languages")
        description.set(
            "Built-in language support for Aardink: tokenizers, folding providers, and a " +
                "pluggable LanguageRegistry covering Kotlin, TypeScript, JSON, XML, HTML, CSS, " +
                "Markdown, and plain text.",
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
