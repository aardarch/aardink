plugins {
    alias(libs.plugins.plugin.android.application) apply false
    alias(libs.plugins.plugin.android.library) apply false
    alias(libs.plugins.plugin.kotlin.compose) apply false
    alias(libs.plugins.plugin.dokka) apply false
    alias(libs.plugins.plugin.spotless) apply false
    alias(libs.plugins.plugin.kotlin.binary.compat) apply false
    alias(libs.plugins.plugin.vanniktech.maven.publish) apply false
}

tasks.register("dokkaAll") {
    group = "documentation"
    description = "Generates Dokka HTML documentation for all published modules."
    dependsOn(":editor:dokkaGenerateHtml", ":languages:dokkaGenerateHtml")
}

// NOTE: kotlinx.binary-compatibility-validator (BCV 0.18) is currently parked.
// As of this writing it does not register apiCheck/apiDump tasks on Android
// library modules under AGP 9 + Kotlin 2.3 — the plugin loads but cannot detect
// the Android variant's Kotlin source sets. KGP's built-in `kotlin.abiValidation`
// is multiplatform-only and likewise unavailable on Android-only modules.
// Re-enable when either BCV adds first-class Android support or the project
// migrates to KMP. Until then, public API surface changes are reviewed manually.
