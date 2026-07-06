pluginManagement {
    // The desktop packaging + custom-widget plugins live in sibling builds so `id("su.onno.desktop")`
    // and `id("su.onno.widgets")` resolve locally without first publishing them to a repository.
    includeBuild("onno-desktop-gradle-plugin")
    includeBuild("onno-widgets-gradle-plugin")
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
