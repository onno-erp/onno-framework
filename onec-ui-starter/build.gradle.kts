plugins {
    `java-library`
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    nodeProjectDir.set(file("src/main/frontend"))
    download.set(true)
    version.set("20.18.0")
}

val buildFrontend by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
    workingDir.set(file("src/main/frontend"))
    inputs.dir("src/main/frontend/src")
    inputs.dir("src/main/frontend/public")
    inputs.file("src/main/frontend/package.json")
    inputs.file("src/main/frontend/vite.config.ts")
    outputs.dir("src/main/frontend/dist")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildFrontend)
    from("src/main/frontend/dist") {
        into("static/ui")
    }
}

dependencies {
    api(project(":onec-framework"))
    implementation(project(":onec-framework-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jdbi3.core)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.security.core)
    // Postgres-portability checks: the date-bound register/document queries can only be
    // verified against a real PostgreSQL (H2 silently casts varchar↔timestamp, so it never
    // reproduces the strict-typing failure). The IT skips when Docker is unavailable.
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}
