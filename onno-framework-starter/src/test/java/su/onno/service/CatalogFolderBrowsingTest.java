package su.onno.service;

import su.onno.access.AccessSubject;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Folder browsing for the picker dialog (#411) over the existing hierarchy queries:
 * {@code children(..., foldersOnly)} lists just the folders, each flagged with whether it holds a
 * readable sub-folder, and {@code paths(...)} returns the tree pruned to the paths to the given
 * records in one call. Both apply the record scope, so neither reveals a hidden record.
 *
 * <pre>
 * Office (folder)                 public
 * ├── Pens (folder)               public
 * │   └── Blue pen                public
 * ├── Vault (folder)              secret   <- hidden from CUSTOMER
 * │   └── Gold pen                public   <- CUSTOMER sees it as a root
 * └── Paper                       public
 * Archive (folder, no children)   public
 * </pre>
 */
class CatalogFolderBrowsingTest {

    @AccessControl(readRoles = {"CLERK", "CUSTOMER"})
    @Catalog(name = "BrowseItems", hierarchical = true)
    static class Item extends CatalogObject {
        @Attribute(length = 20)
        private String tier;
    }

    @Catalog(name = "BrowseFlat")
    static class Flat extends CatalogObject {
    }

    private static final AccessSubject CLERK = AccessSubject.user("clerk@fw.test", Set.of("CLERK"));
    private static final AccessSubject CUSTOMER = AccessSubject.user("c@fw.test", Set.of("CUSTOMER"));

    private Jdbi jdbi;
    private CatalogQueryService query;
    private CatalogDescriptor items;
    private CatalogDescriptor flat;
    private UUID office, pens, bluePen, vault, goldPen, paper, archive;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:browse" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(scanner.scan(Item.class));
        registry.registerCatalog(scanner.scan(Flat.class));
        new SchemaGenerator(registry).execute(jdbi);
        items = registry.allCatalogs().stream().filter(c -> c.javaClass() == Item.class).findFirst().orElseThrow();
        flat = registry.allCatalogs().stream().filter(c -> c.javaClass() == Flat.class).findFirst().orElseThrow();

        office = insert("Office", true, null, "public");
        pens = insert("Pens", true, office, "public");
        bluePen = insert("Blue pen", false, pens, "public");
        vault = insert("Vault", true, office, "secret");
        goldPen = insert("Gold pen", false, vault, "public");
        paper = insert("Paper", false, office, "public");
        archive = insert("Archive", true, null, "public");

        RecordScopeCompiler scopes = new RecordScopeCompiler(new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Item.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("tier", "public")))));
        query = new CatalogQueryService(registry, jdbi, scopes, new AccessService(registry));
    }

    @Test
    void foldersOnlyListsTheFoldersOfALevelWithWhetherEachHasSubFolders() {
        List<Map<String, Object>> roots = query.children(CLERK, items, null, true);
        assertThat(names(roots)).containsExactly("Archive", "Office");
        assertThat(flag(roots, "Office")).isTrue();
        assertThat(flag(roots, "Archive")).isFalse();

        List<Map<String, Object>> inOffice = query.children(CLERK, items, office, true);
        assertThat(names(inOffice)).containsExactly("Pens", "Vault");
        assertThat(flag(inOffice, "Pens")).isFalse(); // only an item inside
    }

    @Test
    void theSubFolderFlagCountsOnlyFoldersTheSubjectMayRead() {
        // Office holds Pens (readable) and Vault (hidden): still has a readable sub-folder.
        assertThat(flag(query.children(CUSTOMER, items, null, true), "Office")).isTrue();
        assertThat(names(query.children(CUSTOMER, items, office, true))).containsExactly("Pens");

        markSecret(pens);
        // Now every sub-folder of Office is hidden: no arrow, nothing to reveal.
        assertThat(flag(query.children(CUSTOMER, items, null, true), "Office")).isFalse();
    }

    @Test
    void withoutFoldersOnlyChildrenAnswersAsBefore() {
        List<Map<String, Object>> inOffice = query.children(CLERK, items, office);
        assertThat(names(inOffice)).containsExactly("Pens", "Vault", "Paper");
        assertThat(inOffice).allSatisfy(row -> assertThat(row).doesNotContainKey("_has_child_folders"));
    }

    @Test
    void pathsReturnTheTreePrunedToTheRequestedRecordsInOneCall() {
        List<Map<String, Object>> tree = query.paths(CLERK, items, List.of(bluePen, paper));

        assertThat(names(tree)).containsExactly("Office");
        List<Map<String, Object>> office = children(tree.get(0));
        assertThat(names(office)).containsExactlyInAnyOrder("Pens", "Paper");
        Map<String, Object> pensNode = office.stream().filter(r -> "Pens".equals(r.get("_description"))).findFirst().orElseThrow();
        assertThat(names(children(pensNode))).containsExactly("Blue pen");
    }

    @Test
    void aPathStopsAtTheFirstAncestorTheSubjectCannotRead() {
        List<Map<String, Object>> tree = query.paths(CUSTOMER, items, List.of(goldPen));

        // Vault is hidden: Gold pen is presented as a root with its parent withheld.
        assertThat(names(tree)).containsExactly("Gold pen");
        assertThat(tree.get(0)).containsEntry("_parent", null).containsEntry("_parent_restricted", true);
        assertThat(tree.toString()).doesNotContain(vault.toString()).doesNotContain("Vault");
    }

    @Test
    void unknownDeletedAndUnreadableIdsAreLeftOut() {
        UUID gone = insert("Gone", false, office, "public");
        jdbi.useHandle(h -> h.execute("UPDATE " + items.tableName() + " SET _deletion_mark = true WHERE _id = ?", gone));

        assertThat(query.paths(CUSTOMER, items, List.of(vault, gone, UUID.randomUUID()))).isEmpty();
        assertThat(query.paths(CLERK, items, List.of())).isEmpty();
    }

    @Test
    void aFlatCatalogHasNoFolderBrowsing() {
        assertThatThrownBy(() -> query.children(CLERK, flat, null, true)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> query.paths(CLERK, flat, List.of(UUID.randomUUID())))
                .isInstanceOf(ServiceException.class);
    }

    private UUID insert(String name, boolean folder, UUID parent, String tier) {
        UUID id = UUID.randomUUID();
        String tierColumn = column("tier");
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + items.tableName()
                        + " (_id, _code, _description, _deletion_mark, _is_folder, _version, _parent, " + tierColumn + ")"
                        + " VALUES (:id, :name, :name, false, :folder, 0, :parent, :tier)")
                .bind("id", id).bind("name", name).bind("folder", folder).bind("parent", parent)
                .bind("tier", tier).execute());
        return id;
    }

    private void markSecret(UUID id) {
        jdbi.useHandle(h -> h.execute("UPDATE " + items.tableName() + " SET " + column("tier") + " = 'secret' WHERE _id = ?", id));
    }

    private String column(String field) {
        return items.attributes().stream().filter(a -> a.fieldName().equals(field)).findFirst().orElseThrow().columnName();
    }

    private static List<Object> names(List<Map<String, Object>> rows) {
        List<Object> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get("_description")));
        return out;
    }

    private static Boolean flag(List<Map<String, Object>> rows, String name) {
        return (Boolean) rows.stream().filter(r -> name.equals(r.get("_description"))).findFirst().orElseThrow()
                .get("_has_child_folders");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("children");
    }
}
