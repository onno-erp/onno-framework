plugins {
    application
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("su.onno.desktop")
    // Compiles src/main/widgets/*.tsx into onno UI widget plugins (see DashboardPage's "eventLog").
    id("su.onno.widgets")
}

dependencies {
    implementation(project(":onno-framework-starter"))
    implementation(project(":onno-ui-starter"))
    implementation(project(":onno-auth-starter"))
    implementation(project(":onno-mcp-starter"))
    implementation(project(":onno-import-starter"))
    implementation(project(":onno-print-starter"))
    implementation(project(":onno-mail-starter"))
    implementation(project(":onno-desktop-starter"))
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
onnoDesktop {
    productName.set("Rentals ERP")
    identifier.set("com.example.rentals")
    iconSource.set(layout.projectDirectory.file("src/main/desktop/icon.png"))
}
