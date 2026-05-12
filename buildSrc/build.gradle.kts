plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

// Keep in sync with `plugin-dokka` in ../gradle/libs.versions.toml — buildSrc
// can't read the catalog, and our DokkaMarkdownPlugin compiles against the
// Dokka Gradle plugin's @InternalDokkaGradlePluginApi types, so the versions
// must match.
dependencies {
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0")
}
