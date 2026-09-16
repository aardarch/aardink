import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Desktop harness for the editor. Not published, not in the ABI gate — this exists so the
// JVM target can be exercised interactively, which is the fastest loop for the keyboard
// shortcuts and scrollbars that only appear off Android.

val jvmVersionInt =
    libs.versions.jvm
        .get()
        .toInt()

plugins {
    alias(libs.plugins.plugin.kotlin.multiplatform)
    alias(libs.plugins.plugin.compose.multiplatform)
    alias(libs.plugins.plugin.kotlin.compose)
    alias(libs.plugins.plugin.spotless)
}

kotlin {
    jvmToolchain(jvmVersionInt)

    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":editor"))
            implementation(project(":languages"))
            implementation(compose.desktop.currentOs)
            // CodeEditorState defaults its scope to Dispatchers.Main, which on the JVM has no
            // implementation until a UI dispatcher is on the classpath. Without this the app
            // throws on the first editor construction. Downstream desktop consumers need the
            // same dependency; see CodeEditorState's KDoc and the README.
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.aardarch.aardink.sampledesktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AardinkSample"
            packageVersion = "1.0.0"
        }
    }
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
