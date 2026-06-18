package su.onno.desktop.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RelativePath
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.loader.tools.LoaderImplementation

/**
 * User-facing configuration for [DesktopPlugin], exposed as the `onnoDesktop {}`
 * block. Everything has a sensible convention so a bare `id("su.onno.desktop")`
 * already works for an app that uses the Spring Boot plugin.
 */
abstract class DesktopExtension {
    /** Bundle / installer product name. Defaults to the project name. */
    abstract val productName: Property<String>

    /** Reverse-DNS bundle identifier. Defaults to `su.onno.<project>`. */
    abstract val identifier: Property<String>

    /** Bundle version. Defaults to the project version. */
    abstract val appVersion: Property<String>

    /** The Spring Boot jar to embed. Defaults to the `bootJar` output. */
    abstract val mainJar: RegularFileProperty

    /** JDK modules included in the jlinked runtime. */
    abstract val jlinkModules: ListProperty<String>

    /** A 1024×1024 PNG used to generate platform icons (`cargo tauri icon`). */
    abstract val iconSource: RegularFileProperty

    /**
     * The Tauri shell sources. Defaults to the `:onno-desktop-starter` project's
     * `src/main/tauri` in a source checkout, or the shell embedded in the starter
     * artifact for external consumers. Override only when using a custom shell.
     */
    abstract val shellSource: DirectoryProperty

    /** Base command used to drive Tauri. Defaults to `["cargo", "tauri"]`. */
    abstract val tauriCommand: ListProperty<String>

    /** Tauri bundle targets. Defaults to `app`; use `["app", "dmg"]` for a DMG too. */
    abstract val bundleTargets: ListProperty<String>

    /** macOS code signing identity, for example `Developer ID Application: Name (TEAMID)`. */
    abstract val macSigningIdentity: Property<String>

    /** Optional macOS entitlements plist used by Tauri when signing. */
    abstract val macEntitlements: RegularFileProperty

    /** Whether Tauri should enable hardened runtime for macOS signing. */
    abstract val macHardenedRuntime: Property<Boolean>

    /** Optional Apple provider short name used during macOS packaging/notarization. */
    abstract val macProviderShortName: Property<String>

    /** Optional minimum supported macOS version for the generated app bundle. */
    abstract val macMinimumSystemVersion: Property<String>
}

/**
 * Adds a `packageDesktop` task that turns an onno Spring Boot app into a native
 * desktop bundle. The pipeline:
 *
 *  1. `desktopRuntime` — jlink a trimmed JRE.
 *  2. `desktopShell`   — materialise the generic Tauri shell, substituting the
 *                        product name / version / identifier into tauri.conf.json.
 *  3. `desktopStage`   — drop the bootJar and the jlinked runtime into the shell
 *                        as Tauri resources.
 *  4. `desktopIcons`   — (optional) generate platform icons from `iconSource`.
 *  5. `packageDesktop` — `cargo tauri build` → .dmg / .msi / .AppImage.
 */
class DesktopPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("onnoDesktop", DesktopExtension::class.java)

        ext.productName.convention(project.provider { project.name })
        ext.identifier.convention(project.provider { "su.onno.${project.name}" })
        ext.appVersion.convention(project.provider { project.version.toString() })
        ext.jlinkModules.convention(
            listOf(
                "java.base", "java.sql", "java.naming", "java.desktop", "java.management",
                "java.instrument", "java.security.jgss", "java.logging", "java.net.http",
                "jdk.crypto.ec", "jdk.unsupported",
                // The `jar:` NIO filesystem provider. Libraries that read resources
                // off the classpath as a FileSystem (JobRunr scans its SQL migration
                // directory this way) throw ProviderNotFoundException without it — the
                // crash is invisible under `bootRun` (full JDK) and only bites the
                // trimmed bundle, where it leaves the desktop window spinning forever.
                "jdk.zipfs"
            )
        )
        ext.tauriCommand.convention(listOf("cargo", "tauri"))
        ext.bundleTargets.convention(listOf("app"))
        ext.macSigningIdentity.convention(project.providers.environmentVariable("APPLE_SIGNING_IDENTITY"))
        ext.macHardenedRuntime.convention(true)
        ext.macProviderShortName.convention(project.providers.environmentVariable("APPLE_PROVIDER_SHORT_NAME"))
        val starterProject = project.rootProject.findProject(":onno-desktop-starter")
        starterProject?.let { starter ->
            ext.shellSource.convention(
                project.layout.dir(project.provider { starter.file("src/main/tauri") })
            )
        }

        val embeddedShell = if (starterProject == null) {
            project.configurations.create("onnoDesktopShell") {
                isCanBeConsumed = false
                isCanBeResolved = true
                defaultDependencies {
                    add(project.dependencies.create("su.onno:onno-desktop-starter:$STARTER_VERSION"))
                }
            }
        } else {
            null
        }

        // Default the jar to the Spring Boot fat jar once that plugin is present.
        project.plugins.withId("org.springframework.boot") {
            val bootJar = project.tasks.named("bootJar", BootJar::class.java)
            ext.mainJar.convention(bootJar.flatMap { it.archiveFile })
            // Spring Boot 3.2+ defaults to the `nested:` jar URL scheme, which some
            // libraries can't read off the classpath — notably JobRunr, which scans
            // its SQL migration resources from inside its own nested jar. The desktop
            // shell runs the app straight from this fat jar, so the embedded server
            // would crash on boot and the window would spin on the splash forever.
            // The classic loader format keeps those resources readable.
            bootJar.configure { loaderImplementation.set(LoaderImplementation.CLASSIC) }
        }

        val workDir = project.layout.buildDirectory.dir("onno-desktop")
        val shellDir = workDir.map { it.dir("shell") }
        val srcTauriDir = shellDir.map { it.dir("src-tauri") }
        val runtimeDir = workDir.map { it.dir("runtime") }
        val extractedShellDir = workDir.map { it.dir("embedded-shell") }

        val desktopRuntime = project.tasks.register("desktopRuntime", Exec::class.java) {
            group = GROUP
            description = "jlink a trimmed JRE for the desktop bundle."
            val out = runtimeDir.get().asFile
            outputs.dir(out)
            val javaHome = System.getProperty("java.home")
            val ext1 = if (isWindows()) ".exe" else ""
            executable = "$javaHome/bin/jlink$ext1"
            doFirst { project.delete(out) }
            argumentProviders.add {
                listOf(
                    "--add-modules", ext.jlinkModules.get().joinToString(","),
                    "--strip-debug", "--no-header-files", "--no-man-pages",
                    "--compress=2",
                    "--output", out.absolutePath
                )
            }
        }

        val extractDesktopShell = project.tasks.register("extractDesktopShell", Copy::class.java) {
            group = GROUP
            description = "Extract the generic Tauri shell from the onno desktop starter artifact."
            onlyIf { embeddedShell != null && !ext.shellSource.isPresent }
            into(extractedShellDir)
            if (embeddedShell != null) {
                from(project.provider { embeddedShell.resolve().map(project::zipTree) }) {
                    include("onno-desktop-shell/**")
                    eachFile {
                        relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
                    }
                    includeEmptyDirs = false
                }
            }
        }

        val desktopShell = project.tasks.register("desktopShell", Copy::class.java) {
            group = GROUP
            description = "Materialise the Tauri shell and substitute bundle metadata."
            dependsOn(extractDesktopShell)
            inputs.property("productName", ext.productName)
            inputs.property("appVersion", ext.appVersion)
            inputs.property("identifier", ext.identifier)
            inputs.file(ext.iconSource).optional().withPropertyName("iconSource")
            inputs.property("macSigningIdentity", ext.macSigningIdentity.orElse(""))
            inputs.file(ext.macEntitlements).optional().withPropertyName("macEntitlements")
            inputs.property("macHardenedRuntime", ext.macHardenedRuntime)
            inputs.property("macProviderShortName", ext.macProviderShortName.orElse(""))
            inputs.property("macMinimumSystemVersion", ext.macMinimumSystemVersion.orElse(""))
            into(shellDir)
            from(project.provider {
                if (ext.shellSource.isPresent) {
                    ext.shellSource.get().asFile
                } else {
                    extractedShellDir.get().asFile
                }
            })
            doLast {
                val config = shellDir.get().file("src-tauri/tauri.conf.json").asFile
                @Suppress("UNCHECKED_CAST")
                val json = JsonSlurper().parse(config) as MutableMap<String, Any?>
                json["productName"] = ext.productName.get()
                json["version"] = ext.appVersion.get()
                json["identifier"] = ext.identifier.get()
                @Suppress("UNCHECKED_CAST")
                val bundle = json["bundle"] as MutableMap<String, Any?>
                bundle["resources"] = listOf("runtime/**/*", "app/onno.jar")
                bundle["icon"] = if (ext.iconSource.isPresent) {
                    listOf(
                        "icons/32x32.png",
                        "icons/128x128.png",
                        "icons/128x128@2x.png",
                        "icons/icon.icns",
                        "icons/icon.ico"
                    )
                } else {
                    emptyList<String>()
                }
                val macOS = linkedMapOf<String, Any?>(
                    "hardenedRuntime" to ext.macHardenedRuntime.get()
                )
                if (ext.macSigningIdentity.isPresent) {
                    macOS["signingIdentity"] = ext.macSigningIdentity.get()
                }
                if (ext.macEntitlements.isPresent) {
                    project.copy {
                        from(ext.macEntitlements)
                        into(config.parentFile)
                        rename { "Entitlements.plist" }
                    }
                    macOS["entitlements"] = "Entitlements.plist"
                }
                if (ext.macProviderShortName.isPresent) {
                    macOS["providerShortName"] = ext.macProviderShortName.get()
                }
                if (ext.macMinimumSystemVersion.isPresent) {
                    macOS["minimumSystemVersion"] = ext.macMinimumSystemVersion.get()
                }
                bundle["macOS"] = macOS
                config.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(json)) + "\n")
            }
        }

        val desktopStage = project.tasks.register("desktopStage", Copy::class.java) {
            group = GROUP
            description = "Stage the bootJar and jlinked runtime into the shell."
            dependsOn(desktopShell, desktopRuntime)
            into(srcTauriDir)
            doFirst {
                deleteGeneratedResource(shellDir.get().dir("runtime").asFile, project)
                deleteGeneratedResource(shellDir.get().dir("app").asFile, project)
                deleteGeneratedResource(srcTauriDir.get().dir("runtime").asFile, project)
                deleteGeneratedResource(srcTauriDir.get().dir("app").asFile, project)
            }
            from(runtimeDir) {
                into("runtime")
                exclude("legal/**")
            }
            from(ext.mainJar) {
                into("app")
                rename { "onno.jar" }
            }
            doLast {
                normalizeResourcePermissions(srcTauriDir.get().dir("runtime").asFile)
                normalizeResourcePermissions(srcTauriDir.get().dir("app").asFile)
            }
        }

        val desktopIcons = project.tasks.register("desktopIcons", Exec::class.java) {
            group = GROUP
            description = "Generate platform icons from onnoDesktop.iconSource."
            dependsOn(desktopShell)
            onlyIf { ext.iconSource.isPresent }
            workingDir(shellDir)
            val cmd = ext.tauriCommand.get()
            executable = cmd.first()
            argumentProviders.add {
                cmd.drop(1) + listOf("icon", ext.iconSource.get().asFile.absolutePath)
            }
        }

        project.tasks.register("packageDesktop", Exec::class.java) {
            group = GROUP
            description = "Build the native desktop bundle (cargo tauri build)."
            dependsOn(desktopStage, desktopIcons)
            workingDir(shellDir)
            val cmd = ext.tauriCommand.get()
            executable = cmd.first()
            argumentProviders.add {
                cmd.drop(1) + listOf("build", "--bundles", ext.bundleTargets.get().joinToString(","))
            }
        }
    }

    private fun isWindows() =
        System.getProperty("os.name").lowercase().contains("win")

    private fun deleteGeneratedResource(file: File, project: Project) {
        if (!file.exists()) {
            return
        }
        file.walkTopDown().forEach { it.setWritable(true) }
        project.delete(file)
    }

    private fun normalizeResourcePermissions(file: File) {
        if (!file.exists()) {
            return
        }
        file.walkTopDown().forEach {
            it.setReadable(true, false)
            it.setWritable(true, true)
            if (it.isDirectory || it.canExecute()) {
                it.setExecutable(true, false)
            }
        }
    }

    companion object {
        private const val GROUP = "onno desktop"
        private const val STARTER_VERSION = "0.1.0"
    }
}
