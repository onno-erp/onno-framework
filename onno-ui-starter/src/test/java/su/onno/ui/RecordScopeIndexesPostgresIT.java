package su.onno.ui;

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

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Record-scope indexes on a real PostgreSQL (#460): a missing index is created, and an existing one
 * issues no statement at all — {@code CREATE INDEX IF NOT EXISTS} would otherwise queue for a SHARE
 * lock behind any open writer on every boot. Skipped without Docker.
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
        new SchemaGenerator(registry).execute(jdbi);

        RecordPolicies policies = new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(PgScopedNote.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("owner", Subject.recordId()))));
        indexes = new RecordScopeIndexes(policies, jdbi, true, "apply");
        index = RecordScopeIndexes.indexName(notes.tableName(), "owner");
    }

    @Test
    void createsAMissingScopeIndex() {
        assertThat(indexExists()).isFalse();

        indexes.afterSingletonsInstantiated();

        assertThat(indexExists()).isTrue();
    }

    @Test
    void anExistingScopeIndexDoesNotWaitBehindAnOpenWriter() {
        indexes.afterSingletonsInstantiated();
        Handle writer = jdbi.open();
        try {
            // An uncommitted insert holds ROW EXCLUSIVE, which conflicts with CREATE INDEX's SHARE lock.
            writer.begin();
            writer.createUpdate("INSERT INTO " + notes.tableName()
                            + " (_id, _code, _description, owner) VALUES (:id, 'A', 'open writer', :id)")
                    .bind("id", UUID.randomUUID()).execute();

            assertTimeoutPreemptively(Duration.ofSeconds(10), indexes::afterSingletonsInstantiated);
        } finally {
            writer.rollback();
            writer.close();
        }
        assertThat(indexExists()).isTrue();
    }

    private boolean indexExists() {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname = :name")
                .bind("name", index).mapTo(Long.class).one()) == 1L;
    }
}
