package su.onno.ui.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OnnoUiPluginTest {

    @TempDir
    lateinit var projectDir: Path

    private fun project(extra: String = "") {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"inventory\"\n")
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
            plugins { java; id("su.onno.ui") }
            $extra
        """.trimIndent())
    }

    private fun run(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .build()

    @Test
    fun `init seeds a Vite app on @onno ui and never overwrites`() {
        project()
        val ui = projectDir.resolve("src/main/ui")
        Files.createDirectories(ui.resolve("pages"))
        Files.writeString(ui.resolve("pages/HomePage.tsx"), "// mine\n")

        run("onnoUiInit")

        for (f in listOf("package.json", "vite.config.ts", "tsconfig.json", "index.html", "main.tsx", "pages/README.md")) {
            assertThat(ui.resolve(f)).exists()
        }
        assertThat(Files.readString(ui.resolve("package.json"))).contains("\"@onno/ui\"")
        assertThat(Files.readString(ui.resolve("vite.config.ts"))).contains("onno()")
        assertThat(Files.readString(ui.resolve("main.tsx"))).contains("defineApp(")
        assertThat(Files.readString(ui.resolve("pages/HomePage.tsx"))).isEqualTo("// mine\n")
    }

    @Test
    fun `a project without a UI skips the build and never installs node`() {
        project()
        val result = run("processResources")
        assertThat(result.task(":processResources")?.outcome).isIn(TaskOutcome.NO_SOURCE, TaskOutcome.UP_TO_DATE, TaskOutcome.SUCCESS)
        assertThat(result.task(":buildUi")).isNull()
        assertThat(result.task(":npmInstall")).isNull()
    }

    @Test
    fun `a project with a UI builds it before resources and ships it under onno-app`() {
        project()
        val ui = projectDir.resolve("src/main/ui")
        Files.createDirectories(ui)
        Files.writeString(ui.resolve("package.json"), "{}\n")

        val result = run("processResources", "--dry-run")
        assertThat(result.output).contains(":npmInstall SKIPPED", ":buildUi SKIPPED", ":processResources SKIPPED")
        assertThat(result.output.indexOf(":buildUi")).isLessThan(result.output.indexOf(":processResources"))
    }

    @Test
    fun `npm can run at the root of a workspace the UI belongs to`() {
        project("""
            onnoUi { npmProjectDir.set(layout.projectDirectory) }
            tasks.register("printNpmDir") {
                val dir = project.extensions.getByType(com.github.gradle.node.NodeExtension::class.java).nodeProjectDir
                doLast { println("npm dir: " + dir.get().asFile.name) }
            }
        """.trimIndent())
        assertThat(run("printNpmDir").output).contains("npm dir: " + projectDir.fileName)
    }
}
