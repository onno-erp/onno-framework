plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "onec-mail-starter"
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

    // Optional: enables MailDispatcher to render bodies via the print starter when present
    compileOnly(project(":onec-print-starter"))

    implementation("org.springframework.boot:spring-boot-autoconfigure:3.4.4")
    implementation("org.springframework:spring-context:6.2.5")

    // SMTP transport (Spring's JavaMailSender). Pulls jakarta.mail.
    api("org.springframework.boot:spring-boot-starter-mail:3.4.4")

    // For body templating when the print starter isn't present
    api("org.thymeleaf:thymeleaf:3.1.2.RELEASE")

    api("com.fasterxml.jackson.core:jackson-databind:2.18.3")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.4")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.4")

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
}
