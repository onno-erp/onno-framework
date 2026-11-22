package su.onno.schema;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import su.onno.annotations.Catalog;
import su.onno.annotations.TabularSection;
import su.onno.metadata.*;
import su.onno.model.CatalogObject;
import su.onno.fixtures.TestInvoiceLine;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CatalogTabularSectionSchemaTest {
    @Catalog(name = "Profiles", tableName = "JournalProfiles", previousNames = "OldProfiles")
    @SuppressWarnings("unused") // Scanned as metadata, not accessed directly.
    static class Profile extends CatalogObject {
        @TabularSection(name = "filters") List<TestInvoiceLine> filters;
    }

    @Test
    void catalogStorageNameAndRenameHistoryApplyToChildTable() {
        var registry = new MetadataRegistry();
        var descriptor = new MetadataScanner(new DefaultNamingStrategy()).scan(Profile.class);
        registry.registerCatalog(descriptor);
        var model = new SchemaModelBuilder(registry).build();
        var section = model.table("catalog_journal_profiles_filters");
        assertThat(section).isNotNull();
        var parent = section.column("_parent_id");
        assertThat(parent).isNotNull();
        assertThat(parent.references()).isEqualTo("catalog_journal_profiles(_id)");
        assertThat(section.previousNames()).containsExactly("catalog_old_profiles_filters");
        var generator = new SchemaGenerator(registry);
        assertThat(generator.generateIndexDDL()).anyMatch(sql -> sql.contains("ON catalog_journal_profiles_filters (_parent_id)"));
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        generator.execute(Jdbi.create(ds));
    }

    @Test
    void reconciliationCreatesNewCatalogSectionOnExistingDatabase() {
        var scanner = new MetadataScanner(new DefaultNamingStrategy());
        var full = scanner.scan(Profile.class);
        var registry = new MetadataRegistry();
        registry.registerCatalog(new CatalogDescriptor(full.logicalName(), full.displayTitle(), full.tableName(),
                full.javaClass(), full.codeLength(), full.hierarchical(), full.autoNumber(), full.codePrefix(),
                full.context(), full.attributes(), full.previousNames()));
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbi = Jdbi.create(ds);
        new SchemaGenerator(registry).execute(jdbi);
        registry = new MetadataRegistry();
        registry.registerCatalog(full);
        var desired = new SchemaModelBuilder(registry).build();
        var plan = jdbi.withHandle(h -> new SchemaDiffEngine(SqlDialect.H2)
                .diff(desired, null, DatabaseIntrospector.read(h)));
        assertThat(plan.changes()).anyMatch(change -> change.type() == SchemaChange.Type.CREATE_TABLE
                && change.table().equals("catalog_journal_profiles_filters"));
    }
}
