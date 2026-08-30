plugins {
    application
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    // Thin development consumer for the reusable CRM starter.
    implementation(project(":onno-crm-starter"))
    implementation(project(":onno-auth-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.h2database:h2")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("su.onno.crmexample.CrmApp")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
