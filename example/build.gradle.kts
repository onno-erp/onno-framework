plugins {
    application
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("su.onno.desktop")
    // Compiles src/main/widgets/*.tsx into onno UI widget plugins (see DashboardPage's "eventLog").
    id("su.onno.widgets")
}

dependencies {
    // Core only. The example deliberately depends on framework + UI + auth and nothing else, so the
    // "simple features" (catalogs, documents, posting, the generated REST API and DivKit UI, role
    // profiles) are shown working end-to-end without the optional starters (mcp/import/mail/print/
    // desktop) in the way. Add a starter back here when you want to demo that module.
    implementation(project(":onno-framework-starter"))
    implementation(project(":onno-ui-starter"))
    implementation(project(":onno-auth-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("com.h2database:h2")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}

application {
    mainClass.set("com.example.BookstoreApp")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // onno reads constructor/record parameter names reflectively; keep them in the bytecode.
    options.compilerArgs.add("-parameters")
}
