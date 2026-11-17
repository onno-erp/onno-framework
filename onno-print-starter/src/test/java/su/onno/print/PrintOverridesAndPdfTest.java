package su.onno.print;

import su.onno.model.DocumentObject;
import su.onno.types.Ref;
import su.onno.types.RefResolver;

import com.sun.net.httpserver.HttpServer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrintOverridesAndPdfTest {

    static class Invoice extends DocumentObject {
    }

    static class InvoiceForm extends PrintForm<Invoice> {
        private final boolean pdf;

        InvoiceForm(boolean pdf) {
            super("invoice", Invoice.class);
            this.pdf = pdf;
        }

        @Override
        protected void configure(PrintFormSpec spec) {
            spec.template("classpath:/print-test/invoice.docx");
            if (pdf) spec.pdf();
        }

        @Override
        protected void fill(Invoice invoice, TemplateModel model) {
            model.text("number", invoice.getNumber());
        }
    }

    private static final Map<Class<?>, String> SCOPES = Map.of(Invoice.class, "documents/Invoices");
    private final RefResolver refs = new RefResolver() {
        @Override
        public <T> Optional<T> resolve(Ref<T> ref) {
            return Optional.empty();
        }
    };
    private PrintTemplateStore store;

    @BeforeEach
    void setUp() {
        Jdbi jdbi = Jdbi.create("jdbc:h2:mem:print-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        store = new PrintTemplateStore(jdbi);
    }

    @Test
    void uploadedTemplateOverridesBuiltInUntilReset() throws IOException {
        PrintService service = service(new InvoiceForm(false), null);
        PrintForm<?> form = service.allForms().get(0);

        service.uploadTemplate(form, "C:\\Users\\me\\custom.docx", docx("Custom {{number}}"), "admin");

        assertThat(text(service.render(form, invoice("INV-1")).content())).isEqualTo("Custom INV-1");
        PrintService.TemplateFile effective = service.effectiveTemplate(form);
        assertThat(effective.uploaded()).isTrue();
        assertThat(effective.fileName()).isEqualTo("custom.docx");
        assertThat(effective.uploadedBy()).isEqualTo("admin");
        assertThat(service.uploadedTemplates()).containsKey("documents/Invoices/invoice");
        assertThat(service.builtinTemplate(form).uploaded()).isFalse();

        assertThat(service.resetTemplate(form)).isTrue();
        assertThat(text(service.render(form, invoice("INV-1")).content())).isEqualTo("Invoice INV-1");
        assertThat(service.resetTemplate(form)).isFalse();
    }

    @Test
    void rejectsUploadsOfWrongFormatCorruptOrOversizedFiles() {
        PrintService service = new PrintService(List.of(new InvoiceForm(false)), SCOPES, refs,
                new DefaultResourceLoader(), store, null, 2_000);
        PrintForm<?> form = service.allForms().get(0);

        assertThatThrownBy(() -> service.uploadTemplate(form, "x.xlsx", new byte[] {1}, "a"))
                .hasMessageContaining("must be a .docx");
        assertThatThrownBy(() -> service.uploadTemplate(form, "x.docx", "not a zip".getBytes(), "a"))
                .hasMessageContaining("Not a readable .docx");
        assertThatThrownBy(() -> service.uploadTemplate(form, "x.docx", new byte[5_000], "a"))
                .hasMessageContaining("exceeds 2000 bytes");
        assertThat(service.effectiveTemplate(form).uploaded()).isFalse();
    }

    @Test
    void pdfFormRequiresConverterAndConvertsFilledDocument() throws IOException {
        assertThatThrownBy(() -> service(new InvoiceForm(true), null))
                .hasMessageContaining("declares pdf() but no PdfConverter");

        List<String> formats = new ArrayList<>();
        PdfConverter fake = (document, format) -> {
            formats.add(format);
            try {
                return ("%PDF-fake " + text(document)).getBytes(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        PrintService service = service(new InvoiceForm(true), fake);
        PrintForm<?> form = service.allForms().get(0);

        PrintResult pdf = service.render(form, invoice("INV-2"));
        assertThat(pdf.fileName()).isEqualTo("invoice-INV-2.pdf");
        assertThat(pdf.contentType()).isEqualTo(PrintService.PDF);
        assertThat(new String(pdf.content(), StandardCharsets.UTF_8)).isEqualTo("%PDF-fake Invoice INV-2");
        assertThat(formats).containsExactly("docx");

        PrintResult source = service.render(form, invoice("INV-2"), PrintOutput.SOURCE);
        assertThat(source.fileName()).isEqualTo("invoice-INV-2.docx");
    }

    @Test
    void zipsBatchesWithUniqueNames() throws IOException {
        PrintService service = service(new InvoiceForm(false), null);
        PrintForm<?> form = service.allForms().get(0);

        PrintResult zip = service.renderZip(form,
                List.of(invoice("A"), invoice("B"), invoice("A")), PrintOutput.SOURCE);

        assertThat(zip.fileName()).isEqualTo("invoice.zip");
        assertThat(zip.contentType()).isEqualTo(PrintService.ZIP);
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.content()))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
                if (e.getName().equals("invoice-B.docx")) assertThat(text(in.readAllBytes())).isEqualTo("Invoice B");
            }
        }
        assertThat(names).containsExactly("invoice-A.docx", "invoice-B.docx", "invoice-A (2).docx");
    }

    @Test
    void recordActionsAndSelectionDownloadsOfferPdfVariant() {
        PrintService service = service(new InvoiceForm(false), (d, f) -> d);
        UUID id = UUID.randomUUID();
        var subject = su.onno.access.AccessSubject.user("u", java.util.Set.of("X"));

        var actions = new PrintRecordActions(service).actions(
                new su.onno.web.RecordActionContributor.RecordActionRequest("documents", "Invoices", Invoice.class, id, subject));
        assertThat(actions).extracting(a -> a.key() + " " + a.url()).containsExactly(
                "print-invoice onno://download/api/print/documents/Invoices/" + id + "/invoice",
                "print-invoice-pdf onno://download/api/print/documents/Invoices/" + id + "/invoice?format=pdf");

        var downloads = new PrintSelectionDownloads(service).downloads(
                new su.onno.web.SelectionDownloadContributor.SelectionRequest("documents", "Invoices", Invoice.class, subject));
        assertThat(downloads).extracting(d -> d.key() + " " + d.url()).containsExactly(
                "print-invoice /api/print/batch/documents/Invoices/invoice",
                "print-invoice-pdf /api/print/batch/documents/Invoices/invoice?format=pdf");
    }

    @Test
    void gotenbergConverterPostsMultipartFile() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, pdf.length);
            exchange.getResponseBody().write(pdf);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            byte[] pdf = new GotenbergPdfConverter(base, Duration.ofSeconds(5)).toPdf("DOCX".getBytes(), "docx");

            assertThat(new String(pdf, StandardCharsets.UTF_8)).isEqualTo("%PDF-1.7");
            assertThat(path.get()).isEqualTo("/forms/libreoffice/convert");
            assertThat(body.get()).contains("name=\"files\"; filename=\"document.docx\"").contains("DOCX");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gotenbergErrorsSurface() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] msg = "bad file".getBytes();
            exchange.sendResponseHeaders(400, msg.length);
            exchange.getResponseBody().write(msg);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> new GotenbergPdfConverter(base, Duration.ofSeconds(5)).toPdf(new byte[1], "xlsx"))
                    .hasMessageContaining("HTTP 400").hasMessageContaining("bad file");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void libreOfficeConverterRunsCommandInIsolatedDirectory(@TempDir Path tmp) throws IOException {
        // A stand-in soffice: writes "<outdir>/document.pdf" from the input, like the real one.
        Path soffice = tmp.resolve("soffice");
        Files.writeString(soffice, """
                #!/bin/sh
                for a; do last="$a"; done
                while [ "$#" -gt 0 ]; do [ "$1" = "--outdir" ] && out="$2"; shift; done
                { printf '%%PDF from '; cat "$last"; } > "$out/document.pdf"
                """);
        Files.setPosixFilePermissions(soffice, PosixFilePermissions.fromString("rwx------"));

        byte[] pdf = new LibreOfficePdfConverter(soffice.toString(), Duration.ofSeconds(120)).toPdf("XLSX".getBytes(), "xlsx");

        assertThat(new String(pdf, StandardCharsets.UTF_8)).isEqualTo("%PDF from XLSX");

        Path failing = tmp.resolve("failing");
        Files.writeString(failing, "#!/bin/sh\necho boom\nexit 3\n");
        Files.setPosixFilePermissions(failing, PosixFilePermissions.fromString("rwx------"));
        assertThatThrownBy(() -> new LibreOfficePdfConverter(failing.toString(), Duration.ofSeconds(120)).toPdf(new byte[1], "docx"))
                .hasMessageContaining("exit 3").hasMessageContaining("boom");
    }

    private PrintService service(PrintForm<?> form, PdfConverter pdf) {
        return new PrintService(List.of(form), SCOPES, refs, new DefaultResourceLoader(), store, pdf, 10_000_000);
    }

    private static Invoice invoice(String number) {
        Invoice invoice = new Invoice();
        invoice.setNumber(number);
        return invoice;
    }

    private static String text(byte[] docx) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            return doc.getParagraphs().get(0).getText();
        }
    }

    private static byte[] docx(String text) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }
}
