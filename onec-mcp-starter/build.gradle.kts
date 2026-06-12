plugins {
    `java-library`
}

dependencies {
    api(project(":onec-framework"))
    // Query + command services and the UiAccessService authz chokepoint live here.
    implementation(project(":onec-ui-starter"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.web)
    // Security is optional at runtime; the /mcp filter chain is conditional on it.
    compileOnly(libs.spring.boot.starter.security)

    // Official MCP Java SDK: core protocol/transport + Jackson JSON binding.
    api(platform(libs.mcp.bom))
    api("io.modelcontextprotocol.sdk:mcp-core")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2")

    compileOnly(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.security)
    testImplementation("io.modelcontextprotocol.sdk:mcp-json-jackson2")
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}
