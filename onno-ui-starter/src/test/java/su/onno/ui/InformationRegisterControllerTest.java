package su.onno.ui;

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
    // Fixture fields are read reflectively by MetadataScanner, not by test code.
    @SuppressWarnings("unused")
    @Catalog(name = "HistoryClients")
    @AccessControl(readRoles = "CUSTOMER")
    static class Client extends CatalogObject {
        @Attribute private UUID owner;
    }

    @SuppressWarnings("unused")
    @InformationRegister(name = "PriceHistory", periodicity = Periodicity.SECOND)
    @AccessControl(readRoles = "CUSTOMER")
    static class Price extends InformationRecord {
        @Dimension private Ref<Client> customer;
        @Dimension private String region;
        @Resource private BigDecimal amount;
        @Attribute private Ref<Client> reviewedBy;
        @Attribute(secret = true) private String token;
    }

    @SuppressWarnings("unused")
    @InformationRegister(name = "CurrentPrices")
    @AccessControl(readRoles = "CUSTOMER")
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
        new SchemaGenerator(registry).execute(jdbi);
        var policies = new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Client.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("owner", Subject.recordId())),
                RecordAccessPolicy.forRegister(Price.class).appliesTo("CUSTOMER").read(RecordScope.via("customer")),
                RecordAccessPolicy.forRegister(CurrentPrice.class).appliesTo("CUSTOMER").read(RecordScope.via("customer"))));
        var access = new UiAccessService(registry);
        controller = new GenericInformationRegisterController(
                new InformationRegisterQueryService(registry, jdbi, new RecordScopeCompiler(policies), access), access);
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
                .param("region", "North").param("customer", owner.toString()).param("limit", "1");
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
                .param("region", "North").param("customer", owner.toString()).param("limit", "1")
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
        assertThat(rows(page(subject, get(URL).param("customer", other.toString())))).isEmpty();
        assertThat(rows(page(subject, get(URL).param("region", "North' OR 1=1 --")))).isEmpty();
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
                get(URL).param("token", "secret-value"), get(URL).param("from", "invalid"),
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

    private MockMvc mvc(AccessSubject caller) {
        return MockMvcBuilders.standaloneSetup(controller)
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
