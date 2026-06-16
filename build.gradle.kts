plugins {
    java
    id("org.springframework.boot") version "3.4.4" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    // Publishes the open-source modules to Maven Central (Central Portal). Applied only to the
    // published subprojects below; the host (CENTRAL_PORTAL) and signing are driven by the
    // SONATYPE_HOST / RELEASE_SIGNING_ENABLED properties in gradle.properties.
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

// The version a build publishes. Release pipelines pass an explicit version via the
// `releaseVersion` Gradle property or the `RELEASE_VERSION` env var. When neither is set
// (the common case for a local `publishToMavenLocal`), default to a `-SNAPSHOT` version so a
// local build can never collide with — and silently shadow — a released tag in mavenLocal.
// See issue #31: a bare `0.1.0` default meant a HEAD build published to mavenLocal masked the
// genuinely older released `0.1.0`, breaking consumers that later switched the same coordinate
// back to the registry.
val releaseVersion = providers.gradleProperty("releaseVersion")
    .orElse(providers.environmentVariable("RELEASE_VERSION"))
    .orElse("0.1.0-SNAPSHOT")

allprojects {
    // Maven coordinate group. The artifacts publish to Maven Central under the GitHub-org-verified
    // `io.github.onec-erp` namespace (no domain ownership required). NOTE: this is the *publish
    // coordinate* only — the Java packages stay `com.onec.*` and the Gradle plugin id stays
    // `com.onec.desktop`. Consumers write `io.github.onec-erp:onec-framework-starter:<ver>` but still
    // `import com.onec...`.
    group = "io.github.onec-erp"
    version = releaseVersion.get()

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

// ---------------------------------------------------------------------------
// Maven Central publishing
// ---------------------------------------------------------------------------
// One convention for every published open-source module instead of a per-module `publishing {}`
// block. The vanniktech plugin auto-creates the sources + javadoc jars, signs the artifacts
// (RELEASE_SIGNING_ENABLED, skipped for -SNAPSHOT) and targets the Central Portal
// (SONATYPE_HOST=CENTRAL_PORTAL) — see gradle.properties. Here we only supply the shared POM
// metadata Central requires; the per-module name/description comes from the map below.
//
// `example` is not published; the desktop Gradle plugin lives in a separate included build and is
// released on its own.
val publishedModules = mapOf(
    "onec-framework" to "Core domain model, JDBI persistence, and entity-change events for the onec ERP toolkit.",
    "onec-framework-starter" to "Spring Boot autoconfiguration for the onec core: datasource, JDBC repositories, and JobRunr background jobs.",
    "onec-ui-starter" to "Server-driven admin UI starter for onec — bundles the frontend and its Spring MVC endpoints.",
    "onec-auth-starter" to "Authentication starter for onec: in-memory and OIDC / OAuth2 (Keycloak, Zitadel) single sign-on.",
    "onec-mcp-starter" to "Model Context Protocol (MCP) server starter exposing onec query and command services to AI agents.",
    "onec-kafka-starter" to "Kafka integration starter publishing onec entity-change events to topics.",
    "onec-print-starter" to "PDF / printing starter for onec using Thymeleaf templates and Flying Saucer.",
    "onec-mail-starter" to "Email starter for onec: SMTP and HTTP dispatch with Thymeleaf-templated bodies.",
    "onec-desktop-starter" to "Desktop (Tauri) packaging starter bundling the onec shell for native app builds.",
)

configure(subprojects.filter { it.name in publishedModules.keys }) {
    apply(plugin = "com.vanniktech.maven.publish")

    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        pom {
            name.set(project.name)
            description.set(publishedModules.getValue(project.name))
            inceptionYear.set("2025")
            url.set("https://github.com/onec-erp/onec-framework")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("onec-erp")
                    name.set("onec-erp")
                    url.set("https://github.com/onec-erp")
                }
            }
            scm {
                url.set("https://github.com/onec-erp/onec-framework")
                connection.set("scm:git:https://github.com/onec-erp/onec-framework.git")
                developerConnection.set("scm:git:ssh://git@github.com/onec-erp/onec-framework.git")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Community integrations catalog
// ---------------------------------------------------------------------------
// INTEGRATIONS.md is *generated* from community/registry.json (the source of truth). Edit the JSON,
// then run `./gradlew generateIntegrationsDoc` and commit both files. There is no CI gate — the
// regenerate step is part of the PR checklist (see CONTRIBUTING.md). Uses Gradle's bundled
// groovy-json, so it needs no extra dependency.
tasks.register("generateIntegrationsDoc") {
    group = "documentation"
    description = "Regenerate INTEGRATIONS.md from community/registry.json"

    val registryFile = layout.projectDirectory.file("community/registry.json")
    val outputFile = layout.projectDirectory.file("INTEGRATIONS.md")
    inputs.file(registryFile)
    outputs.file(outputFile)

    doLast {
        @Suppress("UNCHECKED_CAST")
        val data = groovy.json.JsonSlurper().parse(registryFile.asFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val integrations = (data["integrations"] as? List<Map<String, Any?>>).orEmpty()

        // Display order + heading + blurb per category (keys match registry.schema.json).
        val categories = linkedMapOf(
            "connector" to ("Connectors" to "Integrations that bind onec to an external system."),
            "spi" to ("SPI implementations" to "Pluggable implementations of framework contracts (storage, mail, auth, …)."),
            "ui" to ("UI extensions" to "Custom widgets, pages, and actions."),
            "skill" to ("Agent skills & plugins" to "Skills/plugins that make AI agents good at a domain."),
            "library" to ("Libraries & tools" to "General helpers built on the framework.")
        )

        fun cell(value: Any?): String = (value as? String ?: "").replace("|", "\\|")

        val sb = StringBuilder()
        sb.appendLine("<!-- GENERATED FILE — do not edit by hand.")
        sb.appendLine("     Source of truth: community/registry.json")
        sb.appendLine("     Regenerate:      ./gradlew generateIntegrationsDoc -->")
        sb.appendLine()
        sb.appendLine("# Community integrations")
        sb.appendLine()
        sb.appendLine("Third-party integrations the community has built on onec-framework. These projects are")
        sb.appendLine("maintained by their authors and are not endorsed by the onec-framework team — review before use.")
        sb.appendLine()
        sb.appendLine("> **Want to add yours?** Build it with [docs/EXTENDING.md](docs/EXTENDING.md), then add an entry")
        sb.appendLine("> to [`community/registry.json`](community/registry.json) and run `./gradlew generateIntegrationsDoc`.")
        sb.appendLine("> See [CONTRIBUTING.md](CONTRIBUTING.md#listing-a-community-integration).")
        sb.appendLine()

        if (integrations.isEmpty()) {
            sb.appendLine("## No integrations listed yet")
            sb.appendLine()
            sb.appendLine("Be the first — see [docs/EXTENDING.md](docs/EXTENDING.md) and open a PR adding your project to")
            sb.appendLine("[`community/registry.json`](community/registry.json).")
        } else {
            for ((key, meta) in categories) {
                val rows = integrations
                    .filter { it["category"] == key }
                    .sortedBy { (it["name"] as? String)?.lowercase() ?: "" }
                if (rows.isEmpty()) continue
                sb.appendLine("## ${meta.first}")
                sb.appendLine()
                sb.appendLine(meta.second)
                sb.appendLine()
                sb.appendLine("| Name | Description | Install | onec | License | Status |")
                sb.appendLine("| --- | --- | --- | --- | --- | --- |")
                for (row in rows) {
                    val name = row["name"] as? String ?: ""
                    val repo = row["repository"] as? String ?: ""
                    val nameCell = if (repo.isNotBlank()) "[${cell(name)}]($repo)" else cell(name)
                    val coords = row["coordinates"] as? String
                    val install = if (!coords.isNullOrBlank()) "`${coords}`" else "—"
                    sb.appendLine(
                        "| $nameCell | ${cell(row["description"])} | $install | " +
                            "${cell(row["onecVersion"])} | ${cell(row["license"])} | ${cell(row["status"])} |"
                    )
                }
                sb.appendLine()
            }
        }

        outputFile.asFile.writeText(sb.toString().trimEnd() + "\n")
        logger.lifecycle("Wrote ${outputFile.asFile.relativeTo(projectDir)} (${integrations.size} integration(s)).")
    }
}
// ---------------------------------------------------------------------------
// Generated configuration reference  (docs/CONFIGURATION.md)
// ---------------------------------------------------------------------------
// The property / type / default / meaning grid in docs/CONFIGURATION.md is GENERATED from each
// starter's `META-INF/spring-configuration-metadata.json` — emitted on every build by
// spring-boot-configuration-processor from the `@ConfigurationProperties` field Javadoc. The
// generator never invents content: descriptions come from the Javadoc, defaults from the metadata.
// Editorial prose (section intros, cross-references) lives under docs/_config/ and is merged in.
//
//   ./gradlew generateConfigDocs   # rewrite docs/CONFIGURATION.md from the metadata
//   ./gradlew checkConfigDocs      # fail if it drifted (wired into `check`)
//
// Missing a default the processor can't see (set in a constructor, not a field initializer)? Add it
// to that module's src/main/resources/META-INF/additional-spring-configuration-metadata.json — the
// canonical Spring mechanism — rather than hand-editing the table here.

val configDocFile = file("docs/CONFIGURATION.md")
val configNotesDir = file("docs/_config")

// Render order + human section titles. One entry per published starter that owns `onec.*` props.
val configModuleProjects = listOf(
    "onec-framework-starter", "onec-ui-starter", "onec-auth-starter", "onec-mcp-starter",
    "onec-import-starter", "onec-kafka-starter", "onec-mail-starter", "onec-print-starter",
    "onec-desktop-starter",
)

// Make property descriptions deterministic. spring-boot-configuration-processor reads each
// `@ConfigurationProperties` field's Javadoc into the metadata's `description`, but two Gradle
// optimisations silently defeat it on the property-owning modules:
//   * INCREMENTAL annotation processing wraps `Elements.getDocComment()` so it returns null — the
//     processor then emits names/types with blank descriptions.
//   * the BUILD CACHE can restore a stale metadata JSON produced before a Javadoc edit.
// Either makes `generateConfigDocs` / `checkConfigDocs` flaky. Forcing a non-incremental, uncached
// compile on just these small modules makes the processor read the doc comments fresh every run, so
// the generated docs are complete and identical across machines/CI.
configure(subprojects.filter { it.name in configModuleProjects }) {
    // Only the main compile emits spring-configuration-metadata.json, so scope this to compileJava
    // and leave test compilation on the fast incremental/cached path.
    tasks.named<JavaCompile>("compileJava") {
        options.isIncremental = false
        outputs.cacheIf { false }
    }
}
val configModuleTitles = mapOf(
    "onec-framework-starter" to "Core — `onec-framework-starter` (`OnecProperties`, prefix `onec`)",
    "onec-ui-starter" to "UI — `onec-ui-starter` (`UiProperties` prefix `onec.ui`, `MediaProperties` prefix `onec.media`)",
    "onec-auth-starter" to "Auth — `onec-auth-starter` (`OnecAuthProperties`, prefix `onec.auth`)",
    "onec-mcp-starter" to "MCP — `onec-mcp-starter` (`OnecMcpProperties`, prefix `onec.mcp`)",
    "onec-import-starter" to "Import — `onec-import-starter` (`OnecImportProperties`, prefix `onec.import`)",
    "onec-kafka-starter" to "Kafka — `onec-kafka-starter` (`OnecKafkaProperties`, prefix `onec.kafka`)",
    "onec-mail-starter" to "Mail — `onec-mail-starter` (`MailProperties`, prefix `onec.mail`)",
    "onec-print-starter" to "Print — `onec-print-starter` (`PrintProperties`, prefix `onec.print`)",
    "onec-desktop-starter" to "Desktop — `onec-desktop-starter` (`DesktopProperties`, prefix `onec.desktop`)",
)

// Collapse a fully-qualified type to readable simple names: java.util.List<java.lang.String> -> List<String>,
// com.onec.auth.OnecAuthProperties$Mode -> Mode. Only identifier runs are rewritten; <,> are kept.
fun simplifyConfigType(type: String?): String {
    if (type.isNullOrBlank()) return ""
    return Regex("[A-Za-z0-9_.$]+").replace(type) { m -> m.value.substringAfterLast('.').substringAfterLast('$') }
}

// Replace Javadoc inline tags with plain text, brace-aware so {@code {baseUrl}} survives.
fun stripJavadocTags(input: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < input.length) {
        if (input.startsWith("{@", i)) {
            val space = input.indexOf(' ', i)
            var depth = 0
            var close = -1
            var j = i
            while (j < input.length) {
                val c = input[j]
                if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) { close = j; break } }
                j++
            }
            if (space == -1 || close == -1 || space > close) { sb.append(input[i]); i++; continue }
            val tag = input.substring(i + 2, space).trim()
            val content = input.substring(space + 1, close).trim()
            when (tag) {
                "link", "linkplain" -> sb.append(content.substringBefore("(").substringAfterLast('.').substringAfterLast('#').trim())
                "code" -> sb.append('`').append(content).append('`')
                else -> sb.append(content) // literal, value, …
            }
            i = close + 1
        } else { sb.append(input[i]); i++ }
    }
    return sb.toString()
}

fun renderConfigDefault(value: Any?): String = when (value) {
    null -> "—"
    is List<*> -> if (value.isEmpty()) "—" else value.joinToString(", ") { "`$it`" }
    else -> "`$value`"
}

fun cleanConfigDescription(raw: String?): String =
    stripJavadocTags(raw ?: "").replace("|", "\\|").replace(Regex("\\s+"), " ").trim()

@Suppress("UNCHECKED_CAST")
fun renderConfigDocs(): String {
    val sb = StringBuilder()
    sb.append("# Configuration Reference\n\n")
    sb.append("<!-- GENERATED FILE — do not edit by hand.\n")
    sb.append("     Tables come from each starter's spring-configuration-metadata.json (the\n")
    sb.append("     @ConfigurationProperties Javadoc). Prose comes from docs/_config/. Regenerate with\n")
    sb.append("     `./gradlew generateConfigDocs`; `./gradlew check` fails if this file drifts. -->\n\n")

    file("$configNotesDir/intro.md").takeIf { it.exists() }?.let { sb.append(it.readText().trim()).append("\n\n") }

    val slurper = groovy.json.JsonSlurper()
    for (proj in configModuleProjects) {
        val json = file("$proj/build/classes/java/main/META-INF/spring-configuration-metadata.json")
        if (!json.exists()) throw GradleException("Missing config metadata for $proj ($json). Run `:$proj:classes` first.")
        val parsed = slurper.parse(json) as Map<String, Any?>
        val props = (parsed["properties"] as? List<Map<String, Any?>> ?: emptyList())
            .filter { (it["name"] as? String)?.startsWith("onec") == true }
            .sortedBy { it["name"] as String }

        sb.append("## ").append(configModuleTitles[proj] ?: proj).append("\n\n")
        sb.append("| Property | Type | Default | Meaning |\n| --- | --- | --- | --- |\n")
        for (p in props) {
            val type = simplifyConfigType(p["type"] as? String)
            sb.append("| `").append(p["name"] as String).append("` | ")
                // Backtick the type: it reads as code, and keeps generics like `List<String>` from
                // being parsed as HTML tags by Markdown renderers (e.g. VitePress's Vue compiler).
                .append(if (type.isBlank()) "" else "`$type`").append(" | ")
                .append(renderConfigDefault(p["defaultValue"])).append(" | ")
                .append(cleanConfigDescription(p["description"] as? String)).append(" |\n")
        }
        sb.append("\n")
        file("$configNotesDir/notes/$proj.md").takeIf { it.exists() }?.let { sb.append(it.readText().trim()).append("\n\n") }
    }

    file("$configNotesDir/appendix.md").takeIf { it.exists() }?.let { sb.append(it.readText().trim()).append("\n") }
    return sb.toString().trimEnd() + "\n"
}

tasks.register("generateConfigDocs") {
    group = "documentation"
    description = "Regenerate docs/CONFIGURATION.md from the starters' spring-configuration-metadata.json."
    configModuleProjects.forEach { dependsOn(":$it:classes") }
    inputs.dir(configNotesDir)
    outputs.file(configDocFile)
    doLast {
        configDocFile.writeText(renderConfigDocs())
        logger.lifecycle("Wrote ${configDocFile.relativeTo(projectDir)} from @ConfigurationProperties metadata.")
    }
}

tasks.register("checkConfigDocs") {
    group = "verification"
    description = "Fail if docs/CONFIGURATION.md is out of sync with the @ConfigurationProperties metadata."
    configModuleProjects.forEach { dependsOn(":$it:classes") }
    doLast {
        val expected = renderConfigDocs()
        val actual = if (configDocFile.exists()) configDocFile.readText() else ""
        if (expected != actual) {
            throw GradleException(
                "docs/CONFIGURATION.md is out of date with the @ConfigurationProperties metadata.\n" +
                "Run `./gradlew generateConfigDocs` and commit the result."
            )
        }
        logger.lifecycle("docs/CONFIGURATION.md is in sync with the configuration metadata.")
    }
}

// Make the drift guard part of the standard verification lifecycle.
tasks.named("check") { dependsOn("checkConfigDocs") }

// ---------------------------------------------------------------------------
// Aggregated Javadoc  (build/docs/javadoc) — the API reference for the docs site
// ---------------------------------------------------------------------------
// One combined Javadoc tree across the published modules. The docs workflow copies the output under
// the MkDocs site at /api, so the site's "Reference → Java API (Javadoc)" link resolves. Evaluating
// children first makes each module's `main` source set available here at configuration time.
evaluationDependsOnChildren()

tasks.register<Javadoc>("aggregateJavadoc") {
    group = "documentation"
    description = "Build combined Javadoc for the published modules into build/docs/javadoc."
    setDestinationDir(layout.buildDirectory.dir("docs/javadoc").get().asFile)

    val mains = subprojects
        .filter { it.name in publishedModules.keys }
        .map { it.extensions.getByType<SourceSetContainer>()["main"] }
    source(mains.map { it.allJava })
    classpath = files(mains.map { it.compileClasspath }, mains.map { it.output })
    subprojects.filter { it.name in publishedModules.keys }.forEach { dependsOn("${it.path}:classes") }

    title = "onec-framework API"
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
    // Lombok-generated accessors aren't visible to the Javadoc tool; don't fail the whole API doc
    // over individual missing-symbol warnings.
    isFailOnError = false
}
