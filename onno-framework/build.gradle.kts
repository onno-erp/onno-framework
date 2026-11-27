plugins {
    `java-library`
}

dependencies {
    api(libs.jdbi3.core)
    api(libs.spring.data.commons)
    compileOnly(libs.jackson.annotations)
    // Jakarta Bean Validation is optional: field constraints (@NotBlank, @Size, ...) are enforced
    // when the application brings a provider (spring-boot-starter-validation); without it nothing
    // in core touches jakarta.validation (only su.onno.validation.JakartaBridge imports it).
    compileOnly(libs.jakarta.validation.api)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(libs.slf4j.api)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testImplementation(libs.h2)
    testImplementation(libs.hibernate.validator)
    testImplementation(libs.expressly)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // Property-based tests (jqwik runs as its own JUnit Platform engine beside Jupiter).
    testImplementation(libs.jqwik)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
    // Architecture guardrails (su.onno.archunit.OnnoRules) over this module's main classes.
    testImplementation(project(":onno-archunit"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
