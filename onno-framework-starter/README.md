# onno-framework-starter

Core Spring Boot auto-configuration for the **onno** framework — a 1C-style ERP toolkit on
Spring Boot, Spring Data JDBC and JDBI. This starter is what turns a plain `@SpringBootApplication`
with a `DataSource` into a running onno application: it scans your **catalogs**, **documents**,
**registers**, **enumerations** and **constants**, generates/migrates their schema on startup, and
wires the repositories, posting engine, numbering, lifecycle callbacks, outbox and background jobs.

## Enabling

Add the dependency. There is no enable flag — auto-configuration is `@ConditionalOnBean(DataSource.class)`
and runs *after* `DataSourceAutoConfiguration`, so as long as you have a `DataSource` on the context
(e.g. `spring-boot-starter-data-jdbc` + a JDBC URL) the framework configures itself.

```kotlin
dependencies {
    implementation("su.onno:onno-framework-starter:0.1.0")
}
```

By default entity base packages are taken from `@SpringBootApplication` (via Spring Boot's
`AutoConfigurationPackages`). Point the scanner elsewhere with `onno.scan-packages` if your entities
live outside the application package, or if there is no `@SpringBootApplication` on the context.

### Configuration keys

| Key | Default | Purpose |
|-----|---------|---------|
| `onno.scan-packages` | *(empty — falls back to `@SpringBootApplication` packages)* | Base packages scanned for catalog/document/register/enumeration/constant classes. A non-empty list overrides the auto-detected packages. If neither is resolvable, startup fails with an explanatory `IllegalStateException`. |

Besides `onno.cluster.*` (see [Multi-node live UI](#multi-node-live-ui-cluster-bus)), this is the only
property the starter exposes. Everything else is derived from your annotated metadata.

## What gets auto-configured

`AutoConfiguration.imports` registers two classes:

- **`OnnoAutoConfiguration`** — the heart of the wiring. It extends `AbstractJdbcConfiguration`,
  registers the onno `NamingStrategy` and mapping context, and exposes:
  - `Jdbi` (built from a transaction-aware wrapper around the `DataSource`, so outbox writes join
    Spring JDBC transactions), `MetadataRegistry`, `RefResolver`
  - `SchemaInitializer` (see below)
  - the lifecycle callbacks (`OnnoBeforeConvertCallback`, `OnnoAfterSaveCallback`,
    `OnnoAfterConvertCallback`, `OnnoBeforeDeleteCallback`)
  - `NumberGenerator` (JDBC-backed code/number sequences), `OutboxWriter` (its ordinary
    `append(...)` joins an active Spring transaction; posting uses its handle-bound overload)
  - register persistence/repository maps, `PostingService` (wrapping a `PostingEngine`)
  - information-register, constant and enumeration persistence/managers
  - the `CommandRegistry`, `CheckRegistry` and `PickerOptionsService` over your `Commands`,
    `Checks` and `PickerOptions` beans
  - `BackgroundJobs` — only when a JobRunr `JobScheduler` bean is present
    (`@ConditionalOnBean(JobScheduler.class)`)
  - `ScheduledJobRegistrar` — always validates scheduled task declarations
- **`OnnoRepositoriesAutoConfiguration`** — replaces Spring Boot's stock
  `JdbcRepositoriesAutoConfiguration` with one that excludes `RegisterRepository` subtypes, and
  scans register repositories separately. Register repositories are register-shaped (append-only
  movement records) rather than aggregate-shaped, so they need their own factory.

### TypeScript model for the UI

`su.onno.spring.codegen.TypeScriptModelGenerator` writes `model.gen.ts` — the entities, their
fields and record types, enumerations (values and `@EnumLabel` labels), information registers,
constants and `@AccessControl` roles — for an app's React UI. The `su.onno.ui` Gradle plugin
([onno-ui-gradle-plugin](../onno-ui-gradle-plugin/README.md)) runs it as `generateUiModel` before
every UI build.

### Schema generation

On startup `SchemaInitializer` (an `InitializingBean`) re-scans the same packages and builds a
`MetadataRegistry`. `SchemaGenerator` bootstraps an empty database; `SchemaUpgrader` exclusively
diffs and evolves an existing runtime schema according to `onno.schema.mode`. Versioned
`AppMigration` beans handle application data reshaping. Enumeration rows are seeded as part of
initialization. No Flyway/Liquibase scripts are needed for framework-managed tables.

### One concrete row class per tabular section

Each `@TabularSection` must declare a distinct concrete `TabularSectionRow` class. Spring Data JDBC
maps the row class itself to one generated child table, so reusing it for another document or
section would make one table mapping overwrite the other. The starter rejects that model at startup
and reports both owners. Use separate concrete subclasses for each section; they may share fields
and behavior through a common base row class.

### Enumerations persist as UUIDs

Enums annotated as framework enumerations are first-class persisted values. They are stored as
**UUID columns** through Spring Data JDBC converters registered in `userConverters()`
(`EnumUuidConverters`), and their rows are seeded by the schema generator. If you find a seeder
comment claiming enum-as-UUID is "not yet wired", that comment is stale — it works.

The same representation applies to enum-valued accumulation-register dimensions. The JDBI posting
path binds the stable UUID into movement and totals tables, converts enum filter values to that UUID,
and restores the enum constant on typed reads.

The converters also read enums (and `Ref<T>`) back from a **`varchar` column that holds the UUID as
text**, not just a native `uuid` column. Enum constant names are not a persisted format.

## Usage

Define metadata as annotated classes in your application package; the framework does the rest.

```java
@SpringBootApplication
public class ErpApplication {
    public static void main(String[] args) {
        SpringApplication.run(ErpApplication.class, args);
    }
}

@Catalog(name = "Counterparty")
public class Counterparty extends CatalogObject {
    private String name;
    // getters/setters
}

@Service
class CounterpartyService {
    private final CounterpartyRepository repo;          // Spring Data JDBC, auto-generated
    CounterpartyService(CounterpartyRepository repo) { this.repo = repo; }

    void create() {
        Counterparty c = new Counterparty();
        c.setName("Acme");
        repo.save(c);            // INSERT — code/number auto-assigned, onFilling/beforeWrite run
    }

    void rename(UUID id) {
        Counterparty c = repo.findById(id).orElseThrow();
        c.setName("Acme Ltd");
        repo.save(c);            // UPDATE — load already marked the aggregate not-new
    }
}
```

### Note: loaded entities update, not re-insert

`CatalogObject`/`DocumentObject`/`AccumulationRecord` implement Spring Data `Persistable` with
`isNew = true` by default, so a freshly constructed instance INSERTs. Spring Data JDBC never flips
that flag back after a read, so `OnnoAfterConvertCallback` resets `isNew = false` on every load path.
That means `repository.save(loadedEntity)` correctly issues an **UPDATE**. Find-then-update and sync
code work automatically — you no longer need to call `setNew(false)` by hand (historically you did).

## Transactions & posting (important)

`PostingService`/`PostingEngine` runs in its **own JDBI transaction**, which is **not** enlisted in
any ambient Spring `@Transactional`. Posting reads the persisted document and writes register
movements plus the document's `_posted` flag on that separate connection.

Therefore: **persist (commit) the document before you post it.** Do **not** wrap save + post in one
`@Transactional` method — the posting connection cannot see the still-uncommitted row, so the
`_posted` update matches nothing and silently flips nothing while register movements go missing.

```java
// WRONG — single transaction; posting runs on another connection and sees no committed row
@Transactional
void receiveAndPost(GoodsReceipt doc) {
    repo.save(doc);
    posting.post(doc);   // silently posts nothing
}

// RIGHT — commit first, then post
void receiveAndPost(GoodsReceipt doc) {
    persistService.save(doc);   // commits in its own transaction
    posting.post(doc);          // now sees the committed document
}
```

### Reacting to a post (integration hook)

After a post (or unpost) transaction commits, the framework publishes a Spring application event —
`DocumentPostedEvent` / `DocumentUnpostedEvent` (both carry the `DocumentObject`). This is the
**Spring-bean-reachable "after post" extension point**: unlike the domain `AfterPostHandler.afterPost()`
(which has no access to your beans) and the transactional outbox (which needs a relay), an
`@EventListener` is an ordinary bean with full DI, ideal for firing an external integration.
The listener runs after the post has committed, so an exception it throws is logged and does not
fail the post. (Spring still skips later listeners of that same event, so keep listeners
independent or catch inside them.) Use a `@DomainEvent` outbox row when the
reaction must be guaranteed.

```java
@Component
class TravelerRegistrationListener {
    private final ExternalRegistry registry;   // any bean — full DI
    TravelerRegistrationListener(ExternalRegistry registry) { this.registry = registry; }

    @EventListener
    void onPosted(DocumentPostedEvent event) {
        if (event.document() instanceof CheckIn checkIn) {
            registry.register(checkIn);   // runs after the post has committed
        }
    }
}
```

The listener runs synchronously right after the post commit (posting is its own transaction — see
above), so its side-effects are safely post-commit. No service-locator or Kafka required.

## Performance profiling

onno uses standard Java/Spring observability tools rather than a custom profiler surface.

For local or production JVM profiling, use **Java Flight Recorder** / **JDK Mission Control**. Core
document posting emits a custom JFR event named `su.onno.Operation` with stable operation names and
an item count. The built-in operations include:

- `onno.document.save.before-convert`, `onno.document.save.before-write`,
  `onno.document.save.validate`, `onno.document.save.after-save`
- `onno.document.post`, `onno.document.handle-posting`, `onno.document.post.transaction`
- `onno.document.post.preview`, `onno.document.unpost`

Start an app with continuous JFR recording:

```bash
java -XX:StartFlightRecording=filename=onno.jfr,settings=profile,dumponexit=true -jar app.jar
```

Or attach to a running process:

```bash
jcmd <pid> JFR.start name=onno settings=profile
jcmd <pid> JFR.dump name=onno filename=onno.jfr
```

Open `onno.jfr` in JDK Mission Control and filter for `onno Operation`.

For production dashboards, the Spring starter also records Micrometer meters when a `MeterRegistry`
bean is present:

- `onno.operation.duration` — timer tagged by `operation`
- `onno.operation.items` — distribution summary tagged by `operation`

For Prometheus/Grafana in a Spring Boot app, add Actuator and the Prometheus registry:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
}
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    prometheus:
      access: read_only
```

Prometheus scrapes `/actuator/prometheus`; Grafana then queries Prometheus. Operation tag values are
stable and low-cardinality; do not add document IDs, customer names, or user input to operation
names if you add your own Micrometer instrumentation in application code.

If you want Prometheus histogram buckets for percentile dashboards, enable them with Micrometer
configuration instead of paying that cost by default:

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        onno.operation.duration: true
```

## Background jobs

The starter always validates `@ScheduledJob` declarations in `onno.scan-packages` (by default,
Spring Boot's application packages). Each active concrete task must implement `BackgroundTask`
and be a Spring bean, registered with `@Component` or `@Bean`. Missing beans fail startup with
the job name, class and registration instructions. Declarations disabled by Spring conditions
or profiles are excluded from scanning.

Declared task beans require a JobRunr `JobScheduler`; without one, startup fails with a clear
configuration error. Applications with no scheduled tasks still start without JobRunr.
When a scheduler is present, the starter also contributes the `BackgroundJobs` adapter.

```java
@Component
@ScheduledJob(name = "DailySalesReport", cron = "0 0 2 * * *")
public class DailySalesReportJob implements BackgroundTask {
    @Override
    public void execute() {
        // Generate the report.
    }
}
```

## Multi-node live UI (cluster bus)

Cross-node delivery of entity-change events so a horizontally-scaled onno app — more than one instance
behind a load balancer — keeps its live UI in sync. A write on one node lights up the browsers
connected to every other node.

### Why it exists

The live UI uses Server-Sent Events: `EventPublisher` fans an `EntityChangedEvent` out to the
`SseEmitter`s **held in that JVM**. With a single node that is the whole story. With several nodes, a
browser connected to node B never sees a change made on node A, because the event never leaves A.

The framework starter closes that gap with a small SPI and a database-backed default:

- **`ClusterEventBus`** (`su.onno.cluster`, in `onno-framework`) — the pluggable transport. Swappable
  exactly like `MediaStorage`.
- **`PostgresClusterEventBus`** (`su.onno.cluster.pg`, in this starter) — the default, using Postgres `LISTEN`/`NOTIFY`. No
  infrastructure beyond the database you already run.
- **`NoOpClusterEventBus`** — the local-only fallback used on H2/dev or when disabled.

It is a **best-effort live-UI signal, not a guaranteed event log.** An event dropped during a
reconnect is fine — the browser re-fetches the affected surface on its next interaction. For durable,
exactly-once domain-event distribution between *services*, use the transactional outbox (`onno_outbox`) with an application-owned relay.

### How it works

```
node A: write ──► EntityChangedEvent ──► local SSE clients (synchronous)
                                     └──► ClusterEntityChangeRelay ──► bus.publish ──► pg_notify
                                                                                          │
node B: LISTEN ◄──────────────────────────────────────────────────────────────────────┘
        getNotifications ──► ClusterEventBridge ──► EventPublisher.publish ──► node B's SSE clients
```

A received event is pushed straight to the SSE stream — it is **never re-published as a Spring
`ApplicationEvent`**. So business `@EventListener`s (cache revalidation, search indexing, post-hooks,
the outbox) still run exactly once, on the node that made the change. A node filters out its own
`NOTIFY` echo by the event's `originNodeId`.

### Activation

When the datasource is **PostgreSQL**, the bus activates automatically. On H2
(dev/test) or when the PostgreSQL driver is absent, it falls back to the no-op bus and nothing changes.

It contributes a bus only with `@ConditionalOnMissingBean(ClusterEventBus.class)`, so to plug in a
different transport (Kafka, Redis, …) just expose your own `ClusterEventBus` bean:

```java
@Bean
ClusterEventBus clusterEventBus(/* your deps */) {
    return new MyRedisClusterEventBus(/* … */);
}
```

### Configuration (`onno.cluster.*`)

| Property | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch. When off, a local-only no-op bus is used. |
| `channel` | `onno_cluster_events` | `LISTEN`/`NOTIFY` channel (bare identifier). |
| `node-id` | random UUID | This node's id, used to drop its own `NOTIFY` echoes. |
| `poll-timeout` | `5s` | How long the listener blocks before re-checking for shutdown. |
| `reconnect-backoff-max` | `30s` | Cap on the reconnect backoff after a dropped connection. |
| `max-payload-bytes` | `7000` | Soft cap below Postgres's 8000-byte `NOTIFY` limit; larger events drop their natural key, then degrade to a coarse `*` notice. |

### Operational notes

- The listener holds **one connection** from the datasource pool for its lifetime; size the pool with
  that in mind (e.g. `+1`).
- Multi-node scale-out also needs, outside the framework: a stable `onno.auth.session.remember-me.key`
  (or sticky/shared sessions), and a shared `MediaStorage` (the filesystem default is per-node). See
  the "Scaling out" section of `docs/ARCHITECTURE.md`.
