plugins {
    `java-library`
}

// Package the generic Tauri shell sources into the jar under `onno-desktop-shell/`
// so the `su.onno.desktop` Gradle plugin can extract them for any consumer that
// only has this starter as a binary dependency.
tasks.named<ProcessResources>("processResources") {
    from("src/main/tauri") {
        into("onno-desktop-shell")
    }
}

dependencies {
    api(project(":onno-framework"))
    implementation(project(":onno-framework-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.web)

    // Persist HTTP sessions in the app's (file-backed, relocated-to-home) datasource
    // so a desktop login survives the JVM restart that every app launch performs.
    // Bundling this in the desktop starter scopes "stay logged in" to desktop apps.
    implementation(libs.spring.session.jdbc)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
}
