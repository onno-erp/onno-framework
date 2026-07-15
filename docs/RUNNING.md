# Running & verifying

How to run an onno app, iterate on it quickly, and prove which version is actually live. Applies to
the bundled example app and to consumer apps; agent-specific verification ladders live in
[AGENTS.md](../AGENTS.md#verification).

> **Keep this current.** If a command, port, credential, or dev-mode mechanism changes, update this
> page in the same PR.

## Run the example app

Requires **JDK 21**. The SPA is prebuilt into `onno-ui-starter` — there is no separate frontend
process to run.

```bash
./gradlew :example:bootRun                                # http://localhost:8080
./gradlew :example:bootRun --args='--server.port=8090'    # alternate port
```

Log in as `admin@onnobooks.local`/`admin` (admin profile) or `manager@onnobooks.local`/`manager`
(default profile), or use the one-tap demo buttons on the login screen
(`onno.ui.login.demo-accounts`). First launch seeds a fixed-RNG bookstore into the file-H2 database
at `example/data/` — delete that directory to re-seed.

To reach the app from a phone on the same network, bind to all interfaces and use the machine's
LAN address:

```bash
./gradlew :example:bootRun --args='--server.address=0.0.0.0'
# then open http://<your-lan-ip>:8080 on the device
```

## Iterate with dev mode (no manual reloads)

Dev mode pushes live-reload signals over the existing `/api/events` stream. It switches on
automatically when `spring-boot-devtools` is on the classpath (`onno.ui.dev-mode` overrides
explicitly).

```kotlin
dependencies { developmentOnly("org.springframework.boot:spring-boot-devtools") }
```

```bash
./gradlew :example:bootRun          # terminal 1 — the app
./gradlew -t :example:classes       # terminal 2 — continuous compile
```

Save a file → devtools restarts the context → every open browser reloads itself (the client
full-reloads when the `ready` event's `bootId` changes while `devMode` is on). To force a reload
without a code change, touch the trigger file: `touch .onno-reload` (path configurable via
`onno.ui.dev-reload-trigger`; polled every 500 ms, no HTTP/auth involved).

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

- API call returns HTML → wrong path or unauthenticated (the SPA fallback answers 200 for unknown
  paths — see [GOTCHAS.md](GOTCHAS.md)).
- Login 401 after config changes → you probably overrode `onno.auth.public-paths` without
  re-listing the defaults.
- Signed out after every redeploy → `remember-me.key` unset (newer versions fail fast at startup
  instead).
- UI doesn't react to a change you *know* happened → you're listening on `onmessage`; `/api/events`
  only emits named events.
