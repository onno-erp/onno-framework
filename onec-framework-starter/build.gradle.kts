plugins {
    `java-library`
}

dependencies {
    api(project(":onec-framework"))

    implementation("org.springframework.boot:spring-boot-autoconfigure:3.4.4")
    implementation("org.springframework:spring-context:6.2.5")
    implementation("org.springframework:spring-jdbc:6.2.5")
    implementation("org.springframework.data:spring-data-jdbc:3.4.4")
    implementation("io.micrometer:micrometer-core:1.14.5")

    api("org.jobrunr:jobrunr-spring-boot-3-starter:7.3.2")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.4")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.4")

    testCompileOnly("org.projectlombok:lombok:1.18.36")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.36")
    testImplementation("org.springframework:spring-test:6.2.5")
    testImplementation("io.micrometer:micrometer-core:1.14.5")
    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
