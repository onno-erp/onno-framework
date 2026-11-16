pluginManagement {
    // The desktop packaging and app-UI plugins live in sibling builds so `id("su.onno.desktop")`
    // and `id("su.onno.ui")` resolve locally without first publishing them to a repository.
    includeBuild("onno-desktop-gradle-plugin")
    includeBuild("onno-ui-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "onno-framework"

include(
    "onno-framework",
    "onno-framework-starter",
    "onno-web-starter",
    "onno-auth-starter",
    "onno-mcp-starter",
    "onno-import-starter",
    "onno-print-starter",
    "onno-cluster-starter",
    "onno-kafka-starter",
    "onno-observability-starter",
    "onno-crm-starter",
    "onno-crm-channels-starter",
    "onno-desktop-starter",
    "example"
)
