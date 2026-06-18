package su.onno.importer;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.ui.DocumentCommandService;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentCsvImportServiceTest {

    private final DocumentCommandService commands = mock(DocumentCommandService.class);
    private final OnnoImportProperties properties = new OnnoImportProperties();
    private final DocumentCsvImportService service =
            new DocumentCsvImportService(mock(Jdbi.class), commands, properties);

    private final DocumentDescriptor invoice = new DocumentDescriptor(
            "Invoices", "Invoices", "doc_invoices", Object.class, 9, true, "INV-", "Sales",
            List.of("ADMIN"), List.of("ADMIN"), List.of(), List.of());

    private final DocumentDescriptor salesOrder = new DocumentDescriptor(
            "SalesOrders", "Sales Orders", "doc_sales_orders", Object.class, 9, true, "SO-", "Sales",
            List.of("ADMIN"), List.of("ADMIN"), List.of(),
            List.of(new TabularSectionDescriptor("lines", "lines", "doc_sales_orders_lines",
                    Object.class, List.of(attr("product"), attr("qty")))));

    @Test
    void importCreateOnly_mapsFieldsAndDelegatesToDocumentCommands() {
        byte[] csv = """
                Number,Date
                INV-001,2026-06-04T10:00
                """.getBytes(StandardCharsets.UTF_8);
        UUID id = UUID.randomUUID();
        when(commands.create(eq(invoice), any(), any())).thenReturn(Map.of("_id", id));

        ImportResult result = service.importDocuments(invoice, csv, null,
                Map.of("number", "Number", "date", "Date"),
                DocumentImportMode.CREATE_ONLY, false, false, null, null);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(commands).create(eq(invoice),
                eq(Map.of("number", "INV-001", "date", "2026-06-04T10:00")), any());
        verify(commands, never()).post(any(), any(), any());
    }

    @Test
    void postAfterImport_postsCreatedDocument() {
        byte[] csv = """
                Number,Date
                INV-001,2026-06-04T10:00
                """.getBytes(StandardCharsets.UTF_8);
        UUID id = UUID.randomUUID();
        when(commands.create(eq(invoice), any(), any())).thenReturn(Map.of("_id", id));

        ImportResult result = service.importDocuments(invoice, csv, null,
                Map.of("number", "Number", "date", "Date"),
                DocumentImportMode.CREATE_ONLY, false, true, null, null);

        assertThat(result.posted()).isEqualTo(1);
        verify(commands).post(invoice, id, null);
    }

    @Test
    void upsertByNumber_updatesExistingRows() {
        UUID existingId = UUID.randomUUID();
        Jdbi jdbi = jdbi();
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE doc_invoices (_id UUID PRIMARY KEY, _number VARCHAR(20), _deletion_mark BOOLEAN)");
            h.createUpdate("INSERT INTO doc_invoices (_id, _number, _deletion_mark) VALUES (:id, 'INV-001', false)")
                    .bind("id", existingId)
                    .execute();
        });
        DocumentCsvImportService upsertService = new DocumentCsvImportService(jdbi, commands, properties);
        byte[] csv = """
                Number,Date
                INV-001,2026-06-04T10:00
                """.getBytes(StandardCharsets.UTF_8);
        when(commands.update(eq(invoice), eq(existingId), any(), any())).thenReturn(Map.of("_id", existingId));

        ImportResult result = upsertService.importDocuments(invoice, csv, null,
                Map.of("number", "Number", "date", "Date"),
                DocumentImportMode.UPSERT_BY_NUMBER, false, false, null, null);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.created()).isZero();
        verify(commands).update(eq(invoice), eq(existingId),
                eq(Map.of("number", "INV-001", "date", "2026-06-04T10:00")), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupBy_collapsesRowsIntoTabularSectionLines() {
        byte[] csv = """
                Number,Date,Product,Qty
                SO-001,2026-06-04T10:00,Widget,2
                SO-001,2026-06-04T10:00,Gadget,3
                SO-002,2026-06-04T10:00,Widget,1
                """.getBytes(StandardCharsets.UTF_8);
        when(commands.create(eq(salesOrder), any(), any())).thenReturn(Map.of("_id", UUID.randomUUID()));

        ImportResult result = service.importDocuments(salesOrder, csv, null,
                Map.of("number", "Number", "date", "Date",
                        "lines.product", "Product", "lines.qty", "Qty"),
                DocumentImportMode.CREATE_ONLY, false, false, "Number", null);

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isZero();

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(commands, times(2)).create(eq(salesOrder), body.capture(), any());

        Map<String, Object> first = body.getAllValues().get(0);
        assertThat(first.get("number")).isEqualTo("SO-001");
        assertThat((List<Map<String, Object>>) first.get("lines")).containsExactly(
                Map.of("product", "Widget", "qty", "2"),
                Map.of("product", "Gadget", "qty", "3"));

        Map<String, Object> second = body.getAllValues().get(1);
        assertThat(second.get("number")).isEqualTo("SO-002");
        assertThat((List<Map<String, Object>>) second.get("lines")).containsExactly(
                Map.of("product", "Widget", "qty", "1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutGroupBy_eachRowIsOneDocumentWithASingleLine() {
        byte[] csv = """
                Number,Date,Product,Qty
                SO-001,2026-06-04T10:00,Widget,2
                """.getBytes(StandardCharsets.UTF_8);
        when(commands.create(eq(salesOrder), any(), any())).thenReturn(Map.of("_id", UUID.randomUUID()));

        ImportResult result = service.importDocuments(salesOrder, csv, null,
                Map.of("number", "Number", "date", "Date",
                        "lines.product", "Product", "lines.qty", "Qty"),
                DocumentImportMode.CREATE_ONLY, false, false, null, null);

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(commands).create(eq(salesOrder), body.capture(), any());
        assertThat((List<Map<String, Object>>) body.getValue().get("lines")).containsExactly(
                Map.of("product", "Widget", "qty", "2"));
    }

    private static AttributeDescriptor attr(String name) {
        return new AttributeDescriptor(name, name, name, String.class, 255, false, false, null,
                0, 0, true, true, true, 0, null, null, null,
                AttributeDescriptor.Constraints.NONE, false);
    }

    private static Jdbi jdbi() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return Jdbi.create(ds);
    }
}
