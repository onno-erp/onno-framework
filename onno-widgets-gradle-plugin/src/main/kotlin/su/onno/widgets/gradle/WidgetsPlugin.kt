package su.onno.widgets.gradle

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.NodePlugin
import com.github.gradle.node.task.NodeTask
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources

/**
 * User-facing configuration for [WidgetsPlugin], exposed as the `onnoWidgets {}` block. Conventions
 * make a bare `id("su.onno.widgets")` work: drop `.tsx` files in `src/main/widgets` and build.
 */
abstract class WidgetsExtension {
    /** Directory of widget sources (`*.tsx` / `*.jsx`). Default `src/main/widgets`. */
    abstract val sourceDir: DirectoryProperty

    /** Managed Node version used to run esbuild. Default `20.18.0` (matches the framework's build). */
    abstract val nodeVersion: Property<String>

    /**
     * Whether to write a `tsconfig.json` next to the widget sources so an IDE resolves
     * `@onno/widget-sdk` and `react` types. Default true; never overwrites an existing file.
     */
    abstract val generateIdeConfig: Property<Boolean>
}

/**
 * Compiles consumer-authored React widgets into onno UI plugins with no frontend project to manage.
 *
 * A widget is a `.tsx` in `src/main/widgets` that calls `registerWidget("type", Component)` from
 * `@onno/widget-sdk`. The plugin drives a managed Node + esbuild toolchain (the SDK is bundled inside
 * this plugin, installed via a `file:` dependency — no npm registry needed) to bundle each widget
 * into `onno-plugins/<name>.js`, with React aliased to the host SPA so the output carries no React of
 * its own. That file ships in the app jar; the onno-ui-starter serves it and the SPA loads it at boot.
 *
 * Tasks added:
 *  - `onnoWidgetsPrepare` — materialise the build workspace (SDK, `package.json`, esbuild driver).
 *  - `compileWidgets`     — bundle every widget into `onno-plugins/` (wired before `processResources`).
 *  - `compileWidgetsWatch`— rebuild on change for the dev loop.
 */
class WidgetsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("onnoWidgets", WidgetsExtension::class.java)
        ext.sourceDir.convention(project.layout.projectDirectory.dir("src/main/widgets"))
        ext.nodeVersion.convention(NODE_VERSION)
        ext.generateIdeConfig.convention(true)

        val workDir = project.layout.buildDirectory.dir("onno-widgets")
        // Compiled bundles land under a generated resources root at onno-plugins/<name>.js; that root
        // is added as a resource srcDir, so the files ship in the jar at classpath /onno-plugins/.
        val resourcesRoot = workDir.map { it.dir("resources") }
        val distDir = resourcesRoot.map { it.dir("onno-plugins") }

        // Managed Node, rooted at our generated workspace so npmInstall/esbuild run there.
        project.plugins.apply(NodePlugin::class.java)
        val node = project.extensions.getByType(NodeExtension::class.java)
        node.download.set(true)
        node.version.set(ext.nodeVersion)
        node.nodeProjectDir.set(workDir)

        val prepare = project.tasks.register("onnoWidgetsPrepare") {
            group = GROUP
            description = "Materialise the widget build workspace (SDK, package.json, esbuild driver)."
            outputs.dir(workDir)
            doLast {
                val work = workDir.get().asFile
                work.mkdirs()
                materializeSdk(project, work)
                writeIfChanged(File(work, "package.json"), WORKSPACE_PACKAGE_JSON)
                writeIfChanged(File(work, "build.mjs"), BUILD_MJS)
                if (ext.generateIdeConfig.get()) {
                    writeIdeConfig(ext.sourceDir.get().asFile, work)
                }
            }
        }

        // npm install runs in the workspace once it's been materialised.
        project.tasks.named("npmInstall").configure { dependsOn(prepare) }

        val compile = project.tasks.register<NodeTask>("compileWidgets") {
            group = GROUP
            description = "Bundle src/main/widgets/*.tsx into onno-plugins/ (React aliased to the host)."
            dependsOn("npmInstall")
            script.set(workDir.map { d -> d.file("build.mjs") })
            args.set(project.provider {
                listOf(ext.sourceDir.get().asFile.absolutePath, distDir.get().asFile.absolutePath)
            })
            inputs.dir(ext.sourceDir).optional().withPropertyName("widgetSources")
            outputs.dir(distDir)
            onlyIf { hasWidgets(ext.sourceDir.get().asFile) }
        }

        project.tasks.register<NodeTask>("compileWidgetsWatch") {
            group = GROUP
            description = "Rebuild widgets on change (dev loop)."
            dependsOn("npmInstall")
            script.set(workDir.map { d -> d.file("build.mjs") })
            args.set(project.provider {
                listOf(ext.sourceDir.get().asFile.absolutePath, distDir.get().asFile.absolutePath, "--watch")
            })
        }

        // Only fold widget compilation into the resource/jar build when the project actually has
        // widgets — otherwise applying the plugin would force a Node download on every build. Gradle
        // re-configures each invocation, so adding the first widget and rebuilding picks it up.
        project.afterEvaluate {
            if (hasWidgets(ext.sourceDir.get().asFile)) {
                project.extensions.findByType(SourceSetContainer::class.java)
                    ?.findByName("main")?.resources?.srcDir(resourcesRoot)
                project.tasks.withType<ProcessResources>().configureEach { dependsOn(compile) }
            }
        }
    }

    private fun hasWidgets(dir: File): Boolean =
        dir.isDirectory && dir.listFiles { f -> f.isFile && WIDGET_EXT.matches(f.name) }?.isNotEmpty() == true

    /** Extract the bundled @onno/widget-sdk into `<work>/sdk`, unless already at this plugin version. */
    private fun materializeSdk(project: Project, work: File) {
        val sdkDir = File(work, "sdk")
        val marker = File(sdkDir, ".onno-sdk-version")
        val pluginVersion = javaClass.classLoader.getResourceAsStream("onno-widgets.version")
            ?.bufferedReader()?.use { it.readText().trim() } ?: "dev"
        if (marker.isFile && marker.readText() == pluginVersion) return
        val stream = javaClass.classLoader.getResourceAsStream("onno-widget-sdk.zip")
            ?: error("onno-widgets: bundled onno-widget-sdk.zip missing from the plugin jar")
        val tmp = File.createTempFile("onno-widget-sdk", ".zip")
        try {
            stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            project.delete(sdkDir)
            project.copy {
                from(project.zipTree(tmp))
                into(sdkDir)
            }
            marker.writeText(pluginVersion)
        } finally {
            tmp.delete()
        }
    }

    private fun writeIfChanged(file: File, content: String) {
        if (file.isFile && file.readText() == content) return
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    /** Write an IDE tsconfig beside the widget sources so `@onno/widget-sdk`/`react` types resolve. */
    private fun writeIdeConfig(sourceDir: File, work: File) {
        sourceDir.mkdirs()
        val tsconfig = File(sourceDir, "tsconfig.json")
        if (tsconfig.exists()) return
        val sdk = File(work, "sdk").absolutePath.replace("\\", "/")
        val nm = File(work, "node_modules").absolutePath.replace("\\", "/")
        tsconfig.writeText(
            """
            {
              "// generated by": "su.onno.widgets — resolves SDK + React types for the editor; safe to gitignore",
              "compilerOptions": {
                "target": "ES2020",
                "lib": ["ES2020", "DOM", "DOM.Iterable"],
                "module": "ESNext",
                "moduleResolution": "Bundler",
                "jsx": "react-jsx",
                "jsxImportSource": "react",
                "strict": true,
                "skipLibCheck": true,
                "esModuleInterop": true,
                "baseUrl": ".",
                "paths": {
                  "@onno/widget-sdk": ["$sdk/src/index.ts"],
                  "@onno/widget-sdk/*": ["$sdk/*"],
                  "react": ["$nm/@types/react"],
                  "react/*": ["$nm/@types/react/*"]
                }
              },
              "include": ["**/*.ts", "**/*.tsx", "**/*.jsx"]
            }
            """.trimIndent() + "\n"
        )
    }

    companion object {
        private const val GROUP = "onno widgets"
        private const val NODE_VERSION = "20.18.0"
        private val WIDGET_EXT = Regex(".*\\.(tsx|jsx)$")

        private val WORKSPACE_PACKAGE_JSON = """
            {
              "name": "onno-widgets-workspace",
              "private": true,
              "type": "module",
              "dependencies": {
                "@onno/widget-sdk": "file:./sdk"
              },
              "devDependencies": {
                "esbuild": "^0.24.0",
                "typescript": "~5.6.2",
                "@types/react": "^18.3.18",
                "react": "^18.3.1",
                "tailwindcss": "^3.4.17",
                "postcss": "^8.4.49",
                "tailwindcss-animate": "^1.0.7"
              }
            }
        """.trimIndent() + "\n"

        private val BUILD_MJS = """
            // Generated by su.onno.widgets. Bundles each widget in <srcDir> into <outDir>/<name>.js as an
            // ESM module, with React + the automatic JSX runtime aliased to the host SPA (window.onno) so
            // the output ships with no React of its own. Resolution is rooted at this workspace so the
            // bundled @onno/widget-sdk resolves regardless of where the widget sources live.
            //
            // It also runs Tailwind over the widget sources and emits <outDir>/onno-widgets.css, so
            // utility classes in a widget's own markup produce real CSS (utilities the host doesn't
            // itself emit are otherwise silently dropped — the widget compiles outside the host's
            // Tailwind build). The stylesheet is utilities-only with preflight OFF (no global reset)
            // and carries the host's design tokens (colors → hsl(var(--…)), the rounded-control/field/
            // card radii), which resolve against the CSS variables the host defines at runtime — so a
            // widget's classes match the product and never fight the host stylesheet.
            import { build, context, transform } from "esbuild";
            import { readdirSync, existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
            import { join, parse, dirname } from "node:path";
            import { fileURLToPath } from "node:url";
            import postcss from "postcss";
            import tailwindcss from "tailwindcss";
            import tailwindcssAnimate from "tailwindcss-animate";

            const workDir = dirname(fileURLToPath(import.meta.url));
            const args = process.argv.slice(2);
            const watch = args.includes("--watch");
            const [srcDir, outDir] = args.filter((a) => a !== "--watch");

            if (!srcDir || !existsSync(srcDir)) {
              console.log("[onno-widgets] no widget source dir: " + srcDir);
              process.exit(0);
            }
            const entries = readdirSync(srcDir).filter((f) => /\.(tsx|jsx)${'$'}/.test(f));
            if (entries.length === 0) {
              console.log("[onno-widgets] no widgets to compile in " + srcDir);
              process.exit(0);
            }
            rmSync(outDir, { recursive: true, force: true });
            mkdirSync(outDir, { recursive: true });

            const options = (f) => ({
              entryPoints: [join(srcDir, f)],
              outfile: join(outDir, parse(f).name + ".js"),
              bundle: true,
              format: "esm",
              platform: "browser",
              target: "es2020",
              jsx: "automatic",
              minify: !watch,
              sourcemap: watch,
              legalComments: "none",
              logLevel: "warning",
              absWorkingDir: workDir,
              nodePaths: [join(workDir, "node_modules")],
              alias: {
                react: "@onno/widget-sdk/react-shim",
                "react/jsx-runtime": "@onno/widget-sdk/jsx-runtime-shim",
              },
            });

            // Mirrors onno-ui-starter's tailwind.config.js theme so widget utilities resolve to the
            // same tokens the host uses. Keep in sync with that file when the host theme changes.
            const tailwindConfig = {
              content: [join(srcDir, "**/*.{tsx,jsx,ts,js}")],
              darkMode: ["class"],
              // No preflight: the host already owns the global reset; a second one would fight it.
              corePlugins: { preflight: false },
              theme: {
                extend: {
                  colors: {
                    border: "hsl(var(--border))",
                    input: "hsl(var(--input))",
                    ring: "hsl(var(--ring))",
                    background: "hsl(var(--background))",
                    foreground: "hsl(var(--foreground))",
                    primary: { DEFAULT: "hsl(var(--primary))", foreground: "hsl(var(--primary-foreground))" },
                    secondary: { DEFAULT: "hsl(var(--secondary))", foreground: "hsl(var(--secondary-foreground))" },
                    destructive: { DEFAULT: "hsl(var(--destructive))", foreground: "hsl(var(--destructive-foreground))" },
                    muted: { DEFAULT: "hsl(var(--muted))", foreground: "hsl(var(--muted-foreground))" },
                    accent: { DEFAULT: "hsl(var(--accent))", foreground: "hsl(var(--accent-foreground))" },
                    popover: { DEFAULT: "hsl(var(--popover))", foreground: "hsl(var(--popover-foreground))" },
                    card: { DEFAULT: "hsl(var(--card))", foreground: "hsl(var(--card-foreground))" },
                  },
                  borderRadius: {
                    lg: "var(--radius)",
                    md: "calc(var(--radius) - 2px)",
                    sm: "calc(var(--radius) - 4px)",
                    pill: "var(--radius-control)",
                    control: "var(--radius-control)",
                    field: "var(--radius-field)",
                    panel: "var(--radius-card)",
                    card: "var(--radius-card)",
                  },
                },
              },
              plugins: [tailwindcssAnimate],
            };

            async function emitCss() {
              // Utilities only: base (preflight) and components come from the host's own stylesheet.
              const result = await postcss([tailwindcss(tailwindConfig)]).process("@tailwind utilities;", {
                from: undefined,
              });
              let css = result.css;
              if (!watch) css = (await transform(css, { loader: "css", minify: true })).code;
              writeFileSync(join(outDir, "onno-widgets.css"), css);
              console.log("[onno-widgets] wrote onno-widgets.css (" + css.length + " bytes)");
            }

            if (watch) {
              for (const f of entries) {
                const ctx = await context(options(f));
                await ctx.watch();
              }
              // CSS is generated once on start (no live rescan on class edits in the dev loop).
              await emitCss();
              console.log(`[onno-widgets] watching ${'$'}{entries.length} widget(s) in ${'$'}{srcDir}`);
            } else {
              await Promise.all(entries.map((f) => build(options(f))));
              await emitCss();
              console.log(`[onno-widgets] compiled ${'$'}{entries.length} widget(s) -> ${'$'}{outDir}`);
            }
        """.trimIndent() + "\n"
    }
}
