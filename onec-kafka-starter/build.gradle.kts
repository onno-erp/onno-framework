plugins {
    `java-library`
}

dependencies {
    api(project(":onec-framework"))
    implementation(project(":onec-framework-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.web)
    implementation(libs.spring.kafka)
    implementation(libs.jackson.databind)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.h2)
    testImplementation(libs.jackson.datatype.jsr310)
    testRuntimeOnly(libs.junit.platform.launcher)
}
