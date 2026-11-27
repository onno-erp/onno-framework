package su.onno.web;

import su.onno.service.AccessService;
import su.onno.service.AggregateBuckets;
import su.onno.service.CatalogQueryService;
import su.onno.service.DocumentQueryService;
import su.onno.service.RecordScopeCompiler;
import su.onno.picker.PickerOptionsService;
import su.onno.command.CommandRegistry;
import su.onno.access.AccessSubject;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.Enumeration;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.repository.EnumerationPersistence;
import su.onno.schema.SchemaGenerator;
import su.onno.types.Ref;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import su.onno.service.ServiceException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The structured {@code where} parameter filters rows on every endpoint family that takes the legacy
 * {@code filter}: the keyset list feed (window, focus, count), groups, aggregate buckets and the
 * ref-option typeahead — ANDed with {@code filter} and with the caller's record scope.
 */
class WhereEndpointsTest {

    @Enumeration(name = "WhereStatuses")
    enum Status { OPEN, CLOSED }

    @AccessControl(readRoles = {"CLERK", "CUSTOMER"})
    @Catalog(name = "WhereClients")
    static class Client extends CatalogObject {
        @Attribute(length = 40)
        private String tier;
    }

    @AccessControl(readRoles = "CLERK")
    @Document(name = "WhereOrders")
    static class Order extends DocumentObject {
        @Attribute(precision = 15, scale = 2)
        private BigDecimal total;
        @Attribute
        private Status status;
        @Attribute
        private Ref<Client> client;
        @Attribute(length = 100)
        private String note;
    }

    private static final AccessSubject CLERK = AccessSubject.user("clerk@fw.test", Set.of("CLERK"));
    private static final AccessSubject CUSTOMER = AccessSubject.user("cust@fw.test", Set.of("CUSTOMER"));
    private static final ObjectMapper JSON = new ObjectMapper();

    private Jdbi jdbi;
    private CatalogDescriptor clients;
    private DocumentDescriptor orders;
    private ListDataController list;
    private RefOptionController refOptions;
    private UUID gold;
    private UUID silver;
    private UUID bronze;
    private UUID rushOrder;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:where" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        clients = scanner.scan(Client.class);
        registry.registerCatalog(clients);
        orders = scanner.scanDocument(Order.class);
        registry.registerDocument(orders);
        new SchemaGenerator(registry).execute(jdbi);

        // CUSTOMER sees only gold/silver clients; CLERK is unscoped.
        RecordScopeCompiler scopes = new RecordScopeCompiler(new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Client.class).appliesTo("CUSTOMER")
                        .read(RecordScope.or(RecordScope.eq("tier", "gold"), RecordScope.eq("tier", "silver"))))));
        AccessService access = new AccessService(registry);
        CatalogQueryService catalogQuery = new CatalogQueryService(registry, jdbi, scopes, access);
        DocumentQueryService documentQuery = new DocumentQueryService(registry, jdbi, scopes, access);
        list = new ListDataController(catalogQuery, documentQuery, access, new CommandRegistry(List.of()));
        refOptions = new RefOptionController(catalogQuery, documentQuery, access, new PickerOptionsService(registry, List.of()));

        gold = client("C-1", "Gold Co", "gold");
        silver = client("C-2", "Silver Co", "silver");
        bronze = client("C-3", "Bronze Co", "bronze");

        order("SO-1", true, "100", Status.OPEN, gold, "rush delivery");
        order("SO-2", true, "250", Status.CLOSED, silver, "standard");
        order("SO-3", false, "900", Status.OPEN, null, "Rush, unassigned");
        rushOrder = order("SO-4", true, "1500", Status.OPEN, bronze, "plain");
    }

    @Test
    void listFeedWindowAndCountFollowWhere() {
        MockHttpServletRequest request = request(Map.of(
                "where", "{\"posted\":true,\"total\":{\"gte\":100,\"lt\":1000}}", "count", "exact"));
        Map<String, Object> page = list.documentPage(orders.logicalName(), 100, "number", "asc", null, null, null,
                request, CLERK);
        assertThat(notes(page)).containsExactly("rush delivery", "standard");
        assertThat(page.get("total")).isEqualTo(2L);
    }

    @Test
    void whereSupportsCombinatorsNullsAndEnumNames() {
        MockHttpServletRequest request = request(Map.of(
                "where", "{\"or\":[{\"note\":{\"contains\":\"RUSH\"}},{\"client\":\"" + bronze + "\"}],"
                        + "\"status\":{\"in\":[\"" + EnumerationPersistence.resolveId(Status.class, Status.OPEN) + "\"]}}"));
        assertThat(notes(list.documentPage(orders.logicalName(), 100, "number", "asc", null, null, null,
                request, CLERK))).containsExactly("rush delivery", "Rush, unassigned", "plain");
        // null → IS NULL; { not: null } → IS NOT NULL; enum by constant name
        assertThat(notes(list.documentPage(orders.logicalName(), 100, "number", "asc", null, null, null,
                request(Map.of("where", "{\"client\":null}")), CLERK))).containsExactly("Rush, unassigned");
        assertThat(notes(list.documentPage(orders.logicalName(), 100, "number", "asc", null, null, null,
                request(Map.of("where", "{\"client\":{\"not\":null},\"status\":{\"not\":\"OPEN\"}}")), CLERK)))
                .containsExactly("standard");
    }

    @Test
    void aFocusedRecordTheWhereExcludesYieldsTheFirstWindow() {
        Map<String, Object> page = list.documentPage(orders.logicalName(), 100, "number", "asc", null, null, null,
                request(Map.of("where", "{\"total\":{\"lt\":1000}}", "focus", rushOrder.toString())), CLERK);
        assertThat(page).doesNotContainKey("focus");
        assertThat(notes(page)).doesNotContain("plain");
    }

    @Test
    void catalogFeedAndsWhereWithTheRecordScope() {
        Map<String, Object> page = list.catalogPage(clients.logicalName(), 100, "code", "asc", null,
                request(Map.of("where", "{\"tier\":{\"in\":[\"silver\",\"bronze\"]}}")), CUSTOMER);
        assertThat(descriptions(page)).containsExactly("Silver Co");
        Map<String, Object> clerk = list.catalogPage(clients.logicalName(), 100, "code", "asc", null,
                request(Map.of("where", "{\"tier\":{\"in\":[\"silver\",\"bronze\"]}}")), CLERK);
        assertThat(descriptions(clerk)).containsExactly("Silver Co", "Bronze Co");
    }

    @Test
    void unknownFieldIsA400() {
        assertThatThrownBy(() -> list.documentPage(orders.logicalName(), 100, null, null, null, null, null,
                request(Map.of("where", "{\"discount\":1}")), CLERK))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.status()).isEqualTo(ServiceException.Status.BAD_REQUEST);
                    assertThat(e.getReason()).contains("discount");
                });
    }

    @Test
    void groupsFollowWhere() {
        Map<String, Object> result = list.documentGroups(orders.logicalName(), "status", null, null, null, null,
                request(Map.of("where", "{\"posted\":true}")), CLERK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) result.get("groups");
        assertThat(groups).extracting(g -> g.get("count")).containsExactlyInAnyOrder(2L, 1L);
    }

    @Test
    void aggregateBucketsFollowWhere() {
        Map<String, Object> result = list.documentAggregate(orders.logicalName(), new AggregateBuckets.Request(
                "sum", "total", null, null, null, null, null,
                "{\"posted\":true,\"note\":{\"startsWith\":\"r\"}}", null, null, null), CLERK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) result.get("buckets");
        assertThat(buckets).singleElement().satisfies(b ->
                assertThat(new BigDecimal(b.get("value").toString())).isEqualByComparingTo("100"));

        assertThatThrownBy(() -> list.documentAggregate(orders.logicalName(), new AggregateBuckets.Request(
                "count", null, null, null, null, null, null, "{\"nope\":1}", null, null, null), CLERK))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.status()).isEqualTo(ServiceException.Status.BAD_REQUEST));
    }

    @Test
    void refOptionsFollowWhere() throws Exception {
        List<Map<String, Object>> options = refOptions.search(new RefOptionController.SearchRequest(
                "catalog", clients.logicalName(), null, null, "", 30, null, null, null, null, null, null,
                false, JSON.readTree("{\"tier\":{\"not\":\"gold\"}}")), CLERK);
        assertThat(options).extracting(o -> o.get("description")).containsExactlyInAnyOrder("Silver Co", "Bronze Co");

        // A string body value is accepted as already-encoded JSON.
        List<Map<String, Object>> both = refOptions.search(new RefOptionController.SearchRequest(
                "catalog", clients.logicalName(), null, null, "", 30, null, null, null, null, null,
                null, false, JSON.readTree("\"{\\\"tier\\\":{\\\"notIn\\\":[\\\"gold\\\",\\\"bronze\\\"]}}\"")), CLERK);
        assertThat(both).extracting(o -> o.get("description")).containsExactly("Silver Co");

        Map<String, Object> decorated = refOptions.decorate(new RefOptionController.DecorateRequest(
                "catalog", clients.logicalName(), null, null, null, null, null, null, null, null,
                List.of(gold, silver, bronze), JSON.readTree("{\"tier\":\"gold\"}")), CLERK);
        assertThat(decorated.get("offered")).isEqualTo(List.of(gold.toString()));
    }

    private static MockHttpServletRequest request(Map<String, String> params) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        params.forEach(request::addParameter);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> notes(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("rows")).stream().map(r -> r.get("note")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> descriptions(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("rows")).stream().map(r -> r.get("description")).toList();
    }

    private String column(Class<?> owner, String field) {
        var attrs = owner == Client.class ? clients.attributes() : orders.attributes();
        return attrs.stream().filter(a -> a.fieldName().equals(field)).map(a -> a.columnName())
                .findFirst().orElseThrow();
    }

    private UUID client(String code, String name, String tier) {
        UUID id = UUID.randomUUID();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + clients.tableName()
                        + " (_id, _code, _description, _deletion_mark, _version, "
                        + column(Client.class, "tier") + ") VALUES (:id, :code, :name, false, 0, :tier)")
                .bind("id", id).bind("code", code).bind("name", name).bind("tier", tier).execute());
        return id;
    }

    private UUID order(String number, boolean posted, String total, Status status, UUID client, String note) {
        UUID id = UUID.randomUUID();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + orders.tableName()
                        + " (_id, _number, _date, _posted, _deletion_mark, _version, "
                        + column(Order.class, "total") + ", " + column(Order.class, "status") + ", "
                        + column(Order.class, "client") + ", " + column(Order.class, "note") + ") "
                        + "VALUES (:id, :number, :date, :posted, false, 0, :total, :status, :client, :note)")
                .bind("id", id).bind("number", number).bind("date", LocalDateTime.of(2024, 1, 1, 9, 0))
                .bind("posted", posted).bind("total", new BigDecimal(total))
                .bind("status", EnumerationPersistence.resolveId(Status.class, status))
                .bindByType("client", client, UUID.class).bind("note", note).execute());
        return id;
    }
}
