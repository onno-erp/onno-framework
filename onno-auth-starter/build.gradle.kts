plugins {
    `java-library`
}

dependencies {
    // Shared auth↔UI contract (su.onno.auth.spi) lives here; exposed via api so the
    // AuthMethodsProvider bean's type is visible to consumers (the UI module).
    api(project(":onno-framework"))

    implementation(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.web)
    // onno.auth.mode=database keeps accounts in the application's DataSource (JdbcUserStore).
    implementation(libs.spring.jdbc)

    // OIDC support (Keycloak, Zitadel, …). Only exercised when onno.auth.mode = oidc /
    // resource-server; the standard Spring Boot auto-config for these stays dormant until
    // issuer/client properties are set, so in-memory deployments pay nothing but the jars.
    api(libs.spring.boot.starter.oauth2.client)
    api(libs.spring.boot.starter.oauth2.resource.server)

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.h2)
    // Architecture guardrails (su.onno.archunit.OnnoRules) over this module's main classes.
    testImplementation(project(":onno-archunit"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
