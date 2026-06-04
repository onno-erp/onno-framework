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
            artifactId = "onec-mcp-starter"
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
    // Query + command services and the UiAccessService authz chokepoint live here.
    implementation(project(":onec-ui-starter"))

    implementation("org.springframework.boot:spring-boot-autoconfigure:3.4.4")
    implementation("org.springframework.boot:spring-boot-starter-web:3.4.4")
    // Security is optional at runtime; the /mcp filter chain is conditional on it.
    compileOnly("org.springframework.boot:spring-boot-starter-security:3.4.4")

    // Official MCP Java SDK: core protocol/transport + Jackson JSON binding.
    api(platform("io.modelcontextprotocol.sdk:mcp-bom:1.1.3"))
    api("io.modelcontextprotocol.sdk:mcp-core")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.4")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.4")

    testImplementation("org.springframework.boot:spring-boot-starter-security:3.4.4")
    testImplementation("io.modelcontextprotocol.sdk:mcp-json-jackson2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
