import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "su.onno"
// Track the same version the root build publishes (see the desktop plugin for the rationale), so the
// plugin releases in lockstep with the framework and @onno/ui.
version = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .orElse("2.0.0-SNAPSHOT")
    .get()

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Drives the managed Node install and the npm invocations the plugin adds to a consumer build.
    implementation("com.github.node-gradle:gradle-node-plugin:7.1.0")
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

tasks.test {
    useJUnitPlatform()
}

// Stamp the plugin version into a resource: onnoUiInit seeds the matching @onno/ui version.
val versionResource by tasks.registering {
    val out = layout.buildDirectory.file("version-resource/onno-ui.version")
    val v = version.toString()
    inputs.property("version", v)
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeText(v) }
}

// The onnoUiInit template is a plain file tree in resources; a jar cannot list its resources, so
// write the tree's relative paths to an index the plugin reads (see UiTemplate).
val templateDir = layout.projectDirectory.dir("src/main/resources/su/onno/ui/template")
val templateIndex by tasks.registering {
    val out = layout.buildDirectory.file("template-index/template.index")
    inputs.dir(templateDir)
    outputs.file(out)
    doLast {
        val root = templateDir.asFile
        val paths = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(paths.joinToString("\n", postfix = "\n"))
    }
}

tasks.named<ProcessResources>("processResources") {
    from(versionResource)
    from(templateIndex) { into("su/onno/ui") }
}

gradlePlugin {
    plugins {
        create("onnoUi") {
            id = "su.onno.ui"
            implementationClass = "su.onno.ui.gradle.OnnoUiPlugin"
            displayName = "onno app UI"
            description = "Builds an onno app's React UI (src/main/ui, a Vite app on @onno/ui) into its jar, with TypeScript types generated from the Java model."
        }
    }
}

// Publish the plugin jar + its plugin-marker to the onno Maven repository (repo.onno.su), the channel the
// framework artifacts mirror to, so a containerized tenant build (Kaniko, no local checkout) can
// resolve `id("su.onno.ui")` by version instead of via a local includeBuild. This build is a
// pluginManagement includeBuild, so the root's publishAllPublicationsToOnnoCloudRepository never
// reaches it — CI invokes this project's task explicitly. Mirrors the registry repo defined in the
// root build.gradle.kts; open-core artifacts are served anonymously on GET, publish is token-gated.
publishing {
    repositories {
        maven {
            name = "onnoCloud"
            val registryUrl = providers.gradleProperty("onnoRegistryUrl")
                .orElse(providers.environmentVariable("ONNO_REGISTRY_URL"))
                .orElse("https://repo.onno.su/releases").get()
            setUrl(registryUrl)
            isAllowInsecureProtocol = registryUrl.startsWith("http://")
            credentials {
                username = providers.gradleProperty("onnoRegistryUser")
                    .orElse(providers.environmentVariable("ONNO_REGISTRY_USER"))
                    .orElse("publisher").get()
                password = providers.gradleProperty("onnoRegistryToken")
                    .orElse(providers.environmentVariable("ONNO_REGISTRY_TOKEN"))
                    .orElse("").get()
            }
        }
    }
}
