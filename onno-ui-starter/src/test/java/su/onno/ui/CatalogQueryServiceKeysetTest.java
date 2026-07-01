package su.onno.ui;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogQueryService#keysetPage} — the constant-time default for the list grid. These guard
 * the properties that make keyset paging correct where offset isn't: walking the whole list via
 * {@code nextCursor} visits every live row <em>exactly once</em> in sort order (no skips, no
 * duplicates) even when many rows share a sort value, {@code hasMore}/{@code nextCursor} agree, and a
 * cursor minted for a different sort is ignored rather than returning a wrong window.
 */
class CatalogQueryServiceKeysetTest {

    @Catalog(name = "KsClients")
    static class Client extends CatalogObject {
        @Attribute(length = 120)
        private String tier;
    }

    private static final List<String> NONE = List.of();

    private Jdbi jdbi;
    private CatalogQueryService service;
    private CatalogDescriptor catalog;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:keyset" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Client.class));
        new SchemaGenerator(registry).execute(jdbi);

        service = new CatalogQueryService(registry, jdbi);
        catalog = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Client.class).findFirst().orElseThrow();
    }

    @Test
    void walksEveryLiveRowExactlyOnceAscending() {
        for (int i = 1; i <= 7; i++) {
            insert(String.format("C-%02d", i), "Client " + i, "std");
        }
        List<String> codes = drainByCode(false, 2);
        assertThat(codes).containsExactly("C-01", "C-02", "C-03", "C-04", "C-05", "C-06", "C-07");
    }

    @Test
    void walksEveryLiveRowExactlyOnceDescending() {
        for (int i = 1; i <= 5; i++) {
            insert(String.format("C-%02d", i), "Client " + i, "std");
        }
        // The default list direction (the controller's descending(dir) default).
        assertThat(drainByCode(true, 2)).containsExactly("C-05", "C-04", "C-03", "C-02", "C-01");
    }

    @Test
    void tiebreaksOnIdWhenTheSortValueRepeats() {
        // Every row shares the same sort value: only the _id tiebreaker keeps the order total, so a
        // naive (value-only) seek would loop or skip. All must still appear exactly once.
        for (int i = 1; i <= 6; i++) {
            insert(String.format("C-%02d", i), "Same Name", "std");
        }
        List<String> codes = drainBy("_description", false, 2);
        assertThat(codes).hasSize(6).doesNotHaveDuplicates()
                .containsExactlyInAnyOrder("C-01", "C-02", "C-03", "C-04", "C-05", "C-06");
    }

    @Test
    void nullSafeSeekWalksNullableColumnWithoutSkippingTheNullTail() {
        // 'tier' is an optional attribute → the NULL-safe seek shape. Mix present and null values.
        insert("C-01", "A", "gold");
        insert("C-02", "B", null);
        insert("C-03", "C", "silver");
        insert("C-04", "D", null);
        insert("C-05", "E", "gold");
        List<String> codes = drainBy(tierColumn(), false, 2);
        assertThat(codes).hasSize(5).doesNotHaveDuplicates()
                .containsExactlyInAnyOrder("C-01", "C-02", "C-03", "C-04", "C-05");
    }

    @Test
    void excludesDeletionMarkedRows() {
        insert("C-01", "Live one", "std");
        UUID goneId = insert("C-02", "Gone", "std");
        insert("C-03", "Live two", "std");
        jdbi.useHandle(h -> h.createUpdate("UPDATE " + catalog.tableName()
                + " SET _deletion_mark = true WHERE _id = :id").bind("id", goneId).execute());

        assertThat(drainByCode(false, 2)).containsExactly("C-01", "C-03");
    }

    @Test
    void lastPageReportsNoMoreAndNoCursor() {
        insert("C-01", "Only", "std");
        KeysetPage page = page(null, 50, "_code", false);
        assertThat(page.rows()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void aStaleCursorFromADifferentSortRestartsFromTheFirstWindow() {
        for (int i = 1; i <= 4; i++) {
            insert(String.format("C-%02d", i), "Client " + i, "std");
        }
        // A cursor minted while sorting by _code, then replayed against a _description sort, must be
        // ignored (treated as the first window) rather than seeking to a meaningless position.
        String codeCursor = page(null, 2, "_code", false).nextCursor();
        assertThat(codeCursor).isNotNull();

        KeysetPage restarted = page(codeCursor, 2, "_description", false);
        // First window of the _description sort — i.e. as if no cursor were supplied.
        KeysetPage fresh = page(null, 2, "_description", false);
        assertThat(codes(restarted)).isEqualTo(codes(fresh));
    }

    // --- helpers ---------------------------------------------------------------

    private KeysetPage page(String cursor, int limit, String sort, boolean descending) {
        return service.keysetPage(catalog, cursor, limit, sort, descending, null,
                NONE, NONE, NONE, NONE, NONE, NONE, null);
    }

    private List<String> drainByCode(boolean descending, int limit) {
        return drainBy("_code", descending, limit);
    }

    /** Walk the whole list one window at a time, collecting codes; asserts the hasMore/cursor contract. */
    private List<String> drainBy(String sort, boolean descending, int limit) {
        List<String> codes = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 1000; guard++) {
            KeysetPage p = page(cursor, limit, sort, descending);
            codes.addAll(codes(p));
            if (!p.hasMore()) {
                assertThat(p.nextCursor()).isNull();
                return codes;
            }
            assertThat(p.nextCursor()).isNotNull();
            cursor = p.nextCursor();
        }
        throw new AssertionError("keyset paging did not terminate — likely a seek that skips or loops");
    }

    private static List<String> codes(KeysetPage page) {
        return page.rows().stream().map(r -> (String) r.get("_code")).toList();
    }

    private String tierColumn() {
        return catalog.attributes().stream()
                .filter(a -> a.fieldName().equals("tier"))
                .map(a -> a.columnName()).findFirst().orElseThrow();
    }

    private UUID insert(String code, String description, String tier) {
        UUID id = UUID.randomUUID();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + catalog.tableName()
                        + " (_id, _code, _description, " + tierColumn() + ")"
                        + " VALUES (:id, :code, :description, :tier)")
                .bind("id", id).bind("code", code).bind("description", description)
                .bind("tier", tier).execute());
        return id;
    }
}
