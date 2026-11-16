package su.onno.web;

import su.onno.picker.PickerOptionsService;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recently picked ref values (#409): remembered per user and target, offered first on an empty
 * query, and always re-read through the target's search, so a remembered record the user can no
 * longer see (record policy, deletion mark, the field's {@code refFilter}) is never offered.
 */
class RefRecentOptionsTest {

    @AccessControl(readRoles = {"CLERK", "CUSTOMER"})
    @Catalog(name = "RecentClients")
    static class Client extends CatalogObject {
        @Attribute(length = 40)
        private String tier;
    }

    @AccessControl(readRoles = "CLERK")
    @Document(name = "RecentOrders")
    static class Order extends DocumentObject {
    }

    private static final AccessSubject CLERK = AccessSubject.user("clerk@fw.test", Set.of("CLERK"));
    private static final AccessSubject OTHER_CLERK = AccessSubject.user("other@fw.test", Set.of("CLERK"));
    private static final AccessSubject CUSTOMER = AccessSubject.user("c@fw.test", Set.of("CUSTOMER"));
    private static final AccessSubject OUTSIDER = AccessSubject.user("x@fw.test", Set.of("NOBODY"));

    private Jdbi jdbi;
    private RefOptionController controller;
    private RefRecentStore store;
    private CatalogDescriptor catalog;
    private DocumentDescriptor document;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:recent" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Client.class));
        registry.registerDocument(scanner.scanDocument(Order.class));
        new SchemaGenerator(registry).execute(jdbi);
        catalog = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Client.class).findFirst().orElseThrow();
        document = registry.allDocuments().stream()
                .filter(d -> d.javaClass() == Order.class).findFirst().orElseThrow();

        // CUSTOMER sees only gold clients; CLERK is unscoped.
        RecordScopeCompiler scopes = new RecordScopeCompiler(new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Client.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("tier", "gold")))));
        AccessService access = new AccessService(registry);
        store = new RefRecentStore(jdbi);
        controller = new RefOptionController(
                new CatalogQueryService(registry, jdbi, scopes, access),
                new DocumentQueryService(registry, jdbi, scopes, access),
                access,
                new PickerOptionsService(registry, List.of()),
                store);
    }

    @Test
    void anEmptyQueryLeadsWithRecentPicksInPickOrderThenTheRestWithoutDuplicates() {
        List<UUID> ids = insertClients(10, "gold");
        pick(CLERK, ids.get(6));
        pick(CLERK, ids.get(2));
        pick(CLERK, ids.get(8));
        pick(CLERK, ids.get(2)); // picked again: moves back to the top

        List<Map<String, Object>> options = search(CLERK, "", null, 30);

        assertThat(descriptions(options).subList(0, 3))
                .containsExactly("Client 03", "Client 09", "Client 07");
        assertThat(options.subList(0, 3)).allSatisfy(o -> assertThat(o).containsEntry("_recent", true));
        assertThat(options.subList(3, options.size())).allSatisfy(o -> assertThat(o).doesNotContainKey("_recent"));
        assertThat(options).hasSize(10);
        assertThat(ids(options)).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void atMostFiveRecentPicksAreOfferedAndTheLimitStillBoundsTheResponse() {
        List<UUID> ids = insertClients(12, "gold");
        for (int i = 0; i < 8; i++) {
            pick(CLERK, ids.get(i));
        }

        List<Map<String, Object>> options = search(CLERK, null, null, 30);
        assertThat(options.stream().filter(o -> Boolean.TRUE.equals(o.get("_recent")))).hasSize(5);
        assertThat(descriptions(options).subList(0, 5))
                .containsExactly("Client 08", "Client 07", "Client 06", "Client 05", "Client 04");
        assertThat(options).hasSize(12);

        assertThat(search(CLERK, null, null, 7)).hasSize(7);
        assertThat(search(CLERK, null, null, 3))
                .hasSize(3)
                .allSatisfy(o -> assertThat(o).containsEntry("_recent", true));
    }

    @Test
    void aTypedQueryOrAnExplicitOptOutGetsNoRecentSection() {
        List<UUID> ids = insertClients(5, "gold");
        pick(CLERK, ids.get(4));

        assertThat(search(CLERK, "Client 0", null, 30))
                .allSatisfy(o -> assertThat(o).doesNotContainKey("_recent"));
        assertThat(controller.search(request("", null, 30, false), CLERK))
                .allSatisfy(o -> assertThat(o).doesNotContainKey("_recent"));
    }

    @Test
    void recentPicksPassTheFieldsRefFilter() {
        UUID gold = insert("C-1", "Gold one", "gold");
        UUID silver = insert("C-2", "Silver one", "silver");
        pick(CLERK, silver);
        pick(CLERK, gold);

        List<Map<String, Object>> options = search(CLERK, "", tierColumn() + " = gold", 30);

        assertThat(ids(options)).containsExactly(gold);
        assertThat(options.get(0)).containsEntry("_recent", true);
    }

    @Test
    void aRecentPickLeavingTheRecordScopeOrMarkedDeletedIsNotOffered() {
        UUID kept = insert("C-1", "Kept", "gold");
        UUID demoted = insert("C-2", "Demoted", "gold");
        UUID deleted = insert("C-3", "Deleted", "gold");
        pick(CUSTOMER, kept);
        pick(CUSTOMER, demoted);
        pick(CUSTOMER, deleted);

        jdbi.useHandle(h -> {
            h.createUpdate("UPDATE " + catalog.tableName() + " SET " + tierColumn() + " = 'silver' WHERE _id = :id")
                    .bind("id", demoted).execute();
            h.createUpdate("UPDATE " + catalog.tableName() + " SET _deletion_mark = true WHERE _id = :id")
                    .bind("id", deleted).execute();
        });

        List<Map<String, Object>> options = search(CUSTOMER, "", null, 30);
        assertThat(ids(options)).containsExactly(kept);
        assertThat(options.get(0)).containsEntry("_recent", true);
    }

    @Test
    void aRecordOutsideTheCallersScopeIsNeverRemembered() {
        UUID silver = insert("C-1", "Silver", "silver");
        UUID gold = insert("C-2", "Gold", "gold");

        pick(CUSTOMER, silver);          // invisible to CUSTOMER: silently ignored
        pick(CUSTOMER, UUID.randomUUID()); // unknown id: silently ignored
        pick(CUSTOMER, gold);

        assertThat(store.recent("c@fw.test", "catalog", catalog.logicalName(), 20)).containsExactly(gold);
    }

    @Test
    void recentPicksArePerUser() {
        List<UUID> ids = insertClients(4, "gold");
        pick(CLERK, ids.get(3));

        assertThat(search(OTHER_CLERK, "", null, 30))
                .allSatisfy(o -> assertThat(o).doesNotContainKey("_recent"));
        assertThat(search(CLERK, "", null, 30).get(0)).containsEntry("id", ids.get(3));
    }

    @Test
    void aCallerWithoutReadAccessToTheTargetCanNeitherRecordNorSearch() {
        UUID id = insert("C-1", "Gold", "gold");
        var request = new RefOptionController.RecentRequest("catalog", catalog.logicalName(), id);

        assertThatThrownBy(() -> controller.recent(request, OUTSIDER))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> search(OUTSIDER, "", null, 30))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(store.recent("x@fw.test", "catalog", catalog.logicalName(), 20)).isEmpty();
    }

    @Test
    void theSystemSubjectNeitherRecordsNorGetsRecentPicks() {
        UUID id = insert("C-1", "Gold", "gold");
        controller.recent(new RefOptionController.RecentRequest("catalog", catalog.logicalName(), id),
                AccessSubject.system());

        long remembered = jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + RefRecentStore.TABLE)
                .mapTo(Long.class).one());
        assertThat(remembered).isZero();
    }

    @Test
    void eachUserAndTargetKeepsOnlyTheNewestPicks() {
        List<UUID> ids = insertClients(RefRecentStore.KEEP + 5, "gold");
        for (UUID id : ids) {
            pick(CLERK, id);
        }

        List<UUID> remembered = store.recent("clerk@fw.test", "catalog", catalog.logicalName(), 100);
        assertThat(remembered).hasSize(RefRecentStore.KEEP);
        assertThat(remembered.get(0)).isEqualTo(ids.get(ids.size() - 1));
        assertThat(remembered).doesNotContain(ids.get(0), ids.get(4));
    }

    @Test
    void documentTargetsRememberPicksToo() {
        UUID first = insertOrder("SO-1", LocalDateTime.of(2026, 1, 1, 10, 0));
        UUID second = insertOrder("SO-2", LocalDateTime.of(2026, 2, 1, 10, 0));
        controller.recent(new RefOptionController.RecentRequest("document", document.logicalName(), first), CLERK);

        List<Map<String, Object>> options = controller.search(new RefOptionController.SearchRequest(
                "document", document.logicalName(), null, null, "", 30, null, null, null, null, null, null, null), CLERK);

        assertThat(ids(options)).containsExactly(first, second);
        assertThat(options.get(0)).containsEntry("_recent", true);
        assertThat(options.get(1)).doesNotContainKey("_recent");
    }

    @Test
    void theSearchBodyWithoutTheRecentFlagStillDeserializes() throws Exception {
        var request = new ObjectMapper().readValue(
                "{\"targetKind\":\"catalog\",\"targetName\":\"RecentClients\",\"query\":\"\",\"limit\":30}",
                RefOptionController.SearchRequest.class);
        assertThat(request.recent()).isNull();
        assertThat(request.limit()).isEqualTo(30);

        var optOut = new ObjectMapper().readValue(
                "{\"targetName\":\"RecentClients\",\"recent\":false}", RefOptionController.SearchRequest.class);
        assertThat(optOut.recent()).isFalse();
    }

    private void pick(AccessSubject subject, UUID id) {
        controller.recent(new RefOptionController.RecentRequest("catalog", catalog.logicalName(), id), subject);
    }

    private List<Map<String, Object>> search(AccessSubject subject, String query, String filter, int limit) {
        return controller.search(request(query, filter, limit, null), subject);
    }

    private RefOptionController.SearchRequest request(String query, String filter, int limit, Boolean recent) {
        return new RefOptionController.SearchRequest("catalog", catalog.logicalName(), null, null, query, limit,
                filter, null, null, null, null, null, null, recent);
    }

    private static List<String> descriptions(List<Map<String, Object>> options) {
        return options.stream().map(o -> String.valueOf(o.get("description"))).toList();
    }

    private static List<UUID> ids(List<Map<String, Object>> options) {
        return options.stream().map(o -> (UUID) o.get("id")).toList();
    }

    private String tierColumn() {
        return catalog.attributes().stream().filter(a -> a.fieldName().equals("tier"))
                .map(a -> a.columnName()).findFirst().orElseThrow();
    }

    private List<UUID> insertClients(int n, String tier) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ids.add(insert(String.format("C-%02d", i), "Client " + String.format("%02d", i), tier));
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
