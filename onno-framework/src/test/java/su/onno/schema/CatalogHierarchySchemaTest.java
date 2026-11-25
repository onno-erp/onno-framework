package su.onno.schema;

import su.onno.annotations.Catalog;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.HierarchicalCatalogObject;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Exercises the real reconciler on populated databases, including skipped destructive changes. */
class CatalogHierarchySchemaTest {
    @Catalog(name = "Switchable") static class Flat extends CatalogObject { }
    @Catalog(name = "Switchable", hierarchical = true)
    static class Tree extends HierarchicalCatalogObject<Tree> { }
    private static final String TABLE = "catalog_switchable";

    protected Jdbi database() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return Jdbi.create(source);
    }

    private MetadataRegistry registry(Class<?> type) {
        var registry = new MetadataRegistry();
        registry.registerCatalog(new MetadataScanner(new DefaultNamingStrategy()).scan(type));
        return registry;
    }

    private MigrationPlan upgrade(Jdbi db, Class<?> type, boolean destructive) {
        return new SchemaUpgrader(registry(type), SchemaMode.APPLY, destructive, SearchIndexMode.OFF).run(db);
    }

    private boolean hasColumn(Jdbi db, String column) {
        return db.withHandle(h -> DatabaseIntrospector.read(h).columns(TABLE).contains(column.toUpperCase()));
    }

    private UUID insert(Jdbi db, String code) {
        UUID id = UUID.randomUUID();
        db.useHandle(h -> h.createUpdate("INSERT INTO " + TABLE + " (_id, _code, _description) VALUES (:id, :code, 'Preserved')")
                .bind("id", id).bind("code", code).execute());
        return id;
    }

    @Test void freshFlatSchemasHaveNoHierarchyColumnsOrParentIndex() {
        Jdbi db = database();
        upgrade(db, Flat.class, false);
        assertThat(hasColumn(db, "_parent")).isFalse();
        assertThat(hasColumn(db, "_is_folder")).isFalse();
        assertThat(new SchemaGenerator(registry(Flat.class)).generateIndexDDL())
                .noneMatch(sql -> sql.contains(TABLE) && sql.contains("_parent"));
        assertThat(upgrade(db, Flat.class, false).isEmpty()).isTrue();
    }

    @Test void existingFlatRowsCanBecomeRootsWithoutLosingAnyValues() {
        Jdbi db = database();
        upgrade(db, Flat.class, false);
        UUID id = insert(db, "KEEP");
        var before = db.withHandle(h -> h.createQuery("SELECT * FROM " + TABLE).mapToMap().one());
        MigrationPlan plan = upgrade(db, Tree.class, false);
        assertThat(plan.changes()).filteredOn(c -> c.table().equals(TABLE))
                .extracting(SchemaChange::type).containsOnly(SchemaChange.Type.ADD_COLUMN).hasSize(2);
        var row = db.withHandle(h -> h.createQuery("SELECT * FROM " + TABLE + " WHERE _id=:id").bind("id", id).mapToMap().one());
        assertThat(row).containsAllEntriesOf(before).containsEntry("_is_folder", false).containsEntry("_parent", null);
        assertThat(upgrade(db, Tree.class, false).isEmpty()).isTrue();
    }

    @Test void typedParentUpgradeKeepsExistingUuidValuesAndRows() {
        Jdbi db = database();
        upgrade(db, Tree.class, false);
        UUID parent = insert(db, "PARENT");
        UUID child = insert(db, "CHILD");
        db.useHandle(h -> {
            h.createUpdate("UPDATE " + TABLE + " SET _is_folder=true WHERE _id=:id").bind("id", parent).execute();
            h.createUpdate("UPDATE " + TABLE + " SET _parent=:parent WHERE _id=:child")
                    .bind("parent", parent).bind("child", child).execute();
        });
        var before = db.withHandle(h -> h.createQuery("SELECT * FROM " + TABLE + " ORDER BY _code").mapToMap().list());
        assertThat(upgrade(db, Tree.class, false).isEmpty()).isTrue();
        var after = db.withHandle(h -> h.createQuery("SELECT * FROM " + TABLE + " ORDER BY _code").mapToMap().list());
        assertThat(after).isEqualTo(before);
    }

    @Test void disablingHierarchyPreservesColumnsAndDataUntilDestructiveChangesAreExplicitlyAllowed() {
        Jdbi db = database();
        upgrade(db, Tree.class, false);
        UUID id = insert(db, "KEEP");
        db.useHandle(h -> h.execute("UPDATE " + TABLE + " SET _is_folder=true"));
        var before = db.withHandle(h -> h.createQuery("SELECT * FROM " + TABLE).mapToMap().one());
        MigrationPlan plan = upgrade(db, Flat.class, false);
        assertThat(plan.changes()).filteredOn(c -> c.table().equals(TABLE))
                .allMatch(SchemaChange::destructive).hasSize(2);
        assertThat(hasColumn(db, "_parent")).isTrue();
        assertThat(hasColumn(db, "_is_folder")).isTrue();
        var afterSafeUpgrade = db.withHandle(h -> h.createQuery("SELECT * FROM " + TABLE).mapToMap().one());
        assertThat(afterSafeUpgrade).isEqualTo(before);
        assertThat(upgrade(db, Flat.class, false).changes()).filteredOn(c -> c.table().equals(TABLE)).hasSize(2);
        upgrade(db, Flat.class, true);
        assertThat(hasColumn(db, "_parent")).isFalse();
        assertThat(hasColumn(db, "_is_folder")).isFalse();
        UUID retainedId = db.withHandle(h -> h.createQuery("SELECT _id FROM " + TABLE).mapTo(UUID.class).one());
        String description = db.withHandle(h -> h.createQuery("SELECT _description FROM " + TABLE).mapTo(String.class).one());
        assertThat(retainedId).isEqualTo(id);
        assertThat(description).isEqualTo("Preserved");
    }

    @Test void legacyDatabaseWithoutSnapshotIsNotDestructivelyCleanedOnFirstBoot() {
        Jdbi db = database();
        new SchemaGenerator(registry(Tree.class)).execute(db);
        UUID id = insert(db, "LEGACY");
        upgrade(db, Flat.class, false);
        assertThat(hasColumn(db, "_parent")).isTrue();
        assertThat(hasColumn(db, "_is_folder")).isTrue();
        UUID retainedId = db.withHandle(h -> h.createQuery("SELECT _id FROM " + TABLE).mapTo(UUID.class).one());
        assertThat(retainedId).isEqualTo(id);
    }
}
