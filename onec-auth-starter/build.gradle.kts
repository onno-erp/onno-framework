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
            artifactId = "onec-auth-starter"
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
    // Shared auth↔UI contract (com.onec.auth.spi) lives here; exposed via api so the
    // AuthMethodsProvider bean's type is visible to consumers (the UI module).
    api(project(":onec-framework"))

    implementation("org.springframework.boot:spring-boot-autoconfigure:3.4.4")
    api("org.springframework.boot:spring-boot-starter-security:3.4.4")
    implementation("org.springframework.boot:spring-boot-starter-web:3.4.4")

    // OIDC support (Keycloak, Zitadel, …). Only exercised when onec.auth.mode = oidc /
    // resource-server; the standard Spring Boot auto-config for these stays dormant until
    // issuer/client properties are set, so in-memory deployments pay nothing but the jars.
    api("org.springframework.boot:spring-boot-starter-oauth2-client:3.4.4")
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server:3.4.4")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.4")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test:3.4.4")
    testImplementation("org.springframework.boot:spring-boot-test:3.4.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
