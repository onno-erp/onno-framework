package su.onno.ui;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.Enumeration;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.OnFillingHandler;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.numbering.NumberGenerator;
import su.onno.posting.PostingService;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;
import su.onno.validation.ValidationException;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the generic command path runs the entity write lifecycle ({@code onFilling},
 * {@code beforeWrite}, {@link Validated} rules) and persists fields a hook derives — the divergence
 * issue #158 reported between {@code repository.save(...)} (which ran the lifecycle) and
 * {@code POST/PUT /api/{catalogs,documents}}, which bound the body straight into SQL.
 */
class WriteLifecycleCommandServiceTest {

    @Enumeration(name = "Lifecycle Statuses")
    public enum LifecycleStatus { NEW, IN_PROGRESS, DONE }

    @Document(name = "LifecycleOrders", numberPrefix = "LO-")
    @AccessControl(readRoles = "ADMIN")
    public static class LifecycleOrder extends DocumentObject
            implements OnFillingHandler, BeforeWriteHandler, Validated {
        @Attribute(length = 60) private String title;
        @Attribute private LifecycleStatus status;
        @Attribute(length = 60) private String statusName; // derived mirror of the enum
        @Attribute(length = 120) private String summary;    // derived from number + title
        @Attribute private boolean filled;                  // set by onFilling

        @Override public void onFilling() {
            this.filled = true;
        }

        @Override public void beforeWrite() {
            this.statusName = status == null ? null : status.name();
            this.summary = (getNumber() == null ? "" : getNumber()) + " / " + (title == null ? "" : title);
        }

        @Override public List<BusinessRule> rules() {
            // A cross-field rule that reads the enum: it only passes when the high-fidelity entity
            // really carries the resolved status, so it doubles as proof the materialization works.
            return List.of(new BusinessRule("status-with-title", "A titled order needs a status",
                    () -> title == null || title.isBlank() || status != null));
        }
    }

    @Catalog(name = "LifecycleWidgets", codePrefix = "LW-")
    @AccessControl(readRoles = "ADMIN")
    public static class LifecycleWidget extends CatalogObject implements BeforeWriteHandler, Validated {
        @Attribute(length = 60) private String label;
        @Attribute(length = 60) private String slug; // derived from label

        @Override public void beforeWrite() {
            this.slug = label == null ? null : label.toLowerCase(Locale.ROOT);
        }

        @Override public List<BusinessRule> rules() {
            // Field-scoped, so the dry-run validate can prove the message lands in fieldErrors.
            return List.of(BusinessRule.onField("label", "Label is required",
                    () -> label != null && !label.isBlank()));
        }
    }

    private final Principal admin = new AdminPrincipal();
    private DocumentDescriptor orderDesc;
    private CatalogDescriptor widgetDesc;
    private EnumerationDescriptor statusEnum;
    private DocumentCommandService documentCommands;
    private CatalogCommandService catalogCommands;
    private DocumentQueryService documentQuery;
    private CatalogQueryService catalogQuery;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        statusEnum = scanner.scanEnumeration(LifecycleStatus.class);
        registry.registerEnumeration(statusEnum);
        orderDesc = scanner.scanDocument(LifecycleOrder.class);
        registry.registerDocument(orderDesc);
        widgetDesc = scanner.scan(LifecycleWidget.class);
        registry.registerCatalog(widgetDesc);

        new SchemaGenerator(registry).execute(jdbi);

        UiProperties props = new UiProperties();
        NumberGenerator numbers = new SequentialNumbers();
        SecretCipher cipher = new SecretCipher(null);
        UiAccessService access = new UiAccessService(registry);
        ApplicationEventPublisher events = event -> { };
        documentQuery = new DocumentQueryService(registry, jdbi);
        catalogQuery = new CatalogQueryService(registry, jdbi);
        // Posting is never reached by create/update, so a null PostingService is sufficient here.
        PostingService posting = null;
        documentCommands = new DocumentCommandService(registry, jdbi, props, numbers,
                posting, documentQuery, access, events, cipher);
        catalogCommands = new CatalogCommandService(registry, jdbi, props, numbers,
                catalogQuery, access, events, cipher);
    }

    @Test
    void documentCreate_runsOnFillingAndBeforeWrite_persistingDerivedFields() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Spring order");
        body.put("status", statusId("IN_PROGRESS").toString());

        Map<String, Object> created = documentCommands.create(orderDesc, body, admin);
        UUID id = (UUID) created.get("_id");

        Map<String, Object> row = documentQuery.get(orderDesc, id);
        String number = (String) row.get("_number");
        assertThat(row.get(column(orderDesc.attributes(), "statusName"))).isEqualTo("IN_PROGRESS");
        assertThat(row.get(column(orderDesc.attributes(), "summary"))).isEqualTo(number + " / Spring order");
        assertThat(row.get(column(orderDesc.attributes(), "filled"))).isEqualTo(Boolean.TRUE);
    }

    @Test
    void documentUpdate_recomputesDerivedFieldFromChangedEnum() {
        Map<String, Object> create = new HashMap<>();
        create.put("title", "Order X");
        create.put("status", statusId("NEW").toString());
        UUID id = (UUID) documentCommands.create(orderDesc, create, admin).get("_id");

        Map<String, Object> update = new HashMap<>();
        update.put("status", statusId("DONE").toString());
        documentCommands.update(orderDesc, id, update, admin);

        Map<String, Object> row = documentQuery.get(orderDesc, id);
        assertThat(row.get(column(orderDesc.attributes(), "statusName"))).isEqualTo("DONE");
    }

    @Test
    void documentCreate_failingBusinessRule_rejectsTheWrite() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Titled but statusless");

        assertThatThrownBy(() -> documentCommands.create(orderDesc, body, admin))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("needs a status");
    }

    @Test
    void catalogCreate_runsBeforeWrite_persistingDerivedField() {
        Map<String, Object> body = new HashMap<>();
        body.put("description", "Big Widget");
        body.put("label", "Big Widget");

        UUID id = (UUID) catalogCommands.create(widgetDesc, body, admin).get("_id");

        Map<String, Object> row = catalogQuery.get(widgetDesc, id);
        assertThat(row.get(column(widgetDesc.attributes(), "slug"))).isEqualTo("big widget");
    }

    @Test
    void catalogUpdate_recomputesDerivedField() {
        Map<String, Object> create = new HashMap<>();
        create.put("label", "Alpha");
        UUID id = (UUID) catalogCommands.create(widgetDesc, create, admin).get("_id");

        Map<String, Object> update = new HashMap<>();
        update.put("label", "Beta");
        catalogCommands.update(widgetDesc, id, update, admin);

        Map<String, Object> row = catalogQuery.get(widgetDesc, id);
        assertThat(row.get(column(widgetDesc.attributes(), "slug"))).isEqualTo("beta");
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentValidate_reportsCrossFieldRuleFailure_withoutPersisting() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Titled but statusless");

        Map<String, Object> report = documentCommands.validate(orderDesc, null, body, admin);

        assertThat(report.get("valid")).isEqualTo(false);
        assertThat((List<String>) report.get("formErrors"))
                .anyMatch(m -> m.contains("needs a status"));
        assertThat(documentQuery.list(orderDesc, null, null, null)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentValidate_passesWhenRulesHold() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Complete order");
        body.put("status", statusId("NEW").toString());

        Map<String, Object> report = documentCommands.validate(orderDesc, null, body, admin);

        assertThat(report.get("valid")).isEqualTo(true);
        assertThat((Map<String, List<String>>) report.get("fieldErrors")).isEmpty();
        assertThat((List<String>) report.get("formErrors")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentValidate_updateMode_overlaysChangesOnStoredRecord() {
        Map<String, Object> create = new HashMap<>();
        create.put("title", "Order Y");
        create.put("status", statusId("NEW").toString());
        UUID id = (UUID) documentCommands.create(orderDesc, create, admin).get("_id");

        // Clearing the status on a titled order breaks the cross-field rule — the dry run must
        // see the merged state (stored title + submitted null status), not just the delta.
        Map<String, Object> change = new HashMap<>();
        change.put("status", null);
        Map<String, Object> report = documentCommands.validate(orderDesc, id, change, admin);

        assertThat(report.get("valid")).isEqualTo(false);
        assertThat((List<String>) report.get("formErrors"))
                .anyMatch(m -> m.contains("needs a status"));
        // Dry run only — the stored document still carries its status.
        Map<String, Object> row = documentQuery.get(orderDesc, id);
        assertThat(row.get(column(orderDesc.attributes(), "statusName"))).isEqualTo("NEW");
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogValidate_fieldScopedRule_landsInFieldErrors() {
        Map<String, Object> body = new HashMap<>();
        body.put("description", "Unlabeled");

        Map<String, Object> report = catalogCommands.validate(widgetDesc, null, body, admin);

        assertThat(report.get("valid")).isEqualTo(false);
        Map<String, List<String>> fieldErrors = (Map<String, List<String>>) report.get("fieldErrors");
        assertThat(fieldErrors).containsKey("label");
        assertThat(fieldErrors.get("label")).anyMatch(m -> m.contains("Label is required"));
    }

    private UUID statusId(String name) {
        return statusEnum.values().stream()
                .filter(v -> v.name().equals(name))
                .map(su.onno.metadata.EnumerationValueDescriptor::id)
                .findFirst()
                .orElseThrow();
    }

    private static String column(List<AttributeDescriptor> attributes, String fieldName) {
        return attributes.stream()
                .filter(a -> a.fieldName().equals(fieldName))
                .map(AttributeDescriptor::columnName)
                .findFirst()
                .orElseThrow();
    }

    /** Deterministic numbering so tests don't need the JDBC sequence table. */
    static final class SequentialNumbers implements NumberGenerator {
        private final AtomicInteger counter = new AtomicInteger();

        @Override public String nextNumber(String entityName, int length) {
            return pad(counter.incrementAndGet(), length);
        }

        @Override public String nextCode(String entityName, int length) {
            return pad(counter.incrementAndGet(), length);
        }

        private static String pad(int value, int length) {
            return String.format("%0" + Math.max(1, length) + "d", value);
        }
    }

    /** Minimal authority-bearing principal; {@link UiAccessService} reads roles reflectively. */
    public static final class Authority {
        private final String role;

        public Authority(String role) {
            this.role = role;
        }

        public String getAuthority() {
            return role;
        }
    }

    public static final class AdminPrincipal implements Principal {
        @Override public String getName() {
            return "admin";
        }

        public List<Authority> getAuthorities() {
            return List.of(new Authority("ADMIN"));
        }
    }
}
