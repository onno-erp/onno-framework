plugins {
    `java-library`
    `maven-publish`
    id("com.github.node-gradle.node") version "7.1.0"
}

java {
    withSourcesJar()
    withJavadocJar()
}

node {
    nodeProjectDir.set(file("src/main/frontend"))
    download.set(true)
    version.set("20.18.0")
}

val buildFrontend by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
    workingDir.set(file("src/main/frontend"))
    inputs.dir("src/main/frontend/src")
    inputs.dir("src/main/frontend/public")
    inputs.file("src/main/frontend/package.json")
    inputs.file("src/main/frontend/vite.config.ts")
    outputs.dir("src/main/frontend/dist")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildFrontend)
    from("src/main/frontend/dist") {
        into("static/ui")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "onec-ui-starter"
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
    implementation("org.jdbi:jdbi3-core:3.45.4")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.4")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
