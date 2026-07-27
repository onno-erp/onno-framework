# onno 2.0 release notes

These notes describe the source prepared for `v2.0.0`. The release is available only after the
tag-driven publishing workflow completes and the artifacts appear on Maven Central.

## Highlights

- The typed process engine now supports automatic and decision steps, timers, parallel fork/join,
  subprocesses, durable work items, snapshots, and explicit definition migrations.
- Runtime schema evolution has one owner: `SchemaUpgrader` handles metadata-driven DDL and
  `AppMigration` handles application data reshaping.
- UI collection reads use bounded cursor pagination, and action results use the structured
  `{refresh, feedback}` contract.
- Framework, reliability, and browser UX telemetry can be exported through the process-wide
  OpenTelemetry API.
- Posting and persistence add duplicate-post protection, encrypted-secret preservation, concrete
  tabular-row ownership checks, enum dimensions, and table-name override fixes.
- Active media is served as a download rather than inline content.
- The bundled UI moves to React 19 and React Router 8; Gradle-managed frontend and widget builds use
  Node 22.22.
- Entity forms support `.widget("color")`, a React hex picker that stores validated `#RRGGBB`.

## Breaking changes

- Unbounded catalog and document collection reads were removed. Use
  `/api/list/{catalogs|documents}/{name}?cursor=&limit=` and treat `nextCursor` as opaque.
- Legacy UI value aliases and response shapes were removed. This includes enum names stored instead
  of deterministic UUIDs, data-URL images, `"lat,lng"` points, old widget/icon/currency aliases,
  edit-route aliases, offset pagination, and handler-directed action navigation.
- `SchemaMigrator` was removed. Put framework schema changes through `SchemaUpgrader` and business
  data conversions in versioned `AppMigration` beans.
- The experimental query package and the mail and print starters were removed. Applications should
  own those integrations or provide them as separate extensions.
- Process definitions gain stricter durable graph semantics. Persisted processes must be migrated
  explicitly when their definition changes.
- Custom UI code must be compatible with React 19. The managed Node default is now 22.22.

## Upgrade order

1. Back up the production database and rehearse the upgrade on a staging copy.
2. Read [Migrating an application to onno 2.0](MIGRATING_TO_2_0.md), add the required
   `AppMigration` conversions, and update Java metadata and API clients.
3. If the application uses typed processes, follow
   [Migrating typed authoring](MIGRATING_TYPED_AUTHORING.md) and provide definition migrations for
   active instances.
4. Replace removed query, mail, or print dependencies before changing the framework version.
5. Test against `2.0.0-SNAPSHOT` from `mavenLocal()`, then change every onno module and Gradle plugin
   to the same released version.
6. Run application tests and the authenticated runtime smoke test in
   [Running & verifying](RUNNING.md#run-a-release-smoke-test) before deploying.

## Framework release gate

Run these checks from a clean checkout:

```bash
./gradlew clean check
npm audit --prefix example/build/onno-widgets
./gradlew publishToMavenLocal aggregateJavadoc
(cd onno-ui-starter/src/main/frontend && npm ci && npm audit && npm run test:run)
(cd docs && npm ci && npm audit && npm run docs:build)
```

Then run the isolated authenticated smoke test, confirm `/api/config` reports `2.0.0`, and create the
`v2.0.0` tag. The local publication uses `2.0.0-SNAPSHOT`; the tagged CI workflow verifies the exact
signed `2.0.0` artifacts with the release signing secrets before it publishes. Publishing is CI-only;
do not publish release artifacts from a workstation.
