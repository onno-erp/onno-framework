plugins {
    `java-library`
    id("com.github.node-gradle.node") version "7.1.0"
}

// The UI is the npm package @onno/ui in the top-level onno-ui/ directory, one member of the
// repository's npm workspace (package.json at the root, with the starters' UI packages and the example
// app). One `npm install` at the root serves them all; this module builds its default shell and serves
// it from static/ui.
node {
    nodeProjectDir.set(rootProject.layout.projectDirectory)
    download.set(true)
    version.set("22.22.0")
}

val frontend = "../onno-ui"

// The default shell: what an application without UI code of its own serves.
val buildFrontend by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
    workingDir.set(file(frontend))
    inputs.dir("$frontend/src")
    inputs.dir("$frontend/public")
    inputs.file("$frontend/index.html")
    inputs.file("$frontend/package.json")
    inputs.file("$frontend/tsconfig.json")
    inputs.file("$frontend/tsconfig.app.json")
    inputs.file("$frontend/vite.config.ts")
    inputs.file("$frontend/vite-preset.js")
    inputs.file("$frontend/tailwind.config.js")
    outputs.dir("$frontend/dist")
}

// `tsc -b` over the tsconfig projects: the package, the Vitest suite (tests/, vite.config.ts and
// the CRM packages' sources the tests import) and the public API's type-level tests (tests/types), and the onnoUiInit template. buildFrontend checks only the package, so a type error
// in a test fails `check`, not packaging.
val typecheckFrontend by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "typecheck"))
    workingDir.set(file(frontend))
    inputs.dir("$frontend/src")
    inputs.dir("$frontend/tests")
    inputs.dir("../onno-crm-starter/src/main/ui")
    inputs.dir("../onno-crm-channels-starter/src/main/ui")
    inputs.file("$frontend/tsconfig.json")
    inputs.file("$frontend/tsconfig.app.json")
    inputs.file("$frontend/tsconfig.test.json")
    inputs.file("$frontend/tsconfig.types.json")
    // The app onnoUiInit seeds, type-checked against the current public API (tsconfig.template.json).
    inputs.dir("../onno-ui-gradle-plugin/src/main/resources/su/onno/ui/template")
    inputs.file("$frontend/tsconfig.template.json")
    inputs.file("$frontend/package.json")
    inputs.file("../package-lock.json")
    inputs.file("$frontend/vite.config.ts")
}

val testFrontend by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.named("npmInstall"))
    dependsOn(typecheckFrontend)
    args.set(listOf("run", "test:run"))
    workingDir.set(file(frontend))
    inputs.dir("$frontend/src")
    inputs.dir("$frontend/tests")
    inputs.dir("../onno-crm-starter/src/main/ui")
    inputs.dir("../onno-crm-channels-starter/src/main/ui")
    inputs.file("$frontend/package.json")
    inputs.file("../package-lock.json")
    inputs.file("$frontend/vite.config.ts")
}

// ESLint over the package (eslint.config.js): typescript-eslint, the rules of hooks, and the onno
// rules in eslint-plugin/ (also exported to apps as @onno/ui/eslint). Warnings report, errors fail.
val lintFrontend by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "lint"))
    workingDir.set(file(frontend))
    inputs.dir("$frontend/src")
    inputs.dir("$frontend/tests")
    inputs.dir("$frontend/eslint-plugin")
    inputs.file("$frontend/eslint.config.js")
    inputs.file("$frontend/vite.config.ts")
    inputs.file("$frontend/vite-preset.js")
    inputs.file("$frontend/tailwind.config.js")
    inputs.file("$frontend/package.json")
    inputs.file("../package-lock.json")
}

tasks.named("check") {
    dependsOn(testFrontend)
    dependsOn(lintFrontend)
}

tasks.named<Test>("test") {
    // The full-context record-policy conformance suite boots Spring Boot; slf4j-simple is the test
    // logger, so keep Boot from insisting on Logback.
    systemProperty("org.springframework.boot.logging.LoggingSystem", "none")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildFrontend)
    from("$frontend/dist") {
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
    // The service layer (su.onno.service) is part of this starter's API: controllers here and every
    // module built on it (import, print, CRM) call those beans.
    api(project(":onno-framework-starter"))

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
    testImplementation(testFixtures(project(":onno-framework-starter")))
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.spring.security.core)
    // The record-policy conformance suite boots a full web context (DataSource, JDBC, MockMvc).
    testImplementation(libs.spring.boot.starter.data.jdbc) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
    testImplementation(libs.spring.test)
    // Context-runner assertions for the auto-configuration gating tests.
    testImplementation(libs.spring.boot.test)
    // In-memory engine for the read-path query-service tests (e.g. information-register
    // related-list rows) — same lightweight pattern the framework module uses.
    testImplementation(libs.spring.jdbc) // Verify SSE observes only committed JDBC changes.
    testImplementation(libs.h2)
    // Jakarta constraints on entities flow through /validate and the generic write path.
    testImplementation(libs.hibernate.validator)
    testImplementation(libs.expressly)
    // Postgres-portability checks: the date-bound register/document queries can only be
    // verified against a real PostgreSQL (H2 silently casts varchar↔timestamp, so it never
    // reproduces the strict-typing failure). The IT skips when Docker is unavailable.
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
    // Architecture guardrails (su.onno.archunit.OnnoRules) over this module's main classes.
    testImplementation(project(":onno-archunit"))
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}
