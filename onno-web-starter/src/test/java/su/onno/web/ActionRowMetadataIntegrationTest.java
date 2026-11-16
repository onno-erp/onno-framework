package su.onno.web;

import su.onno.service.AccessService;
import su.onno.service.BatchRunner;
import su.onno.service.CatalogQueryService;
import su.onno.service.CommandService;
import su.onno.service.DocumentQueryService;
import su.onno.service.KeysetPage;
import su.onno.service.WebProperties;
import su.onno.command.CommandRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;
import su.onno.service.ServiceException;
import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.annotations.Attribute;
import su.onno.command.CommandResult;
import su.onno.command.CommandSpec;
import su.onno.command.Commands;
import su.onno.command.RecordRow;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.repository.EnumerationPersistence;
import su.onno.types.Ref;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the actual list/menu/execution boundaries that supply {@link RecordRow} metadata. */
class ActionRowMetadataIntegrationTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
    private final CatalogQueryService catalogs = mock(CatalogQueryService.class);
    private final DocumentQueryService documents = mock(DocumentQueryService.class);
    private final AccessService access = mock(AccessService.class);

    private static Map<String, Object> storageRow(boolean approved) {
        return new LinkedHashMap<>(Map.of("_id", ID, "assigned_to", EMPLOYEE_ID,
                "assigned_to_display", "Alice", "approval_flag", approved,
                "review_status", EnumerationPersistence.resolveId(Status.class, Status.APPROVED)));
    }

    private static boolean eligible(RecordRow<? extends OrderFields> row) {
        return EMPLOYEE_ID.equals(row.get(OrderFields::getAssignedTo))
                && row.bool(OrderFields::getNeedsApproval)
                && row.enumValue(OrderFields::getReviewStatus, Status.class) == Status.APPROVED;
    }

    private static <E extends OrderFields> Commands<E> view(Class<E> entity) {
        return new Commands<>() {
            @Override
            public Class<E> entity() {
                return entity;
            }
            @Override
            public void define(CommandSpec<E> commands) {
                commands.command("approve")
                        .availableWhen(ActionRowMetadataIntegrationTest::eligible)
                        .enabledWhen(ActionRowMetadataIntegrationTest::eligible)
                        .title(row -> row.text(OrderFields::getAssignedTo))
                        .run(ctx -> CommandResult.ok());
            }
        };
    }

    @ParameterizedTest
    @CsvSource({"false,false,logical", "false,true,logical", "true,false,logical", "true,true,logical",
            "false,false,storage", "false,true,storage", "true,false,storage", "true,true,storage"})
    @SuppressWarnings("unchecked")
    void listActionsUseMetadataForCatalogsAndDocuments(
            boolean document, boolean ids, String representation) {
        Map<String, Object> source = storageRow(true);
        List<Map<String, Object>> rows = List.of(source);
        var catalog = scanner.scan(OrderCatalog.class);
        var doc = scanner.scanDocument(OrderDocument.class);
        Class<? extends OrderFields> entity = document ? OrderDocument.class : OrderCatalog.class;
        when(catalogs.require("orders")).thenReturn(catalog);
        when(documents.require("orders")).thenReturn(doc);
        when(catalogs.rowsByIds(null, catalog, List.of(ID))).thenReturn(rows);
        when(documents.rowsByIds(null, doc, List.of(ID))).thenReturn(rows);
        when(catalogs.keysetPage(isNull(), eq(catalog), isNull(), isNull(), isNull(), eq(100),
                isNull(), eq(true), isNull(), anyList(), anyList(), anyList(), anyList(), anyList(),
                anyList(), isNull(), isNull())).thenReturn(new KeysetPage(rows, null, false));
        when(documents.keysetPage(isNull(), eq(doc), isNull(), isNull(), isNull(), eq(100),
                isNull(), eq(true), isNull(), isNull(), isNull(), anyList(), anyList(), anyList(),
                anyList(), anyList(), anyList(), isNull(), isNull())).thenReturn(new KeysetPage(rows, null, false));
        @SuppressWarnings("rawtypes")
        List<Commands> views = List.of(view(entity));
        ListDataController controller = new ListDataController(catalogs, documents, access,
                new CommandRegistry(views));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("representation", representation);
        if (ids) request.addParameter("ids", ID.toString());
        Map<String, Object> response = document
                ? controller.documentPage("orders", 100, null, null, null, null, null, null, request, null)
                : controller.catalogPage("orders", 100, null, null, null, null, request, null);
        var result = ((List<Map<String, Object>>) response.get("rows")).getFirst();
        String prefix = representation.equals("storage") ? "_" : "";
        assertThat((Map<String, Object>) ((Map<?, ?>) result.get(prefix + "actions")).get("approve"))
                .containsEntry("visible", true).containsEntry("enabled", true).containsEntry("title", "Alice");
        // Row styling is the client's job now: the server never tints rows.
        assertThat(result).doesNotContainKey(prefix + "style");
        assertThat(source).containsEntry("assigned_to", EMPLOYEE_ID).doesNotContainKey("assignedTo");
    }

    @ParameterizedTest
    @CsvSource({"catalogs", "documents"})
    @SuppressWarnings("unchecked")
    void menuAndServerEnabledCheckUseTheSameMetadata(String kind) {
        AccessSubject subject = AccessSubject.user("alice", Set.of("OPERATOR"));
        var catalog = scanner.scan(OrderCatalog.class);
        var doc = scanner.scanDocument(OrderDocument.class);
        boolean document = kind.equals("documents");
        Class<? extends OrderFields> entity = document ? OrderDocument.class : OrderCatalog.class;
        when(catalogs.require("orders")).thenReturn(catalog);
        when(documents.require("orders")).thenReturn(doc);
        Map<String, Object> row = storageRow(true);
        when(catalogs.get(subject, catalog, ID)).thenReturn(row);
        when(documents.get(subject, doc, ID)).thenReturn(row);
        when(catalogs.inScope(subject, catalog, ID, AccessMode.WRITE)).thenReturn(true);
        when(documents.inScope(subject, doc, ID, AccessMode.WRITE)).thenReturn(true);
        BatchRunner batch = new BatchRunner(1);
        try {
            CommandController controller = new CommandController(new CommandService(catalogs, documents, access,
                    new CommandRegistry(List.of(view(entity))), new WebProperties(), batch));
            Map<String, Object> state = (Map<String, Object>) ((Map<?, ?>) controller
                    .list(kind, "orders", ID, subject).get("state")).get("approve");
            assertThat(state).containsEntry("visible", true).containsEntry("enabled", true)
                    .containsEntry("title", "Alice");
            assertThat(controller.run(kind, "orders", "approve", ID, Map.of(), subject)).isNotNull();
            row.put("approval_flag", false);
            assertThatThrownBy(() -> controller.run(kind, "orders", "approve", ID, Map.of(), subject))
                    .isInstanceOfSatisfying(ServiceException.class,
                            error -> assertThat(error.status()).isEqualTo(ServiceException.Status.CONFLICT));
            when(catalogs.inScope(subject, catalog, ID, AccessMode.WRITE)).thenReturn(false);
            when(documents.inScope(subject, doc, ID, AccessMode.WRITE)).thenReturn(false);
            assertThatThrownBy(() -> controller.run(kind, "orders", "approve", ID, Map.of(), subject))
                    .isInstanceOfSatisfying(ServiceException.class,
                            error -> assertThat(error.status()).isEqualTo(ServiceException.Status.NOT_FOUND));
        } finally {
            batch.destroy();
        }
    }

    @Test
    void detailStateReadsTypedFieldsAndResolvedLabels() {
        CommandSpec<OrderFields> spec = new CommandSpec<>();
        spec.command("approve")
                .availableWhen(ActionRowMetadataIntegrationTest::eligible)
                .enabledWhen(ActionRowMetadataIntegrationTest::eligible)
                .title(row -> row.text(OrderFields::getAssignedTo))
                .run(ctx -> CommandResult.ok());
        var state = CommandRegistry.recordState(spec.commands().getFirst(), storageRow(true),
                scanner.scan(OrderCatalog.class).attributes());
        assertThat(state.available()).isTrue();
        assertThat(state.enabled()).isTrue();
        assertThat(state.title()).isEqualTo("Alice");
    }

    interface OrderFields {
        Ref<Employee> getAssignedTo();
        Boolean getNeedsApproval();
        Status getReviewStatus();
    }

    @Catalog(name = "ActionRowOrders")
    static class OrderCatalog extends CatalogObject implements OrderFields {
        @Attribute
        private Ref<Employee> assignedTo;
        @Attribute(name = "approval_flag")
        private Boolean needsApproval;
        @Attribute
        private Status reviewStatus;
        public Ref<Employee> getAssignedTo() { return assignedTo; }
        public Boolean getNeedsApproval() { return needsApproval; }
        public Status getReviewStatus() { return reviewStatus; }
    }

    @Document(name = "ActionRowDocuments")
    static class OrderDocument extends DocumentObject implements OrderFields {
        @Attribute
        private Ref<Employee> assignedTo;
        @Attribute(name = "approval_flag")
        private Boolean needsApproval;
        @Attribute
        private Status reviewStatus;
        public Ref<Employee> getAssignedTo() { return assignedTo; }
        public Boolean getNeedsApproval() { return needsApproval; }
        public Status getReviewStatus() { return reviewStatus; }
    }

    @Catalog(name = "ActionRowEmployees")
    static class Employee extends CatalogObject {}
    enum Status { NEW, APPROVED }
}
