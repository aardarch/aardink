val jvmVersion: String = libs.versions.jvm.get()
val jvmVersionInt = jvmVersion.toInt()

plugins {
    alias(libs.plugins.plugin.android.library)
    alias(libs.plugins.plugin.kotlin.serialization)
    id("aardink.dokka-gfm")
    alias(libs.plugins.plugin.spotless)
    alias(libs.plugins.plugin.vanniktech.maven.publish)
    signing
}

android {
    namespace = "com.aardarch.aardink.languages.lsp"
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
    api(project(":editor"))
    // JsonElement is part of LspClient's public API, hence `api` rather than `implementation`.
    api(libs.kotlinx.serialization.json)
    // CoroutineScope is in LspClient's constructor signature — likewise `api`. This module needs
    // no Compose of its own: every editor `core` type it bridges to is plain Kotlin.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ── Publishing ────────────────────────────────────────────────────────────────

if (providers.gradleProperty("signingInMemoryKey").orNull == null) {
    signing {
        useGpgCmd()
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "com.aardarch",
        artifactId = "aardink-languages-lsp",
        version = providers.gradleProperty("VERSION_NAME").get(),
    )

    pom {
        name.set("Aardink Languages LSP")
        description.set(
            "Language Server Protocol bridge module for Aardink code editor: " +
                "provides LspClient and LspLanguageService adapter to connect to standard LSP servers.",
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
