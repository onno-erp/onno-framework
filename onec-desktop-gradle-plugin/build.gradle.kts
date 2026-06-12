plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.onec"
version = "0.1.0"

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
        create("onecDesktop") {
            id = "com.onec.desktop"
            implementationClass = "com.onec.desktop.gradle.DesktopPlugin"
            displayName = "onec desktop packaging"
            description = "Packages an onec Spring Boot app into a native desktop bundle via Tauri."
        }
    }
}
