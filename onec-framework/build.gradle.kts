plugins {
    `java-library`
}

dependencies {
    api(libs.jdbi3.core)
    api(libs.spring.data.commons)
    compileOnly(libs.jackson.annotations)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(libs.slf4j.api)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testImplementation(libs.h2)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
