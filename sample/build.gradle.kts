val jvmVersion: String = libs.versions.jvm.get()
val jvmVersionInt = jvmVersion.toInt()

// The sample follows the library version (VERSION_NAME in gradle.properties) so each
// release ships an installable APK whose versionCode increases monotonically.
val sampleVersionName: String = providers.gradleProperty("VERSION_NAME").get()
val sampleVersionCode: Int =
    sampleVersionName
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { it.toInt() }
        .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

plugins {
    alias(libs.plugins.plugin.android.application)
    alias(libs.plugins.plugin.kotlin.compose)
    alias(libs.plugins.plugin.spotless)
    alias(libs.plugins.plugin.roborazzi)
}

android {
    namespace = "com.aardarch.aardink.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aardarch.aardink.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = sampleVersionCode
        versionName = sampleVersionName
    }

    buildTypes {
        debug {
            // Lets a debug build coexist with the release APK from GitHub Releases.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // The sample is a demo, not a store app: sign it with the debug key so the
            // release APK attached to GitHub Releases installs without a private keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jvmVersion)
        targetCompatibility = JavaVersion.toVersion(jvmVersion)
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources/manifest to inflate the sample's theme.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

tasks.withType<Test> {
    // Roborazzi renders full-screen Compose layouts to bitmaps on the JVM; the default
    // 512m unit-test heap is not enough for 1080x2400 captures.
    maxHeapSize = "2g"
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
    implementation(project(":editor"))
    implementation(project(":languages"))
    implementation(project(":languages-lsp"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose.core)
    implementation(libs.compose.icons.core)
    debugImplementation(libs.bundles.compose.debug)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.google.android.material)

    // Screenshot capture (scripts/capture-screenshots.ps1). Roborazzi/Robolectric run on the
    // JUnit 4 runner, so unlike the library modules the sample's tests are not JUnit 5.
    testImplementation(libs.bundles.testing.screenshot)
    testImplementation(libs.androidx.compose.ui.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.ui.test.manifest)
    // Roborazzi/Robolectric build the unit-test manifest from the debug variant's merged
    // manifest, so ui-test-manifest's ComponentActivity declaration must also be a debug dep
    // (not just a test dep) for createComposeRule() to find an activity to launch.
    debugImplementation(libs.androidx.compose.ui.ui.test.manifest)
}
