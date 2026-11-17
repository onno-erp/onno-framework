package su.onno.service;

import su.onno.access.AccessSubject;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The focused keyset window behind "open in list" ({@code ?focus=<id>}) and the backward window
 * ({@code ?before=<prevCursor>}). A record deep in a long list must arrive inside the first window
 * the client fetches, and paging on from that window in either direction must visit every other
 * live row exactly once — the same no-skip/no-duplicate guarantee as the forward feed.
 */
class KeysetFocusWindowTest {

    @AccessControl(readRoles = "CUSTOMER")
    @Catalog(name = "FwClients")
    static class Client extends CatalogObject {
        @Attribute(length = 40)
        private String tier;
    }

    @Document(name = "FwOrders")
    static class Order extends DocumentObject {
    }

    private static final List<String> NONE = List.of();
    private static final AccessSubject CUSTOMER = AccessSubject.user("c@fw.test", Set.of("CUSTOMER"));

    private Jdbi jdbi;
    private MetadataRegistry registry;
    private CatalogQueryService catalogs;
    private DocumentQueryService documents;
    private CatalogDescriptor catalog;
    private DocumentDescriptor document;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:focus" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Client.class));
        registry.registerDocument(scanner.scanDocument(Order.class));
        new SchemaGenerator(registry).execute(jdbi);

        // CUSTOMER sees only gold clients; everyone else is unscoped.
        RecordScopeCompiler scopes = new RecordScopeCompiler(new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Client.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("tier", "gold")))));
        AccessService access = new AccessService(registry);
        catalogs = new CatalogQueryService(registry, jdbi, scopes, access);
        documents = new DocumentQueryService(registry, jdbi, scopes, access);
        catalog = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Client.class).findFirst().orElseThrow();
        document = registry.allDocuments().stream()
                .filter(d -> d.javaClass() == Order.class).findFirst().orElseThrow();
    }

    @Test
    void aRecordDeepInALongListArrivesInsideTheWindowAndPagingOnSkipsNothing() {
        List<UUID> ids = insertClients(500, "gold");
        UUID target = ids.get(319); // C-0320 — far past the first window of 50

        KeysetPage window = catalogPage(AccessSubject.system(), null, null, target, 50);

        assertThat(window.focus()).isEqualTo(target);
        assertThat(window.rows()).hasSize(50);
        assertThat(codes(window)).contains("C-0320");
        // Balanced: up to half the limit of rows sit above the focused record.
        assertThat(codes(window).indexOf("C-0320")).isEqualTo(24);
        assertThat(window.hasPrevious()).isTrue();
        assertThat(window.hasMore()).isTrue();

        // Walking up via prevCursor and down via nextCursor reconstructs the whole list exactly once.
        assertThat(drainAround(AccessSubject.system(), window)).isEqualTo(allCodes(500));
    }

    @Test
    void aRecordNearTheEndFillsTheWindowFromAbove() {
        List<UUID> ids = insertClients(100, "gold");
        KeysetPage window = catalogPage(AccessSubject.system(), null, null, ids.get(98), 20);

        assertThat(window.rows()).hasSize(20);
        assertThat(codes(window)).endsWith("C-0099", "C-0100");
        assertThat(window.hasMore()).isFalse();
        assertThat(window.nextCursor()).isNull();
        assertThat(window.hasPrevious()).isTrue();
    }

    @Test
    void aRecordOnTheFirstWindowHasNothingAbove() {
        List<UUID> ids = insertClients(30, "gold");
        KeysetPage window = catalogPage(AccessSubject.system(), null, null, ids.get(2), 10);

        assertThat(codes(window)).startsWith("C-0001", "C-0002", "C-0003");
        assertThat(window.hasPrevious()).isFalse();
        assertThat(window.prevCursor()).isNull();
        assertThat(window.focus()).isEqualTo(ids.get(2));
    }

    @Test
    void anUnknownDeletedOrOutOfScopeRecordFallsBackToTheFirstWindow() {
        List<UUID> gold = insertClients(40, "gold");
        UUID silver = insert("C-9000", "Silver client", "silver");
        UUID deleted = gold.get(30);
        jdbi.useHandle(h -> h.createUpdate("UPDATE " + catalog.tableName()
                + " SET _deletion_mark = true WHERE _id = :id").bind("id", deleted).execute());
        List<String> firstWindow = codes(catalogPage(CUSTOMER, null, null, null, 10));

        for (UUID hidden : List.of(UUID.randomUUID(), deleted, silver)) {
            KeysetPage page = catalogPage(CUSTOMER, null, null, hidden, 10);
            assertThat(page.focus()).as("focus %s", hidden).isNull();
            assertThat(page.hasPrevious()).isFalse();
            assertThat(codes(page)).isEqualTo(firstWindow);
        }
        // The same silver record is in an unscoped subject's list.
        assertThat(catalogPage(AccessSubject.system(), null, null, silver, 10).focus()).isEqualTo(silver);
    }

    @Test
    void aRecordOutsideTheActiveSearchFallsBackToTheFirstWindow() {
        List<UUID> ids = insertClients(60, "gold");
        KeysetPage page = catalogs.keysetPage(AccessSubject.system(), catalog, null, null, ids.get(40), 10,
                "_code", false, "Client 001", NONE, NONE, NONE, NONE, NONE, NONE, null);
        assertThat(page.focus()).isNull();
    }

    @Test
    void aScopedSubjectsWindowAndCursorsStayWithinItsScope() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            ids.add(insert(String.format("C-%04d", i), "Client " + i, i % 2 == 0 ? "gold" : "silver"));
        }
        KeysetPage window = catalogPage(CUSTOMER, null, null, ids.get(79), 10); // C-0080, gold

        assertThat(window.focus()).isEqualTo(ids.get(79));
        List<String> walked = drainAround(CUSTOMER, window);
        assertThat(walked).hasSize(60).allSatisfy(code -> assertThat(
                Integer.parseInt(code.substring(2)) % 2).isZero());
    }

    @Test
    void tiebreaksOnIdWhenEveryRowSharesTheSortValue() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            ids.add(insert(String.format("C-%04d", i), "Same name", "gold"));
        }
        KeysetPage window = catalogs.keysetPage(AccessSubject.system(), catalog, null, null, ids.get(20), 8,
                "_description", false, null, NONE, NONE, NONE, NONE, NONE, NONE, null);

        assertThat(window.focus()).isEqualTo(ids.get(20));
        List<String> walked = drainAround(AccessSubject.system(), window, "_description", false);
        assertThat(walked).hasSize(40).doesNotHaveDuplicates();
    }

    @Test
    void theNullSafeSeekWalksBothWaysAcrossTheNullTail() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            ids.add(insert(String.format("C-%04d", i), "Client " + i, i % 3 == 0 ? null : "t" + (i % 5)));
        }
        String tier = tierColumn();
        for (boolean descending : List.of(false, true)) {
            for (UUID focus : List.of(ids.get(2), ids.get(10))) { // a null tier and a non-null one
                KeysetPage window = catalogs.keysetPage(AccessSubject.system(), catalog, null, null, focus, 6,
                        tier, descending, null, NONE, NONE, NONE, NONE, NONE, NONE, null);
                assertThat(window.focus()).isEqualTo(focus);
                List<String> walked = drainAround(AccessSubject.system(), window, tier, descending);
                List<String> forward = drainForward(tier, descending);
                assertThat(walked).as("desc=%s focus=%s", descending, focus).isEqualTo(forward);
            }
        }
    }

    @Test
    void aBackwardWindowContinuesForwardFromItsLastRow() {
        insertClients(30, "gold");
        KeysetPage second = catalogPage(AccessSubject.system(),
                catalogPage(AccessSubject.system(), null, null, null, 10).nextCursor(), null, null, 10);
        // A forward window starts at the top of nothing it doesn't show: no prevCursor.
        assertThat(second.prevCursor()).isNull();

        KeysetPage focused = catalogPage(AccessSubject.system(), null, null,
                firstId("C-0025"), 10);
        KeysetPage above = catalogPage(AccessSubject.system(), null, focused.prevCursor(), null, 10);
        assertThat(codes(above)).containsExactly("C-0011", "C-0012", "C-0013", "C-0014", "C-0015",
                "C-0016", "C-0017", "C-0018", "C-0019", "C-0020");
        assertThat(above.hasPrevious()).isTrue();
        // Its nextCursor resumes right after its last row — the focused window's first row.
        KeysetPage resumed = catalogPage(AccessSubject.system(), above.nextCursor(), null, null, 1);
        assertThat(codes(resumed)).containsExactly(codes(focused).get(0));
    }

    @Test
    void aDocumentWindowHonoursTheDefaultNewestFirstSortAndTheDateRange() {
        List<UUID> ids = new ArrayList<>();
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 9, 0);
        for (int i = 0; i < 300; i++) {
            ids.add(insertOrder(String.format("O-%04d", i + 1), start.plusHours(i)));
        }
        UUID target = ids.get(100); // O-0101; newest first puts it at position 199
        KeysetPage window = documents.keysetPage(AccessSubject.system(), document, null, null, target, 40,
                null, false, null, null, null, NONE, NONE, NONE, NONE, NONE, NONE, null);

        assertThat(window.focus()).isEqualTo(target);
        List<String> numbers = numbers(window);
        assertThat(numbers.get(19)).isEqualTo("O-0101");
        assertThat(numbers.get(18)).isEqualTo("O-0102"); // newer one sits above
        assertThat(window.hasPrevious()).isTrue();
        assertThat(window.hasMore()).isTrue();

        // Outside the requested date range → plain first window.
        KeysetPage ranged = documents.keysetPage(AccessSubject.system(), document, null, null, target, 40,
                null, false, null, "2026-01-10T00:00:00", null, NONE, NONE, NONE, NONE, NONE, NONE, null);
        assertThat(ranged.focus()).isNull();
        assertThat(numbers(ranged).get(0)).isEqualTo("O-0300");
    }

    @Test
    void subMillisecondDocumentDatesPageBothWaysWithoutDuplicatesOrGaps() {
        // A document's date defaults to LocalDateTime.now(): microsecond precision, and several
        // documents may share one millisecond. Cursors must keep that precision, or the seek
        // boundary drifts and the window above repeats its neighbour's first row.
        List<UUID> ids = new ArrayList<>();
        LocalDateTime start = LocalDateTime.of(2026, 3, 1, 12, 0, 0, 123_456_000);
        for (int i = 0; i < 60; i++) {
            ids.add(insertOrder(String.format("O-%04d", i + 1), start.plusNanos(i * 250_000L))); // 4 per ms
        }
        KeysetPage window = documents.keysetPage(AccessSubject.system(), document, null, null, ids.get(30), 10,
                null, false, null, null, null, NONE, NONE, NONE, NONE, NONE, NONE, null);
        assertThat(window.focus()).isEqualTo(ids.get(30));

        List<String> all = new ArrayList<>(numbers(window));
        KeysetPage page = window;
        while (page.hasPrevious()) {
            page = documents.keysetPage(AccessSubject.system(), document, null, page.prevCursor(), null, 10,
                    null, false, null, null, null, NONE, NONE, NONE, NONE, NONE, NONE, null);
            all.addAll(0, numbers(page));
        }
        page = window;
        while (page.hasMore()) {
            page = documents.keysetPage(AccessSubject.system(), document, page.nextCursor(), null, null, 10,
                    null, false, null, null, null, NONE, NONE, NONE, NONE, NONE, NONE, null);
            all.addAll(numbers(page));
        }
        List<String> newestFirst = new ArrayList<>();
        for (int i = 60; i >= 1; i--) newestFirst.add(String.format("O-%04d", i));
        assertThat(all).isEqualTo(newestFirst);
    }

    @Test
    void aCursorTakesPrecedenceOverFocus() {
        List<UUID> ids = insertClients(30, "gold");
        String next = catalogPage(AccessSubject.system(), null, null, null, 10).nextCursor();
        KeysetPage page = catalogPage(AccessSubject.system(), next, null, ids.get(25), 10);
        assertThat(page.focus()).isNull();
        assertThat(codes(page).get(0)).isEqualTo("C-0011");
    }

    // --- helpers ---------------------------------------------------------------

    private KeysetPage catalogPage(AccessSubject subject, String cursor, String before, UUID focus, int limit) {
        return catalogs.keysetPage(subject, catalog, cursor, before, focus, limit, "_code", false, null,
                NONE, NONE, NONE, NONE, NONE, NONE, null);
    }

    private List<String> drainAround(AccessSubject subject, KeysetPage window) {
        return drainAround(subject, window, "_code", false);
    }

    /** Page up via prevCursor to the top and down via nextCursor to the end, in list order. */
    private List<String> drainAround(AccessSubject subject, KeysetPage window, String sort, boolean descending) {
        List<String> above = new ArrayList<>();
        KeysetPage page = window;
        for (int guard = 0; page.hasPrevious(); guard++) {
            assertThat(guard).as("backward paging did not terminate").isLessThan(1000);
            assertThat(page.prevCursor()).isNotNull();
            page = catalogs.keysetPage(subject, catalog, null, page.prevCursor(), null, window.rows().size(),
                    sort, descending, null, NONE, NONE, NONE, NONE, NONE, NONE, null);
            above.addAll(0, codes(page));
        }
        assertThat(page.prevCursor()).isNull();

        List<String> all = new ArrayList<>(above);
        all.addAll(codes(window));
        page = window;
        for (int guard = 0; page.hasMore(); guard++) {
            assertThat(guard).as("forward paging did not terminate").isLessThan(1000);
            page = catalogs.keysetPage(subject, catalog, page.nextCursor(), null, null, window.rows().size(),
                    sort, descending, null, NONE, NONE, NONE, NONE, NONE, NONE, null);
            all.addAll(codes(page));
        }
        assertThat(all).doesNotHaveDuplicates();
        return all;
    }

    private List<String> drainForward(String sort, boolean descending) {
        List<String> all = new ArrayList<>();
        String cursor = null;
        do {
            KeysetPage page = catalogs.keysetPage(AccessSubject.system(), catalog, cursor, 7, sort, descending,
                    null, NONE, NONE, NONE, NONE, NONE, NONE, null);
            all.addAll(codes(page));
            cursor = page.nextCursor();
        } while (cursor != null);
        return all;
    }

    private static List<String> allCodes(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(String.format("C-%04d", i));
        return out;
    }

    private static List<String> codes(KeysetPage page) {
        return page.rows().stream().map(r -> (String) r.get("_code")).toList();
    }

    private static List<String> numbers(KeysetPage page) {
        return page.rows().stream().map(r -> (String) r.get("_number")).toList();
    }

    private String tierColumn() {
        return catalog.attributes().stream()
                .filter(a -> a.fieldName().equals("tier"))
                .map(a -> a.columnName()).findFirst().orElseThrow();
    }

    private UUID firstId(String code) {
        return jdbi.withHandle(h -> h.createQuery("SELECT _id FROM " + catalog.tableName() + " WHERE _code = :c")
                .bind("c", code).mapTo(UUID.class).one());
    }

    private List<UUID> insertClients(int n, String tier) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ids.add(insert(String.format("C-%04d", i), "Client " + String.format("%04d", i), tier));
        }
        return ids;
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

    private UUID insertOrder(String number, LocalDateTime date) {
        UUID id = UUID.randomUUID();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + document.tableName()
                        + " (_id, _number, _date) VALUES (:id, :number, :date)")
                .bind("id", id).bind("number", number).bind("date", Timestamp.valueOf(date)).execute());
        return id;
    }
}
