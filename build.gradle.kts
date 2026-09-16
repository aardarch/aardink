plugins {
    alias(libs.plugins.plugin.android.application) apply false
    alias(libs.plugins.plugin.kotlin.compose) apply false
    alias(libs.plugins.plugin.kotlin.serialization) apply false
    alias(libs.plugins.plugin.kotlin.multiplatform) apply false
    alias(libs.plugins.plugin.android.kmp.library) apply false
    alias(libs.plugins.plugin.compose.multiplatform) apply false
    alias(libs.plugins.plugin.dokka) apply false
    alias(libs.plugins.plugin.spotless) apply false
    alias(libs.plugins.plugin.vanniktech.maven.publish) apply false
    alias(libs.plugins.plugin.roborazzi) apply false
}

/** The three published Kotlin Multiplatform libraries. */
val publishedLibraries = listOf(":editor", ":languages", ":languages-lsp")

tasks.register("dokkaAll") {
    group = "documentation"
    description = "Generates Dokka HTML documentation for all published modules."
    dependsOn(publishedLibraries.map { "$it:dokkaGenerateHtml" })
}

// `dokkaAllGfm` is gone along with the Markdown format it drove. Dokka 2's Gradle plugin does
// not support the GFM output format: the gfm-plugin dependency resolves and the task reports
// success, but it emits zero files (verified at Dokka 2.2.0). release.yml bundles the HTML
// output instead. See buildSrc/src/main/kotlin/aardink.dokka.gradle.kts.

tasks.register("checkAbiAll") {
    group = "verification"
    description = "Checks the public ABI of all published modules against the committed dumps."
    dependsOn(publishedLibraries.map { "$it:checkKotlinAbi" })
}

tasks.register("updateAbiAll") {
    group = "verification"
    description = "Rewrites the committed public ABI dumps for all published modules."
    dependsOn(publishedLibraries.map { "$it:updateKotlinAbi" })
}
