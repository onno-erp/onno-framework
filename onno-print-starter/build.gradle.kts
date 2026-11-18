plugins {
    `java-library`
}

dependencies {
    api(project(":onno-framework"))
    // api: PrintController and the contributor beans expose UI-starter types in public signatures (#464).
    api(project(":onno-web-starter"))

    api(libs.poi.ooxml)

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jdbi3.core)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.h2)
    testImplementation(libs.spring.boot.test)
    // Architecture guardrails (su.onno.archunit.OnnoRules) over this module's main classes.
    testImplementation(project(":onno-archunit"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

