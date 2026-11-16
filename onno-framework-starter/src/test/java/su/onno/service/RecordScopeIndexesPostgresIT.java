package su.onno.service;

import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
import su.onno.access.Subject;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;
import su.onno.schema.SearchIndexMode;
import su.onno.schema.SearchIndexReconciler;
import su.onno.schema.SqlDialect;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Record-scope indexes on a real PostgreSQL: a missing index is built with
 * {@code CREATE INDEX CONCURRENTLY} on a background thread, so neither startup nor writes wait for
 * it; an index left invalid by an interrupted build is dropped and rebuilt, while an invalid index
 * that is not a plain one on the scoped column is left alone; an existing index waits neither for a
 * writer nor for another build on the table; another session's build on the same
 * table, even by a role whose progress is hidden, is waited for instead of deadlocking with it, while
 * an autovacuum of the table is not, and a table that stays busy is left for the next start; it and
 * the search-index reconciliation take turns under the shared build lock, neither skipping for the
 * other nor holding a connection while it waits; both advisory locks are released; and a node skips
 * the build while another holds the lock.
 * Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class RecordScopeIndexesPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Catalog(name = "Pg Scoped Notes")
    @AccessControl(readRoles = "CUSTOMER")
    public static class PgScopedNote extends CatalogObject {
        @Attribute private UUID owner; // scalar, so the schema itself leaves it unindexed
    }

    private Jdbi jdbi;
    private CatalogDescriptor notes;
    private SchemaGenerator generator;
    private RecordPolicies policies;
    private RecordScopeIndexes indexes;
    private String index;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        MetadataRegistry registry = new MetadataRegistry();
        notes = new MetadataScanner(new DefaultNamingStrategy()).scan(PgScopedNote.class);
        registry.registerCatalog(notes);

        // Fresh table (and so no scope index) per test; the container is shared across the class.
        jdbi.useHandle(h -> h.execute("DROP TABLE IF EXISTS " + notes.tableName()));
        generator = new SchemaGenerator(registry);
        generator.execute(jdbi);

        policies = new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(PgScopedNote.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("owner", Subject.recordId()))));
        indexes = new RecordScopeIndexes(policies, jdbi, true, "apply");
        index = RecordScopeIndexes.indexName(notes.tableName(), "owner");
    }

    @Test
    void buildsAMissingScopeIndex() {
        assertThat(validity()).isEmpty();

        indexes.build();

        assertThat(validity()).contains(true);
    }

    @Test
    void neitherStartupNorWritesWaitForTheBuild() {
        Handle writer = jdbi.open();
        try {
            // An open write keeps the concurrent build waiting, so it is still in flight below.
            writer.begin();
            insert(writer, UUID.randomUUID());

            assertTimeoutPreemptively(Duration.ofSeconds(10), indexes::afterSingletonsInstantiated);
            await("the index build to start", this::buildInFlight);

            // A plain CREATE INDEX would queue a SHARE lock here and stall this write behind it.
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> jdbi.useHandle(h -> insert(h, UUID.randomUUID())));
        } finally {
            writer.rollback();
            writer.close();
        }
        await("the index to become valid", () -> validity().orElse(false));
        await("the build to release its lock", this::buildLockReleased);
    }

    @Test
    void rebuildsAnIndexLeftInvalidByAnInterruptedBuild() {
        interruptBuild("CREATE INDEX CONCURRENTLY " + index + " ON " + notes.tableName() + " (owner)");
        assertThat(validity()).contains(false);

        indexes.build();

        assertThat(validity()).contains(true);
    }

    @Test
    void leavesAnInvalidIndexThatIsNotAPlainScopeIndexInPlace() {
        UUID owner = UUID.randomUUID();
        jdbi.useHandle(h -> {
            insert(h, owner);
            insert(h, owner);
        });
        // A unique build over duplicate owners fails and leaves an invalid unique index under the name.
        assertThatThrownBy(() -> jdbi.useHandle(h -> h.execute(
                "CREATE UNIQUE INDEX CONCURRENTLY " + index + " ON " + notes.tableName() + " (owner)")));
        assertThat(validity()).contains(false);

        indexes.build();

        assertThat(validity()).contains(false);
        assertThat(definition()).startsWith("CREATE UNIQUE INDEX");
    }

    @Test
    void anExistingScopeIndexDoesNotWaitBehindAnOpenWriter() {
        indexes.build();
        Handle writer = jdbi.open();
        try {
            writer.begin();
            insert(writer, UUID.randomUUID());

            assertTimeoutPreemptively(Duration.ofSeconds(10), indexes::build);
        } finally {
            writer.rollback();
            writer.close();
        }
        assertThat(validity()).contains(true);
    }

    @Test
    void anExistingScopeIndexDoesNotWaitForAnotherBuildOnTheTable() {
        indexes.build();
        String otherIndex = "idx_other_search";
        Handle writer = jdbi.open();
        CompletableFuture<Void> otherBuild;
        try {
            // Another build on the table, kept in flight by an open write. A valid index needs no
            // statement, so there is nothing to wait for (the table-busy limit here is 30 minutes).
            writer.begin();
            insert(writer, UUID.randomUUID());
            otherBuild = CompletableFuture.runAsync(() -> jdbi.useHandle(h -> h.execute(
                    "CREATE INDEX CONCURRENTLY " + otherIndex + " ON " + notes.tableName() + " (lower(_description))")));
            await("the other build to start", () -> buildInFlight(otherIndex));

            assertTimeoutPreemptively(Duration.ofSeconds(10), indexes::build);

            assertThat(validity()).contains(true);
        } finally {
            writer.rollback();
            writer.close();
        }
        assertThat(otherBuild).succeedsWithin(Duration.ofSeconds(30));
        assertThat(validity(otherIndex)).contains(true);
    }

    @Test
    void skipsTheBuildWhileAnotherNodeHoldsTheLock() {
        Handle otherNode = jdbi.open();
        try {
            assertThat(otherNode.createQuery("SELECT pg_try_advisory_lock(:key)")
                    .bind("key", RecordScopeIndexes.LOCK_KEY).mapTo(Boolean.class).one()).isTrue();

            assertTimeoutPreemptively(Duration.ofSeconds(10), indexes::build);

            assertThat(validity()).isEmpty();
        } finally {
            otherNode.createQuery("SELECT pg_advisory_unlock(:key)")
                    .bind("key", RecordScopeIndexes.LOCK_KEY).mapTo(Boolean.class).one();
            otherNode.close();
        }
        indexes.build();
        assertThat(validity()).contains(true);
    }

    @Test
    void waitsForAnotherBuildOfTheIndexAndKeepsIt() {
        waitsForAnotherBuildOfTheIndexAndKeepsIt(indexes);
    }

    @Test
    void waitsForABuildByAnotherRoleItCannotSeeAndKeepsIt() {
        waitsForAnotherBuildOfTheIndexAndKeepsIt(new RecordScopeIndexes(policies, appRole(), true, "apply"));
    }

    /** {@code builder} must wait for this superuser's build of the index rather than drop it. */
    private void waitsForAnotherBuildOfTheIndexAndKeepsIt(RecordScopeIndexes builder) {
        Handle writer = jdbi.open();
        CompletableFuture<Void> otherBuild;
        CompletableFuture<Void> ours;
        long otherIndex;
        try {
            writer.begin();
            insert(writer, UUID.randomUUID());
            otherBuild = CompletableFuture.runAsync(() -> jdbi.useHandle(h -> h.execute(
                    "CREATE INDEX CONCURRENTLY " + index + " ON " + notes.tableName() + " (owner)")));
            await("the other build to start", this::buildInFlight);
            assertThat(validity()).contains(false);
            otherIndex = indexOid();

            // Dropping or building now would deadlock with that build; this one waits instead.
            ours = CompletableFuture.runAsync(builder::build);
            pause(2_000);
            assertThat(ours).isNotDone();
        } finally {
            writer.rollback();
            writer.close();
        }
        assertThat(otherBuild).succeedsWithin(Duration.ofSeconds(30));
        assertThat(ours).succeedsWithin(Duration.ofSeconds(30));
        assertThat(validity()).contains(true);
        assertThat(indexOid()).as("the other session's index is kept, not dropped and rebuilt").isEqualTo(otherIndex);
    }

    @Test
    void waitsForTheSearchIndexReconciliationToFinish() {
        dropSearchIndexes();
        RecordScopeIndexes waiting = new RecordScopeIndexes(policies, tagged("onno-scope-wait"), true, "apply");
        Handle writer = jdbi.open();
        CompletableFuture<Void> search;
        CompletableFuture<Void> ours;
        try {
            // An open write keeps the search reconciliation's concurrent build in flight below.
            writer.begin();
            insert(writer, UUID.randomUUID());
            search = CompletableFuture.runAsync(() -> reconcileSearchIndexes(jdbi));
            await("the search index build to start", () -> buildInFlight("idx_trgm_" + notes.tableName()));

            ours = CompletableFuture.runAsync(waiting::build);
            pause(2_000);
            assertThat(ours).isNotDone();
            assertThat(validity()).isEmpty();
            assertThat(heldSessions("onno-scope-wait")).as("connections held while waiting").isZero();
        } finally {
            writer.rollback();
            writer.close();
        }
        assertThat(search).succeedsWithin(Duration.ofSeconds(30));
        assertThat(ours).succeedsWithin(Duration.ofSeconds(30));
        assertThat(searchIndexes()).containsExactlyInAnyOrderElementsOf(declaredSearchIndexes());
        assertThat(validity()).contains(true);
    }

    @Test
    void theSearchIndexReconciliationWaitsForItsBuildInsteadOfSkipping() {
        dropSearchIndexes();
        Handle writer = jdbi.open();
        CompletableFuture<Void> ours;
        CompletableFuture<Void> search;
        try {
            // An open write keeps our concurrent build in flight below.
            writer.begin();
            insert(writer, UUID.randomUUID());
            ours = CompletableFuture.runAsync(indexes::build);
            await("our build to start", this::buildInFlight);

            Jdbi waiting = tagged("onno-search-wait");
            search = CompletableFuture.runAsync(() -> reconcileSearchIndexes(waiting));
            pause(2_000);
            assertThat(search).as("waits for our build rather than build alongside or skip").isNotDone();
            assertThat(searchIndexes()).isEmpty();
            assertThat(heldSessions("onno-search-wait")).as("connections held while waiting").isZero();
        } finally {
            writer.rollback();
            writer.close();
        }
        assertThat(ours).succeedsWithin(Duration.ofSeconds(30));
        assertThat(search).succeedsWithin(Duration.ofSeconds(30));
        assertThat(validity()).contains(true);
        assertThat(searchIndexes()).containsExactlyInAnyOrderElementsOf(declaredSearchIndexes());
    }

    @Test
    void leavesItsIndexesForTheNextStartWhileAnotherReconciliationKeepsBuilding() {
        RecordScopeIndexes impatient = new RecordScopeIndexes(policies, jdbi, true, "apply", Duration.ofSeconds(2));
        Handle otherReconciliation = jdbi.open();
        try {
            assertThat(tryLock(otherReconciliation, SearchIndexReconciler.BUILD_LOCK_KEY)).isTrue();

            assertTimeoutPreemptively(Duration.ofSeconds(15), impatient::build);

            assertThat(validity()).isEmpty();
        } finally {
            unlock(otherReconciliation, SearchIndexReconciler.BUILD_LOCK_KEY);
            otherReconciliation.close();
        }
    }

    @Test
    void releasesBothLocksWhenTheConnectionStaysOpen() {
        // A pool keeps the session, and with it any advisory lock left unreleased.
        SingleConnectionDataSource pooled = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        try {
            new RecordScopeIndexes(policies, Jdbi.create(pooled), true, "apply").build();

            assertThat(validity()).contains(true);
            assertThat(lockFree(RecordScopeIndexes.LOCK_KEY)).isTrue();
            assertThat(lockFree(SearchIndexReconciler.BUILD_LOCK_KEY)).isTrue();
        } finally {
            pooled.destroy();
        }
    }

    @Test
    void doesNotDeadlockWithAnotherIndexBuildOnTheSameTable() {
        String searchIndex = "idx_other_search";
        Handle writer = jdbi.open();
        CompletableFuture<Void> otherBuild;
        CompletableFuture<Void> ours;
        try {
            writer.begin();
            insert(writer, UUID.randomUUID());
            // An expression index, like the search indexes SearchIndexReconciler builds concurrently.
            otherBuild = CompletableFuture.runAsync(() -> jdbi.useHandle(h -> h.execute(
                    "CREATE INDEX CONCURRENTLY " + searchIndex + " ON " + notes.tableName() + " (lower(_description))")));
            await("the other build to start", () -> buildInFlight(searchIndex));

            ours = CompletableFuture.runAsync(indexes::build);
            pause(2_000);
            assertThat(ours).isNotDone();
        } finally {
            writer.rollback();
            writer.close();
        }
        assertThat(otherBuild).succeedsWithin(Duration.ofSeconds(30));
        assertThat(ours).succeedsWithin(Duration.ofSeconds(30));
        assertThat(validity(searchIndex)).contains(true);
        assertThat(validity()).contains(true);
    }

    @Test
    void leavesATableThatStaysBusyForTheNextStart() {
        String searchIndex = "idx_other_search";
        RecordScopeIndexes impatient = new RecordScopeIndexes(policies, jdbi, true, "apply", Duration.ofSeconds(2));
        Handle writer = jdbi.open();
        CompletableFuture<Void> otherBuild;
        try {
            writer.begin();
            insert(writer, UUID.randomUUID());
            otherBuild = CompletableFuture.runAsync(() -> jdbi.useHandle(h -> h.execute(
                    "CREATE INDEX CONCURRENTLY " + searchIndex + " ON " + notes.tableName() + " (lower(_description))")));
            await("the other build to start", () -> buildInFlight(searchIndex));

            assertTimeoutPreemptively(Duration.ofSeconds(15), impatient::build);

            assertThat(validity()).isEmpty();
        } finally {
            writer.rollback();
            writer.close();
        }
        assertThat(otherBuild).succeedsWithin(Duration.ofSeconds(30));
        assertThat(validity(searchIndex)).contains(true);
    }

    @Test
    void doesNotWaitForAnAutovacuumOfTheTable() {
        dropSearchIndexes();
        RecordScopeIndexes impatient = new RecordScopeIndexes(policies, appRole(), true, "apply", Duration.ofSeconds(2));
        jdbi.useHandle(h -> {
            h.execute("INSERT INTO " + notes.tableName() + " (_id, _code, _description, owner) "
                    + "SELECT gen_random_uuid(), 'c' || i, 'note', gen_random_uuid() FROM generate_series(1, 200000) i");
            // Throttle autovacuum on this table to a crawl, so it still runs when the build starts.
            h.execute("ALTER TABLE " + notes.tableName() + " SET (autovacuum_vacuum_threshold = 0, "
                    + "autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_cost_delay = 100, "
                    + "autovacuum_vacuum_cost_limit = 1)");
            h.execute("DELETE FROM " + notes.tableName() + " WHERE _code LIKE '%0'");
            h.execute("ALTER SYSTEM SET autovacuum_naptime = 1");
            h.createQuery("SELECT pg_reload_conf()").mapTo(Boolean.class).one();
        });
        try {
            await("autovacuum to work on the table", this::autovacuumOnTable);

            // Waiting the autovacuum out would leave the index for the next start; the build cancels it.
            assertTimeoutPreemptively(Duration.ofSeconds(60), impatient::build);

            assertThat(validity()).contains(true);
        } finally {
            jdbi.useHandle(h -> {
                h.execute("ALTER SYSTEM RESET autovacuum_naptime");
                h.createQuery("SELECT pg_reload_conf()").mapTo(Boolean.class).one();
            });
        }
    }

    @Test
    void recognisesItsOwnIndexOnAKeywordColumn() {
        // pg_get_indexdef quotes a column named after a keyword ("position", "time", ...); an
        // unquoted comparison would never treat such an index as ours, so it could never be repaired.
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE scope_keyword_cols (position integer, plain integer)");
            try {
                for (String column : List.of("position", "plain")) {
                    var wanted = new RecordScopeIndexes.ScopeIndex(
                            RecordScopeIndexes.indexName("scope_keyword_cols", column), "scope_keyword_cols", column);
                    h.execute(wanted.ddl(false));
                    String def = h.createQuery("SELECT pg_get_indexdef(to_regclass(:name))")
                            .bind("name", wanted.name()).mapTo(String.class).one();
                    var existing = new RecordScopeIndexes.ExistingIndex(wanted.name(), "scope_keyword_cols", false, def);
                    assertThat(existing.isPlainIndexOn(wanted, RecordScopeIndexes.quoteIdent(h, column)))
                            .as("%s is recognised as the scope index", def).isTrue();
                }
            } finally {
                h.execute("DROP TABLE scope_keyword_cols");
            }
        });
    }

    @Test
    void repairsItsIndexWhileTheSameNameIsBuiltInAnotherSchema() throws Exception {
        interruptBuild("CREATE INDEX CONCURRENTLY " + index + " ON " + notes.tableName() + " (owner)");
        assertThat(validity()).contains(false);
        String otherTable = "scope_other." + notes.tableName();
        jdbi.useHandle(h -> {
            h.execute("DROP SCHEMA IF EXISTS scope_other CASCADE");
            h.execute("CREATE SCHEMA scope_other");
            h.execute("CREATE TABLE " + otherTable + " (owner uuid)");
        });
        Handle writer = jdbi.open();
        CompletableFuture<Void> otherBuild = null;
        try {
            writer.begin();
            writer.createUpdate("INSERT INTO " + otherTable + " (owner) VALUES (:owner)")
                    .bind("owner", UUID.randomUUID()).execute();
            otherBuild = CompletableFuture.runAsync(() -> jdbi.useHandle(h -> h.execute(
                    "CREATE INDEX CONCURRENTLY " + index + " ON " + otherTable + " (owner)")));
            await("the other schema's build to start", this::buildInFlight);

            // A same-named build in another schema must not pass for this schema's index being built.
            assertTimeoutPreemptively(Duration.ofSeconds(10), indexes::build);

            assertThat(validity()).contains(true);
        } finally {
            writer.rollback();
            writer.close();
            if (otherBuild != null) {
                otherBuild.get(30, TimeUnit.SECONDS);
            }
            jdbi.useHandle(h -> h.execute("DROP SCHEMA IF EXISTS scope_other CASCADE"));
        }
    }

    /** Starts {@code sql}, a concurrent build, and cancels it while it waits for an open write. */
    private void interruptBuild(String sql) {
        Handle writer = jdbi.open();
        try {
            writer.begin();
            insert(writer, UUID.randomUUID());
            CompletableFuture<Void> build = CompletableFuture.runAsync(() -> jdbi.useHandle(h -> h.execute(sql)));
            await("the index build to start", this::buildInFlight);

            jdbi.useHandle(h -> h.createQuery("""
                            SELECT pg_cancel_backend(pid) FROM pg_stat_activity
                            WHERE pid <> pg_backend_pid() AND query ILIKE 'CREATE%INDEX%'
                              AND query LIKE '%' || :name || '%'
                            """)
                    .bind("name", index).mapTo(Boolean.class).list());
            assertThat(build).failsWithin(Duration.ofSeconds(10));
        } finally {
            writer.rollback();
            writer.close();
        }
    }

    /** A non-superuser application role that owns the notes table, as in a real deployment. */
    private Jdbi appRole() {
        jdbi.useHandle(h -> {
            h.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'scope_app') "
                    + "THEN CREATE ROLE scope_app LOGIN PASSWORD 'scope_app'; END IF; END $$");
            h.execute("GRANT CREATE ON SCHEMA public TO scope_app");
            h.execute("ALTER TABLE " + notes.tableName() + " OWNER TO scope_app");
        });
        return Jdbi.create(POSTGRES.getJdbcUrl(), "scope_app", "scope_app");
    }

    /** Whether an autovacuum worker holds the notes table's SHARE UPDATE EXCLUSIVE lock. */
    private boolean autovacuumOnTable() {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                            WHERE l.relation = to_regclass(:table) AND l.mode = 'ShareUpdateExclusiveLock'
                              AND l.granted AND a.backend_type = 'autovacuum worker')
                        """)
                .bind("table", notes.tableName()).mapTo(Boolean.class).one());
    }

    private void reconcileSearchIndexes(Jdbi on) {
        new SearchIndexReconciler(generator, SearchIndexMode.AUTO).reconcile(on, SqlDialect.POSTGRESQL);
    }

    /** A Jdbi whose sessions carry {@code applicationName}, so they can be found in pg_stat_activity. */
    private static Jdbi tagged(String applicationName) {
        String url = POSTGRES.getJdbcUrl();
        return Jdbi.create(url + (url.contains("?") ? "&" : "?") + "ApplicationName=" + applicationName,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Sessions tagged {@code applicationName} that have stayed open for over a second. */
    private long heldSessions(String applicationName) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE application_name = :app AND backend_start < now() - interval '1 second'
                        """)
                .bind("app", applicationName).mapTo(Long.class).one());
    }

    /** Drops the search indexes the schema generator created, so the reconciliation has some to build. */
    private void dropSearchIndexes() {
        jdbi.useHandle(h -> {
            for (String name : declaredSearchIndexes()) {
                h.execute("DROP INDEX IF EXISTS " + name);
            }
        });
        assertThat(searchIndexes()).isEmpty();
    }

    private List<String> declaredSearchIndexes() {
        List<String> names = generator.searchIndexes(SearchIndexMode.AUTO).stream()
                .filter(searchIndex -> searchIndex.table().equals(notes.tableName()))
                .map(SchemaGenerator.SearchIndex::name).toList();
        assertThat(names).isNotEmpty();
        return names;
    }

    /** The valid trigram search indexes on the notes table. */
    private List<String> searchIndexes() {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT c.relname FROM pg_index i
                        JOIN pg_class c ON c.oid = i.indexrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND i.indrelid = to_regclass(:table)
                          AND i.indisvalid AND pg_get_indexdef(c.oid) LIKE '%gin_trgm_ops%'
                        """)
                .bind("table", notes.tableName()).mapTo(String.class).list());
    }

    private void insert(Handle handle, UUID owner) {
        handle.createUpdate("INSERT INTO " + notes.tableName()
                        + " (_id, _code, _description, owner) VALUES (:id, :code, 'note', :owner)")
                .bind("id", UUID.randomUUID())
                .bind("code", UUID.randomUUID().toString().substring(0, 8))
                .bind("owner", owner)
                .execute();
    }

    /** Whether the scope index is valid; empty when it does not exist. */
    private Optional<Boolean> validity() {
        return validity(index);
    }

    /** Whether the named current-schema index is valid; empty when it does not exist. */
    private Optional<Boolean> validity(String name) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT i.indisvalid FROM pg_index i
                        JOIN pg_class c ON c.oid = i.indexrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND c.relname = :name
                        """)
                .bind("name", name).mapTo(Boolean.class).findOne());
    }

    private long indexOid() {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT c.oid FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND c.relname = :name
                        """)
                .bind("name", index).mapTo(Long.class).one());
    }

    private String definition() {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT pg_get_indexdef(c.oid) FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND c.relname = :name
                        """)
                .bind("name", index).mapTo(String.class).one());
    }

    private boolean buildInFlight() {
        return buildInFlight(index);
    }

    private boolean buildInFlight(String name) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE pid <> pg_backend_pid() AND state = 'active'
                          AND query ILIKE 'CREATE%INDEX%' AND query LIKE '%' || :name || '%'
                        """)
                .bind("name", name).mapTo(Long.class).one()) > 0;
    }

    private static boolean tryLock(Handle handle, long key) {
        return handle.createQuery("SELECT pg_try_advisory_lock(:key)").bind("key", key).mapTo(Boolean.class).one();
    }

    private static void unlock(Handle handle, long key) {
        handle.createQuery("SELECT pg_advisory_unlock(:key)").bind("key", key).mapTo(Boolean.class).one();
    }

    /** Whether advisory lock {@code key} is free: takes it from a fresh session and gives it back. */
    private boolean lockFree(long key) {
        Handle handle = jdbi.open();
        try {
            boolean locked = tryLock(handle, key);
            if (locked) unlock(handle, key);
            return locked;
        } finally {
            handle.close();
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private boolean buildLockReleased() {
        return jdbi.withHandle(h -> h.createQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'")
                .mapTo(Long.class).one()) == 0;
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + what, e);
            }
        }
    }
}
