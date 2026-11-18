plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":onno-framework"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.context)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.data.jdbc)
    implementation(libs.jdbi3.spring5)
    implementation(libs.micrometer.core)
    implementation(libs.jackson.databind)
    // The service layer (su.onno.service) caches the resolved AccessSubject on the current request
    // when spring-web is present; it is optional so a non-web app does not need it.
    compileOnly(libs.spring.web)

    api(libs.jobrunr.spring.boot3.starter)

    // Cross-node live-UI bus (su.onno.cluster.pg). The PostgreSQL driver comes with a Postgres
    // DataSource at runtime; on H2 the bus is never instantiated, so org.postgresql.* never loads.
    compileOnly(libs.postgresql)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testImplementation(libs.spring.test)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.h2)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.postgresql)
    testImplementation(libs.mockito.core)
    // Service-layer tests: AccessService reads roles from the Spring Security context.
    testImplementation(libs.spring.security.core)
    testImplementation(libs.jackson.datatype.jsr310)
    // Architecture guardrails (su.onno.archunit.OnnoRules) over this module's main classes.
    testImplementation(project(":onno-archunit"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// TestEntityStore (src/testFixtures): the real Spring Data JDBC write path on a test's own
// DataSource, shared with the web/MCP starters' service tests. Not published.
dependencies {
    testFixturesImplementation(libs.spring.context)
    testFixturesImplementation(libs.spring.jdbc)
    testFixturesImplementation(libs.spring.data.jdbc)
    testFixturesImplementation(libs.jdbi3.spring5)
}
(components["java"] as AdhocComponentWithVariants).apply {
    withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
