package su.onno.ui;

import su.onno.annotations.Catalog;
import su.onno.annotations.Attribute;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

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

class RefOptionControllerTest {

    @Catalog(name = "DecoratedEmployees")
    static class Employee extends CatalogObject {
        @Attribute
        private String email;
    }

    @Test
    void searchDecoratesOptionsWithLiveFormAndRowContext() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        CatalogDescriptor employee = scanner.scan(Employee.class);
        registry.registerCatalog(employee);

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

        AtomicReference<RefOptionContext> seen = new AtomicReference<>();
        CapturingDecorator decorator = new CapturingDecorator(seen, unavailable);
        RefOptionService optionService = new RefOptionService(List.of(decorator));
        RefOptionController controller = new RefOptionController(
                new CatalogQueryService(registry, jdbi),
                new DocumentQueryService(registry, jdbi),
                new UiAccessService(registry),
                optionService);

        UUID documentId = UUID.randomUUID();
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("startsAt", "2026-07-20T10:00");
        form.put("endsAt", "2026-07-20T12:00");
        Map<String, Object> row = Map.of("role", "HOST");
        var request = new RefOptionController.SearchRequest(
                "catalog", "DecoratedEmployees", CapturingDecorator.class.getName(),
                "", 30, null, "participants.employee", form,
                "participants", 1, row, documentId);

        var principal = new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        List<Map<String, Object>> result = controller.search(request, principal);

        assertThat(result).hasSize(2);
        Map<String, Object> decorated = result.stream()
                .filter(item -> unavailable.equals(item.get("_id")))
                .findFirst().orElseThrow();
        assertThat(decorated)
                .containsEntry("_optionBadge", "Unavailable")
                .containsEntry("_optionTone", "danger")
                .containsEntry("_optionDisabled", true)
                .containsEntry("_optionReason", "Overlaps SO-42");

        RefOptionContext context = seen.get();
        assertThat(context.targetKind()).isEqualTo("catalog");
        assertThat(context.targetName()).isEqualTo("DecoratedEmployees");
        assertThat(context.fieldPath()).isEqualTo("participants.employee");
        assertThat(context.formValues()).containsEntry("startsAt", "2026-07-20T10:00");
        assertThat(context.section()).isEqualTo("participants");
        assertThat(context.rowIndex()).isEqualTo(1);
        assertThat(context.rowValues()).containsEntry("role", "HOST");
        assertThat(context.documentId()).isEqualTo(documentId);
        assertThat(decorated).containsEntry("email", null);
    }

    @Test
    void decoratorCanFilterAnOptionFromLiveFormContext() {
        UUID visible = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(new LinkedHashMap<>(Map.of("_id", visible)));
        rows.add(new LinkedHashMap<>(Map.of("_id", hidden)));
        RefOptionDecorator decorator = (context, options) ->
                Map.of(hidden, RefOptionDecoration.filteredOut());

        List<Map<String, Object>> result = new RefOptionService(List.of(decorator))
                .decorate(decorator.getClass().getName(), new RefOptionContext(
                        "catalog", "Employees", "participants.employee",
                        Map.of("showUnavailable", false), "", null, Map.of(), null), rows);

        assertThat(result).extracting(row -> row.get("_id")).containsExactly(visible);
    }

    static final class CapturingDecorator implements RefOptionDecorator {
        private final AtomicReference<RefOptionContext> seen;
        private final UUID unavailable;

        CapturingDecorator(AtomicReference<RefOptionContext> seen, UUID unavailable) {
            this.seen = seen;
            this.unavailable = unavailable;
        }

        @Override
        public Map<UUID, RefOptionDecoration> decorate(
                RefOptionContext context, List<RefOption> options) {
            seen.set(context);
            assertThat(options).hasSize(2);
            return Map.of(unavailable,
                    RefOptionDecoration.disabled(
                            "Unavailable", RefOptionTone.DANGER, "Overlaps SO-42"));
        }
    }
}
