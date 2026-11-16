package su.onno.print;

import su.onno.access.AccessSubject;
import su.onno.annotations.EnumLabel;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.types.Ref;
import su.onno.types.RefResolver;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrintServiceTest {

    static class Customer extends CatalogObject {
    }

    enum Status {
        @EnumLabel("Shipped") SHIPPED,
        DRAFT
    }

    static class Invoice extends DocumentObject {
        Ref<Customer> customer;
        Status status;
    }

    static final class InvoiceForm extends PrintForm<Invoice> {
        private final String[] roles;

        InvoiceForm(String key, String... roles) {
            super(key, Invoice.class);
            this.roles = roles;
        }

        @Override
        protected void configure(PrintFormSpec spec) {
            spec.label("Invoice").template("memory:invoice.docx").roles(roles);
        }

        @Override
        protected void fill(Invoice invoice, TemplateModel model) {
            model.text("customer", invoice.customer).text("status", invoice.status).text("number", invoice.getNumber());
        }
    }

    static final Map<Class<?>, String> SCOPES = Map.of(Invoice.class, "documents/Invoices", Customer.class, "catalogs/Customers");

    private final Customer acme = customer();
    private final RefResolver refs = new RefResolver() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> resolve(Ref<T> ref) {
            return ref.id().equals(acme.getId()) ? Optional.of((T) acme) : Optional.empty();
        }
    };

    @Test
    void rendersDocxWithDisplayTextAndDefaultFileName() throws IOException {
        PrintService service = service(List.of(new InvoiceForm("invoice")));
        Invoice invoice = new Invoice();
        invoice.setNumber("INV-42");
        invoice.customer = Ref.of(Customer.class, acme.getId());
        invoice.status = Status.SHIPPED;

        PrintResult result = service.render(service.form(Invoice.class, "invoice").orElseThrow(), invoice);

        assertThat(result.fileName()).isEqualTo("invoice-INV-42.docx");
        assertThat(result.contentType()).isEqualTo(PrintService.DOCX);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result.content()))) {
            assertThat(doc.getParagraphs().get(0).getText()).isEqualTo("Acme Ltd / Shipped / INV-42");
        }
    }

    @Test
    void enumWithoutLabelRendersItsName() {
        assertThat(service(List.of()).text(Status.DRAFT)).isEqualTo("DRAFT");
    }

    @Test
    void rolesRestrictTheFormButAdminAlwaysPasses() {
        PrintService service = service(List.of(new InvoiceForm("invoice", "manager")));
        PrintForm<?> form = service.form(Invoice.class, "invoice").orElseThrow();

        assertThat(service.permits(form, AccessSubject.user("clerk", Set.of("CLERK")))).isFalse();
        assertThat(service.permits(form, AccessSubject.user("boss", Set.of("ROLE_MANAGER")))).isTrue();
        assertThat(service.permits(form, AccessSubject.user("root", Set.of("ADMIN")))).isTrue();
    }

    @Test
    void rejectsDuplicateKeysUnregisteredTypesMissingTemplatesAndBadKeys() {
        assertThatThrownBy(() -> service(List.of(new InvoiceForm("invoice"), new InvoiceForm("invoice"))))
                .hasMessageContaining("Duplicate print form 'invoice'");
        assertThatThrownBy(() -> new PrintService(List.of(new InvoiceForm("invoice")), Map.of(), refs, loader()))
                .hasMessageContaining("not a registered @Catalog or @Document");
        PrintForm<Invoice> missing = new PrintForm<>("nothing", Invoice.class) {
            @Override
            protected void fill(Invoice record, TemplateModel model) {
            }
        };
        assertThatThrownBy(() -> service(List.of(missing)))
                .hasMessageContaining("classpath:/print/nothing.docx or classpath:/print/nothing.xlsx");
        assertThatThrownBy(() -> new InvoiceForm("Bad Key")).isInstanceOf(IllegalArgumentException.class);
    }

    private PrintService service(List<PrintForm<?>> forms) {
        return new PrintService(forms, SCOPES, refs, loader());
    }

    /** Serves {@code memory:invoice.docx} from bytes built with POI. */
    private static DefaultResourceLoader loader() {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        loader.addProtocolResolver((location, l) -> {
            if (!location.equals("memory:invoice.docx")) return null;
            return named(docx("{{customer}} / {{status}} / {{number}}"), "invoice.docx");
        });
        return loader;
    }

    private static Resource named(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static byte[] docx(String text) {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Customer customer() {
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setCode("C-1");
        c.setDescription("Acme Ltd");
        return c;
    }
}
