pluginManagement {
    // The desktop packaging plugin lives in a sibling build so `id("com.onec.desktop")`
    // resolves locally without first publishing it to a repository.
    includeBuild("onec-desktop-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "onec-framework"

include(
    "onec-framework",
    "onec-framework-starter",
    "onec-ui-starter",
    "onec-auth-starter",
    "onec-mcp-starter",
    "onec-kafka-starter",
    "onec-print-starter",
    "onec-mail-starter",
    "onec-desktop-starter",
    "onec-hospedajes-starter",
    "onec-guesty-starter",
    "example"
)
