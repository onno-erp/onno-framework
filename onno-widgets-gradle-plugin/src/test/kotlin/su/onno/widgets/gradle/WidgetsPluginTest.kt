package su.onno.widgets.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
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
}
