pluginManagement {
    // The app-UI plugin lives in a sibling build so `id("su.onno.ui")` resolves locally without
    // first publishing it to a repository.
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
    "onno-crm-starter",
    "onno-crm-channels-starter",
    "onno-archunit",
    "example"
)
