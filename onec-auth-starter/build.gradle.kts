plugins {
    `java-library`
}

dependencies {
    // Shared auth↔UI contract (com.onec.auth.spi) lives here; exposed via api so the
    // AuthMethodsProvider bean's type is visible to consumers (the UI module).
    api(project(":onec-framework"))

    implementation(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.web)

    // OIDC support (Keycloak, Zitadel, …). Only exercised when onec.auth.mode = oidc /
    // resource-server; the standard Spring Boot auto-config for these stays dormant until
    // issuer/client properties are set, so in-memory deployments pay nothing but the jars.
    api(libs.spring.boot.starter.oauth2.client)
    api(libs.spring.boot.starter.oauth2.resource.server)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
