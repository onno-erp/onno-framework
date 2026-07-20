package su.onno.ui;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contextual reference-picker search. POST is intentional: the live parent form and row context
 * belongs in a JSON body, not a query string.
 */
@RestController
@RequestMapping("/api/ref-options")
public final class RefOptionController {

    private final CatalogQueryService catalogs;
    private final DocumentQueryService documents;
    private final UiAccessService access;
    private final RefOptionService options;

    public RefOptionController(CatalogQueryService catalogs,
                               DocumentQueryService documents,
                               UiAccessService access,
                               RefOptionService options) {
        this.catalogs = catalogs;
        this.documents = documents;
        this.access = access;
        this.options = options;
    }

    @PostMapping("/search")
    public List<Map<String, Object>> search(@RequestBody SearchRequest request, Principal principal) {
        int cap = request.limit() == null ? 30 : Math.max(1, Math.min(request.limit(), 200));
        String kind = "document".equalsIgnoreCase(request.targetKind()) ? "document" : "catalog";
        List<Map<String, Object>> rows;
        if ("document".equals(kind)) {
            DocumentDescriptor target = documents.require(request.targetName());
            access.requireRead(principal, target);
            rows = documents.search(target, request.query(), cap, request.filter());
        } else {
            CatalogDescriptor target = catalogs.require(request.targetName());
            access.requireRead(principal, target);
            rows = catalogs.search(target, request.query(), cap, request.filter());
        }
        RefOptionContext context = new RefOptionContext(
                kind,
                request.targetName(),
                request.fieldPath(),
                request.formValues(),
                request.section(),
                request.rowIndex(),
                request.rowValues(),
                request.documentId());
        return options.decorate(request.decorator(), context, rows);
    }

    public record SearchRequest(
            String targetKind,
            String targetName,
            String decorator,
            String query,
            Integer limit,
            String filter,
            String fieldPath,
            Map<String, Object> formValues,
            String section,
            Integer rowIndex,
            Map<String, Object> rowValues,
            UUID documentId
    ) {
    }
}
