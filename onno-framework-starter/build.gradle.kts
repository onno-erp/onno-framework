plugins {
    `java-library`
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
    testImplementation(libs.jackson.datatype.jsr310)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
