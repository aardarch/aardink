import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.net.URI

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
    alias(libs.plugins.plugin.dokka)
    alias(libs.plugins.plugin.spotless)
    alias(libs.plugins.plugin.vanniktech.maven.publish)
    signing
}

kotlin {
    jvmToolchain(jvmVersionInt)

    android {
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
        named("jvmTest") {
            dependencies {
                implementation(libs.kotlin.test.junit5)
                runtimeOnly(libs.junit.jupiter.engine)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }

    // ABI validation (Kotlin 2.4 built-in, replacing the binary-compatibility-validator
    // plugin that never worked on this repo's Android modules). Dumps live in `api/`;
    // `checkLegacyAbi` gates CI and `updateLegacyAbi` refreshes them. Every PR through to
    // 0.5.0 must show a purely additive diff.
    //
    // Configured here rather than in a shared convention plugin: buildSrc would need the
    // Kotlin Gradle Plugin on its classpath for typed access to this extension, and that
    // makes Gradle reject each module's own versioned
    // `alias(libs.plugins.plugin.kotlin.multiplatform)` with "already on the classpath with
    // an unknown version". It is the same collision that keeps AGP off buildSrc (see
    // the Dokka note below).
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation { }
}

// ── API documentation ─────────────────────────────────────────────────────────
// Dokka is applied per-module rather than through a buildSrc convention plugin. A
// precompiled script plugin in buildSrc is loaded by a parent classloader that cannot see
// plugins resolved through a module's own `plugins {}` block, so Dokka applied from there
// could not find KotlinBasePlugin, registered no source sets, and generated "Nothing to
// document" -- which is also why every module shipped an empty javadoc jar. Resolving Dokka
// and the Kotlin plugin through the same root `plugins {}` block is what Dokka's own
// diagnostic recommends.
dokka {
    dokkaSourceSets.configureEach {
        // Turn resolved external references into hyperlinks.
        externalDocumentationLinks.register("androidx") {
            url.set(URI("https://developer.android.com/reference/kotlin/"))
            packageListUrl.set(URI("https://developer.android.com/reference/kotlin/androidx/package-list"))
        }
        externalDocumentationLinks.register("coroutines") {
            url.set(URI("https://kotlinlang.org/api/kotlinx.coroutines/"))
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
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
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
