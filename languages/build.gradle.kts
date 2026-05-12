val jvmVersion: String = libs.versions.jvm.get()
val jvmVersionInt = jvmVersion.toInt()

plugins {
    alias(libs.plugins.plugin.android.library)
    alias(libs.plugins.plugin.kotlin.compose)
    id("aardink.dokka-gfm")
    alias(libs.plugins.plugin.spotless)
    alias(libs.plugins.plugin.vanniktech.maven.publish)
    signing
}

android {
    namespace = "com.aardarch.aardink.languages"
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
    api(project(":editor"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.bundles.compose.core)

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
        artifactId = "aardink-languages",
        version = providers.gradleProperty("VERSION_NAME").get(),
    )

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
