plugins { `java-library` }
dependencies {
    api(project(":onno-crm-starter"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jdbc)
    implementation("org.springframework.boot:spring-boot-starter-mail:3.4.4")
    implementation("org.jsoup:jsoup:1.18.3")
    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    testImplementation(libs.h2)
    testImplementation(libs.spring.boot.starter.test)
    // Architecture guardrails (su.onno.archunit.OnnoRules) over this module's main classes.
    testImplementation(project(":onno-archunit"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

