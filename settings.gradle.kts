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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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
