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
    inputs.file("src/main/frontend/index.html")
    inputs.file("src/main/frontend/package.json")
    inputs.file("src/main/frontend/vite.config.ts")
    outputs.dir("src/main/frontend/dist")
}

val testFrontend by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "test:run"))
    workingDir.set(file("src/main/frontend"))
    inputs.dir("src/main/frontend/src")
    inputs.dir("src/main/frontend/tests")
    inputs.file("src/main/frontend/package.json")
    inputs.file("src/main/frontend/package-lock.json")
    inputs.file("src/main/frontend/vite.config.ts")
}

tasks.named("check") {
    dependsOn(testFrontend)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildFrontend)
    from("src/main/frontend/dist") {
        into("static/ui")
    }
    // Bake the build version into onno-build.properties so OnnoBuildInfo can read it at runtime.
    // Scoped to that one file with filesMatching so `expand` never touches the frontend dist (whose
    // minified JS is full of ${...} that would otherwise blow up template expansion).
    filesMatching("META-INF/onno-build.properties") {
        expand("onnoVersion" to project.version)
    }
}

dependencies {
    api(project(":onno-framework"))
    implementation(project(":onno-framework-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jdbi3.core)
    // @TransactionalEventListener (assignment notifications fire AFTER_COMMIT so the producer's
    // read-back sees the committed row). Present at runtime via onno-framework-starter's data-jdbc,
    // but that's an `implementation` dep so it isn't on this module's compile classpath. Pin an
    // explicit version (via the catalog) so the published POM carries one — a bare
    // `org.springframework:spring-tx` resolves transitively at build time but publishes versionless,
    // which the Maven Central Portal rejects ("Dependency version information is missing"; this broke
    // the 1.4.4–1.5.1 releases).
    implementation(libs.spring.tx)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.security.core)
    // Context-runner assertions for the auto-configuration gating tests.
    testImplementation(libs.spring.boot.test)
    // In-memory engine for the read-path query-service tests (e.g. information-register
    // related-list rows) — same lightweight pattern the framework module uses.
    testImplementation(libs.h2)
    // Postgres-portability checks: the date-bound register/document queries can only be
    // verified against a real PostgreSQL (H2 silently casts varchar↔timestamp, so it never
    // reproduces the strict-typing failure). The IT skips when Docker is unavailable.
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}
