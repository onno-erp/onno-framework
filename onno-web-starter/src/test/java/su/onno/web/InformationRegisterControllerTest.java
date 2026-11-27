package su.onno.web;

import su.onno.access.RoleGrants;
import su.onno.access.RoleKey;

import su.onno.service.AccessService;
import su.onno.service.InformationRegisterQueryService;
import su.onno.service.RecordScopeCompiler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.access.*;
import su.onno.annotations.*;
import su.onno.metadata.*;
import su.onno.model.CatalogObject;
import su.onno.model.InformationRecord;
import su.onno.model.Periodicity;
import su.onno.schema.SchemaGenerator;
import su.onno.types.Ref;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InformationRegisterControllerTest {

    enum TestRole implements RoleKey { CUSTOMER }

    private static final RoleGrants GRANTS = RoleGrants.of(TestRole.class)
            .role(TestRole.CUSTOMER, r -> r.read(Client.class, Price.class, CurrentPrice.class));
    // Fixture fields are read reflectively by MetadataScanner, not by test code.
    @SuppressWarnings("unused")
    @Catalog(name = "HistoryClients")
    static class Client extends CatalogObject {
        @Attribute private UUID owner;
    }

    @SuppressWarnings("unused")
    @InformationRegister(name = "PriceHistory", periodicity = Periodicity.SECOND)
    static class Price extends InformationRecord {
        @Dimension private Ref<Client> customer;
        @Dimension private String region;
        @Resource private BigDecimal amount;
        @Attribute private Ref<Client> reviewedBy;
        @Attribute(secret = true) private String token;
    }

    @SuppressWarnings("unused")
    @InformationRegister(name = "CurrentPrices")
    static class CurrentPrice extends InformationRecord {
        @Dimension private Ref<Client> customer;
        @Resource private BigDecimal amount;
    }

    private static final String URL = "/api/information-registers/PriceHistory/records";
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final AccessSubject subject = AccessSubject.user("customer", Set.of("CUSTOMER")).withRecordId(owner);
    private final ObjectMapper json = new ObjectMapper();
    private GenericInformationRegisterController controller;
    private InformationRegisterListController listController;
    private Jdbi database;
    private String clientTable;
    private String historyTable;

    @BeforeEach
    void setUp() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:history" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);
        var registry = new MetadataRegistry();
        var scanner = new MetadataScanner(new DefaultNamingStrategy());
        var clients = scanner.scan(Client.class);
        var history = scanner.scanInformationRegister(Price.class);
        var current = scanner.scanInformationRegister(CurrentPrice.class);
        registry.registerCatalog(clients);
        registry.registerInformationRegister(history);
        registry.registerInformationRegister(current);
        database = jdbi;
        clientTable = clients.tableName();
        historyTable = history.tableName();
        new SchemaGenerator(registry).execute(jdbi);
        var policies = new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Client.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("owner", Subject.recordId())),
                RecordAccessPolicy.forRegister(Price.class).appliesTo("CUSTOMER").read(RecordScope.via("customer")),
                RecordAccessPolicy.forRegister(CurrentPrice.class).appliesTo("CUSTOMER").read(RecordScope.via("customer"))));
        var access = new AccessService(registry, GRANTS);
        var query = new InformationRegisterQueryService(registry, jdbi, new RecordScopeCompiler(policies), access);
        controller = new GenericInformationRegisterController(query, access);
        listController = new InformationRegisterListController(registry, query, access,
                org.mockito.Mockito.mock(ResolvedMetadataService.class), new UiMessages(Map.of()));
        jdbi.useHandle(h -> {
            for (UUID id : List.of(owner, other)) {
                h.createUpdate("INSERT INTO " + clients.tableName()
                                + " (_id, _code, _description, owner) VALUES (:id, :code, :label, :id)")
                        .bind("id", id).bind("code", id.equals(owner) ? "A" : "B")
                        .bind("label", id.equals(owner) ? "Visible client" : "HIDDEN-CLIENT").execute();
            }
            String[] periods = {"2024-03-01T10:00:00", "2024-03-01T10:00:00", "2024-03-01T11:00:00",
                    "2024-04-01T00:00:00", "2024-03-01T10:00:00", "2024-02-28T00:00:00"};
            for (int i = 0; i < periods.length; i++) {
                h.createUpdate("INSERT INTO " + history.tableName()
                                + " (_id, _period, customer, region, amount, reviewed_by, token)"
                                + " VALUES (:id, :period, :customer, :region, :amount, :other, 'secret-value')")
                        .bind("id", new UUID(0, i + 1)).bind("period", LocalDateTime.parse(periods[i]))
                        .bind("customer", i == 4 ? other : owner).bind("region", i == 1 ? "South" : "North")
                        .bind("amount", BigDecimal.valueOf((i + 1) * 10)).bind("other", other).execute();
            }
            h.createUpdate("INSERT INTO " + current.tableName() + " (_id, customer, amount) VALUES (:id, :customer, 15)")
                    .bind("id", UUID.randomUUID()).bind("customer", owner).execute();
        });
    }

    @Test
    void filtersInclusivePeriodAndDimensionsBeforePagingAndResolvesReferences() throws Exception {
        var request = get(URL).param("from", "2024-03-01T10:00:00").param("to", "2024-03-01T11:00:00")
                .param("where", where(owner)).param("limit", "1");
        var first = page(subject, request);
        List<Map<String, Object>> rows = rows(first);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("amount", 30.0).containsEntry("customerDisplay", "Visible client")
                .containsEntry("period", "2024-03-01T11:00").containsEntry("reviewedByRestricted", true)
                .containsEntry("reviewedBy", null);
        assertThat(rows.getFirst()).containsKey("reviewedByDisplay").doesNotContainKeys("_id", "customer_display");
        assertThat(json.writeValueAsString(rows)).doesNotContain("HIDDEN-CLIENT", "secret-value", other.toString());
        assertThat(first).containsEntry("hasMore", true).containsKey("nextCursor");

        var second = page(subject, get(URL).param("from", "2024-03-01T10:00:00").param("to", "2024-03-01T11:00:00")
                .param("where", where(owner)).param("limit", "1")
                .param("cursor", (String) first.get("nextCursor")));
        assertThat(rows(second)).singleElement().satisfies(r -> assertThat(r).containsEntry("amount", 10.0));
        assertThat(second).containsEntry("hasMore", false).containsEntry("nextCursor", null);
    }

    @Test
    void tiedPeriodsHaveStablePagesAndForeignDimensionCannotEscapeScope() throws Exception {
        var window = get(URL).param("from", "2024-03-01T10:00:00").param("to", "2024-03-01T10:00:00")
                .param("limit", "1");
        var first = page(subject, window);
        assertThat(rows(first).getFirst()).containsEntry("amount", 20.0);
        var second = page(subject, get(URL).param("from", "2024-03-01T10:00:00").param("to", "2024-03-01T10:00:00")
                .param("limit", "1").param("cursor", (String) first.get("nextCursor")));
        assertThat(rows(second).getFirst()).containsEntry("amount", 10.0);
        assertThat(second).containsEntry("hasMore", false);
        assertThat(rows(page(subject, get(URL).param("where", "{\"customer\":\"" + other + "\"}")))).isEmpty();
        assertThat(rows(page(subject, get(URL).param("where", "{\"region\":\"North' OR 1=1 --\"}")))).isEmpty();
    }

    @Test
    void cursorIsSealedToTheCaller() throws Exception {
        String cursor = (String) page(subject, get(URL).param("limit", "1")).get("nextCursor");
        assertThat(cursor).isNotNull();
        mvc(AccessSubject.system()).perform(get(URL).param("cursor", cursor)).andExpect(status().isBadRequest());
    }

    @Test
    void deniesMissingReadRoleAndUnknownRegisters() throws Exception {
        mvc(AccessSubject.user("anonymous", Set.of())).perform(get(URL)).andExpect(status().isForbidden());
        mvc(subject).perform(get("/api/information-registers/missing/records")).andExpect(status().isNotFound());
    }

    @Test
    void validatesInputClampsLimitAndSupportsNonperiodicRegisters() throws Exception {
        for (var request : List.of(get(URL).param("offset", "1"), get(URL).param("nope", "x"),
                get(URL).param("region", "North"), get(URL).param("where", "{\"token\":\"secret-value\"}"),
                get(URL).param("where", "{\"nope\":1}"), get(URL).param("from", "invalid"),
                get(URL).param("from", "2024-04-01T00:00:00").param("to", "2024-03-01T00:00:00"),
                get("/api/information-registers/CurrentPrices/records").param("to", "2024-03-01T00:00:00"))) {
            mvc(subject).perform(request).andExpect(status().isBadRequest());
        }
        assertThat(rows(page(subject, get(URL).param("limit", "0")))).hasSize(5);
        assertThat(rows(page(subject, get(URL).param("limit", "1001")))).hasSize(5);
        var current = rows(page(subject, get("/api/information-registers/CurrentPrices/records")));
        assertThat(current).hasSize(1);
        assertThat(current.getFirst()).containsEntry("amount", 15.0).doesNotContainKey("period");
    }

    @Test
    void sliceLastReturnsTheRecordInForcePerDimensionTuple() throws Exception {
        String slice = "/api/information-registers/PriceHistory/slice-last";
        // Owner's tuples: (owner, North) last at 2024-04-01 → 40; (owner, South) only 2024-03-01 10:00 → 20.
        List<Map<String, Object>> now = list(subject, get(slice));
        assertThat(now).extracting(r -> r.get("amount")).containsExactlyInAnyOrder(40.0, 20.0);
        // As of March: North's latest is 11:00 → 30.
        List<Map<String, Object>> march = list(subject, get(slice).param("at", "2024-03-15T00:00:00")
                .param("where", "{\"region\":\"North\"}"));
        assertThat(march).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("amount", 30.0).containsEntry("period", "2024-03-01T11:00")
                .containsEntry("customerDisplay", "Visible client"));
        // `where` on a resource compares the value in force, after slicing.
        assertThat(list(subject, get(slice).param("where", "{\"amount\":{\"lt\":25}}")))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("region", "South"));
        // The other client's tuple stays out of scope; before any record there is nothing in force.
        assertThat(list(subject, get(slice).param("at", "2024-01-01T00:00:00"))).isEmpty();
        assertThat(list(AccessSubject.system(), get(slice))).hasSize(3);
        // A non-periodic register's slice is its records, and takes no `at`.
        assertThat(list(subject, get("/api/information-registers/CurrentPrices/slice-last"))).hasSize(1);
        mvc(subject).perform(get("/api/information-registers/CurrentPrices/slice-last").param("at", "2024-01-01T00:00:00"))
                .andExpect(status().isBadRequest());
        mvc(subject).perform(get(slice).param("customer", owner.toString())).andExpect(status().isBadRequest());
    }

    @Test
    void standardListSortsFiltersCountsAndPagesWithinTheRecordScope() throws Exception {
        String url = "/api/list/information-registers/price_history";
        var first = page(subject, get(url).param("sort", "amount").param("dir", "asc")
                .param("limit", "1").param("ge", "amount,20").param("count", "exact"));
        assertThat(first).containsEntry("total", 4).containsEntry("hasMore", true);
        assertThat(rows(first)).singleElement().satisfies(row -> assertThat(row).containsEntry("amount", 20.0)
                .containsEntry("token", "__SECRET_SET__").doesNotContainKey("_id"));
        var second = page(subject, get(url).param("sort", "amount").param("dir", "asc")
                .param("limit", "1").param("ge", "amount,20")
                .param("cursor", (String) first.get("nextCursor")));
        assertThat(rows(second).getFirst()).containsEntry("amount", 30.0);
        mvc(AccessSubject.system()).perform(get(url).param("sort", "amount").param("dir", "asc")
                .param("ge", "amount,20").param("cursor", (String) first.get("nextCursor")))
                .andExpect(status().isBadRequest());
        mvc(subject).perform(get(url).param("sort", "token")).andExpect(status().isBadRequest());
        mvc(AccessSubject.user("outsider", Set.of())).perform(get(url)).andExpect(status().isForbidden());
    }

    @Test
    void datedListIncludesTheLastMicrosecondButExcludesTheNextMidnight() throws Exception {
        database.useHandle(h -> {
            String[] periods = {"2024-03-15T23:59:59.999999", "2024-03-16T00:00:00"};
            for (int i = 0; i < periods.length; i++) {
                h.createUpdate("INSERT INTO " + historyTable
                                + " (_id, _period, customer, region, amount) VALUES (:id, :period, :customer, 'Boundary', :amount)")
                        .bind("id", UUID.randomUUID()).bind("period", LocalDateTime.parse(periods[i]))
                        .bind("customer", owner).bind("amount", 81 + i).execute();
            }
        });
        String url = "/api/list/information-registers/price_history";
        assertThat(rows(page(subject, get(url).param("eq", "region,Boundary")
                .param("at", "2024-03-15T23:59:59.999999"))))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("amount", 81.0));
        assertThat(rows(page(subject, get(url).param("eq", "region,Boundary")
                .param("at", "2024-03-16T00:00:00"))))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("amount", 82.0));
    }

    @Test
    void standardListPagesLatestValuesAndFiltersAfterSlicing() throws Exception {
        String url = "/api/list/information-registers/price_history";
        var first = page(subject, get(url).param("at", "2024-03-15T00:00:00")
                .param("sort", "amount").param("dir", "asc").param("limit", "1").param("count", "exact"));
        assertThat(first).containsEntry("total", 2).containsEntry("hasMore", true);
        assertThat(rows(first).getFirst()).containsEntry("amount", 20.0);
        var second = page(subject, get(url).param("at", "2024-03-15T00:00:00")
                .param("sort", "amount").param("dir", "asc").param("limit", "1")
                .param("cursor", (String) first.get("nextCursor")));
        assertThat(rows(second).getFirst()).containsEntry("amount", 30.0);
        assertThat(second).containsEntry("hasMore", false);
        assertThat(rows(page(subject, get(url).param("at", "2024-03-15T00:00:00")
                .param("le", "amount,25")))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("region", "South"));
        assertThat(rows(page(subject, get(url).param("at", "2024-01-01T00:00:00")))).isEmpty();
        var current = page(subject, get("/api/list/information-registers/current_prices"));
        assertThat(rows(current)).singleElement().satisfies(row -> assertThat(row).doesNotContainKey("period"));
        mvc(subject).perform(get("/api/list/information-registers/current_prices").param("at", "2024-01-01T00:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void selectedReferenceFilterWorksInHistoryAndLatestValues() throws Exception {
        String url = "/api/list/information-registers/price_history";
        for (boolean sliced : List.of(false, true)) {
            var request = get(url).param("in", "customer," + owner).param("count", "exact");
            if (sliced) request.param("at", "2024-03-15T23:59:59");
            var result = page(subject, request);
            assertThat(result).containsEntry("total", sliced ? 2 : 5);
            assertThat(rows(result)).allSatisfy(row -> assertThat(row).containsEntry("customer", owner.toString()));
            var hidden = get(url).param("in", "customer," + other);
            if (sliced) hidden.param("at", "2024-03-15T23:59:59");
            assertThat(rows(page(subject, hidden))).isEmpty();
        }
    }

    @Test
    void referenceColumnsKeepIdOrderAcrossPagesInHistoryAndDatedSlices() throws Exception {
        String[] names = {"Zulu", "alpha", "Alpha", "beta", ""};
        database.useHandle(h -> {
            for (int i = 0; i < names.length; i++) {
                UUID client = new UUID(0, 100 + i);
                h.createUpdate("INSERT INTO " + clientTable
                                + " (_id, _code, _description, owner) VALUES (:id, :code, :label, :owner)")
                        .bind("id", client).bind("code", i == 4 ? "Aardvark" : "C" + i)
                        .bind("label", names[i]).bind("owner", owner).execute();
                for (int day = 1; day <= 2; day++) {
                    h.createUpdate("INSERT INTO " + historyTable
                                    + " (_id, _period, customer, region, amount) VALUES (:id, :period, :client, 'Sort', :price)")
                            .bind("id", new UUID(0, 1000 + i * 10 + day))
                            .bind("period", LocalDateTime.of(2024, 3, day, 0, 0))
                            .bind("client", client).bind("price", day).execute();
                }
            }
        });
        String url = "/api/list/information-registers/price_history";
        for (boolean sliced : List.of(false, true)) {
            for (String direction : List.of("asc", "desc")) {
                var found = new java.util.ArrayList<Map<String, Object>>();
                String cursor = null;
                do {
                    var request = get(url).param("sort", "customer").param("dir", direction)
                            .param("eq", "region,Sort").param("limit", "1");
                    if (sliced) request.param("at", "2024-03-01T23:59:59");
                    if (cursor != null) request.param("cursor", cursor);
                    var window = page(subject, request);
                    found.addAll(rows(window));
                    assertThat(found.size()).isLessThanOrEqualTo(10);
                    cursor = (String) window.get("nextCursor");
                } while (cursor != null);
                assertThat(found).hasSize(sliced ? 5 : 10);
                assertThat(found.stream().map(r -> r.get("id")).distinct().count()).isEqualTo(found.size());
                var ids = found.stream().map(row -> row.get("customer").toString()).toList();
                var expected = new java.util.ArrayList<>(ids);
                expected.sort(direction.equals("asc") ? java.util.Comparator.naturalOrder() : java.util.Comparator.reverseOrder());
                assertThat(ids).isEqualTo(expected);
                assertThat(found.get(direction.equals("asc") ? 0 : found.size() - 1).get("customerDisplay"))
                        .isEqualTo("Zulu");
            }
        }
    }

    @Test
    void idSortDoesNotExposeRestrictedReferenceNames() throws Exception {
        UUID hidden = new UUID(0, 500);
        database.useHandle(h -> {
            h.createUpdate("INSERT INTO " + clientTable
                            + " (_id, _code, _description, owner) VALUES (:id, 'H', 'ZZZ-SECRET', :id)")
                    .bind("id", hidden).execute();
            h.createUpdate("UPDATE " + historyTable + " SET reviewed_by = :hidden WHERE amount = 10")
                    .bind("hidden", hidden).execute();
        });
        String url = "/api/list/information-registers/price_history";
        var first = page(subject, get(url).param("sort", "reviewed_by").param("dir", "asc").param("limit", "1"));
        assertThat(rows(first).getFirst()).containsEntry("amount", 10.0).containsEntry("reviewedByRestricted", true);
        var second = page(subject, get(url).param("sort", "reviewed_by").param("dir", "asc").param("limit", "1")
                .param("cursor", (String) first.get("nextCursor")));
        assertThat(rows(second).getFirst()).containsEntry("amount", 20.0);
        assertThat(json.writeValueAsString(first)).doesNotContain("ZZZ-SECRET", "HIDDEN-CLIENT", "_info_ref_sort");
    }

    private static String where(UUID customer) {
        return "{\"region\":\"North\",\"customer\":\"" + customer + "\"}";
    }

    private List<Map<String, Object>> list(AccessSubject caller, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc(caller).perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, new TypeReference<>() {});
    }

    private MockMvc mvc(AccessSubject caller) {
        return MockMvcBuilders.standaloneSetup(controller, listController)
                .setControllerAdvice(new ServiceExceptionHandler())
                .setCustomArgumentResolvers(AccessSubjectArgumentResolver.fixed(caller)).build();
    }

    private Map<String, Object> page(AccessSubject caller, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc(caller).perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("rows");
    }
}
