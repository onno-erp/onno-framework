package su.onno.print;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.types.Ref;
import su.onno.types.RefResolver;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.UiAccessService;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Print form downloads. Both endpoints require read access to the entity and the record (a record
 * outside the caller's scope is a 404, like a missing one); a form's {@code roles} gate it further.
 *
 * <pre>
 * GET /api/print/{kind}/{name}/{id}          forms available for the record
 * GET /api/print/{kind}/{name}/{id}/{form}   the filled file (Content-Disposition: attachment)
 * </pre>
 */
@RestController
@RequestMapping("/api/print")
public class PrintController {

    private final PrintService print;
    private final CatalogQueryService catalogs;
    private final DocumentQueryService documents;
    private final UiAccessService access;
    private final RefResolver refs;

    public PrintController(PrintService print, CatalogQueryService catalogs, DocumentQueryService documents,
                           UiAccessService access, RefResolver refs) {
        this.print = print;
        this.catalogs = catalogs;
        this.documents = documents;
        this.access = access;
        this.refs = refs;
    }

    /** One available form. */
    public record PrintFormInfo(String key, String label, String format, String url) {
    }

    @GetMapping("/{kind}/{name}/{id}")
    public List<PrintFormInfo> forms(@PathVariable String kind, @PathVariable String name, @PathVariable UUID id,
                                     AccessSubject subject) {
        Class<?> type = requireReadable(kind, name, id, subject);
        return print.formsFor(type).stream()
                .filter(form -> print.permits(form, subject))
                .map(form -> new PrintFormInfo(form.key(), form.label(), print.format(form),
                        "/api/print/" + kind + "/" + name + "/" + id + "/" + form.key()))
                .toList();
    }

    @GetMapping("/{kind}/{name}/{id}/{form}")
    public ResponseEntity<byte[]> render(@PathVariable String kind, @PathVariable String name,
                                         @PathVariable UUID id, @PathVariable String form,
                                         AccessSubject subject) {
        Class<?> type = requireReadable(kind, name, id, subject);
        PrintForm<?> printForm = print.form(type, form)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Print form not found: " + form));
        if (!print.permits(printForm, subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to print form " + form);
        }
        Object record = load(type, id);
        PrintResult result = print.render(printForm, record);
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
        switch (kind) {
            case "catalogs" -> {
                CatalogDescriptor desc = catalogs.require(name);
                access.requireRead(subject, desc);
                if (!catalogs.inScope(subject, desc, id, AccessMode.READ)) throw notFound(id);
                return desc.javaClass();
            }
            case "documents" -> {
                DocumentDescriptor desc = documents.require(name);
                access.requireRead(subject, desc);
                if (!documents.inScope(subject, desc, id, AccessMode.READ)) throw notFound(id);
                return desc.javaClass();
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown kind: " + kind);
        }
    }

    private Object load(Class<?> type, UUID id) {
        Optional<?> record = refs.resolve(Ref.of(type, id));
        return record.orElseThrow(() -> notFound(id));
    }

    private static ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found: " + id);
    }
}
