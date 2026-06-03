# onec-desktop-starter

Spring Boot starter that runs a oneC web application as a **native desktop app**. The app keeps
running as a normal embedded Spring Boot server; a generic **Tauri** shell launches that server,
waits for it to come up, then draws a native window pointed at the local UI. Window appearance is
declared once in Java (config-as-code) rather than in a hand-edited `tauri.conf.json`.

Packaging into a distributable bundle (`.dmg`/`.msi`/`.AppImage`) is done by the sibling
[`com.onec.desktop`](#packaging-the-comonecdesktop-gradle-plugin) Gradle plugin.

## How it works at runtime

The shell ships generic and dumb. At launch it:

1. boots the embedded JVM (passing `--onec.desktop.home=<os-app-data-dir>`),
2. polls `GET /api/desktop/ready` until it returns `200` (context is up; doubles as a liveness
   probe, so no Spring Actuator dependency),
3. fetches `GET /api/desktop/manifest` and draws the window (title, geometry, tray, splash)
   accordingly.

Both endpoints are served by this starter under `/api/desktop`.

Because every launch is a fresh JVM, this starter also keeps the user **logged in across launches**
(see [Desktop mode side effects](#desktop-mode-side-effects)).

## Enabling

Add the dependency. Auto-configuration activates automatically — it is a web app and
`onec.desktop.enabled` defaults to `true`, so no config is required:

```yaml
onec:
  desktop:
    enabled: true   # optional; this is the default
```

When active (`@ConditionalOnWebApplication` + `onec.desktop.enabled` not `false`),
`OnecDesktopAutoConfiguration` exposes a `DesktopManifest`, the `DesktopManifestController`, and — if
the application defines no `DesktopApp` bean — a default `DesktopApp` (application name as title,
1280×800 window).

## Describing the window (`DesktopApp`)

Define one `DesktopApp` bean to describe how the app presents itself. It is the desktop peer of
`com.onec.ui.Layout`: fluent top-level setters plus nested lambda builders, producing the immutable
`DesktopManifest` the shell reads at boot.

```java
@Component
class RentalsDesktop implements DesktopApp {
    public void configure(DesktopSpec app) {
        app.title("Rentals ERP")
           .window(w -> w.size(1400, 900).minSize(1024, 720))
           .singleInstance(true)
           .tray(t -> t.tooltip("Rentals").quit("Quit"))
           .splash("Starting Rentals ERP…");
    }
}
```

If no `DesktopApp` bean is present, dropping the starter on the classpath is enough to ship a window.

### `DesktopSpec` defaults

| Setting | Default | Notes |
|---------|---------|-------|
| `title(...)` | `spring.application.name` (else `onec`) | Window/title-bar text. |
| `singleInstance(...)` | `true` | A second launch focuses the running window. |
| `splash(...)` | `"Starting <title>…"` | Message shown while the server boots. |
| `window().size(w, h)` | `1280 × 800` | Initial window size. |
| `window().minSize(w, h)` | `0 × 0` | Minimum window size. |
| `window().resizable(...)` | `true` | |
| `window().center(...)` | `true` | |
| `window().maximized(...)` | `false` | |
| `tray(...)` | disabled | Calling `tray(...)` at all enables the tray. |
| `tray().tooltip(...)` | resolved title | Tray hover text. |
| `tray().quit(...)` | `"Quit"` | Label of the tray's quit menu item. |

## Configuration keys

Only environment/launch concerns are bound as properties (`DesktopProperties`, prefix
`onec.desktop`); everything visual lives in the `DesktopApp` bean above.

| Key | Default | Purpose |
|-----|---------|---------|
| `onec.desktop.enabled` | `true` | Master switch. Set `false` to disable the endpoints and the desktop-mode data/session relocation. |
| `onec.desktop.home` | `""` (empty) | Per-user data home the shell passes at launch via `--onec.desktop.home`. When set, triggers H2 relocation and session persistence (below). Left unset during `bootRun`/dev, so normal runs are untouched. |

## Desktop mode side effects

When `onec.desktop.home` is set (i.e. the shell launched the JVM), a
`DesktopEnvironmentPostProcessor` runs before the datasource is bound and applies two changes — but
only in desktop mode, so `bootRun`/server runs are unaffected:

- **H2 relocation.** If `spring.datasource.url` is an embedded H2 *file* datasource
  (`jdbc:h2:file:…`), the database is relocated under `<home>/data/` so it lives in the OS app-data
  directory rather than next to the read-only, ephemeral bundled binary. This is unconditional in
  desktop mode (no opt-in flag); the override is added at highest precedence so it wins over YAML.
- **Stay logged in.** Sessions are persisted via Spring Session JDBC into the (now file-backed)
  datasource and a long-lived persistent cookie is issued, so login survives the JVM restart that
  every launch performs. Defaults (`server.servlet.session.timeout=30d`,
  `…cookie.max-age=30d`, `spring.session.timeout=30d`, and for H2 an idempotent schema bootstrap from
  `classpath:onec-desktop/session/schema-h2.sql`) are added at lowest precedence, so an app can still
  override them.

> The H2 `jar:`/classpath resource quirks that only bite the trimmed bundle (not full-JDK
> `bootRun`) are handled by the Gradle plugin (classic loader format + `jdk.zipfs` module), below.

## Packaging: the `com.onec.desktop` Gradle plugin

The sibling **`onec-desktop-gradle-plugin`** (plugin id **`com.onec.desktop`**) turns the Spring
Boot app into a native bundle. In this repo it is wired in via `includeBuild("onec-desktop-gradle-plugin")`
in `settings.gradle.kts`, so `id("com.onec.desktop")` resolves locally without publishing.

```kotlin
plugins {
    id("org.springframework.boot")   // the plugin defaults mainJar to bootJar's output
    id("com.onec.desktop")
}

onecDesktop {
    productName.set("Rentals ERP")
    identifier.set("com.acme.rentals")
    bundleTargets.set(listOf("app", "dmg"))    // default is just ["app"]
    iconSource.set(file("icon.png"))           // optional 1024×1024 PNG
    // macSigningIdentity / macEntitlements / macHardenedRuntime / etc. for macOS signing
}
```

A bare `id("com.onec.desktop")` already works for an app that uses the Spring Boot plugin —
everything in the `onecDesktop {}` block has a convention (product name → project name, identifier →
`com.onec.<project>`, version → project version, main jar → `bootJar` output, runtime modules,
Tauri command `["cargo", "tauri"]`, bundle target `app`).

### Tasks (group `onec desktop`)

| Task | Does |
|------|------|
| `desktopRuntime` | `jlink` a trimmed JRE for the bundle. |
| `extractDesktopShell` | Extract the generic Tauri shell from the starter artifact (only for external consumers that have the starter as a binary dependency). |
| `desktopShell` | Materialise the Tauri shell and substitute bundle metadata (product name/version/identifier, macOS signing) into `tauri.conf.json`. |
| `desktopStage` | Stage the `bootJar` (as `app/onec.jar`) and the jlinked runtime into the shell as Tauri resources. |
| `desktopIcons` | Generate platform icons from `onecDesktop.iconSource` (runs only when an icon source is set; `cargo tauri icon`). |
| `packageDesktop` | The entry point: build the native bundle via `cargo tauri build --bundles <targets>`. Depends on all of the above. |

Run packaging with:

```bash
./gradlew :your-app:packageDesktop
```

### How the starter and plugin relate

- The starter **provides the runtime side**: the manifest/readiness endpoints, the `DesktopApp`
  config-as-code model, and H2/session relocation. It also **carries the generic Tauri shell
  sources** (`src/main/tauri`), which `processResources` packages into the jar under
  `onec-desktop-shell/` so the plugin can extract them for binary-only consumers.
- The plugin **provides the build side**: jlink + shell materialisation + staging the fat jar →
  `cargo tauri build`. In a source checkout it reads the shell straight from
  `:onec-desktop-starter`'s `src/main/tauri`.
- The plugin forces two settings that only matter for the trimmed bundle (and are otherwise
  invisible under `bootRun`): the Spring Boot fat jar uses the **classic loader format** (the
  default `nested:` scheme can't be read off the classpath by libraries such as JobRunr), and the
  jlinked runtime includes **`jdk.zipfs`** (the `jar:` NIO filesystem provider, likewise needed by
  classpath-as-FileSystem readers). Without them the desktop window spins on the splash forever.

> **Prerequisite:** `packageDesktop`/`desktopIcons` shell out to the Tauri CLI (`cargo tauri`), so a
> Rust/Cargo toolchain with `tauri-cli` must be installed on the build machine.
