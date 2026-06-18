plugins {
    `java-library`
}

dependencies {
    api(project(":onno-framework"))
    implementation(project(":onno-framework-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.context)

    api(libs.thymeleaf)
    api(libs.flying.saucer.pdf.openpdf)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}
