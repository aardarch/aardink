val jvmVersion: String = libs.versions.jvm.get()
val jvmVersionInt = jvmVersion.toInt()

plugins {
    alias(libs.plugins.plugin.android.application)
    alias(libs.plugins.plugin.kotlin.compose)
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

dependencies {
    implementation(project(":editor"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose.core)
    debugImplementation(libs.bundles.compose.debug)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.google.android.material)
}
