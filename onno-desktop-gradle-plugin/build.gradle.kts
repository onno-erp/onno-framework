plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "su.onno"
// Track the same version the root build publishes — the `releaseVersion` Gradle property or the
// `RELEASE_VERSION` env var, both propagated to this included build — so the desktop plugin is
// released in lockstep with the framework artifacts instead of pinned to a stale literal.
// Defaults to -SNAPSHOT locally so a HEAD build never shadows a released tag in mavenLocal.
version = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .orElse("0.1.0-SNAPSHOT")
    .get()

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Lets the plugin reference BootJar / LoaderImplementation when it tunes the
    // app's fat jar for the embedded desktop runtime. Provided at apply time by
    // the consuming build, hence compileOnly.
    compileOnly(libs.spring.boot.gradle.plugin)
    compileOnly(libs.spring.boot.loader.tools)
}

gradlePlugin {
    plugins {
        create("onnoDesktop") {
            id = "su.onno.desktop"
            implementationClass = "su.onno.desktop.gradle.DesktopPlugin"
            displayName = "onno desktop packaging"
            description = "Packages an onno Spring Boot app into a native desktop bundle via Tauri."
        }
    }
}
