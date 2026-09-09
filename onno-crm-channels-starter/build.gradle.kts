plugins { `java-library`; id("su.onno.widgets") }
dependencies {
    api(project(":onno-crm-starter"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jdbc)
    implementation("org.springframework.boot:spring-boot-starter-mail:3.4.4")
    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

onnoWidgets { npmDependencies.put("lucide-react", "^0.469.0") }
