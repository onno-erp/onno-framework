package su.onno.schema;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trigram search index reconciler against a real PostgreSQL: undeclared framework-owned
 * indexes are dropped (by definition, not name), declared ones are built concurrently, and a
 * same-named non-trigram index is left alone. Skipped when Docker is unavailable.
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
}
