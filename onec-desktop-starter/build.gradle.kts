plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
    withJavadocJar()
}

// Package the generic Tauri shell sources into the jar under `onec-desktop-shell/`
// so the `com.onec.desktop` Gradle plugin can extract them for any consumer that
// only has this starter as a binary dependency.
tasks.named<ProcessResources>("processResources") {
    from("src/main/tauri") {
        into("onec-desktop-shell")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "onec-desktop-starter"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/onec-erp/onec-framework")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

dependencies {
    api(project(":onec-framework"))
    implementation(project(":onec-framework-starter"))

    implementation("org.springframework.boot:spring-boot-autoconfigure:3.4.4")
    implementation("org.springframework.boot:spring-boot-starter-web:3.4.4")

    // Persist HTTP sessions in the app's (file-backed, relocated-to-home) datasource
    // so a desktop login survives the JVM restart that every app launch performs.
    // Bundling this in the desktop starter scopes "stay logged in" to desktop apps.
    implementation("org.springframework.session:spring-session-jdbc:3.4.1")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.4")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.4")
}
