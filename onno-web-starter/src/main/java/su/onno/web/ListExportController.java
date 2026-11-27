package su.onno.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import su.onno.access.AccessSubject;
import su.onno.service.CatalogQueryService;
import su.onno.service.DocumentQueryService;
import su.onno.service.RegisterQueryService;
import su.onno.service.InformationRegisterQueryService;
import su.onno.service.AccessService;
import su.onno.service.EntityListExport;
import su.onno.service.WebProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** Downloads the same authorized row windows the list displays, from the first matching row. */
@RestController
@RequestMapping("/api/list/export")
public class ListExportController {
    private final CatalogQueryService catalogs;
    private final DocumentQueryService documents;
    private final RegisterQueryService registers;
    private final InformationRegisterQueryService informationRegisters;
    private final AccessService access;
    private final UiMessages messages;
    private final WebProperties properties;
    private final ListExportJournal journal;
    private final TransactionTemplate snapshot;

    ListExportController(CatalogQueryService catalogs, DocumentQueryService documents,
                         RegisterQueryService registers, InformationRegisterQueryService informationRegisters,
                         AccessService access, UiMessages messages, WebProperties properties,
                         ListExportJournal journal, PlatformTransactionManager transactionManager) {
        this.catalogs = catalogs;
        this.documents = documents;
        this.registers = registers;
        this.informationRegisters = informationRegisters;
        this.access = access;
        this.messages = messages;
        this.properties = properties;
        this.journal = journal;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public record ExportRequest(String format, String view, List<EntityListExport.Column> columns,
                                Map<String, List<String>> query, String groupBy, String granularity) {}

    @PostMapping("/{kind}/{name}")
    public void export(@PathVariable String kind, @PathVariable String name, @RequestBody ExportRequest request,
                       AccessSubject.User user, HttpServletResponse response) throws IOException {
        var settings = properties.getList();
        if (settings.getExportRoles().stream().noneMatch(user.roles()::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "List export is not permitted for this role");
        }
        if (request.format() == null) throw badRequest("Choose csv or xlsx");
        String contentType = switch (request.format()) {
            case "csv" -> "text/csv; charset=UTF-8";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> throw badRequest("Choose csv or xlsx");
        };
        if (request.columns() == null || request.columns().isEmpty() || request.columns().size() > 16_384
                || request.columns().stream().anyMatch(c -> c == null || c.key() == null || c.key().isBlank()
                        || c.label() == null || c.label().length() > 512)) {
            throw badRequest("Invalid export columns");
        }
        var params = request.query() == null ? Map.<String, List<String>>of() : request.query();
        if (params.values().stream().anyMatch(values -> values == null || values.stream().anyMatch(v -> v == null))) {
            throw badRequest("Invalid list query");
        }
        var terms = terms(params);
        var grouping = request.groupBy() == null || request.groupBy().isBlank() ? null
                : new EntityListExport.Grouping(request.groupBy(), request.granularity());
        var columns = request.columns();
        int cap = settings.getExportRowLimit();
        String view = request.view();
        if (view != null && !("registers".equals(kind) && ("movements".equals(view) || "balance".equals(view)))) {
            throw badRequest("Unknown export view");
        }
        if (grouping != null && !("catalogs".equals(kind) || "documents".equals(kind))) {
            throw badRequest("This list does not support grouping");
        }
        var data = switch (kind) {
            case "catalogs" -> EntityListExport.catalog(catalogs, access, user, name, terms, columns, cap, grouping);
            case "documents" -> EntityListExport.document(documents, access, user, name, terms, columns, cap, grouping);
            case "registers" -> {
                if (view == null) throw badRequest("Choose movements or balance");
                yield EntityListExport.register(registers, access, user, Map.of("RECEIPT", messages.get("register.receipt"), "EXPENSE", messages.get("register.expense")), name, terms, columns, cap,
                        "movements".equals(view), first(params, "asOf"));
            }
            case "information-registers" -> EntityListExport.informationRegister(informationRegisters, access, user,
                    name, columns, cap, dateTime(terms.from()), dateTime(terms.to()), first(params, "where"));
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown list type");
        };

        // Finish file generation before committing the response. A late cap failure (including
        // page N) must still yield a normal error instead of a truncated CSV or invalid XLSX.
        var file = Files.createTempFile("onno-list-export-", ".tmp");
        try {
            snapshot.executeWithoutResult(transaction -> {
                try (var output = Files.newOutputStream(file)) {
                    if ("csv".equals(request.format())) ListExportWriter.writeCsv(output, data.headings(), data.rows());
                    else ListExportWriter.writeXlsx(output, data.headings(), data.rows());
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            });
            journal.prepared(user.username(), kind, name, view, request.format());
            String filename = name.replaceAll("[\\p{Cntrl}/\\\\]", "_")
                    + (view == null ? "" : "-" + view) + "." + request.format();
            response.setContentType(contentType);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(filename, StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(Files.size(file));
            var output = response.getOutputStream();
            Files.copy(file, output);
            output.flush();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static EntityListExport.Query terms(Map<String, List<String>> params) {
        String dir = first(params, "dir");
        return new EntityListExport.Query(first(params, "sort"), dir == null || dir.isBlank() || dir.equalsIgnoreCase("desc"),
                first(params, "q"), first(params, "from"), first(params, "to"), params.getOrDefault("eq", List.of()),
                params.getOrDefault("in", List.of()), params.getOrDefault("like", List.of()), params.getOrDefault("prefix", List.of()),
                params.getOrDefault("ge", List.of()), params.getOrDefault("le", List.of()), first(params, "where"));
    }

    private static String first(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static LocalDateTime dateTime(String value) {
        if (value == null) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException invalid) {
            throw badRequest("Invalid period boundary");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
