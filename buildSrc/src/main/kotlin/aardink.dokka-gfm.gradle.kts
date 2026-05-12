import java.net.URI
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.jetbrains.dokka.gradle.DokkaExtension

// Applies Dokka and registers a Markdown (GFM) output format alongside the
// default HTML. Exposes `dokkaGenerateHtml` and `dokkaGenerateMarkdown`
// tasks on the project.
plugins {
    id("org.jetbrains.dokka")
}

apply<DokkaMarkdownPlugin>()

// AGP 9 compiles Kotlin via the embedded `KotlinBaseApiPlugin`, not the
// standalone `org.jetbrains.kotlin.android` plugin, and `android.newDsl=true`
// (the AGP 9 default) hides the legacy `BaseExtension`. Neither Dokka adapter
// can find what it's looking for, so we register the source set ourselves and
// wire its classpath.
//
// We could import `com.android.build.api.variant.LibraryAndroidComponentsExtension`
// directly, but adding AGP to buildSrc's classpath collides with the module-
// level `alias(libs.plugins.plugin.android.library)` plugin resolution. So we
// grab `androidComponents.sdkComponents.bootClasspath` reflectively. Ugly, but
// confined to this one block.
val bootClasspath: Provider<List<RegularFile>> = provider {
    val androidComponents = extensions.findByName("androidComponents")
        ?: return@provider emptyList<RegularFile>()
    val sdkComponents = androidComponents.javaClass
        .getMethod("getSdkComponents")
        .invoke(androidComponents)
    @Suppress("UNCHECKED_CAST")
    (
        sdkComponents.javaClass
            .getMethod("getBootClasspath")
            .invoke(sdkComponents) as Provider<List<RegularFile>>
        ).get()
}

extensions.configure<DokkaExtension> {
    dokkaSourceSets.register("main") {
        sourceRoots.from("src/main/java")

        // Android boot classpath (android.jar) — resolves framework types
        // like `WindowInsets`, `DrawScope`, etc.
        classpath.from(bootClasspath)

        // Module compile classpath — gives Dokka the Compose, AndroidX, and
        // kotlinx-coroutines jars so KDoc refs like `[LaunchedEffect]` or
        // `[Dispatchers.Default]` resolve. Use an artifactView with the AGP
        // `artifactType` attribute pinned to `android-classes-jar`, otherwise
        // resolution of project-to-project deps (e.g. languages → editor) is
        // ambiguous across the many AGP variants.
        classpath.from(
            configurations.named("debugCompileClasspath").map { cfg ->
                cfg.incoming.artifactView {
                    attributes {
                        attribute(
                            org.gradle.api.attributes.Attribute.of(
                                "artifactType",
                                String::class.java,
                            ),
                            "android-classes-jar",
                        )
                    }
                    lenient(true)
                }.files
            },
        )

        // Turn resolved external references into hyperlinks.
        externalDocumentationLinks.register("androidx") {
            url.set(URI("https://developer.android.com/reference/kotlin/"))
            packageListUrl.set(
                URI("https://developer.android.com/reference/kotlin/androidx/package-list"),
            )
        }
        externalDocumentationLinks.register("coroutines") {
            url.set(URI("https://kotlinlang.org/api/kotlinx.coroutines/"))
        }
    }
}
