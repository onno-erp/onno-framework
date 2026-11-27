package su.onno.web;

import su.onno.service.TestEntityStore;

import su.onno.service.AccessService;
import su.onno.service.CatalogCommandService;
import su.onno.service.CatalogQueryService;
import su.onno.service.DocumentCommandService;
import su.onno.service.DocumentQueryService;
import su.onno.service.EntityJsonRepresentation;
import su.onno.service.WebProperties;
import su.onno.access.AccessSubject;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.Enumeration;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.AfterWriteHandler;
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
import su.onno.model.TabularSectionRow;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    @SuppressWarnings({"unused", "FieldMayBeFinal", "FieldCanBeLocal", "MismatchedQueryAndUpdateOfCollection"}) // Reflective model fixture.
    public static class LifecycleOrder extends DocumentObject
            implements OnFillingHandler, BeforeWriteHandler, AfterWriteHandler, Validated {
        static final AtomicInteger AFTER_WRITES = new AtomicInteger();
        @Attribute(length = 60) private String title;
        @Attribute private LifecycleStatus status;
        @Attribute(length = 60) private String statusName; // derived mirror of the enum
        @Attribute(length = 120) private String summary;    // derived from number + title
        @Attribute private boolean filled;                  // set by onFilling
        @Attribute private LocalDateTime startsAt;
        @TabularSection(name = "items")
        private List<LifecycleOrderLine> items = new ArrayList<>();

        @Override public void onFilling() {
            this.filled = true;
        }

        @Override public void beforeWrite() {
            this.statusName = status == null ? null : status.name();
            this.summary = (getNumber() == null ? "" : getNumber()) + " / " + (title == null ? "" : title);
            for (LifecycleOrderLine item : items) {
                BigDecimal quantity = item.quantity == null ? BigDecimal.ZERO : item.quantity;
                BigDecimal price = item.price == null ? BigDecimal.ZERO : item.price;
                item.amount = quantity.multiply(price);
            }
        }

        @Override public void afterWrite() {
            AFTER_WRITES.incrementAndGet();
        }

        @Override public List<BusinessRule> rules() {
            // A cross-field rule that reads the enum: it only passes when the high-fidelity entity
            // really carries the resolved status, so it doubles as proof the materialization works.
            return List.of(new BusinessRule("status-with-title", "A titled order needs a status",
                    () -> title == null || title.isBlank() || status != null));
        }
    }

    @SuppressWarnings("unused") // Attribute values are populated through reflection.
    public static class LifecycleOrderLine extends TabularSectionRow {
        @Attribute private BigDecimal quantity;
        @Attribute private BigDecimal price;
        @Attribute private BigDecimal amount;
        @Attribute private LocalDateTime happensAt;
    }

    @Catalog(name = "LifecycleWidgets", codePrefix = "LW-")
    @AccessControl(readRoles = "ADMIN")
    @SuppressWarnings({"unused", "FieldMayBeFinal", "FieldCanBeLocal", "MismatchedQueryAndUpdateOfCollection"}) // Reflective model fixture.
    public static class LifecycleWidget extends CatalogObject
            implements BeforeWriteHandler, AfterWriteHandler, Validated {
        static final AtomicInteger AFTER_WRITES = new AtomicInteger();
        @Attribute(length = 60) private String label;
        @Attribute(length = 60) private String slug; // derived from label
        @Attribute private LocalDateTime observedAt;
        @TabularSection(name = "lines") private List<CatalogLine> lines = new ArrayList<>();
        @Attribute private int lineCount;

        @Override public void beforeWrite() {
            this.slug = label == null ? null : label.toLowerCase(Locale.ROOT);
            this.lineCount = lines.size();
        }

        @Override public void afterWrite() {
            AFTER_WRITES.incrementAndGet();
        }

        @Override public List<BusinessRule> rules() {
            // Field-scoped, so the dry-run validate can prove the message lands in fieldErrors.
            return List.of(BusinessRule.onField("label", "Label is required",
                    () -> label != null && !label.isBlank()));
        }
    }

    @SuppressWarnings("unused") // Attribute values are populated through reflection.
    public static class CatalogLine extends TabularSectionRow {
        private static boolean failConstruction;

        private CatalogLine() {
            if (failConstruction) throw new IllegalStateException("Cannot construct catalog line");
        }

        @Attribute(required = true, length = 20) private String property;
        @Attribute(secret = true) private String secret;
        @Attribute private LocalDateTime happensAt;
    }

    private Jdbi database;

    private final AccessSubject admin = AccessSubject.user("admin", java.util.Set.of("ADMIN"));
    private DocumentDescriptor orderDesc;
    private CatalogDescriptor widgetDesc;
    private EnumerationDescriptor statusEnum;
    private DocumentCommandService documentCommands;
    private CatalogCommandService catalogCommands;
    private GenericCatalogController catalogController;
    private DocumentQueryService documentQuery;
    private CatalogQueryService catalogQuery;

    @BeforeEach
    void setUp() {
        LifecycleOrder.AFTER_WRITES.set(0);
        LifecycleWidget.AFTER_WRITES.set(0);
        CatalogLine.failConstruction = false;
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);
        database = jdbi;

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        statusEnum = scanner.scanEnumeration(LifecycleStatus.class);
        registry.registerEnumeration(statusEnum);
        orderDesc = scanner.scanDocument(LifecycleOrder.class);
        registry.registerDocument(orderDesc);
        widgetDesc = scanner.scan(LifecycleWidget.class);
        registry.registerCatalog(widgetDesc);

        new SchemaGenerator(registry).execute(jdbi);

        WebProperties props = new WebProperties();
        NumberGenerator numbers = new SequentialNumbers();
        SecretCipher cipher = new SecretCipher(null);
        AccessService access = new AccessService(registry);
        ApplicationEventPublisher events = event -> { };
        documentQuery = new DocumentQueryService(registry, jdbi);
        catalogQuery = new CatalogQueryService(registry, jdbi);
        // Posting is never reached by create/update, so a null PostingService is sufficient here.
        PostingService posting = null;
        TestEntityStore.Stack stack = TestEntityStore.builder(ds, registry).numbers(numbers).cipher(cipher).events(events::publishEvent).build();
        documentCommands = new DocumentCommandService(registry, stack.jdbi(), props, posting, documentQuery, access, stack.store());
        catalogCommands = new CatalogCommandService(registry, stack.jdbi(), props, catalogQuery, access, stack.store());
        // duplicate() only needs the catalog services and messages.
        catalogController = new GenericCatalogController(catalogQuery, access, catalogCommands,
                new UiMessages(Map.of()), null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogLines_saveReplaceClearAndKeepOnPartialUpdate() {
        var created = catalogCommands.create(widgetDesc, Map.of("label", "Profile", "lines",
                List.of(Map.of("property", "event", "happens_at", "2026-10-26T12:00:00"), Map.of("property", "duration"))), admin);
        UUID id = UUID.fromString(created.get("_id").toString());
        assertThat(created.get("line_count")).isEqualTo(2);
        var lines = (List<Map<String, Object>>) created.get("lines");
        assertThat(lines).extracting(r -> r.get("property")).containsExactly("event", "duration");
        assertThat(lines).extracting(r -> r.get("_line_number")).containsExactly(1, 2);
        var logical = EntityJsonRepresentation.catalog(widgetDesc, created, EntityJsonRepresentation.Mode.LOGICAL);
        assertThat(((List<Map<String, Object>>) logical.get("lines")).getFirst())
                .containsKeys("id", "parentId", "lineNumber", "property")
                .containsEntry("happensAt", "2026-10-26T12:00");

        var partial = catalogCommands.update(widgetDesc, id, Map.of("label", "Renamed"), admin);
        assertThat(partial.get("line_count")).isEqualTo(2);
        assertThat((List<?>) partial.get("lines")).hasSize(2);
        var replaced = catalogCommands.update(widgetDesc, id,
                Map.of("lines", List.of(Map.of("property", "changed"))), admin);
        assertThat(replaced.get("line_count")).isEqualTo(1);
        assertThat((List<?>) replaced.get("lines")).hasSize(1);
        var cleared = catalogCommands.update(widgetDesc, id, Map.of("lines", List.of()), admin);
        assertThat((List<?>) cleared.get("lines")).isEmpty();
        assertThat(cleared.get("line_count")).isEqualTo(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogDuplicate_copiesOrderedLinesWithFreshIdsAndWithoutSecrets() {
        var source = catalogCommands.create(widgetDesc, Map.of("label", "Profile", "lines", List.of(
                Map.of("property", "event", "happensAt", "2026-10-28T12:00"),
                Map.of("property", "duration"))), admin);
        UUID sourceId = (UUID) source.get("_id");
        var sourceLines = (List<Map<String, Object>>) source.get("lines");
        String table = widgetDesc.tabularSections().getFirst().tableName();
        database.useHandle(h -> h.createUpdate("UPDATE " + table + " SET secret = :secret WHERE _parent_id = :id")
                .bind("secret", "source secret").bind("id", sourceId).execute());

        var copy = catalogController.duplicate(widgetDesc.logicalName(), sourceId, "logical", admin);
        UUID copyId = UUID.fromString(copy.get("id").toString());
        var copiedLines = (List<Map<String, Object>>) copy.get("lines");
        assertThat(copyId).isNotEqualTo(sourceId);
        assertThat(copy.get("code")).isNotEqualTo(source.get("_code"));
        assertThat(copy.get("label")).isEqualTo("Profile");
        assertThat(copiedLines).extracting(row -> row.get("property")).containsExactly("event", "duration");
        assertThat(copiedLines).extracting(row -> row.get("lineNumber")).containsExactly(1, 2);
        assertThat(copiedLines.getFirst()).containsEntry("happensAt", "2026-10-28T12:00");
        for (int i = 0; i < copiedLines.size(); i++) {
            assertThat(copiedLines.get(i).get("id")).isNotEqualTo(sourceLines.get(i).get("_id"));
            assertThat(copiedLines.get(i).get("parentId")).isEqualTo(copyId);
        }
        assertThat(database.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + table
                        + " WHERE _parent_id = :id AND secret IS NOT NULL")
                .bind("id", copyId).mapTo(Integer.class).one()).intValue()).isZero();

        catalogCommands.update(widgetDesc, copyId, Map.of("lines", List.of()), admin);
        assertThat((List<?>) catalogQuery.get(admin, widgetDesc, sourceId).get("lines")).hasSize(2);
    }

    @Test
    void catalogLines_constructorFailureRejectsSaveInsteadOfDroppingRows() {
        CatalogLine.failConstruction = true;
        assertThatThrownBy(() -> catalogCommands.create(widgetDesc,
                Map.of("label", "Profile", "lines", List.of(Map.of("property", "event"))), admin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lines");
        assertThat(database.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + widgetDesc.tableName())
                .mapTo(Integer.class).one()).intValue()).isZero();
        assertThat(LifecycleWidget.AFTER_WRITES.get()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogLines_validateWithoutSavingAndRejectMalformedInput() {
        var report = catalogCommands.validate(widgetDesc, null,
                Map.of("label", "Profile", "lines", List.of(Map.of())), admin);
        assertThat(report.get("valid")).isEqualTo(false);
        assertThat((Map<String, ?>) report.get("fieldErrors")).containsKey("lines[0].property");
        assertThat(LifecycleWidget.AFTER_WRITES.get()).isZero();
        for (Object invalid : List.of("not a list", List.of("not a row"), List.of(Map.of()))) {
            assertThatThrownBy(() -> catalogCommands.create(widgetDesc,
                    Map.of("label", "Profile", "lines", invalid), admin))
                    .isInstanceOf(ValidationException.class);
        }
        assertThat(database.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + widgetDesc.tableName())
                .mapTo(Integer.class).one()).intValue()).isZero();
    }

    @Test
    void catalogLines_staleVersionCannotReplaceRows() {
        var created = catalogCommands.create(widgetDesc, Map.of("label", "Original", "lines",
                List.of(Map.of("property", "kept"))), admin);
        UUID id = UUID.fromString(created.get("_id").toString());
        catalogCommands.update(widgetDesc, id, Map.of("label", "Updated"), admin);
        assertThatThrownBy(() -> catalogCommands.update(widgetDesc, id,
                Map.of("version", created.get("_version"), "lines", List.of()), admin))
                .isInstanceOf(su.onno.service.ServiceException.class)
                .satisfies(error -> assertThat(((su.onno.service.ServiceException) error)
                        .status().code()).isEqualTo(409));
        assertThat((List<?>) catalogQuery.get(admin, widgetDesc, id).get("lines")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogLines_databaseFailureRollsBackHeaderAndAllLines() {
        var created = catalogCommands.create(widgetDesc, Map.of("label", "Original", "lines",
                List.of(Map.of("property", "kept"))), admin);
        UUID id = UUID.fromString(created.get("_id").toString());
        database.useHandle(h -> h.execute("ALTER TABLE " + widgetDesc.tabularSections().getFirst().tableName()
                + " ADD CONSTRAINT reject_bad CHECK (property <> 'bad')"));
        int hooksBefore = LifecycleWidget.AFTER_WRITES.get();
        assertThatThrownBy(() -> catalogCommands.update(widgetDesc, id,
                Map.of("label", "Changed", "lines", List.of(Map.of("property", "ok"), Map.of("property", "bad"))), admin))
                .isInstanceOf(RuntimeException.class);
        var unchanged = catalogQuery.get(admin, widgetDesc, id);
        assertThat(unchanged.get("label")).isEqualTo("Original");
        assertThat(unchanged.get("_version")).isEqualTo(created.get("_version"));
        assertThat((List<Map<String, Object>>) unchanged.get("lines"))
                .extracting(r -> r.get("property")).containsExactly("kept");
        assertThat(LifecycleWidget.AFTER_WRITES.get()).isEqualTo(hooksBefore);
        assertThatThrownBy(() -> catalogCommands.create(widgetDesc,
                Map.of("label", "Failed", "lines", List.of(Map.of("property", "bad"))), admin))
                .isInstanceOf(RuntimeException.class);
        assertThat(database.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + widgetDesc.tableName())
                .mapTo(Integer.class).one()).intValue()).isEqualTo(1);
    }

    @Test
    void documentCreate_runsOnFillingAndBeforeWrite_persistingDerivedFields() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Spring order");
        body.put("status", statusId("IN_PROGRESS").toString());

        Map<String, Object> created = documentCommands.create(orderDesc, body, admin);
        UUID id = (UUID) created.get("_id");

        Map<String, Object> row = documentQuery.get(AccessSubject.system(), orderDesc, id);
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

        Map<String, Object> row = documentQuery.get(AccessSubject.system(), orderDesc, id);
        assertThat(row.get(column(orderDesc.attributes(), "statusName"))).isEqualTo("DONE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentCreateAndUpdate_persistDerivedTabularRowFields() {
        Map<String, Object> create = new HashMap<>();
        create.put("status", statusId("NEW").toString());
        create.put("items", List.of(Map.of("quantity", 2, "price", 7.5)));

        Map<String, Object> created = documentCommands.create(orderDesc, create, admin);
        UUID id = (UUID) created.get("_id");
        List<Map<String, Object>> createdItems = (List<Map<String, Object>>) created.get("items");
        assertThat(createdItems).singleElement().satisfies(row ->
                assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("15.00"));

        documentCommands.update(orderDesc, id,
                Map.of("items", List.of(Map.of("quantity", 3, "price", 4))), admin);

        List<Map<String, Object>> updatedItems = (List<Map<String, Object>>)
                documentQuery.get(AccessSubject.system(), orderDesc, id).get("items");
        assertThat(updatedItems).singleElement().satisfies(row ->
                assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("12.00"));
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

        Map<String, Object> row = catalogQuery.get(AccessSubject.system(), widgetDesc, id);
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

        Map<String, Object> row = catalogQuery.get(AccessSubject.system(), widgetDesc, id);
        assertThat(row.get(column(widgetDesc.attributes(), "slug"))).isEqualTo("beta");
    }

    @Test
    void genericCommandsRunAfterWriteOnlyAfterSuccessfulPersistence() {
        UUID documentId = (UUID) documentCommands.create(orderDesc,
                Map.of("status", statusId("NEW").toString()), admin).get("_id");
        documentCommands.update(orderDesc, documentId,
                Map.of("status", statusId("DONE").toString()), admin);
        documentCommands.validate(orderDesc, documentId,
                Map.of("status", statusId("NEW").toString()), admin);

        UUID catalogId = (UUID) catalogCommands.create(widgetDesc,
                Map.of("label", "Alpha"), admin).get("_id");
        catalogCommands.update(widgetDesc, catalogId, Map.of("label", "Beta"), admin);
        catalogCommands.validate(widgetDesc, catalogId, Map.of("label", "Gamma"), admin);

        assertThat(LifecycleOrder.AFTER_WRITES).hasValue(2);
        assertThat(LifecycleWidget.AFTER_WRITES).hasValue(2);
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
        assertThat(documentQuery.count(AccessSubject.system(), orderDesc)).isZero();
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
        Map<String, Object> row = documentQuery.get(AccessSubject.system(), orderDesc, id);
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

    @Test
    void genericWritesRejectOffsetBearingLocalDateTimesWithFieldErrors() {
        assertInvalidLocalDateTime(
                () -> catalogCommands.create(widgetDesc,
                        Map.of("label", "Clock", "observedAt", "2026-06-04T10:00+03:00"), admin),
                "observedAt");

        assertInvalidLocalDateTime(
                () -> documentCommands.create(orderDesc,
                        Map.of("startsAt", "2026-06-04T10:00Z"), admin),
                "startsAt");

        assertInvalidLocalDateTime(
                () -> documentCommands.create(orderDesc,
                        Map.of("date", "2026-06-04T10:00+03:00"), admin),
                "date");

        assertInvalidLocalDateTime(
                () -> documentCommands.create(orderDesc,
                        Map.of("items", List.of(Map.of(
                                "happensAt", "2026-06-04T10:00+03:00"))), admin),
                "items[0].happensAt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dryRunValidationRejectsOffsetBearingLocalDateTimes() {
        Map<String, Object> report = documentCommands.validate(orderDesc, null,
                Map.of("startsAt", "2026-06-04T10:00+03:00"), admin);

        assertThat(report.get("valid")).isEqualTo(false);
        Map<String, List<String>> fieldErrors =
                (Map<String, List<String>>) report.get("fieldErrors");
        assertThat(fieldErrors).containsKey("startsAt");
        assertThat(fieldErrors.get("startsAt")).allMatch(message -> message.contains("offset-free ISO"));
    }

    private static void assertInvalidLocalDateTime(Runnable write, String field) {
        assertThatThrownBy(write::run)
                .isInstanceOfSatisfying(ValidationException.class, error -> {
                    assertThat(error.fieldErrors()).containsKey(field);
                    assertThat(error.fieldErrors().get(field))
                            .allMatch(message -> message.contains("offset-free ISO"));
                });
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

}
