rootProject.name = "onno-widgets-gradle-plugin"

// This is an included build (see the root settings.gradle.kts pluginManagement block), so the main
// build's version catalog is not inherited automatically — import it here so `libs.*` resolve.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
