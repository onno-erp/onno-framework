plugins {
    `java-library`
}

dependencies {
    api(project(":onno-framework"))
    implementation(project(":onno-framework-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.jackson.databind)

    // The PostgreSQL driver is provided at runtime by a Postgres deployment (it is the DataSource);
    // on H2 the bus is never instantiated, so org.postgresql.* is never loaded.
    compileOnly(libs.postgresql)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.h2)
    testImplementation(libs.jackson.datatype.jsr310)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
