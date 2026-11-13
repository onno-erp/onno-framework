package su.onno.print;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.DocumentDescriptor;
import su.onno.model.DocumentObject;
import su.onno.types.Ref;
import su.onno.types.RefResolver;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.RecordActionContributor;
import su.onno.ui.UiAccessService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrintControllerTest {

    static class Invoice extends DocumentObject {
    }

    static final class InvoiceForm extends PrintForm<Invoice> {
        InvoiceForm() {
            super("invoice", Invoice.class);
        }

        @Override
        protected void configure(PrintFormSpec spec) {
            spec.label("Invoice").template("classpath:/print-test/invoice.docx").roles("MANAGER");
        }

        @Override
        protected void fill(Invoice invoice, TemplateModel model) {
            model.text("number", invoice.getNumber());
        }
    }

    private final UUID id = UUID.randomUUID();
    private final DocumentQueryService documents = mock(DocumentQueryService.class);
    private final DocumentDescriptor desc = mock(DocumentDescriptor.class);
    private final RefResolver refs = mock(RefResolver.class);
    private final AccessSubject manager = AccessSubject.user("m", Set.of("MANAGER"));
    private PrintController controller;
    private PrintService service;

    @BeforeEach
    void setUp() {
        ResourceLoader resources = new org.springframework.core.io.DefaultResourceLoader();
        service = new PrintService(List.of(new InvoiceForm()), Map.of(Invoice.class, "documents/Invoices"), refs, resources);
        controller = new PrintController(service, mock(CatalogQueryService.class), documents,
                mock(UiAccessService.class), refs);
        when(documents.require("Invoices")).thenReturn(desc);
        doReturn(Invoice.class).when(desc).javaClass();
        Invoice invoice = new Invoice();
        invoice.setNumber("INV-1");
        doReturn(Optional.of(invoice)).when(refs).resolve(any(Ref.class));
    }

    @Test
    void downloadsTheFilledFormAsAttachment() {
        when(documents.inScope(manager, desc, id, AccessMode.READ)).thenReturn(true);

        ResponseEntity<byte[]> response = controller.render("documents", "Invoices", id, "invoice", null, manager);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment").contains("invoice-INV-1.docx");
        assertThat(response.getHeaders().getContentType()).hasToString(PrintService.DOCX);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void recordOutsideScopeIsNotFound() {
        when(documents.inScope(eq(manager), eq(desc), eq(id), eq(AccessMode.READ))).thenReturn(false);

        assertThatThrownBy(() -> controller.render("documents", "Invoices", id, "invoice", null, manager))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void formRolesAreEnforcedAndFilterTheMenu() {
        AccessSubject clerk = AccessSubject.user("c", Set.of("CLERK"));
        when(documents.inScope(any(), eq(desc), eq(id), eq(AccessMode.READ))).thenReturn(true);

        assertThatThrownBy(() -> controller.render("documents", "Invoices", id, "invoice", null, clerk))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(controller.forms("documents", "Invoices", id, clerk)).isEmpty();
        assertThat(controller.forms("documents", "Invoices", id, manager))
                .extracting(PrintController.PrintFormInfo::key).containsExactly("invoice");

        PrintRecordActions actions = new PrintRecordActions(service);
        var request = new RecordActionContributor.RecordActionRequest("documents", "Invoices", Invoice.class, id, manager);
        assertThat(actions.actions(request)).singleElement().satisfies(a -> {
            assertThat(a.key()).isEqualTo("print-invoice");
            assertThat(a.icon()).isEqualTo("printer");
            assertThat(a.url()).isEqualTo("onno://download/api/print/documents/Invoices/" + id + "/invoice");
        });
        assertThat(actions.actions(new RecordActionContributor.RecordActionRequest(
                "documents", "Invoices", Invoice.class, id, clerk))).isEmpty();
    }

    @Test
    void batchZipsOnlyRecordsInScopeAndEnforcesLimits() throws java.io.IOException {
        UUID hidden = UUID.randomUUID();
        when(documents.inScope(manager, desc, id, AccessMode.READ)).thenReturn(true);
        when(documents.inScope(manager, desc, hidden, AccessMode.READ)).thenReturn(false);

        ResponseEntity<byte[]> zip = controller.batch("documents", "Invoices", "invoice", null,
                new PrintController.BatchRequest(List.of(id, hidden, id)), manager);

        assertThat(zip.getHeaders().getContentType()).hasToString(PrintService.ZIP);
        int entries = 0;
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip.getBody()))) {
            while (in.getNextEntry() != null) entries++;
        }
        assertThat(entries).isEqualTo(1);

        assertThatThrownBy(() -> controller.batch("documents", "Invoices", "invoice", null,
                new PrintController.BatchRequest(List.of(hidden)), manager))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        PrintController small = new PrintController(service, mock(CatalogQueryService.class), documents,
                mock(UiAccessService.class), refs, List.of("ADMIN"), 1);
        assertThatThrownBy(() -> small.batch("documents", "Invoices", "invoice", null,
                new PrintController.BatchRequest(List.of(id, hidden)), manager))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unsupportedOrUnconfiguredFormatIsBadRequest() {
        when(documents.inScope(manager, desc, id, AccessMode.READ)).thenReturn(true);

        for (String format : List.of("pdf", "xlsx")) {
            assertThatThrownBy(() -> controller.render("documents", "Invoices", id, "invoice", format, manager))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(controller.render("documents", "Invoices", id, "invoice", "DOCX", manager).getBody()).isNotEmpty();
        assertThat(controller.forms("documents", "Invoices", id, manager).get(0).formats()).containsExactly("docx");
    }

    @Test
    void templateManagementRequiresManageRole() {
        assertThatThrownBy(() -> controller.templates(manager))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        AccessSubject admin = AccessSubject.user("root", Set.of("ADMIN"));
        PrintController.TemplateList list = controller.templates(admin);
        assertThat(list.uploadsEnabled()).isFalse();
        assertThat(list.forms()).singleElement().satisfies(t -> {
            assertThat(t.kind() + "/" + t.name() + "/" + t.key()).isEqualTo("documents/Invoices/invoice");
            assertThat(t.uploaded()).isFalse();
            assertThat(t.url()).isEqualTo("/api/print/templates/documents/Invoices/invoice");
        });
        ResponseEntity<byte[]> template = controller.template("documents", "Invoices", "invoice", false, admin);
        assertThat(template.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("invoice.docx");

        PrintController managers = new PrintController(service, mock(CatalogQueryService.class), documents,
                mock(UiAccessService.class), refs, List.of("ROLE_MANAGER"), 10);
        assertThat(managers.templates(manager).forms()).hasSize(1);
    }

    @Test
    void readOnlyModeRejectsTemplateChangesButStillPrints() {
        PrintController readOnly = new PrintController(service, mock(CatalogQueryService.class), documents,
                mock(UiAccessService.class), refs, List.of("ADMIN"), 10, true);
        AccessSubject admin = AccessSubject.user("root", Set.of("ADMIN"));
        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile("file", "invoice.docx", null, new byte[] {1});

        assertThatThrownBy(() -> readOnly.upload("documents", "Invoices", "invoice", file, admin))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> readOnly.reset("documents", "Invoices", "invoice", admin))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(readOnly.templates(admin).uploadsEnabled()).isFalse();

        when(documents.inScope(manager, desc, id, AccessMode.READ)).thenReturn(true);
        assertThat(readOnly.render("documents", "Invoices", id, "invoice", null, manager).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
