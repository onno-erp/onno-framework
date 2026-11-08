package su.onno.schema;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.jdbi.v3.core.ConnectionFactory;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The trigram search index reconciler against a real PostgreSQL: undeclared framework-owned
 * indexes are dropped (by definition, not name), declared ones are built concurrently, and a
 * same-named non-trigram index is left alone. Another node's search reconciliation is skipped for,
 * another index reconciliation is waited for without holding a connection, and both advisory locks
 * are released. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class SearchIndexReconcilerPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Catalog(name = "ReconcileNotes")
    static class Note extends CatalogObject {
        @Attribute(searchIndex = true)
        private String title;

        @Attribute
        private String body;

        @Attribute
        private String tag;
    }

    private Jdbi jdbi;
    private SchemaGenerator generator;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbi.useHandle(h -> h.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(new MetadataScanner(new DefaultNamingStrategy()).scan(Note.class));
        generator = new SchemaGenerator(registry);
        jdbi.useHandle(h -> {
            for (String ddl : generator.generateDDL(SqlDialect.POSTGRESQL)) {
                h.execute(ddl);
            }
            h.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            // What an older onno left behind: a trigram index on an attribute nobody opted in…
            h.execute("CREATE INDEX idx_trgm_catalog_reconcile_notes_body ON catalog_reconcile_notes"
                    + " USING gin (LOWER(CAST(body AS VARCHAR)) gin_trgm_ops)");
            // …and an app's placeholder workaround reusing the framework's name pattern.
            h.execute("CREATE INDEX idx_trgm_catalog_reconcile_notes_tag ON catalog_reconcile_notes (_id)"
                    + " WHERE false");
        });
    }

    private List<String> trigramIndexes() {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT indexname FROM pg_indexes
                        WHERE tablename = 'catalog_reconcile_notes' AND indexdef LIKE '%gin_trgm_ops%'
                        ORDER BY indexname""").mapTo(String.class).list());
    }

    private List<String> allIndexes() {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'catalog_reconcile_notes'")
                .mapTo(String.class).list());
    }

    @Test
    void autoKeepsSystemAndOptedInColumnsAndDropsTheRest() {
        new SearchIndexReconciler(generator, SearchIndexMode.AUTO).reconcile(jdbi, SqlDialect.POSTGRESQL);

        assertThat(trigramIndexes()).containsExactly(
                "idx_trgm_catalog_reconcile_notes__code",
                "idx_trgm_catalog_reconcile_notes__description",
                "idx_trgm_catalog_reconcile_notes_title");
        // The non-trigram placeholder is not ours — kept.
        assertThat(allIndexes()).contains("idx_trgm_catalog_reconcile_notes_tag");

        // Idempotent: a second pass changes nothing.
        new SearchIndexReconciler(generator, SearchIndexMode.AUTO).reconcile(jdbi, SqlDialect.POSTGRESQL);
        assertThat(trigramIndexes()).hasSize(3);
    }

    @Test
    void offDropsEveryFrameworkOwnedTrigramIndex() {
        new SearchIndexReconciler(generator, SearchIndexMode.AUTO).reconcile(jdbi, SqlDialect.POSTGRESQL);
        new SearchIndexReconciler(generator, SearchIndexMode.OFF).reconcile(jdbi, SqlDialect.POSTGRESQL);

        assertThat(trigramIndexes()).isEmpty();
        assertThat(allIndexes()).contains("idx_trgm_catalog_reconcile_notes_tag");
    }

    @Test
    void skipsWhileAnotherNodeReconcilesSearchIndexes() {
        List<String> before = trigramIndexes();
        Handle otherNode = jdbi.open();
        try {
            assertThat(tryLock(otherNode, SearchIndexReconciler.LOCK_KEY)).isTrue();

            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    new SearchIndexReconciler(generator, SearchIndexMode.AUTO).reconcile(jdbi, SqlDialect.POSTGRESQL));

            assertThat(trigramIndexes()).isEqualTo(before);
        } finally {
            unlock(otherNode, SearchIndexReconciler.LOCK_KEY);
            otherNode.close();
        }
    }

    @Test
    void waitsForAnotherIndexReconciliationInsteadOfSkipping() {
        List<String> before = trigramIndexes();
        Jdbi waiting = tagged("onno-search-wait");
        Handle otherReconciliation = jdbi.open();
        CompletableFuture<Void> search;
        try {
            assertThat(tryLock(otherReconciliation, SearchIndexReconciler.BUILD_LOCK_KEY)).isTrue();

            search = CompletableFuture.runAsync(() ->
                    new SearchIndexReconciler(generator, SearchIndexMode.AUTO).reconcile(waiting, SqlDialect.POSTGRESQL));
            pause(2_000);
            assertThat(search).as("waits rather than build alongside or skip").isNotDone();
            assertThat(trigramIndexes()).isEqualTo(before);
            assertThat(heldSessions("onno-search-wait")).as("connections held while waiting").isZero();
        } finally {
            unlock(otherReconciliation, SearchIndexReconciler.BUILD_LOCK_KEY);
            otherReconciliation.close();
        }
        assertThat(search).succeedsWithin(Duration.ofSeconds(30));
        assertThat(trigramIndexes()).containsExactly(
                "idx_trgm_catalog_reconcile_notes__code",
                "idx_trgm_catalog_reconcile_notes__description",
                "idx_trgm_catalog_reconcile_notes_title");
    }

    @Test
    void releasesBothLocksWhenTheConnectionStaysOpen() throws Exception {
        // A pool keeps the session, and with it any advisory lock left unreleased.
        Handle pooled = jdbi.open();
        try {
            Connection connection = pooled.getConnection();
            Jdbi onPooled = Jdbi.create(new ConnectionFactory() {
                @Override
                public Connection openConnection() {
                    return connection;
                }

                @Override
                public void closeConnection(Connection ignored) {
                    // Returned to the "pool": the session, and its advisory locks, live on.
                }
            });
            new SearchIndexReconciler(generator, SearchIndexMode.AUTO).reconcile(onPooled, SqlDialect.POSTGRESQL);

            assertThat(pooled.getConnection().isClosed()).isFalse();

            assertThat(trigramIndexes()).hasSize(3);
            assertThat(lockFree(SearchIndexReconciler.LOCK_KEY)).isTrue();
            assertThat(lockFree(SearchIndexReconciler.BUILD_LOCK_KEY)).isTrue();
        } finally {
            pooled.close();
        }
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

    private static boolean tryLock(Handle handle, long key) {
        return handle.createQuery("SELECT pg_try_advisory_lock(:key)").bind("key", key).mapTo(Boolean.class).one();
    }

    private static void unlock(Handle handle, long key) {
        handle.createQuery("SELECT pg_advisory_unlock(:key)").bind("key", key).mapTo(Boolean.class).one();
    }

    /** Whether advisory lock {@code key} is free: takes it from a fresh session and gives it back. */
    private boolean lockFree(long key) {
        try (Handle handle = jdbi.open()) {
            boolean locked = tryLock(handle, key);
            if (locked) unlock(handle, key);
            return locked;
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
}
