plugins {
    application
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.onec.desktop")
}

dependencies {
    implementation(project(":onec-framework-starter"))
    implementation(project(":onec-ui-starter"))
    implementation(project(":onec-auth-starter"))
    implementation(project(":onec-mcp-starter"))
    implementation(project(":onec-import-starter"))
    implementation(project(":onec-print-starter"))
    implementation(project(":onec-mail-starter"))
    implementation(project(":onec-desktop-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("com.h2database:h2")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}

application {
    mainClass.set("com.example.ExampleApp")
}

// Native desktop bundle: `./gradlew :example:packageDesktop`.
// Requires a Rust toolchain + cargo-tauri.
onecDesktop {
    productName.set("Rentals ERP")
    identifier.set("com.example.rentals")
    iconSource.set(layout.projectDirectory.file("src/main/desktop/icon.png"))
}
