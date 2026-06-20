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

This is the only property the starter exposes. Everything else is derived from your annotated metadata.

## What gets auto-configured

`AutoConfiguration.imports` registers two classes:

- **`OnnoAutoConfiguration`** — the heart of the wiring. It extends `AbstractJdbcConfiguration`,
  registers the onno `NamingStrategy` and mapping context, and exposes:
  - `Jdbi` (built from the `DataSource`), `MetadataRegistry`, `RefResolver`
  - `SchemaInitializer` (see below)
  - the lifecycle callbacks (`OnnoBeforeConvertCallback`, `OnnoAfterSaveCallback`,
    `OnnoAfterConvertCallback`, `OnnoBeforeDeleteCallback`)
  - `NumberGenerator` (JDBC-backed code/number sequences), `OutboxWriter`
  - register persistence/repository maps, `PostingService` (wrapping a `PostingEngine`)
  - information-register, constant and enumeration persistence/managers
  - the UI layout model (`LayoutSet`, `UiLayout`, resolvers) assembled from your `Layout` beans
  - `BackgroundJobs` and `ScheduledJobRegistrar` — only when a JobRunr `JobScheduler` bean is present
    (`@ConditionalOnBean(JobScheduler.class)`)
- **`OnnoRepositoriesAutoConfiguration`** — replaces Spring Boot's stock
  `JdbcRepositoriesAutoConfiguration` with one that excludes `RegisterRepository` subtypes, and
  scans register repositories separately. Register repositories are register-shaped (append-only
  movement records) rather than aggregate-shaped, so they need their own factory.

### Schema generation

On startup `SchemaInitializer` (an `InitializingBean`) re-scans the same packages, builds a
`MetadataRegistry`, and runs `SchemaGenerator` against the `DataSource`. This is **idempotent**:
tables are created with `CREATE TABLE IF NOT EXISTS` and migrations are **additive** (new columns are
added; nothing is dropped or altered destructively). Enumeration rows are seeded here too. No
Flyway/Liquibase scripts are needed for framework-managed tables.

### Enumerations persist as UUIDs

Enums annotated as framework enumerations are first-class persisted values. They are stored as
**UUID columns** through Spring Data JDBC converters registered in `userConverters()`
(`EnumUuidConverters`), and their rows are seeded by the schema generator. If you find a seeder
comment claiming enum-as-UUID is "not yet wired", that comment is stale — it works.

The converters also read enums (and `Ref<T>`) back from a **`varchar` column that holds the UUID as
text**, not just a native `uuid` column. This makes an attribute that changed from `String` to an
`@Enumeration`/`Ref<T>` across deploys resilient: the column may still be `varchar` from when it
held free text, yet onno keeps writing the value's UUID and reads it back correctly instead of
throwing `Enum.valueOf(<uuid string>)` (issue #168). A value that is a bare enum `name()` (truly
legacy data from before onno used UUIDs) still resolves by name.

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
(which has no access to your beans) and the Kafka outbox (which requires that starter), an
`@EventListener` is an ordinary bean with full DI, ideal for firing an external integration.

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

If your app puts a JobRunr `JobScheduler` on the context, the starter contributes `BackgroundJobs`
(a `JobrunrBackgroundJobs` adapter) and a `ScheduledJobRegistrar` that registers your annotated
scheduled jobs. Without a `JobScheduler` bean these are simply absent — jobs are opt-in.
