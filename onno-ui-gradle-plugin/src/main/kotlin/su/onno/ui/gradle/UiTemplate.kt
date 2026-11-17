package su.onno.ui.gradle

/**
 * The app `onnoUiInit` seeds into `src/main/ui`. The template is an ordinary file tree under
 * `src/main/resources/su/onno/ui/template` — edit those files, not this class. Every default screen
 * (login, home) is wired explicitly in the seeded `main.tsx`, so an app replaces one by editing its
 * page file.
 *
 * The build writes the tree's file list to [INDEX] (a jar's resources cannot be listed). Two
 * substitutions apply on seeding: `{{onnoUiVersion}}` becomes the matching `@onno/ui` version, and a
 * top-level file named `gitignore` is written as `.gitignore`, and `npmrc` as `.npmrc` (resource
 * processing drops dotfiles; the `.npmrc` points the `@onno` scope at the onno npm registry).
 */
internal object UiTemplate {

    const val ROOT = "su/onno/ui/template"
    const val INDEX = "su/onno/ui/template.index"

    fun files(version: String): List<Pair<String, String>> {
        val loader = UiTemplate::class.java.classLoader
        val paths = loader.getResourceAsStream(INDEX)?.bufferedReader()?.use { it.readLines() }
            ?.map(String::trim)?.filter(String::isNotEmpty)
            ?: error("onno: the UI template index $INDEX is missing from the plugin jar")
        return paths.map { path ->
            val content = loader.getResourceAsStream("$ROOT/$path")?.bufferedReader()?.use { it.readText() }
                ?: error("onno: the UI template file $path is missing from the plugin jar")
            target(path) to content.replace("{{onnoUiVersion}}", version)
        }
    }

    private fun target(path: String): String = when (path) {
        "gitignore" -> ".gitignore"
        "npmrc" -> ".npmrc"
        else -> path
    }
}
