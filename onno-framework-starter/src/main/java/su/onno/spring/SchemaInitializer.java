package su.onno.spring;

import su.onno.metadata.*;
import su.onno.migration.AppMigration;
import su.onno.migration.MigrationRunner;
import su.onno.schema.SchemaMode;
import su.onno.schema.SchemaUpgrader;
import su.onno.schema.SearchIndexMode;
import su.onno.schema.SqlDialect;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;
import java.util.List;

public class SchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    /**
     * Fixed key for the Postgres session-level advisory lock that serializes schema apply across the
     * nodes of a horizontally-scaled deployment. Derived deterministically from {@code "onno"}/
     * {@code "schema"} so every node — running the same code — computes the identical bigint.
     */
    private static final long SCHEMA_LOCK_KEY =
            ((long) "onno".hashCode() << 32) | ("schema".hashCode() & 0xFFFFFFFFL);

    private final DataSource dataSource;
    private final List<String> scanPackages;
    private final SchemaMode mode;
    private final boolean allowDestructive;
    private final SearchIndexMode searchIndexes;
    private final List<AppMigration> migrations;

    public SchemaInitializer(DataSource dataSource, List<String> scanPackages) {
        this(dataSource, scanPackages, SchemaMode.APPLY, false, List.of());
    }

    public SchemaInitializer(DataSource dataSource, List<String> scanPackages,
                             SchemaMode mode, boolean allowDestructive,
                             List<AppMigration> migrations) {
        this(dataSource, scanPackages, mode, allowDestructive, SearchIndexMode.AUTO, migrations);
    }

    public SchemaInitializer(DataSource dataSource, List<String> scanPackages,
                             SchemaMode mode, boolean allowDestructive,
                             SearchIndexMode searchIndexes, List<AppMigration> migrations) {
        this.dataSource = dataSource;
        this.scanPackages = scanPackages;
        this.mode = mode;
        this.allowDestructive = allowDestructive;
        this.searchIndexes = searchIndexes;
        this.migrations = migrations;
    }

    @Override
    public void afterPropertiesSet() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        CatalogScanner catalogScanner = new CatalogScanner();
        for (Class<?> clazz : catalogScanner.scan(scanPackages)) {
            CatalogDescriptor descriptor = scanner.scan(clazz);
            registry.registerCatalog(descriptor);
        }

        DocumentScanner documentScanner = new DocumentScanner();
        for (Class<?> clazz : documentScanner.scan(scanPackages)) {
            DocumentDescriptor descriptor = scanner.scanDocument(clazz);
            registry.registerDocument(descriptor);
        }

        AccumulationScanner accumulationScanner = new AccumulationScanner();
        for (Class<?> clazz : accumulationScanner.scan(scanPackages)) {
            registry.registerAccumulation(scanner.scanRegister(clazz));
        }

        for (Class<?> clazz : new EnumerationScanner().scan(scanPackages)) {
            registry.registerEnumeration(scanner.scanEnumeration(clazz));
        }
        for (Class<?> clazz : new InformationRegisterScanner().scan(scanPackages)) {
            registry.registerInformationRegister(scanner.scanInformationRegister(clazz));
        }
        for (Class<?> clazz : new ConstantScanner().scan(scanPackages)) {
            registry.registerConstant(scanner.scanConstant(clazz));
        }

        if (mode == SchemaMode.OFF) {
            log.info("onno.schema.mode=off — skipping schema upgrade and migrations.");
            return;
        }

        Jdbi jdbi = Jdbi.create(dataSource);
        SqlDialect dialect = jdbi.withHandle(handle -> SqlDialect.detect(handle.getConnection()));

        if (dialect == SqlDialect.POSTGRESQL && mode == SchemaMode.APPLY) {
            // A horizontally-scaled deployment boots N instances that would otherwise race to run DDL.
            // Hold a session-level advisory lock for the whole apply so exactly one node mutates the
            // schema at a time; the others wait, then re-run against the now-current schema (the diff
            // is empty and migrations are already recorded in onno_schema_history, so it is idempotent).
            // The lock gates by session, not by which pooled connection runs the DDL. H2 is single-node
            // and skips this; PLAN/VALIDATE only read, so they need no lock.
            jdbi.useHandle(lock -> {
                acquireSchemaLock(lock);
                try {
                    applySchema(jdbi, registry);
                } finally {
                    lock.execute("SELECT pg_advisory_unlock(?)", SCHEMA_LOCK_KEY);
                }
            });
        } else {
            applySchema(jdbi, registry);
        }
    }

    /**
     * Poll {@code pg_try_advisory_lock} rather than block in {@code pg_advisory_lock}: a session
     * parked inside one long-running statement holds a snapshot, and a background
     * {@code CREATE INDEX CONCURRENTLY} (search index reconciliation on another node) waits for every
     * older snapshot to finish — while the lock holder may in turn wait on that index build. Short
     * try-statements hold no snapshot between attempts, so that cycle cannot form.
     */
    private static void acquireSchemaLock(org.jdbi.v3.core.Handle lock) {
        while (!Boolean.TRUE.equals(lock.createQuery("SELECT pg_try_advisory_lock(:key)")
                .bind("key", SCHEMA_LOCK_KEY).mapTo(Boolean.class).one())) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the schema advisory lock", e);
            }
        }
    }

    private void applySchema(Jdbi jdbi, MetadataRegistry registry) {
        new SchemaUpgrader(registry, mode, allowDestructive, searchIndexes).run(jdbi);

        MigrationRunner runner = new MigrationRunner(registry);
        switch (mode) {
            case APPLY -> runner.run(jdbi, migrations);
            case PLAN -> {
                List<AppMigration> pending = runner.pending(jdbi, migrations);
                if (!pending.isEmpty()) {
                    log.info("Pending migrations (not applied in plan mode): {}",
                            pending.stream().map(AppMigration::version).toList());
                }
            }
            case VALIDATE -> {
                List<AppMigration> pending = runner.pending(jdbi, migrations);
                if (!pending.isEmpty()) {
                    throw new IllegalStateException(
                            "Schema validation failed (onno.schema.mode=validate): unapplied migrations "
                                    + pending.stream().map(AppMigration::version).toList());
                }
            }
            default -> { }
        }
    }
}
