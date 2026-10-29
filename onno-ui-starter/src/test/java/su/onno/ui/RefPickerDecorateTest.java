package su.onno.ui;

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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The picker dialog's window decoration (#411): every row on screen is re-read through the
 * dropdown's own search, so the dialog offers only what the dropdown would (the field's
 * {@code refFilter}, record scope, deletion mark, a decorator's filtered-out option) and disables
 * what the decorator disables.
 */
class RefPickerDecorateTest {

    @AccessControl(readRoles = {"CLERK", "CUSTOMER"})
    @Catalog(name = "PickerClients")
    static class Client extends CatalogObject {
        @Attribute(length = 40)
        private String tier;
    }

    private static final AccessSubject CLERK = AccessSubject.user("clerk@fw.test", Set.of("CLERK"));
    private static final AccessSubject CUSTOMER = AccessSubject.user("c@fw.test", Set.of("CUSTOMER"));
    private static final AccessSubject OUTSIDER = AccessSubject.user("x@fw.test", Set.of("NOBODY"));

    private Jdbi jdbi;
    private CatalogDescriptor catalog;
    private RefOptionController controller;
    private final AtomicReference<RefOptionContext> seen = new AtomicReference<>();
    private UUID blocked;
    private UUID filteredOut;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pickerdecorate" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(new MetadataScanner(new DefaultNamingStrategy()).scan(Client.class));
        new SchemaGenerator(registry).execute(jdbi);
        catalog = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Client.class).findFirst().orElseThrow();

        // CUSTOMER sees only gold clients; CLERK is unscoped.
        RecordScopeCompiler scopes = new RecordScopeCompiler(new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Client.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("tier", "gold")))));
        UiAccessService access = new UiAccessService(registry);
        controller = new RefOptionController(
                new CatalogQueryService(registry, jdbi, scopes, access),
                new DocumentQueryService(registry, jdbi, scopes, access),
                access,
                new RefOptionService(List.of(new Availability())),
                null);
    }

    @Test
    void onlyRowsTheFieldsRefFilterAdmitsAreOffered() {
        UUID gold = insert("Gold", "gold", false);
        UUID silver = insert("Silver", "silver", false);

        Map<String, Object> out = decorate(CLERK, "tier = gold", null, gold, silver);

        assertThat(out.get("offered")).isEqualTo(List.of(gold.toString()));
        assertThat(out.get("decorations")).isEqualTo(Map.of());
    }

    @Test
    void outOfScopeDeletedAndUnknownIdsAreAllLeftOutTheSameWay() {
        UUID gold = insert("Gold", "gold", false);
        UUID silver = insert("Silver", "silver", false);
        UUID deleted = insert("Gone", "gold", true);
        UUID unknown = UUID.randomUUID();

        Map<String, Object> out = decorate(CUSTOMER, null, null, gold, silver, deleted, unknown);

        assertThat(out.get("offered")).isEqualTo(List.of(gold.toString()));
        assertThat(out.toString()).doesNotContain(silver.toString(), deleted.toString(), unknown.toString());
    }

    @Test
    void theFieldsDecoratorDisablesAndFiltersOutWithTheLiveFormContext() {
        UUID free = insert("Free", "gold", false);
        blocked = insert("Blocked", "gold", false);
        filteredOut = insert("Filtered", "gold", false);

        Map<String, Object> out = decorate(CLERK, null, Availability.class.getName(),
                free, blocked, filteredOut);

        assertThat(out.get("offered")).isEqualTo(List.of(free.toString(), blocked.toString()));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> decorations = (Map<String, Map<String, Object>>) out.get("decorations");
        assertThat(decorations).containsOnlyKeys(blocked.toString());
        assertThat(decorations.get(blocked.toString()))
                .containsEntry("disabled", true)
                .containsEntry("badge", "Busy")
                .containsEntry("tone", "danger")
                .containsEntry("reason", "Booked");
        assertThat(seen.get().fieldPath()).isEqualTo("lines.client");
        assertThat(seen.get().rowIndex()).isEqualTo(2);
        assertThat(seen.get().formValues()).containsEntry("status", "OPEN");
    }

    @Test
    void anEmptyIdListAnswersNothingAndAnUnreadableTargetIsRefused() {
        assertThat(decorate(CLERK, null, null).get("offered")).isEqualTo(List.of());
        UUID gold = insert("Gold", "gold", false);
        assertThatThrownBy(() -> decorate(OUTSIDER, null, null, gold))
                .isInstanceOf(ResponseStatusException.class);
    }

    private Map<String, Object> decorate(AccessSubject subject, String filter, String decorator, UUID... ids) {
        return controller.decorate(new RefOptionController.DecorateRequest(
                "catalog", "PickerClients", decorator, filter, "lines.client",
                Map.of("status", "OPEN"), "lines", 2, Map.of(), null, List.of(ids)), subject);
    }

    private UUID insert(String description, String tier, boolean deleted) {
        UUID id = UUID.randomUUID();
        String tierColumn = catalog.attributes().stream()
                .filter(a -> a.fieldName().equals("tier")).findFirst().orElseThrow().columnName();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + catalog.tableName()
                        + " (_id, _code, _description, _deletion_mark, " + tierColumn + ")"
                        + " VALUES (:id, :code, :description, :deleted, :tier)")
                .bind("id", id).bind("code", description).bind("description", description)
                .bind("deleted", deleted).bind("tier", tier).execute());
        return id;
    }

    final class Availability implements RefOptionDecorator {
        @Override
        public Map<UUID, RefOptionDecoration> decorate(RefOptionContext context, List<RefOption> options) {
            seen.set(context);
            return Map.of(
                    blocked, RefOptionDecoration.disabled("Busy", RefOptionTone.DANGER, "Booked"),
                    filteredOut, RefOptionDecoration.filteredOut());
        }
    }
}
