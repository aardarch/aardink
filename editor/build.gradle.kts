val jvmVersion: String = libs.versions.jvm.get()
val jvmVersionInt = jvmVersion.toInt()

plugins {
    alias(libs.plugins.plugin.android.library)
    alias(libs.plugins.plugin.kotlin.compose)
    alias(libs.plugins.plugin.spotless)
    alias(libs.plugins.plugin.vanniktech.maven.publish)
}

android {
    namespace = "com.aardarch.editor"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
            }
        }
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
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.bundles.compose.core)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ── Publishing ────────────────────────────────────────────────────────────────

mavenPublishing {
    coordinates(
        groupId = "com.aardarch",
        artifactId = "aardink",
        version = "0.1.0",
    )

    pom {
        name.set("AardInk")
        description.set(
            "A Jetpack Compose-native code editor: incremental tokenization, " +
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
// TODO: Re-enable kotlinx.binary-compatibility-validator (or migrate to KGP's
// built-in ABI validation) once Android library support is stable. See the
// note in the root build.gradle.kts for context.
