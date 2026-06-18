package su.onno.importer;

import su.onno.metadata.CatalogDescriptor;
import su.onno.ui.CatalogCommandService;

import org.apache.commons.csv.CSVParser;
import org.jdbi.v3.core.Jdbi;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parses CSV files and applies rows to a catalog through {@link CatalogCommandService}.
 */
public class CatalogCsvImportService {

    private final Jdbi jdbi;
    private final CatalogCommandService catalogCommands;
    private final OnnoImportProperties properties;

    public CatalogCsvImportService(Jdbi jdbi, CatalogCommandService catalogCommands,
                                   OnnoImportProperties properties) {
        this.jdbi = jdbi;
        this.catalogCommands = catalogCommands;
        this.properties = properties;
    }

    public CsvPreview preview(byte[] csv, String charsetName) {
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> headers = List.of();
        int total = 0;
        try (CSVParser parser = CsvImportSupport.parse(csv, charsetName)) {
            headers = CsvImportSupport.displayHeaders(parser.getHeaderNames());
            for (var record : parser) {
                total++;
                if (rows.size() < Math.max(0, properties.getPreviewRows())) {
                    rows.add(CsvImportSupport.rowMap(parser.getHeaderNames(), record));
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not parse CSV: " + e.getMessage(), e);
        }
        return new CsvPreview(headers, rows, total);
    }

    /**
     * Imports catalog rows using a field-to-header mapping, e.g. {@code {"description":"Name"}}.
     */
    public ImportResult importCatalog(CatalogDescriptor desc, byte[] csv, String charsetName,
                                      Map<String, String> mapping, CatalogImportMode mode,
                                      boolean dryRun, Principal principal) {
        if (mapping == null || mapping.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mapping is required");
        }

        int total = 0;
        int created = 0;
        int updated = 0;
        List<ImportRowError> errors = new ArrayList<>();

        try (CSVParser parser = CsvImportSupport.parse(csv, charsetName)) {
            List<String> headers = CsvImportSupport.displayHeaders(parser.getHeaderNames());
            CsvImportSupport.validateMapping(headers, mapping);

            for (var record : parser) {
                total++;
                if (total > properties.getMaxRows()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "CSV has more than " + properties.getMaxRows() + " rows");
                }

                Map<String, Object> body = CsvImportSupport.body(parser.getHeaderNames(), mapping, record);
                try {
                    UUID existingId = mode == CatalogImportMode.UPSERT_BY_CODE
                            ? existingIdByCode(desc, body.get("code"))
                            : null;
                    if (!dryRun) {
                        if (existingId == null) {
                            catalogCommands.create(desc, body, principal);
                        } else {
                            catalogCommands.update(desc, existingId, body, principal);
                        }
                    }
                    if (existingId == null) {
                        created++;
                    } else {
                        updated++;
                    }
                } catch (Exception e) {
                    errors.add(new ImportRowError(total, CsvImportSupport.message(e)));
                }
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not import CSV: " + e.getMessage(), e);
        }

        return new ImportResult("catalog", desc.logicalName(), mode.name(), dryRun, total,
                created, updated, 0, errors.size(), errors);
    }

    private UUID existingIdByCode(CatalogDescriptor desc, Object code) {
        if (code == null || code.toString().isBlank()) {
            return null;
        }
        return jdbi.withHandle(h ->
                h.createQuery("SELECT _id FROM " + desc.tableName() +
                                " WHERE _deletion_mark = false AND _code = :code")
                        .bind("code", code.toString())
                        .mapTo(UUID.class)
                        .findOne()
                        .orElse(null));
    }

}
