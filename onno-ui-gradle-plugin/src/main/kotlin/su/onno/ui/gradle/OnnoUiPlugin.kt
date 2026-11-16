package su.onno.ui.gradle

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.NodePlugin
import com.github.gradle.node.npm.task.NpmTask
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources

/** The `onnoUi {}` block. A bare `id("su.onno.ui")` needs none of it. */
abstract class OnnoUiExtension {
    /** The app's UI: an ordinary Vite app (`package.json`, `vite.config.ts`, `main.tsx`). Default `src/main/ui`. */
    abstract val sourceDir: DirectoryProperty

    /**
     * Where `npm install` runs: the UI directory itself (default), or the root of an npm workspace
     * the UI belongs to.
     */
    abstract val npmProjectDir: DirectoryProperty

    /** Managed Node version. Default `22.22.0`. */
    abstract val nodeVersion: Property<String>

    /**
     * Extra packages whose entities go into the generated model (the app's own top-level packages
     * are always scanned), e.g. a starter's entities the UI uses. Default none.
     */
    abstract val modelPackages: ListProperty<String>
}

/**
 * Builds an onno app's React UI into its jar.
 *
 * The UI in `src/main/ui` is a normal Vite app that imports `@onno/ui`. This plugin only does what
 * needs Gradle:
 *
 *  - `generateUiModel` — writes `model.gen.ts` (TypeScript types of the Java entities) into the UI.
 *  - `buildUi`         — `npm run build`, into `classpath:/onno-app/`, which the server serves in
 *                        place of the default shell. Wired before `processResources`.
 *  - `onnoUiInit`      — seeds `src/main/ui` with a starter app whose default screens are wired
 *                        explicitly (never overwrites); the template is `resources/su/onno/ui/template`.
 *
 * For development, run `npm run dev` in `src/main/ui` next to the running backend.
 */
class OnnoUiPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("onnoUi", OnnoUiExtension::class.java)
        ext.sourceDir.convention(project.layout.projectDirectory.dir("src/main/ui"))
        ext.npmProjectDir.convention(ext.sourceDir)
        ext.nodeVersion.convention(NODE_VERSION)
        ext.modelPackages.convention(emptyList())

        project.plugins.apply(NodePlugin::class.java)
        val node = project.extensions.getByType(NodeExtension::class.java)
        node.download.set(true)
        node.version.set(ext.nodeVersion)
        node.nodeProjectDir.set(ext.npmProjectDir)

        val outputRoot = project.layout.buildDirectory.dir("onno-ui")
        val hasUi = { File(ext.sourceDir.get().asFile, "package.json").isFile }

        project.tasks.register("onnoUiInit") {
            group = GROUP
            description = "Seed src/main/ui with a minimal onno app (never overwrites)."
            doLast {
                val dir = ext.sourceDir.get().asFile
                val written = UiTemplate.files(pluginVersion()).mapNotNull { (path, content) ->
                    val file = File(dir, path)
                    if (file.exists()) null else {
                        file.parentFile.mkdirs()
                        file.writeText(content)
                        path
                    }
                }
                logger.lifecycle(if (written.isEmpty()) "onno: $dir already has every skeleton file"
                    else "onno: seeded $dir with ${written.joinToString()}")
            }
        }

        val build = project.tasks.register<NpmTask>("buildUi") {
            group = GROUP
            description = "Build the app UI (src/main/ui) with Vite into the jar's onno-app/."
            dependsOn("npmInstall")
            workingDir.fileProvider(ext.sourceDir.map { it.asFile })
            args.set(listOf("run", "build"))
            val out = outputRoot.map { it.dir("onno-app") }
            environment.put("ONNO_UI_OUT_DIR", out.map { it.asFile.absolutePath })
            inputs.files(project.fileTree(ext.sourceDir) { exclude("node_modules/**", "dist/**") })
                .withPropertyName("ui")
            // The @onno packages the app compiles against, wherever the workspace links them from.
            inputs.files(project.provider { onnoPackageSources(project, ext.sourceDir.get().asFile) })
                .withPropertyName("onnoPackages")
            outputs.dir(out)
            onlyIf { hasUi() }
        }

        project.tasks.named("npmInstall").configure { onlyIf { hasUi() } }

        // The typed model, generated from the compiled Java entities. Needs the onno framework on the
        // runtime classpath (it runs the framework's generator).
        project.plugins.withId("java") {
            val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
            val modelFile = project.provider { File(ext.sourceDir.get().asFile, MODEL_FILE) }
            val generate = project.tasks.register<JavaExec>("generateUiModel") {
                group = GROUP
                description = "Generate TypeScript types for the Java business model into src/main/ui/model.gen.ts."
                // Compiled classes + dependencies only: the full runtime classpath would pull in
                // processResources, which waits for the UI build.
                dependsOn(main.compileJavaTaskName)
                classpath = project.files(main.output.classesDirs,
                    project.configurations.getByName(main.runtimeClasspathConfigurationName))
                mainClass.set(MODEL_GENERATOR)
                inputs.files(main.output.classesDirs).withPropertyName("classes")
                inputs.property("modelPackages", ext.modelPackages)
                outputs.file(modelFile).withPropertyName("model")
                argumentProviders.add {
                    listOf(modelFile.get().absolutePath,
                        main.output.classesDirs.files.joinToString(File.pathSeparator) { it.absolutePath }) +
                        ext.modelPackages.get()
                }
                onlyIf { hasUi() && classpath.files.any { it.name.startsWith("onno-framework-starter") } }
            }
            build.configure { dependsOn(generate) }
        }

        // Fold the UI into the jar only when the project has one, so applying the plugin to a project
        // without a UI never downloads Node.
        project.afterEvaluate {
            if (hasUi()) {
                project.extensions.findByType(SourceSetContainer::class.java)
                    ?.findByName("main")?.resources?.srcDir(outputRoot)
                project.tasks.withType<ProcessResources>().configureEach { dependsOn(build) }
                project.tasks.matching { it.name == "sourcesJar" }.configureEach { dependsOn(build) }
            }
        }
    }

    /** Source files of the @onno packages installed for the UI (symlinked in a workspace). */
    private fun onnoPackageSources(project: Project, uiDir: File): List<File> {
        var dir: File? = uiDir
        while (dir != null) {
            val scope = File(dir, "node_modules/@onno")
            if (scope.isDirectory) {
                return scope.listFiles().orEmpty().map { it.canonicalFile }.flatMap { pkg ->
                    project.fileTree(pkg) { exclude("node_modules/**", "dist/**") }.files
                }
            }
            dir = dir.parentFile
        }
        return emptyList()
    }

    private fun pluginVersion(): String =
        javaClass.classLoader.getResourceAsStream("onno-ui.version")?.bufferedReader()?.use { it.readText().trim() }
            ?: "2.0.0"

    companion object {
        const val GROUP = "onno"
        const val NODE_VERSION = "22.22.0"
        const val MODEL_FILE = "model.gen.ts"
        const val MODEL_GENERATOR = "su.onno.spring.codegen.TypeScriptModelGenerator"
    }
}
