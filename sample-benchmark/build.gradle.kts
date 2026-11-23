// Baseline profile and macrobenchmarks for :sample (docs/AARDINK_ROADMAP.md §8.4). Run on a
// device or emulator, never in pre-push:
//
//   ./gradlew :sample:generateBaselineProfile        writes sample/src/release/generated/baselineProfiles/
//   ./gradlew :sample-benchmark:connectedBenchmarkReleaseAndroidTest \
//       -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
//
// CI's android-perf job runs both on an emulator, for main and for the change, and
// tools/android-perf/compare.mjs compares the two.

val jvmVersion: String = libs.versions.jvm.get()

plugins {
    alias(libs.plugins.plugin.android.test)
    alias(libs.plugins.plugin.baselineprofile)
    alias(libs.plugins.plugin.spotless)
}

android {
    namespace = "com.aardarch.aardink.sample.benchmark"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        // Baseline profiles are collected on API 28 and later.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Timings from an emulator are only compared with the same emulator's (§16's choice).
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY"
    }

    targetProjectPath = ":sample"

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jvmVersion)
        targetCompatibility = JavaVersion.toVersion(jvmVersion)
    }
}

kotlin {
    jvmToolchain(jvmVersion.toInt())
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_function-naming" to "disabled",
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
