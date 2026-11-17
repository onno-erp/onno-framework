# Running & verifying

How to run an onno app, iterate on it quickly, and prove which version is actually live. Applies to
the bundled example app and to consumer apps; agent-specific verification ladders live in
[AGENTS.md](../AGENTS.md#verification).

> **Keep this current.** If a command, port, credential, or dev-mode mechanism changes, update this
> page in the same PR.

## Intended development workflow

Develop against the example using **Vite for the frontend** and a separate backend. Run all
commands below from the repository root. Requires **JDK 21** and **Node 22+**; run `npm install`
once to install the shared npm workspace.

| Command | Purpose |
| --- | --- |
| `npm run dev` | Vite with React Fast Refresh at `http://localhost:5173`; proxies the backend on port 8091. |
| `npm run dev:backend` | Generate the typed UI model and start the example backend on port 8091 with Spring DevTools. |
| `npm run dev:watch` | Continuously compile application Java/resources and regenerate `model.gen.ts`; DevTools restarts the running development backend. |

For a fresh development session, use three terminals:

```bash
# Terminal 1: backend
npm run dev:backend

# Terminal 2: application Java/model changes
npm run dev:watch

# Terminal 3: frontend — open http://localhost:5173
npm run dev
```

For frontend-only work, reuse an existing backend on port 8091 and run **only `npm run dev`**.
Pages, components, styles, and linked `@onno/*` UI sources hot-reload through Vite. Do not rebuild a
jar or restart the backend for a frontend edit. Open port **5173** to see live changes; the UI served
by the backend on **8091** is a built snapshot. API, auth, SSE, and default branding requests proxy
to the backend. To use another backend:

```bash
ONNO_BACKEND=http://localhost:8080 npm run dev
```

For application Java/model/resource changes, use `dev:backend` with `dev:watch`. These commands
skip production frontend builds, so UI edits stay in Vite. DevTools restarts the application
context after recompilation: onno rescans metadata, reconciles the schema, and tells connected
browsers to refresh over `/api/events`. The watcher also regenerates `model.gen.ts` after model
changes. If a framework dependency jar or the runtime classpath changes, restart `dev:backend`
to load it; do not assume DevTools reloads dependency jars.

A packaged `java -jar` backend does not enable DevTools restarts. Stop it before switching to
`dev:backend` on the same port. Use one backend for the example's existing file-H2 database at
`example/data/bookstore`; this workflow does not reset the data. Stop each development command
with Ctrl+C in its terminal.

Production packaging and verification remain separate: use `./gradlew check`,
`./gradlew :example:bootJar`, and `./gradlew publishToMavenLocal` to verify tests and artifacts.
They retain their normal frontend packaging; do not carry the development task exclusions into
release or verification commands.

### DevTools and explicit reloads

The example already includes:

```kotlin
dependencies { developmentOnly("org.springframework.boot:spring-boot-devtools") }
```

onno's backend live-reload support enables itself when DevTools is on the classpath
(`onno.web.dev-mode` overrides explicitly). The client reloads when a `ready` event's `bootId`
changes while `devMode` is on. To force a reload without changing code, touch the configured
`onno.web.dev-reload-trigger` file (`.onno-reload` relative to the backend's working directory;
polled every 500 ms).

### Consumer applications

The root npm commands above belong to this repository. For a separate application using
`su.onno.ui`, run its backend and `npm run dev` in its `src/main/ui` directory. The preset defaults
to port 8080; set `ONNO_BACKEND` or `onno({ backend })` to match that application's backend.
For Java reloads, add DevTools and continuously compile that app's classes and regenerate its
UI model. See [the UI plugin](../onno-ui-gradle-plugin/README.md#development-loop).

## Run with the built UI

Use this to inspect production-style frontend output, rather than as the routine edit loop:

```bash
./gradlew :example:bootJar
cd example
java -jar build/libs/example-2.0.0-SNAPSHOT.jar --server.port=8091
# http://localhost:8091/ui/ (use the jar matching the checkout's version)
```

Gradle bundles the example's Vite UI into the jar; its first build downloads the managed Node
runtime and installs the npm workspace. JDK 21 is required.

The example automatically logs anonymous visitors in as `manager@onnobooks.local` (default profile)
through `onno.auth.demo.auto-login-username`. Remove that property to restore the login screen, where
`admin@onnobooks.local`/`admin`, `manager@onnobooks.local`/`manager`, and the one-tap demo buttons are
available. First launch seeds a fixed-RNG bookstore into the file-H2 database at `example/data/`.
Keep that directory to preserve the existing data.

The example temporarily defaults `ONNO_DEMO_FRAME_ANCESTORS` to `*`, so any landing page can frame
it. Set that variable to an exact origin to restore an allowlist. For an HTTPS cross-site iframe,
also set `ONNO_DEMO_COOKIE_SAME_SITE=none` and `ONNO_DEMO_COOKIE_SECURE=true`; those settings preserve
session/CSRF state.

To reach the built app from a phone on the same network, bind to all interfaces and use the
machine's LAN address:

```bash
java -jar build/libs/example-2.0.0-SNAPSHOT.jar --server.port=8091 --server.address=0.0.0.0
# from example/, open http://<your-lan-ip>:8091/ui/ on the device
```

## Run a release smoke test

Use an isolated in-memory database so an existing example process or file lock cannot affect the
result:

```bash
./gradlew :example:bootRun \
  --args='--server.port=8090 --spring.datasource.url=jdbc:h2:mem:onno_smoke;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE'
```

Then verify a real authenticated collection read from another shell:

```bash
base=http://localhost:8090
jar=$(mktemp)
curl -fsS -c "$jar" "$base/api/config" | jq '.update.current'
curl -fsS -b "$jar" -c "$jar" -X POST "$base/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin@onnobooks.local","password":"admin"}' >/dev/null
curl -fsS -b "$jar" "$base/api/list/catalogs/Books?limit=5" |
  jq -e '.rows | type == "array"'
```

Spring Boot devtools starts the application context on a restart thread. A context failure there can
leave the Gradle `bootRun` task reporting success, so a zero process exit is not a sufficient smoke
test: require the HTTP probes above to pass.

## Verify what is actually running

"I redeployed but still see the old page" is almost always a stale process or cached bundle. Check
the running build directly instead of guessing:

- `GET /api/config` — the `update` block reports `{available, current, latest}`; `current` is the
  framework version the live process was built against.
- `META-INF/onno-build.properties` inside the running jar carries the build stamp.
- The release feed the in-app update checker polls: `https://cloud.onno.su/releases/v1/latest`.

To verify a published artifact exists before upgrading a consumer (see
[CONSUMING.md](CONSUMING.md)):

```bash
# Maven Central (open core)
curl -sI https://repo1.maven.org/maven2/su/onno/onno-framework-starter/<v>/onno-framework-starter-<v>.pom
# cloud registry mirror (license key as password)
curl -sI -u license:$ONNO_LICENSE_KEY https://cloud.onno.su/modules/su/onno/onno-framework-starter/<v>/onno-framework-starter-<v>.pom
```

## When something looks broken

- API call returns HTML → verify that a proxy has not rewritten it to an HTML navigation under
  `onno.ui.path`; unknown `/api/**` routes now return `404` (see [GOTCHAS.md](GOTCHAS.md)).
- Login 401 after config changes → you probably overrode `onno.auth.public-paths` without
  re-listing the defaults.
- Signed out after every redeploy → `remember-me.key` unset (newer versions fail fast at startup
  instead).
- UI doesn't react to a change you *know* happened → you're listening on `onmessage`; `/api/events`
  only emits named events.
