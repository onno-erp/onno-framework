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
            artifactId = "onec-hospedajes-starter"
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
    implementation(project(":onec-framework-spring-boot-starter"))

    implementation("org.springframework.boot:spring-boot-autoconfigure:3.4.4")

    // JAXB for marshalling the parte-de-viajeros XML payload (validated against MIR XSDs).
    api("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    runtimeOnly("org.glassfish.jaxb:jaxb-runtime:4.0.5")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.4")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
