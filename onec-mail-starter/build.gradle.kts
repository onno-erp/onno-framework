plugins {
    `java-library`
}

dependencies {
    api(project(":onec-framework"))
    implementation(project(":onec-framework-starter"))

    // Optional: enables MailDispatcher to render bodies via the print starter when present
    compileOnly(project(":onec-print-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.context)

    // Optional web layer: universal HTTP dispatcher (RestClient) + preview/webhook controllers.
    // compileOnly so apps without spring-web still use the starter; beans are conditionally wired.
    compileOnly(libs.spring.web)

    // SMTP transport (Spring's JavaMailSender). Pulls jakarta.mail.
    api(libs.spring.boot.starter.mail)

    // For body templating when the print starter isn't present
    api(libs.thymeleaf)

    api(libs.jackson.databind)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
