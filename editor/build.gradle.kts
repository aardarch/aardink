import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Set before the plugins block below applies com.vanniktech.maven.publish, which auto-detects
// and locks group/version as soon as it sees org.jetbrains.kotlin.multiplatform applied — an
// explicit `coordinates(groupId = ..., version = ...)` call later in this file is too late.
group = "com.aardarch"
version = providers.gradleProperty("VERSION_NAME").get()

val jvmVersionInt =
    libs.versions.jvm
        .get()
        .toInt()

plugins {
    alias(libs.plugins.plugin.kotlin.multiplatform)
    alias(libs.plugins.plugin.android.kmp.library)
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
        namespace = "com.aardarch.aardink"
        compileSdk = 37
        minSdk = 26
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        // The published artifact is the klib, not an app — but Compose Multiplatform's own
        // wasmJsBrowserTest check requires a bundled executable (with the Skiko runtime) to run
        // ANY test in the browser, even these plain kotlin.test unit tests that never touch
        // Compose UI. See https://youtrack.jetbrains.com/issue/CMP-4906.
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            // The only non-Compose runtime dependency in :editor — EditorThemeParser.kt uses it
            // instead of the Android-only org.json, so the module compiles as common Kotlin.
            // See AGENTS.md.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
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

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
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
    coordinates(artifactId = "aardink")

    configure(
        KotlinMultiplatform(
            // TODO(KMP Dokka): aardink.dokka-gfm.gradle.kts still hooks com.android.library,
            // which this module no longer applies — its dokkaGenerateHtml/dokkaGenerateMarkdown
            // tasks don't exist yet. Swap back to JavadocJar.Dokka(...) once that convention
            // plugin is rewritten to hook org.jetbrains.kotlin.multiplatform instead (tracked in
            // docs/KMP_MIGRATION_PLAN.md section 9.1).
            javadocJar = JavadocJar.Empty(),
            sourcesJar = true,
        ),
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("Aardink")
        description.set(
            "A Compose Multiplatform-native code editor: incremental tokenization, " +
                "LSP-lite language services, code folding, find/replace, and rich gutter annotations.",
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
