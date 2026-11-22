package su.onno.print;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.types.Ref;
import su.onno.types.RefResolver;
import su.onno.service.CatalogQueryService;
import su.onno.service.DocumentQueryService;
import su.onno.service.AccessService;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Print form downloads and template management. Record endpoints require read access to the entity
 * and the record (a record outside the caller's scope is a 404, like a missing one); a form's
 * {@code roles} gate it further. Template endpoints require
 * {@link PrintPermissions#MANAGE_TEMPLATES} ({@code print.templates.manage}); upload and reset are rejected in
 * {@code onno.web.read-only} mode.
 *
 * <pre>
 * GET    /api/print/{kind}/{name}/{id}                 forms available for the record
 * GET    /api/print/{kind}/{name}/{id}/{form}[?format]  the filled file (Content-Disposition: attachment)
 * POST   /api/print/batch/{kind}/{name}/{form}[?format] {"ids":[…]} → zip of the filled files
 * GET    /api/print/templates                          every form with its template status
 * GET    /api/print/templates/{kind}/{name}/{form}[?builtin=true]   the effective (or built-in) template
 * PUT    /api/print/templates/{kind}/{name}/{form}     multipart "file" → replace the template
 * DELETE /api/print/templates/{kind}/{name}/{form}     back to the built-in template
 * </pre>
 */
@RestController
@RequestMapping("/api/print")
public class PrintController {

    private final PrintService print;
    private final CatalogQueryService catalogs;
    private final DocumentQueryService documents;
    private final AccessService access;
    private final RefResolver refs;
    private final int maxBatch;
    private final boolean readOnly;

    public PrintController(PrintService print, CatalogQueryService catalogs, DocumentQueryService documents,
                           AccessService access, RefResolver refs) {
        this(print, catalogs, documents, access, refs, 200);
    }

    public PrintController(PrintService print, CatalogQueryService catalogs, DocumentQueryService documents,
                           AccessService access, RefResolver refs, int maxBatch) {
        this(print, catalogs, documents, access, refs, maxBatch, false);
    }

    /**
     * @param maxBatch    most records one batch request may include
     * @param readOnly    {@code onno.web.read-only}: template upload/reset are rejected, printing still works
     */
    public PrintController(PrintService print, CatalogQueryService catalogs, DocumentQueryService documents,
                           AccessService access, RefResolver refs, int maxBatch,
                           boolean readOnly) {
        this.readOnly = readOnly;
        this.print = print;
        this.catalogs = catalogs;
        this.documents = documents;
        this.access = access;
        this.refs = refs;
        this.maxBatch = maxBatch;
    }

    /** One available form; {@code formats} lists every {@code ?format=} value it accepts, default first. */
    public record PrintFormInfo(String key, String label, String format, List<String> formats, String url) {
    }

    /** Batch request body. */
    public record BatchRequest(List<UUID> ids) {
    }

    /** A form and the state of its template, for the template management widget. */
    public record TemplateInfo(String kind, String name, String key, String label, String format,
                               boolean uploaded, String fileName, String uploadedBy, Instant uploadedAt,
                               String url) {
    }

    /** Template management listing. */
    public record TemplateList(boolean uploadsEnabled, List<TemplateInfo> forms) {
    }

    @GetMapping("/{kind}/{name}/{id}")
    public List<PrintFormInfo> forms(@PathVariable String kind, @PathVariable String name, @PathVariable UUID id,
                                     AccessSubject subject) {
        Class<?> type = requireReadable(kind, name, id, subject);
        return print.formsFor(type).stream()
                .filter(form -> print.permits(form, subject))
                .map(form -> new PrintFormInfo(form.key(), form.label(),
                        print.format(form, print.defaultOutput(form)), formats(form),
                        "/api/print/" + kind + "/" + name + "/" + id + "/" + form.key()))
                .toList();
    }

    @GetMapping("/{kind}/{name}/{id}/{form}")
    public ResponseEntity<byte[]> render(@PathVariable String kind, @PathVariable String name,
                                         @PathVariable UUID id, @PathVariable String form,
                                         @RequestParam(required = false) String format,
                                         AccessSubject subject) {
        Class<?> type = requireReadable(kind, name, id, subject);
        PrintForm<?> printForm = requirePermittedForm(type, form, subject);
        PrintOutput output = output(printForm, format);
        return file(print.render(printForm, load(type, id).orElseThrow(() -> notFound(id)), output));
    }

    @PostMapping("/batch/{kind}/{name}/{form}")
    public ResponseEntity<byte[]> batch(@PathVariable String kind, @PathVariable String name,
                                        @PathVariable String form, @RequestParam(required = false) String format,
                                        @RequestBody BatchRequest request, AccessSubject subject) {
        List<UUID> ids = request == null || request.ids() == null ? List.of() : request.ids();
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No records selected");
        if (ids.size() > maxBatch) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Select at most " + maxBatch + " records to print at once");
        }
        Class<?> type = requireReadableEntity(kind, name, subject);
        PrintForm<?> printForm = requirePermittedForm(type, form, subject);
        PrintOutput output = output(printForm, format);
        // Records outside the caller's scope (or gone) are skipped, exactly as a list would hide them.
        List<Object> records = new ArrayList<>();
        for (UUID id : ids.stream().distinct().toList()) {
            if (inScope(kind, name, id, subject)) load(type, id).ifPresent(records::add);
        }
        if (records.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No printable records selected");
        return file(print.renderZip(printForm, records, output));
    }

    @GetMapping("/templates")
    public TemplateList templates(AccessSubject subject) {
        requireManager(subject);
        Map<String, PrintTemplateStore.StoredTemplate> uploaded = print.uploadedTemplates();
        List<TemplateInfo> forms = print.allForms().stream().map(form -> {
            String scope = print.scope(form);
            PrintTemplateStore.StoredTemplate stored = uploaded.get(scope + "/" + form.key());
            String[] parts = scope.split("/", 2);
            return new TemplateInfo(parts[0], parts[1], form.key(), form.label(), print.format(form),
                    stored != null, stored != null ? stored.fileName() : null,
                    stored != null ? stored.uploadedBy() : null, stored != null ? stored.uploadedAt() : null,
                    "/api/print/templates/" + scope + "/" + form.key());
        }).toList();
        return new TemplateList(print.templateUploadsEnabled() && !readOnly, forms);
    }

    @GetMapping("/templates/{kind}/{name}/{form}")
    public ResponseEntity<byte[]> template(@PathVariable String kind, @PathVariable String name,
                                           @PathVariable String form,
                                           @RequestParam(defaultValue = "false") boolean builtin,
                                           AccessSubject subject) {
        requireManager(subject);
        PrintForm<?> printForm = requireManagedForm(kind, name, form);
        PrintService.TemplateFile file = builtin ? print.builtinTemplate(printForm) : print.effectiveTemplate(printForm);
        String type = "docx".equals(print.format(printForm)) ? PrintService.DOCX : PrintService.XLSX;
        return file(new PrintResult(file.content(), type, file.fileName()));
    }

    @PutMapping("/templates/{kind}/{name}/{form}")
    public TemplateInfo upload(@PathVariable String kind, @PathVariable String name, @PathVariable String form,
                               @RequestPart("file") MultipartFile file, AccessSubject subject) {
        requireManager(subject);
        requireWritable();
        PrintForm<?> printForm = requireManagedForm(kind, name, form);
        if (!print.templateUploadsEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Uploaded print templates are disabled");
        }
        String uploadedBy = subject instanceof AccessSubject.User user ? user.username() : null;
        try {
            print.uploadTemplate(printForm, file.getOriginalFilename() == null ? "" : file.getOriginalFilename(),
                    file.getBytes(), uploadedBy);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return templates(subject).forms().stream()
                .filter(t -> t.key().equals(printForm.key()) && (t.kind() + "/" + t.name()).equals(print.scope(printForm)))
                .findFirst().orElseThrow();
    }

    @DeleteMapping("/templates/{kind}/{name}/{form}")
    public ResponseEntity<Void> reset(@PathVariable String kind, @PathVariable String name, @PathVariable String form,
                                      AccessSubject subject) {
        requireManager(subject);
        requireWritable();
        print.resetTemplate(requireManagedForm(kind, name, form));
        return ResponseEntity.noContent().build();
    }

    private List<String> formats(PrintForm<?> form) {
        List<String> formats = new ArrayList<>();
        formats.add(print.format(form, print.defaultOutput(form)));
        if (print.defaultOutput(form) == PrintOutput.PDF) {
            formats.add(print.format(form));
        } else if (print.pdfAvailable()) {
            formats.add("pdf");
        }
        return formats;
    }

    private PrintOutput output(PrintForm<?> form, String format) {
        if (format == null || format.isBlank()) return print.defaultOutput(form);
        if (format.equalsIgnoreCase("pdf")) {
            if (!print.pdfAvailable()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF output is not configured");
            }
            return PrintOutput.PDF;
        }
        if (format.equalsIgnoreCase(print.format(form))) return PrintOutput.SOURCE;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unsupported format '" + format + "' for print form " + form.key());
    }

    private PrintForm<?> requirePermittedForm(Class<?> type, String key, AccessSubject subject) {
        PrintForm<?> form = print.form(type, key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Print form not found: " + key));
        if (!print.permits(form, subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to print form " + key);
        }
        return form;
    }

    private PrintForm<?> requireManagedForm(String kind, String name, String key) {
        Class<?> type = switch (kind) {
            case "catalogs" -> catalogs.require(name).javaClass();
            case "documents" -> documents.require(name).javaClass();
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown kind: " + kind);
        };
        return print.form(type, key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Print form not found: " + key));
    }

    private void requireManager(AccessSubject subject) {
        if (!access.has(subject, PrintPermissions.MANAGE_TEMPLATES)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to print templates");
        }
    }

    private void requireWritable() {
        if (readOnly) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The application is in read-only mode");
        }
    }

    private static ResponseEntity<byte[]> file(PrintResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(result.fileName()).toString())
                .body(result.content());
    }

    /** An ASCII name goes out as a plain {@code filename}; anything else adds the RFC 5987 {@code filename*}. */
    private static ContentDisposition attachment(String fileName) {
        ContentDisposition.Builder builder = ContentDisposition.attachment();
        return (StandardCharsets.US_ASCII.newEncoder().canEncode(fileName)
                ? builder.filename(fileName)
                : builder.filename(fileName, StandardCharsets.UTF_8)).build();
    }

    private Class<?> requireReadable(String kind, String name, UUID id, AccessSubject subject) {
        Class<?> type = requireReadableEntity(kind, name, subject);
        if (!inScope(kind, name, id, subject)) throw notFound(id);
        return type;
    }

    private Class<?> requireReadableEntity(String kind, String name, AccessSubject subject) {
        switch (kind) {
            case "catalogs" -> {
                CatalogDescriptor desc = catalogs.require(name);
                access.requireRead(subject, desc);
                return desc.javaClass();
            }
            case "documents" -> {
                DocumentDescriptor desc = documents.require(name);
                access.requireRead(subject, desc);
                return desc.javaClass();
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown kind: " + kind);
        }
    }

    private boolean inScope(String kind, String name, UUID id, AccessSubject subject) {
        return "catalogs".equals(kind)
                ? catalogs.inScope(subject, catalogs.require(name), id, AccessMode.READ)
                : documents.inScope(subject, documents.require(name), id, AccessMode.READ);
    }

    private Optional<?> load(Class<?> type, UUID id) {
        return refs.resolve(Ref.of(type, id));
    }

    private static ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found: " + id);
    }
}
