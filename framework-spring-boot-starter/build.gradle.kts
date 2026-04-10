plugins {
    `java-library`
}

dependencies {
    api(project(":framework"))

    implementation("org.springframework.boot:spring-boot-autoconfigure:3.4.4")
    implementation("org.springframework:spring-context:6.2.5")
    implementation("org.springframework:spring-jdbc:6.2.5")
    implementation("org.springframework.data:spring-data-jdbc:3.4.4")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.4")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.4")
}
