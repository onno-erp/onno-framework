package su.onno.ui;

import su.onno.access.AccessSubject;
import su.onno.annotations.Catalog;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * Folder navigation for the picker dialog (#411): a hierarchical catalog's list feed and search
 * accept {@code _parent = <uuid>} (one folder's children) and {@code _parent = null} (the roots),
 * composed with the field's own {@code refFilter}; a flat catalog ignores the clause.
 */
class CatalogParentFilterTest {

    @Catalog(name = "ParentGroups", hierarchical = true)
    static class Group extends CatalogObject {
    }

    @Catalog(name = "ParentFlat")
    static class Flat extends CatalogObject {
    }

    private Jdbi jdbi;
    private CatalogQueryService query;
    private CatalogDescriptor groups;
    private CatalogDescriptor flat;
    private final UUID tools = UUID.randomUUID();
    private final UUID paint = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        groups = scanner.scan(Group.class);
        flat = scanner.scan(Flat.class);
        registry.registerCatalog(groups);
        registry.registerCatalog(flat);
        new SchemaGenerator(registry).execute(jdbi);

        insert(groups, tools, "Tools", true, null);
        insert(groups, paint, "Paint", true, null);
        insert(groups, UUID.randomUUID(), "Hammer", false, tools);
        insert(groups, UUID.randomUUID(), "Saw", false, tools);
        insert(groups, UUID.randomUUID(), "Brush", false, paint);
        insert(flat, UUID.randomUUID(), "One", false, null);
        query = new CatalogQueryService(registry, jdbi);
    }

    @Test
    void aFolderListsOnlyItsChildrenAndTheRootsListOnlyTopLevelRecords() {
        assertThat(names(page("_parent = " + tools))).containsExactly("Hammer", "Saw");
        assertThat(names(page("_parent = null"))).containsExactly("Paint", "Tools");
    }

    @Test
    void theParentClauseComposesWithTheFieldsRefFilterAndTheSearch() {
        assertThat(names(page("_is_folder = true AND _parent = null"))).containsExactly("Paint", "Tools");
        assertThat(names(query.search(AccessSubject.system(), groups, "a", 50, "_parent = " + tools)))
                .containsExactly("Hammer", "Saw");
    }

    @Test
    void aFlatCatalogRejectsTheParentClause() {
        assertThatThrownBy(() ->
                query.keysetPage(
                        AccessSubject.system(),
                        flat,
                        null, null, null,
                        50,
                        "_description",
                        false,
                        null, null, null, null, null, null, null,
                        "_parent = null"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("_parent");
    }

    private List<Map<String, Object>> page(String filter) {
        return query.keysetPage(AccessSubject.system(), groups, null, null, null, 50, "_description", false,
                null, null, null, null, null, null, null, filter).rows();
    }

    private static List<Object> names(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("_description")).toList();
    }

    private void insert(CatalogDescriptor desc, UUID id, String name, boolean folder, UUID parent) {
        String parentCol = desc.hierarchical() ? ", _parent" : "";
        String parentVal = desc.hierarchical() ? ", :parent" : "";
        jdbi.useHandle(h -> {
            var u = h.createUpdate("INSERT INTO " + desc.tableName()
                            + " (_id, _code, _description, _deletion_mark, _is_folder, _version" + parentCol + ") "
                            + "VALUES (:id, :name, :name, false, :folder, 0" + parentVal + ")")
                    .bind("id", id).bind("name", name).bind("folder", folder);
            if (desc.hierarchical()) {
                u.bind("parent", parent);
            }
            u.execute();
        });
    }
}
