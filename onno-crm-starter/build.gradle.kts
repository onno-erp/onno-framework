plugins {
    `java-library`
}

dependencies {
    api(project(":onno-framework-starter"))
    api(project(":onno-web-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.data.jdbc)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.tx)
    implementation(libs.spring.jdbc)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.h2)
    testImplementation(libs.jdbi3.spring5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}


tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
