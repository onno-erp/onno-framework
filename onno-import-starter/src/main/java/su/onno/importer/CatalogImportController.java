package su.onno.importer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.UiAccessService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/import")
public class CatalogImportController {

    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final UiAccessService access;
    private final CatalogCsvImportService catalogImports;
    private final DocumentCsvImportService documentImports;
    private final OnnoImportProperties properties;
    private final ObjectMapper objectMapper;

    public CatalogImportController(CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                                   UiAccessService access, CatalogCsvImportService catalogImports,
                                   DocumentCsvImportService documentImports, OnnoImportProperties properties,
                                   ObjectMapper objectMapper) {
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.access = access;
        this.catalogImports = catalogImports;
        this.documentImports = documentImports;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/catalogs/{name}/csv/preview")
    public CsvPreview preview(@PathVariable String name,
                              @RequestPart("file") MultipartFile file,
                              @RequestParam(required = false) String charset,
                              Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireRead(principal, desc);
        return catalogImports.preview(bytes(file), charset);
    }

    @PostMapping("/documents/{name}/csv/preview")
    public CsvPreview previewDocument(@PathVariable String name,
                                      @RequestPart("file") MultipartFile file,
                                      @RequestParam(required = false) String charset,
                                      Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireRead(principal, desc);
        return catalogImports.preview(bytes(file), charset);
    }

    @PostMapping("/catalogs/{name}/csv")
    public ImportResult importCsv(@PathVariable String name,
                                  @RequestPart("file") MultipartFile file,
                                  @RequestParam String mapping,
                                  @RequestParam(defaultValue = "CREATE_ONLY") CatalogImportMode mode,
                                  @RequestParam(defaultValue = "false") boolean dryRun,
                                  @RequestParam(required = false) String charset,
                                  Principal principal) {
        CatalogDescriptor desc = catalogQuery.require(name);
        access.requireWrite(principal, desc);
        return catalogImports.importCatalog(desc, bytes(file), charset, parseMapping(mapping), mode, dryRun, principal);
    }

    @PostMapping("/documents/{name}/csv")
    public ImportResult importDocumentCsv(@PathVariable String name,
                                          @RequestPart("file") MultipartFile file,
                                          @RequestParam String mapping,
                                          @RequestParam(defaultValue = "CREATE_ONLY") DocumentImportMode mode,
                                          @RequestParam(defaultValue = "false") boolean dryRun,
                                          @RequestParam(defaultValue = "false") boolean postAfterImport,
                                          @RequestParam(required = false) String groupBy,
                                          @RequestParam(required = false) String charset,
                                          Principal principal) {
        DocumentDescriptor desc = documentQuery.require(name);
        access.requireWrite(principal, desc);
        return documentImports.importDocuments(desc, bytes(file), charset, parseMapping(mapping),
                mode, dryRun, postAfterImport, groupBy, principal);
    }

    private byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file is required");
        }
        if (file.getSize() > properties.getMaxFileBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "CSV file is too large; max is " + properties.getMaxFileBytes() + " bytes");
        }
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read uploaded file: " + e.getMessage(), e);
        }
    }

    private Map<String, String> parseMapping(String mapping) {
        if (mapping == null || mapping.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mapping is required");
        }
        try {
            return objectMapper.readValue(mapping, new TypeReference<>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mapping must be JSON object of fieldName to CSV header: " + e.getMessage(), e);
        }
    }
}
