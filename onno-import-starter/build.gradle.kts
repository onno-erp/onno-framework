plugins {
    `java-library`
}

dependencies {
    api(project(":onno-framework"))
    implementation(project(":onno-ui-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.commons.csv)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
