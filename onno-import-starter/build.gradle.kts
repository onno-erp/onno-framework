plugins {
    `java-library`
}

dependencies {
    api(project(":onno-framework"))
    implementation(project(":onno-web-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.commons.csv)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.h2)
    // TestEntityStore: imports through the real generic write path.
    testImplementation(testFixtures(project(":onno-framework-starter")))
    // Architecture guardrails (su.onno.archunit.OnnoRules) over this module's main classes.
    testImplementation(project(":onno-archunit"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
