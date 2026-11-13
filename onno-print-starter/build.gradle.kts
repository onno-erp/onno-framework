plugins {
    `java-library`
    // Compiles src/main/widgets/*.tsx (the printTemplates admin widget) into this starter's jar.
    id("su.onno.widgets")
}

dependencies {
    api(project(":onno-framework"))
    implementation(project(":onno-ui-starter"))

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
    testRuntimeOnly(libs.junit.platform.launcher)
}

onnoWidgets {
    npmDependencies.put("lucide-react", "^0.469.0")
}
