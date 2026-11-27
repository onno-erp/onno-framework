package su.onno.service;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.onno.access.AccessSubject;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordScope;
import su.onno.access.RecordPolicies;
import su.onno.annotations.Attribute;
import su.onno.annotations.RefTargets;
import su.onno.types.PolyRef;
import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;
import su.onno.metadata.*;
import su.onno.model.CatalogObject;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.model.DocumentObject;
import su.onno.schema.SchemaGenerator;
import su.onno.types.Ref;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ListSortQueryTest {
    @AccessControl(readRoles = "VIEWER")
    @Catalog(name = "SortClients")
    static class Client extends CatalogObject {
        @Attribute private String tier;
    }
    @Enumeration(name = "SortStatus")
    enum Status { @EnumLabel("Zulu") FIRST, @EnumLabel("Alpha") SECOND }
    @AccessControl(readRoles = "VIEWER")
    @Document(name = "SortOrders")
    static class Order extends DocumentObject {
        @Attribute private Ref<Client> customer;
        @Attribute private Status status;
        @Attribute private Integer price;
        @Attribute private Instant instantDate;
        @Attribute @RefTargets({Client.class, TargetDocument.class}) private PolyRef target;
    }
    @AccumulationRegister(name = "SortStock", type = AccumulationType.BALANCE)
    static class Stock extends AccumulationRecord {
        @Dimension private Ref<Client> customer;
        @Dimension private String location;
        @Resource private Integer quantity;
    }
    @Document(name = "SortTargetDocuments")
    @AccessControl(readRoles = "VIEWER")
    static class TargetDocument extends DocumentObject {
    }

    private Jdbi jdbi;
    private MetadataRegistry registry;
    private CatalogDescriptor clients;
    private DocumentDescriptor orders;
    private CatalogQueryService catalogs;
    private DocumentQueryService documents;
    private static final List<String> NONE = List.of();

    @BeforeEach void setup() {
        jdbi = createJdbi();
        registry = new MetadataRegistry();
        var scanner = new MetadataScanner(new DefaultNamingStrategy());
        clients = scanner.scan(Client.class); orders = scanner.scanDocument(Order.class);
        registry.registerCatalog(clients); registry.registerDocument(orders);
        registry.registerDocument(scanner.scanDocument(TargetDocument.class));
        registry.registerEnumeration(scanner.scanEnumeration(Status.class));
        registry.registerAccumulation(scanner.scanRegister(Stock.class));
        new SchemaGenerator(registry).execute(jdbi);
        catalogs = new CatalogQueryService(registry, jdbi);
        documents = new DocumentQueryService(registry, jdbi);
    }

    Jdbi createJdbi() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:multisort" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return Jdbi.create(ds);
    }

    UUID client(String code, String name, String tier) {
        UUID id = UUID.randomUUID();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + clients.tableName()
                + " (_id, _code, _description, tier) VALUES (:id, :code, :name, :tier)")
                .bind("id", id).bind("code", code).bind("name", name).bind("tier", tier).execute());
        return id;
    }
    void order(String number, UUID customer, Status status, Integer price) {
        UUID state = registry.allEnumerations().iterator().next().values().stream()
                .filter(v -> v.name().equals(status.name())).findFirst().orElseThrow().id();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + orders.tableName()
                + " (_id, _number, _date, customer, status, price) VALUES (:id, :n, CURRENT_TIMESTAMP, :c, :s, :p)")
                .bind("id", UUID.randomUUID()).bind("n", number).bind("c", customer).bind("s", state).bind("p", price).execute());
    }
    KeysetPage clients(String cursor, String before, UUID focus, int limit, String sort) {
        return catalogs.keysetPage(AccessSubject.system(), clients, cursor, before, focus, limit,
                sort, false, null, NONE, NONE, NONE, NONE, NONE, NONE, null);
    }
    List<Map<String, Object>> orderRows(DocumentQueryService service, AccessSubject subject, String sort) {
        List<Map<String, Object>> result = new ArrayList<>(); String cursor = null;
        do {
            var page = service.keysetPage(subject, orders, cursor, null, null, 1, sort, false, null,
                    null, null, NONE, NONE, NONE, NONE, NONE, NONE, null);
            result.addAll(page.rows()); cursor = page.nextCursor();
            if (result.size() > 20) throw new AssertionError("Cursor loop");
        } while (cursor != null);
        return result;
    }
    List<String> codes(KeysetPage page) { return page.rows().stream().map(row -> row.get("_code").toString()).toList(); }
    List<String> numbers(List<Map<String, Object>> rows) { return rows.stream().map(row -> row.get("_number").toString()).toList(); }

    @Test void instantDateOnlySortHonorsSecondaryPrice() {
        UUID customer = client("C1", "Alice", null);
        order("O1", customer, Status.FIRST, 129);
        order("O2", customer, Status.FIRST, 58);
        order("O3", customer, Status.FIRST, 40);
        for (int i = 1; i <= 3; i++) {
            String number = "O" + i;
            var value = java.time.OffsetDateTime.parse("2026-08-04T1" + i + ":00:00Z");
            jdbi.useHandle(h -> h.createUpdate("UPDATE " + orders.tableName() + " SET instant_date = :v WHERE _number = :n")
                    .bind("v", value).bind("n", number).execute());
        }
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "instant_date:day,price")))
                .containsExactly("O3", "O2", "O1");
    }

    @Test void polymorphicReferencesSortByVisibleLabel() {
        UUID first = client("C1", "Zulu", null), second = client("C2", "Alpha", null);
        UUID low = new UUID(0, 1), high = new UUID(0, 2);
        jdbi.useHandle(h -> {
            h.createUpdate("UPDATE " + clients.tableName() + " SET _id = :v WHERE _id = :old")
                    .bind("v", low).bind("old", first).execute();
            h.createUpdate("UPDATE " + clients.tableName() + " SET _id = :v WHERE _id = :old")
                    .bind("v", high).bind("old", second).execute();
        });
        order("O1", low, Status.FIRST, 1);
        order("O2", high, Status.FIRST, 2);
        jdbi.useHandle(h -> {
            h.createUpdate("UPDATE " + orders.tableName() + " SET target = :v WHERE _number = 'O1'")
                    .bind("v", PolyRef.of(Client.class, low).externalForm()).execute();
            h.createUpdate("UPDATE " + orders.tableName() + " SET target = :v WHERE _number = 'O2'")
                    .bind("v", PolyRef.of(Client.class, high).externalForm()).execute();
        });
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "target,price")))
                .containsExactly("O2", "O1");
    }

    @Test void instantDayUsesTheDisplayZoneAndBindsItsCursorToThatZone() {
        UUID customer = client("C1", "Alice", null);
        order("O1", customer, Status.FIRST, 129);
        order("O2", customer, Status.FIRST, 58);
        order("O3", customer, Status.FIRST, 40);
        String[] timestamps = {"2026-08-04T22:30:00Z", "2026-08-05T01:00:00Z", "2026-08-05T21:30:00Z"};
        for (int i = 0; i < timestamps.length; i++) {
            String number = "O" + (i + 1);
            var instant = java.time.OffsetDateTime.parse(timestamps[i]);
            jdbi.useHandle(h -> h.createUpdate("UPDATE " + orders.tableName() + " SET instant_date = :v WHERE _number = :n")
                    .bind("v", instant).bind("n", number).execute());
        }
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "instant_date:day@UTC,price")))
                .containsExactly("O1", "O3", "O2");
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "instant_date:day@Europe/Moscow,price")))
                .containsExactly("O2", "O1", "O3");
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "instant_date:day@+03:00,price")))
                .containsExactly("O2", "O1", "O3");
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "instant_date:day@-03:30,price")))
                .containsExactly("O2", "O1", "O3");
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "-instant_date:day@Europe/Moscow,price")))
                .containsExactly("O3", "O2", "O1");
        var first = documents.keysetPage(AccessSubject.system(), orders, null, null, null, 1,
                "instant_date:day@UTC,price", false, null, null, null, NONE, NONE, NONE, NONE, NONE, NONE, null);
        assertThatThrownBy(() -> documents.keysetPage(AccessSubject.system(), orders, first.nextCursor(),
                null, null, 1, "instant_date:day@Europe/Moscow,price", false, null, null, null,
                NONE, NONE, NONE, NONE, NONE, NONE, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> orderRows(documents, AccessSubject.system(), "instant_date:day@No/SuchZone,price"))
                .isInstanceOf(ServiceException.class);
    }

    @Test void polymorphicReferencesMixCatalogAndDocumentLabelsAcrossPages() {
        UUID z = client("C1", "Zulu", null), a = client("C2", "Alpha", null);
        UUID document = UUID.randomUUID();
        var target = registry.allDocuments().stream().filter(d -> d.javaClass() == TargetDocument.class).findFirst().orElseThrow();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + target.tableName()
                + " (_id, _number, _date) VALUES (:id, 'Beta', CURRENT_TIMESTAMP)").bind("id", document).execute());
        order("O1", z, Status.FIRST, 10);
        order("O2", a, Status.FIRST, 20);
        order("O3", a, Status.FIRST, 30);
        Object[][] values = {{"O1", PolyRef.of(Client.class, z)}, {"O2", PolyRef.of(Client.class, a)},
                {"O3", PolyRef.of(TargetDocument.class, document)}};
        for (Object[] value : values) {
            jdbi.useHandle(h -> h.createUpdate("UPDATE " + orders.tableName() + " SET target = :v WHERE _number = :n")
                    .bind("v", value[1].toString()).bind("n", value[0]).execute());
        }
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "target,price")))
                .containsExactly("O2", "O3", "O1");
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "-target,price")))
                .containsExactly("O1", "O3", "O2");
    }

    @Test void polymorphicHiddenCatalogAndDeniedDocumentShareAMaskedSortKey() {
        UUID a = client("C1", "Alpha", "hidden"), z = client("C2", "Zulu", "hidden");
        UUID document = UUID.randomUUID();
        var target = registry.allDocuments().stream().filter(d -> d.javaClass() == TargetDocument.class).findFirst().orElseThrow();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + target.tableName()
                + " (_id, _number, _date) VALUES (:id, 'Beta', CURRENT_TIMESTAMP)").bind("id", document).execute());
        order("O1", a, Status.FIRST, 10);
        order("O2", a, Status.FIRST, 20);
        order("O3", z, Status.FIRST, 30);
        Object[][] values = {{"O1", PolyRef.of(Client.class, a)}, {"O2", PolyRef.of(TargetDocument.class, document)},
                {"O3", PolyRef.of(Client.class, z)}};
        for (Object[] value : values) {
            jdbi.useHandle(h -> h.createUpdate("UPDATE " + orders.tableName() + " SET target = :v WHERE _number = :n")
                    .bind("v", value[1].toString()).bind("n", value[0]).execute());
        }
        var scope = new RecordScopeCompiler(new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Client.class).appliesTo("VIEWER").read(RecordScope.eq("tier", "visible")))));
        var policy = new EntityAccessPolicy() {
            @Override public boolean allows(Set<String> roles, String type, String name, boolean write) {
                return !type.equals("document") || !name.equals(target.logicalName().toLowerCase(Locale.ROOT));
            }
        };
        var access = new AccessService(registry, () -> List.of(policy));
        var service = new DocumentQueryService(registry, jdbi, scope, access);
        var viewer = AccessSubject.user("viewer", Set.of("VIEWER"));
        assertThat(scope.isScoped(Client.class, viewer, su.onno.access.AccessMode.READ)).isTrue();
        assertThat(access.canRead(viewer, "document", target.logicalName())).isFalse();
        var rows = orderRows(service, viewer, "target,-price");
        assertThat(numbers(rows)).containsExactly("O3", "O2", "O1");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("target_restricted")).isEqualTo(true);
            assertThat(row.get("target")).isNull();
        });
    }

    @Test void defaultCatalogOrderUsesNamesIgnoringCase() {
        client("C1", "zebra", null); client("C2", "Alpha", null); client("C3", "beta", null);
        assertThat(codes(clients(null, null, null, 10, null))).containsExactly("C2", "C3", "C1");
    }

    @Test void multipleKeysWithNullsAndMixedDirectionsPageBothWaysAndFocus() {
        UUID focus = client("C1", "beta", "a");
        client("C2", "Alpha", "a"); client("C3", "zebra", "b");
        client("C4", "omega", null); client("C5", "Alpha", null);
        String sort = "tier,-description";
        var first = clients(null, null, null, 2, sort);
        var second = clients(first.nextCursor(), null, null, 2, sort);
        var last = clients(second.nextCursor(), null, null, 2, sort);
        assertThat(codes(first)).containsExactly("C1", "C2");
        assertThat(codes(second)).containsExactly("C3", "C4");
        assertThat(codes(last)).containsExactly("C5");
        var around = clients(null, null, focus, 3, sort);
        assertThat(around.focus()).isEqualTo(focus);
        var aroundTail = clients(null, null, (UUID) last.rows().getFirst().get("_id"), 2, sort);
        var previous = clients(null, aroundTail.prevCursor(), null, 2, sort);
        assertThat(codes(previous)).containsExactly("C2", "C3");
        assertThat(first.rows()).allSatisfy(row -> assertThat(row.keySet()).noneMatch(k -> k.startsWith("_onno_sort_")));
    }

    @Test void referencesUseVisibleNamesAndEnumsUseLabelsBeforePaging() {
        UUID z = client("C1", "Zebra", "visible"), a = client("C2", "alpha", "visible");
        order("O1", z, Status.FIRST, 10); order("O2", a, Status.FIRST, 30);
        order("O3", a, Status.SECOND, 20); order("O4", a, Status.SECOND, null);
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "customer,-price")))
                .containsExactly("O2", "O3", "O4", "O1");
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "status,-price")))
                .containsExactly("O3", "O4", "O2", "O1");
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "price,status")))
                .containsExactly("O1", "O3", "O2", "O4");
    }

    @Test void hiddenReferenceNamesDoNotAffectOrderOrAppearInCursor() {
        UUID z = client("C1", "secret-zebra", "hidden"), a = client("C2", "secret-alpha", "hidden");
        order("O1", z, Status.FIRST, 10); order("O2", a, Status.FIRST, 20);
        var scope = new RecordScopeCompiler(new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Client.class).appliesTo("VIEWER").read(RecordScope.eq("tier", "visible")))));
        var service = new DocumentQueryService(registry, jdbi, scope, new AccessService(registry));
        var rows = orderRows(service, AccessSubject.user("viewer", Set.of("VIEWER")), "customer,-price");
        assertThat(numbers(rows)).containsExactly("O2", "O1");
        assertThat(rows).allSatisfy(row -> assertThat(row.get("customer_restricted")).isEqualTo(true));
    }

    @Test void changingAnySortPriorityOrDirectionRejectsAnOldCursor() {
        client("C1", "Alpha", "a"); client("C2", "Beta", "b");
        var page = clients(null, null, null, 1, "tier,description");
        assertThatThrownBy(() -> clients(page.nextCursor(), null, null, 1, "tier,-description"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> clients(page.nextCursor(), null, null, 1, "description,tier"))
                .isInstanceOf(ServiceException.class);
    }

    @Test void dateOnlySortUsesPriceInsideADayInsteadOfHiddenTimestampFractions() {
        UUID customer = client("C1", "Alice", null);
        order("O1", customer, Status.FIRST, 129);
        order("O2", customer, Status.FIRST, 58);
        order("O3", customer, Status.FIRST, 40);
        order("O4", customer, Status.FIRST, 1);
        String[] timestamps = {"2026-08-04T13:54:06.467186", "2026-08-04T13:54:06.553179",
                "2026-08-04T13:54:06.563620", "2026-08-05T00:01:00"};
        for (int i = 0; i < timestamps.length; i++) {
            String number = "O" + (i + 1), timestamp = timestamps[i];
            jdbi.useHandle(h -> h.createUpdate("UPDATE " + orders.tableName() + " SET _date = :d WHERE _number = :n")
                    .bind("d", java.time.LocalDateTime.parse(timestamp)).bind("n", number).execute());
        }
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "date:day,price")))
                .containsExactly("O3", "O2", "O1", "O4");
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "-date:day,price")))
                .containsExactly("O4", "O3", "O2", "O1");
        // An API consumer asking for full timestamps still receives exact chronological order.
        assertThat(numbers(orderRows(documents, AccessSubject.system(), "date,price")))
                .containsExactly("O1", "O2", "O3", "O4");
    }

    @Test void registerMovementsAndBalancesSortReferenceLabelsAndSecondaryResources() {
        UUID a = client("C2", "Alpha", null), z = client("C1", "Zebra", null);
        var service = new RegisterQueryService(registry, jdbi);
        var stock = service.require("SortStock");
        for (var entry : List.of(new Object[]{z, "north", 10}, new Object[]{a, "north", 5}, new Object[]{a, "south", 20})) {
            jdbi.useHandle(h -> {
                h.createUpdate("INSERT INTO " + stock.totalsTableName() + " (customer, location, quantity) VALUES (:c, :l, :q)")
                        .bind("c", entry[0]).bind("l", entry[1]).bind("q", entry[2]).execute();
                h.createUpdate("INSERT INTO " + stock.tableName()
                        + " (_id, _period, _document_ref, _active, _movement_type, customer, location, quantity)"
                        + " VALUES (:id, CURRENT_TIMESTAMP, :r, true, 'RECEIPT', :c, :l, :q)")
                        .bind("id", UUID.randomUUID()).bind("r", UUID.randomUUID())
                        .bind("c", entry[0]).bind("l", entry[1]).bind("q", entry[2]).execute();
            });
        }
        List<Integer> balances = new ArrayList<>(), movements = new ArrayList<>();
        String cursor = null;
        do {
            var page = service.balancePage(AccessSubject.system(), stock, null, "customer,-quantity", false, cursor, 1);
            page.rows().forEach(row -> balances.add(((Number) row.get("quantity")).intValue()));
            cursor = page.nextCursor();
        } while (cursor != null);
        cursor = null;
        do {
            var page = service.movementsPage(AccessSubject.system(), stock, null, null, null, "customer,-quantity", false, cursor, 1);
            page.rows().forEach(row -> movements.add(((Number) row.get("quantity")).intValue()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(balances).containsExactly(20, 5, 10);
        assertThat(movements).containsExactly(20, 5, 10);
        var historical = service.balancePage(AccessSubject.system(), stock, null, "customer,-quantity", false, null, 10, "2099-01-01");
        assertThat(historical.rows()).extracting(row -> ((Number) row.get("quantity")).intValue()).containsExactly(20, 5, 10);
    }

    @Test void invalidAndRepeatedSortColumnsCannotInjectSql() {
        client("C1", "Beta", "a"); client("C2", "Alpha", "a");
        assertThat(codes(clients(null, null, null, 10, "tier,tier,description;DROP TABLE x,description")))
                .containsExactly("C2", "C1");
    }
}
