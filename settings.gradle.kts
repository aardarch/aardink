// When included as a composite build, this project needs its own local.properties with sdk.dir.
// Auto-populate it from ANDROID_HOME so developers don't need a separate setup step.
val localPropsFile = file("local.properties")
val localProps = java.util.Properties().apply {
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
        google()
        mavenCentral()
    }
}

rootProject.name = "Aardink"
include(":editor")
include(":languages")
include(":sample")
