import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.net.URI

// Browser bridge for the editor: mounts CodeEditorLayout into a DOM element behind a small,
// JS-friendly API (AardinkWeb). wasmJs only. Published as a klib; the consuming app module
// (:sample-web, or a product's own wasmJs module) builds the executable and owns the
// @JsExport surface -- see docs/KMP_MIGRATION_PLAN.md §6.

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
    alias(libs.plugins.plugin.compose.multiplatform)
    alias(libs.plugins.plugin.kotlin.compose)
    alias(libs.plugins.plugin.kotlin.serialization)
    alias(libs.plugins.plugin.dokka)
    alias(libs.plugins.plugin.spotless)
    alias(libs.plugins.plugin.vanniktech.maven.publish)
    signing
}

kotlin {
    jvmToolchain(jvmVersionInt)

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        // See editor/build.gradle.kts's matching comment: the published artifact is the klib,
        // but wasmJsBrowserTest needs an executable with the Skiko runtime to run any test.
        binaries.executable()
    }

    sourceSets {
        named("wasmJsMain") {
            dependencies {
                api(project(":editor"))
                api(project(":languages"))
                // JetBrains Mono is bundled here, not in :editor: Android and desktop hosts
                // have system monospace fonts, the browser canvas has none.
                implementation(libs.compose.mp.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        named("wasmJsTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }

    // See editor/build.gradle.kts for why this is configured per module.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation { }
}

compose.resources {
    packageOfResClass = "com.aardarch.aardink.web.res"
    // The default (Auto) only generates Res when components-resources is a commonMain
    // dependency; here it is wasmJsMain-only, as is the font.
    generateResClass = org.jetbrains.compose.resources.ResourcesExtension.ResourceClassGeneration.Always
}

// ── API documentation ─────────────────────────────────────────────────────────
// Per-module for the reason given in editor/build.gradle.kts.
dokka {
    dokkaSourceSets.configureEach {
        externalDocumentationLinks.register("coroutines") {
            url.set(URI("https://kotlinlang.org/api/kotlinx.coroutines/"))
        }
    }
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

// ── Publishing ────────────────────────────────────────────────────────────────

if (providers.gradleProperty("signingInMemoryKey").orNull == null) {
    signing {
        useGpgCmd()
    }
}

mavenPublishing {
    coordinates(artifactId = "aardink-editor-web")

    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("Aardink Editor Web")
        description.set(
            "Browser bridge for the Aardink code editor: mounts the Compose Multiplatform editor " +
                "into a DOM element behind a small JavaScript-friendly API.",
        )
        url.set("https://github.com/aardarch/aardink")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
            license {
                name.set("SIL Open Font License, Version 1.1 (bundled JetBrains Mono)")
                url.set("https://openfontlicense.org")
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
