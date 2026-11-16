# onno-ui-gradle-plugin

The Gradle plugin `su.onno.ui`: it builds an onno application's own React UI into its jar. The UI
in `src/main/ui` is an ordinary Vite app on [`@onno/ui`](../onno-ui/README.md); the plugin only does
what needs Gradle.

```kotlin
plugins {
    java
    id("org.springframework.boot")
    id("su.onno.ui") version "<onno version>"
}
```

| Task | What it does |
| --- | --- |
| `onnoUiInit` | Seeds `src/main/ui` with a starter app (`package.json`, `vite.config.ts`, `tsconfig.json`, `index.html`, `.gitignore`, `main.tsx`, `pages/LoginPage.tsx`, `pages/HomePage.tsx`, `pages/README.md`, `components/README.md`). The default screens are wired explicitly in `main.tsx` (`login: LoginPage` rendering `<DefaultLogin>`, home rendering `<DefaultHome>`), so an app extends one by editing its file. Never overwrites a file. The template is a plain file tree in [`src/main/resources/su/onno/ui/template`](src/main/resources/su/onno/ui/template); `{{onnoUiVersion}}` is replaced with the matching `@onno/ui` version and `gitignore` is written as `.gitignore`. `onno-ui`'s `tsconfig.template.json` type-checks it against the current public API. |
| `generateUiModel` | Writes `src/main/ui/model.gen.ts` — TypeScript types of the Java entities, enumerations, information registers, constants and roles — from the compiled classes. Runs before every UI build; git-ignore the file. |
| `buildUi` | `npm run build` (Vite) into `build/onno-ui/onno-app/`, which goes into the jar as `classpath:/onno-app/`. Wired before `processResources`. `onno-web-starter` serves it in place of its default shell. |

A project without `src/main/ui/package.json` skips all of it and never downloads Node, so the plugin
is safe to apply before the UI exists.

## Configuration

A bare `id("su.onno.ui")` needs no configuration. The optional `onnoUi {}` block:

| Property | Default | Purpose |
| --- | --- | --- |
| `sourceDir` | `src/main/ui` | The Vite app. |
| `npmProjectDir` | `sourceDir` | Where `npm install` runs — the root of an npm workspace the UI belongs to, if any. |
| `nodeVersion` | `22.22.0` | The managed Node version (downloaded by the node-gradle plugin). |
| `modelPackages` | none | Extra packages whose entities go into `model.gen.ts` (the app's own packages are always scanned), e.g. a starter's entities the UI uses. |

## Development loop

The intended development workflow is Vite hot reload with a separate backend. In this framework
repository, use the root scripts `npm run dev`, `npm run dev:backend`, and `npm run dev:watch`;
see [Running & verifying](../docs/RUNNING.md#intended-development-workflow). The example frontend
runs on port 5173 against the backend on 8091. Reuse the backend for frontend-only changes; do not
rebuild jars as the edit loop.

For a standalone consumer application:

```bash
./gradlew bootRun                       # terminal 1: its backend, default port 8080
cd src/main/ui && npm run dev           # terminal 2: Vite, proxy to 8080
```

`ONNO_BACKEND` or `onno({ backend })` selects another backend. After Java entity changes,
`./gradlew generateUiModel` refreshes the typed model. A consumer can enable Spring DevTools and
continuous application compilation for Java reloads; its own development build can exclude its
`buildUi` task while Vite serves the frontend. Leave production packaging and verification intact.
The repository's root npm scripts are not generated into consumer applications.

The generated app registers screens in `defineApp({ pages })`. Put presentation in TSX components:
`{ list: "Orders", component: OrdersPage }` injects `ListPageProps<"Orders">`; `{ record: "Orders",
component: OrderPage }` injects `RecordPageProps<"Orders">`. Compose `EntityList`, `RecordPage`,
`RecordFields`, and `RecordLines` with custom React controls. Icons are imported Lucide components
from `@onno/ui/icons`. See [the UI package](../onno-ui/README.md) for complete examples.
