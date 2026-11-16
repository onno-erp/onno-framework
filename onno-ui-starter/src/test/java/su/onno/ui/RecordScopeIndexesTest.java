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

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@code onno.schema.mode} values let the record-scope indexes be created (#460). The mode is
 * read exactly as the schema initializer reads it, so a blank value (an empty environment variable,
 * say) still means apply, while every other mode only logs the statements.
 */
class RecordScopeIndexesTest {

    // Fixture fields are read reflectively by MetadataScanner, not by test code.
    @SuppressWarnings("unused")
    @Catalog(name = "ScopeIndexNotes")
    @AccessControl(readRoles = "CUSTOMER")
    static class Note extends CatalogObject {
        @Attribute private UUID owner; // scalar, so the schema itself leaves it unindexed
    }

    @Test
    void applyAndBlankModesCreateTheIndex() {
        for (String mode : new String[] {null, "", "  ", "apply", "APPLY", " Apply "}) {
            assertThat(createsIndex(mode, true)).as("onno.schema.mode=[%s]", mode).isTrue();
        }
    }

    @Test
    void otherModesOnlyLogTheStatements() {
        for (String mode : new String[] {"plan", "validate", "off", "update"}) {
            assertThat(createsIndex(mode, true)).as("onno.schema.mode=[%s]", mode).isFalse();
        }
    }

    @Test
    void disabledIndexCreationRunsNoDdl() {
        assertThat(createsIndex("apply", false)).isFalse();
    }

    private static boolean createsIndex(String mode, boolean enabled) {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:scope_indexes_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);
        var registry = new MetadataRegistry();
        CatalogDescriptor notes = new MetadataScanner(new DefaultNamingStrategy()).scan(Note.class);
        registry.registerCatalog(notes);
        new SchemaGenerator(registry).execute(jdbi);
        var policies = new RecordPolicies(registry, List.of(RecordAccessPolicy.forCatalog(Note.class)
                .appliesTo("CUSTOMER").read(RecordScope.eq("owner", Subject.recordId()))));

        new RecordScopeIndexes(policies, jdbi, enabled, mode).afterSingletonsInstantiated();

        String index = RecordScopeIndexes.indexName(notes.tableName(), "owner");
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(INDEX_NAME) = UPPER(:name)")
                .bind("name", index).mapTo(Integer.class).one()) == 1;
    }
}
