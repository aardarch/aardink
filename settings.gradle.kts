import java.util.Properties

// When included as a composite build, this project needs its own local.properties with sdk.dir.
// Auto-populate it from ANDROID_HOME so developers don't need a separate setup step.
val localPropsFile = file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) load(localPropsFile.inputStream())
}
if (!localProps.containsKey("sdk.dir")) {
    val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (sdkDir != null) {
        localPropsFile.appendText("sdk.dir=${sdkDir.replace("\\", "\\\\")}\n")
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_PROJECT (not FAIL_ON_PROJECT_REPOS) because the Kotlin/Wasm Gradle plugin adds its
    // own Node.js distribution repository (https://nodejs.org/dist, not a Maven repo mirror)
    // directly to the root project when downloading the toolchain for wasmJs tests
    // (kotlinWasmNodeJsSetup) — FAIL_ON_PROJECT_REPOS would reject it outright, and
    // PREFER_SETTINGS would silently ignore it (nodejs.org isn't on Maven Central).
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        // Checked first so :tools:consumer-smoke picks up a freshly `publishToMavenLocal`'d
        // com.aardarch:aardink before falling back to whatever is already on Maven Central.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "Aardink"
include(":editor")
include(":languages")
include(":languages-lsp")
include(":sample")
include(":tools:consumer-smoke")
