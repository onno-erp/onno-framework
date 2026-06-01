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
    implementation(project(":onec-print-starter"))
    implementation(project(":onec-mail-starter"))
    implementation(project(":onec-desktop-starter"))
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

// Native desktop bundle: `./gradlew :example:packageDesktop`.
// Requires a Rust toolchain + cargo-tauri.
onecDesktop {
    productName.set("Rentals ERP")
    identifier.set("com.example.rentals")
    iconSource.set(layout.projectDirectory.file("src/main/desktop/icon.png"))
}
