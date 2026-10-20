plugins {
    `java-library`
}

dependencies {
    api(project(":onno-framework"))
    implementation(project(":onno-ui-starter"))

    api(libs.poi.ooxml)

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.web)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
