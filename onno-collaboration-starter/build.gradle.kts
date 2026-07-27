plugins {
    `java-library`
    id("su.onno.widgets")
}

onnoWidgets {
    stylesheetName.set("onno-collaboration.css")
}

dependencies {
    api(project(":onno-ui-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.tx)
    implementation(libs.jdbi3.core)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.spring.security.core)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}
