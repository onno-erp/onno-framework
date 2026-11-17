package su.onno.web;

import su.onno.service.AccessService;
import su.onno.service.CatalogQueryService;
import su.onno.service.DocumentQueryService;
import su.onno.picker.PickerOptionsService;
import su.onno.access.AccessSubject;
import su.onno.annotations.Catalog;
import su.onno.annotations.Attribute;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.picker.PickerCandidate;
import su.onno.picker.PickerContext;
import su.onno.picker.PickerOption;
import su.onno.picker.PickerOptions;
import su.onno.picker.PickerTone;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefOptionControllerTest {

    @Catalog(name = "DecoratedEmployees")
    static class Employee extends CatalogObject {
        @Attribute
        private String email;
    }

    /** The owner whose form holds the decorated {@code participants.employee} field. */
    @Catalog(name = "DecoratedEvents")
    static class Event extends CatalogObject {
    }

    @Test
    void searchDecoratesOptionsWithLiveFormAndRowContext() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        CatalogDescriptor employee = scanner.scan(Employee.class);
        registry.registerCatalog(employee);
        registry.registerCatalog(scanner.scan(Event.class));

        UUID available = UUID.randomUUID();
        UUID unavailable = UUID.randomUUID();
        Jdbi jdbi = Jdbi.create("jdbc:h2:mem:ref_options;DB_CLOSE_DELAY=-1");
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE " + employee.tableName() + " ("
                    + "_id UUID PRIMARY KEY, _code VARCHAR(40), _description VARCHAR(200), "
                    + "email VARCHAR(200), "
                    + "_deletion_mark BOOLEAN NOT NULL)");
            handle.createUpdate("INSERT INTO " + employee.tableName()
                            + " (_id, _code, _description, _deletion_mark) VALUES (:id, :code, :name, false)")
                    .bind("id", available).bind("code", "E-1").bind("name", "Alex").execute();
            handle.createUpdate("INSERT INTO " + employee.tableName()
                            + " (_id, _code, _description, _deletion_mark) VALUES (:id, :code, :name, false)")
                    .bind("id", unavailable).bind("code", "E-2").bind("name", "Sam").execute();
        });

        AtomicReference<PickerContext> seen = new AtomicReference<>();
        CapturingDecorator decorator = new CapturingDecorator(seen, unavailable);
        PickerOptionsService optionService = new PickerOptionsService(registry, List.of(decorator));
        RefOptionController controller = new RefOptionController(
                new CatalogQueryService(registry, jdbi),
                new DocumentQueryService(registry, jdbi),
                new AccessService(registry),
                optionService);

        UUID documentId = UUID.randomUUID();
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("startsAt", "2026-07-20T10:00");
        form.put("endsAt", "2026-07-20T12:00");
        Map<String, Object> row = Map.of("role", "HOST");
        var request = new RefOptionController.SearchRequest(
                "catalog", "DecoratedEmployees", "catalog", "DecoratedEvents",
                "", 30, "participants.employee", form,
                "participants", 1, row, documentId, null, null);

        var principal = AccessSubject.user("admin", java.util.Set.of("ADMIN"));
        List<Map<String, Object>> result = controller.search(request, principal);

        assertThat(result).hasSize(2);
        Map<String, Object> decorated = result.stream()
                .filter(item -> unavailable.equals(item.get("id")))
                .findFirst().orElseThrow();
        assertThat(decorated)
                .containsEntry("_optionBadge", "Unavailable")
                .containsEntry("_optionTone", "danger")
                .containsEntry("_optionDisabled", true)
                .containsEntry("_optionReason", "Overlaps SO-42");

        PickerContext context = seen.get();
        assertThat(context.targetKind()).isEqualTo("catalog");
        assertThat(context.targetName()).isEqualTo("DecoratedEmployees");
        assertThat(context.fieldPath()).isEqualTo("participants.employee");
        assertThat(context.formValues()).containsEntry("startsAt", "2026-07-20T10:00");
        assertThat(context.section()).isEqualTo("participants");
        assertThat(context.rowIndex()).isEqualTo(1);
        assertThat(context.rowValues()).containsEntry("role", "HOST");
        assertThat(context.recordId()).isEqualTo(documentId);
        assertThat(decorated)
                .containsEntry("email", null)
                .containsEntry("description", "Sam")
                .doesNotContainKey("_description");

        List<Map<String, Object>> storage = controller.search(request, "storage", principal);
        assertThat(storage).anySatisfy(item -> assertThat(item)
                .containsEntry("_id", unavailable)
                .containsEntry("_description", "Sam")
                .doesNotContainKey("description"));

        // Another owner (or none) names a field no PickerOptions binds: options stay undecorated.
        var otherOwner = new RefOptionController.SearchRequest(
                "catalog", "DecoratedEmployees", "catalog", "DecoratedEmployees",
                "", 30, "participants.employee", form, "participants", 1, row, documentId, null, null);
        assertThat(controller.search(otherOwner, principal))
                .allSatisfy(item -> assertThat(item).doesNotContainKey("_optionBadge"));
    }

    @Test
    void twoBeansBindingOneFieldFailStartup() {
        MetadataRegistry registry = new MetadataRegistry();
        PickerOptions<Event> one = new CapturingDecorator(new AtomicReference<>(), UUID.randomUUID());
        PickerOptions<Event> two = new CapturingDecorator(new AtomicReference<>(), UUID.randomUUID());
        assertThatThrownBy(() -> new PickerOptionsService(registry, List.of(one, two)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("participants.employee");
    }

    @Test
    void decoratorCanFilterAnOptionFromLiveFormContext() {
        UUID visible = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(new LinkedHashMap<>(Map.of("_id", visible)));
        rows.add(new LinkedHashMap<>(Map.of("_id", hidden)));
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(new MetadataScanner(new DefaultNamingStrategy()).scan(Event.class));
        PickerOptions<Event> decorator = new PickerOptions<>() {
            public Class<Event> entity() { return Event.class; }
            public String field() { return "participants.employee"; }
            public Map<UUID, PickerOption> decorate(PickerContext context, List<PickerCandidate> options) {
                return Map.of(hidden, PickerOption.filteredOut());
            }
        };

        List<Map<String, Object>> result = new PickerOptionsService(registry, List.of(decorator))
                .decorate("catalogs", "DecoratedEvents", new PickerContext(
                        "catalog", "Employees", "participants.employee",
                        Map.of("showUnavailable", false), "", null, Map.of(), null), rows);

        assertThat(result).extracting(row -> row.get("_id")).containsExactly(visible);
    }

    static final class CapturingDecorator implements PickerOptions<Event> {
        private final AtomicReference<PickerContext> seen;
        private final UUID unavailable;

        CapturingDecorator(AtomicReference<PickerContext> seen, UUID unavailable) {
            this.seen = seen;
            this.unavailable = unavailable;
        }

        @Override
        public Class<Event> entity() {
            return Event.class;
        }

        @Override
        public String field() {
            return "participants.employee";
        }

        @Override
        public Map<UUID, PickerOption> decorate(
                PickerContext context, List<PickerCandidate> options) {
            seen.set(context);
            assertThat(options).hasSize(2);
            return Map.of(unavailable,
                    PickerOption.disabled(
                            "Unavailable", PickerTone.DANGER, "Overlaps SO-42"));
        }
    }
}
