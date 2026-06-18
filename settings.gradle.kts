pluginManagement {
    // The desktop packaging plugin lives in a sibling build so `id("su.onno.desktop")`
    // resolves locally without first publishing it to a repository.
    includeBuild("onno-desktop-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "onno-framework"

include(
    "onno-framework",
    "onno-framework-starter",
    "onno-ui-starter",
    "onno-auth-starter",
    "onno-mcp-starter",
    "onno-import-starter",
    "onno-cluster-starter",
    "onno-kafka-starter",
    "onno-print-starter",
    "onno-mail-starter",
    "onno-desktop-starter",
    "example"
)
