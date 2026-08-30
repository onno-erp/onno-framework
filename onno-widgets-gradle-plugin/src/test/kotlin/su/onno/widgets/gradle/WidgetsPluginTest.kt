package su.onno.widgets.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WidgetsPluginTest {

    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `style file name is unique to the artifact coordinate`() {
        assertThat(widgetStyleFileName("com.acme", "inventory-ui"))
            .isEqualTo("com-acme-inventory-ui-widgets.css")
        assertThat(widgetStyleFileName("org.example", "inventory-ui"))
            .isNotEqualTo(widgetStyleFileName("com.acme", "inventory-ui"))
    }

    @Test
    fun `prepared build driver watches source changes and rebuilds css`() {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"inventory-ui\"\n")
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
            plugins { id("su.onno.widgets") }
            group = "com.acme"
        """.trimIndent())

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("onnoWidgetsPrepare", "--stacktrace")
            .build()

        val driver = Files.readString(projectDir.resolve("build/onno-widgets/build.mjs"))
        assertThat(driver)
            .contains("watch as watchFiles", "watchFiles(srcDir", "cssBuild.then(emitCss)")
    }

    @Test
    fun `workspace package json includes sorted consumer dependencies`() {
        val json = workspacePackageJson(mapOf("zod" to "^3.24.0", "date-fns" to "^4.1.0"))

        assertThat(json).contains(
            "\"@onno/widget-sdk\": \"file:./sdk\"",
            "\"date-fns\": \"^4.1.0\"",
            "\"zod\": \"^3.24.0\"",
        )
        assertThat(json.indexOf("date-fns")).isLessThan(json.indexOf("zod"))
    }

    @Test
    fun `consumer dependencies cannot replace the host react runtime`() {
        val error = assertThrows<IllegalArgumentException> {
            workspacePackageJson(mapOf("react" to "18.3.1"))
        }

        assertThat(error.message).contains("must not override framework-managed package 'react'")
    }

    @Test
    fun `prepare materializes consumer npm dependencies and IDE resolution`() {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"inventory-ui\"\n")
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
            plugins { id("su.onno.widgets") }
            group = "com.acme"
            onnoWidgets {
                npmDependencies.put("date-fns", "^4.1.0")
            }
        """.trimIndent())

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("onnoWidgetsPrepare", "--stacktrace")
            .build()

        assertThat(Files.readString(projectDir.resolve("build/onno-widgets/package.json")))
            .contains("\"date-fns\": \"^4.1.0\"")
        assertThat(Files.readString(projectDir.resolve("src/main/widgets/tsconfig.json")))
            .contains("\"*\": [")
    }

    @Test
    fun `published widget module builds widgets before its sources jar`() {
        Files.createDirectories(projectDir.resolve("src/main/widgets"))
        Files.writeString(projectDir.resolve("src/main/widgets/Inbox.tsx"), "export default function Inbox() {}\n")
        Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"crm-ui\"\n")
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
            plugins {
                `java-library`
                id("su.onno.widgets")
            }
            java { withSourcesJar() }
            tasks.register("assertSourcesJarDependencies") {
                doLast {
                    val sourcesJar = tasks.named("sourcesJar").get()
                    val dependencyNames = sourcesJar.taskDependencies
                        .getDependencies(sourcesJar)
                        .map { it.name }
                    check("compileWidgets" in dependencyNames) {
                        "sourcesJar must depend on compileWidgets, got ${'$'}dependencyNames"
                    }
                }
            }
        """.trimIndent())

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("assertSourcesJarDependencies", "--stacktrace")
            .build()
    }
}
