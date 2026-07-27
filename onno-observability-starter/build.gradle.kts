plugins {
    `java-library`
}

dependencies {
    api(project(":onno-framework"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.context)
    implementation(libs.spring.web)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}
