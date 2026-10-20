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
        service = new PrintService(List.of(new InvoiceForm()), Set.of(Invoice.class), refs, resources);
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

        ResponseEntity<byte[]> response = controller.render("documents", "Invoices", id, "invoice", manager);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment").contains("invoice-INV-1.docx");
        assertThat(response.getHeaders().getContentType()).hasToString(PrintService.DOCX);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void recordOutsideScopeIsNotFound() {
        when(documents.inScope(eq(manager), eq(desc), eq(id), eq(AccessMode.READ))).thenReturn(false);

        assertThatThrownBy(() -> controller.render("documents", "Invoices", id, "invoice", manager))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void formRolesAreEnforcedAndFilterTheMenu() {
        AccessSubject clerk = AccessSubject.user("c", Set.of("CLERK"));
        when(documents.inScope(any(), eq(desc), eq(id), eq(AccessMode.READ))).thenReturn(true);

        assertThatThrownBy(() -> controller.render("documents", "Invoices", id, "invoice", clerk))
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
}
