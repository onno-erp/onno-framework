plugins {
    application
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":onec-framework-spring-boot-starter"))
    implementation(project(":onec-ui-spring-boot-starter"))
    implementation(project(":onec-auth-spring-boot-starter"))
    implementation(project(":onec-print-starter"))
    implementation(project(":onec-mail-starter"))
    implementation(project(":onec-hospedajes-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("com.h2database:h2")
    implementation("org.projectlombok:lombok:1.18.42")

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
}

application {
    mainClass.set("com.example.ExampleApp")
}
