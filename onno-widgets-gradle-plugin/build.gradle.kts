import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "su.onno"
// Track the same version the root build publishes (see the desktop plugin for the rationale), so the
// widgets plugin releases in lockstep with the framework and the bundled SDK.
version = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .orElse("0.1.0-SNAPSHOT")
    .get()

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Drives the managed Node install + npm/esbuild invocations the plugin adds to a consumer build.
    implementation("com.github.node-gradle:gradle-node-plugin:7.1.0")
}

// Bundle the @onno/widget-sdk source into the plugin jar as a single zip resource. At apply time the
// plugin extracts it into the consumer's build workspace and installs it via a file: dependency — so
// authors get the SDK (types + runtime bindings) with no npm registry access and one source of truth.
val sdkZip by tasks.registering(Zip::class) {
    archiveFileName.set("onno-widget-sdk.zip")
    destinationDirectory.set(layout.buildDirectory.dir("sdk-zip"))
    from(layout.projectDirectory.dir("../onno-widget-sdk")) {
        exclude("node_modules", "dist", "**/*.tgz")
    }
}

// Stamp the plugin version into a resource so the plugin can bust its extracted-SDK cache on upgrade.
val versionResource by tasks.registering {
    val out = layout.buildDirectory.file("version-resource/onno-widgets.version")
    val v = version.toString()
    inputs.property("version", v)
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeText(v) }
}

tasks.named<ProcessResources>("processResources") {
    from(sdkZip)
    from(versionResource)
}

gradlePlugin {
    plugins {
        create("onnoWidgets") {
            id = "su.onno.widgets"
            implementationClass = "su.onno.widgets.gradle.WidgetsPlugin"
            displayName = "onno custom widgets"
            description = "Compiles consumer-authored React widgets (src/main/widgets/*.tsx) into onno UI plugins."
        }
    }
}
