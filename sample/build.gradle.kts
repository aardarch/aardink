val jvmVersion: String = libs.versions.jvm.get()
val jvmVersionInt = jvmVersion.toInt()

plugins {
    alias(libs.plugins.plugin.android.application)
    alias(libs.plugins.plugin.kotlin.compose)
    alias(libs.plugins.plugin.spotless)
}

android {
    namespace = "com.aardarch.aardink.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aardarch.aardink.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jvmVersion)
        targetCompatibility = JavaVersion.toVersion(jvmVersion)
    }

    buildFeatures {
        compose = true
    }
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

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose.core)
    implementation(libs.compose.icons.core)
    debugImplementation(libs.bundles.compose.debug)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.google.android.material)
}
