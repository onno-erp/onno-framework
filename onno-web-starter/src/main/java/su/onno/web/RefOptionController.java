package su.onno.web;

import su.onno.service.AccessService;
import su.onno.service.CatalogQueryService;
import su.onno.service.DocumentQueryService;
import su.onno.service.EntityJsonRepresentation;
import su.onno.service.Where;
import su.onno.picker.PickerOptionsService;
import su.onno.access.AccessSubject;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contextual reference-picker search. POST is intentional: the live parent form and row context
 * belongs in a JSON body, not a query string.
 *
 * <p>With an empty query the search also offers the caller's recently picked records first
 * (#409): up to {@link #RECENT_LIMIT} options flagged {@code _recent: true}, followed by the
 * ordinary options without duplicates. Picks are remembered through {@code POST /recent}; the
 * remembered ids are re-read through the same target search, so they obey the same deletion mark,
 * record scope and {@code refFilter} as every other option.</p>
 *
 * <p>Both bodies take the field's resolved {@code refFilter} as the string {@code filter} and/or a
 * structured {@code where} (a JSON object, see {@link Where}); given together they are ANDed.</p>
 */
@RestController
@RequestMapping("/api/ref-options")
public final class RefOptionController {

    /** Recently picked options offered ahead of the ordinary ones on an empty query. */
    static final int RECENT_LIMIT = 5;

    /** Upper bound of ids one {@code /decorate} call re-reads (a dialog window is far smaller). */
    static final int MAX_DECORATE_IDS = 500;

    private final CatalogQueryService catalogs;
    private final DocumentQueryService documents;
    private final AccessService access;
    private final PickerOptionsService options;
    private final RefRecentStore recents;

    /** Without a {@link RefRecentStore} the picker offers no recent section and remembers nothing. */
    public RefOptionController(CatalogQueryService catalogs,
                               DocumentQueryService documents,
                               AccessService access,
                               PickerOptionsService options) {
        this(catalogs, documents, access, options, null);
    }

    public RefOptionController(CatalogQueryService catalogs,
                               DocumentQueryService documents,
                               AccessService access,
                               PickerOptionsService options,
                               RefRecentStore recents) {
        this.catalogs = catalogs;
        this.documents = documents;
        this.access = access;
        this.options = options;
        this.recents = recents;
    }

    @PostMapping("/search")
    public List<Map<String, Object>> search(@RequestBody SearchRequest request,
                                             @RequestParam(defaultValue = "logical") String representation,
                                             AccessSubject subject) {
        int cap = request.limit() == null ? 30 : Math.max(1, Math.min(request.limit(), 200));
        String kind = "document".equalsIgnoreCase(request.targetKind()) ? "document" : "catalog";
        EntityJsonRepresentation.Mode mode = EntityJsonRepresentation.parse(representation);
        if ("document".equals(kind)) {
            DocumentDescriptor target = documents.require(request.targetName());
            access.requireRead(subject, target);
            List<Map<String, Object>> rows = documents.search(subject, target, request.query(), cap, request.filter(),
                    Where.text(request.where()), null);
            List<UUID> recentIds = recentIds(request, subject, kind, target.logicalName());
            if (!recentIds.isEmpty()) {
                rows = withRecent(recentIds, documents.search(subject, target, null, recentIds.size(),
                        request.filter(), Where.text(request.where()), recentIds), rows, cap);
            }
            return EntityJsonRepresentation.documents(target,
                    options.decorate(request.ownerKind(), request.ownerName(), context(request, kind), rows), mode);
        } else {
            CatalogDescriptor target = catalogs.require(request.targetName());
            access.requireRead(subject, target);
            List<Map<String, Object>> rows = catalogs.search(subject, target, request.query(), cap, request.filter(),
                    Where.text(request.where()), null);
            List<UUID> recentIds = recentIds(request, subject, kind, target.logicalName());
            if (!recentIds.isEmpty()) {
                rows = withRecent(recentIds, catalogs.search(subject, target, null, recentIds.size(),
                        request.filter(), Where.text(request.where()), recentIds), rows, cap);
            }
            return EntityJsonRepresentation.catalogs(target,
                    options.decorate(request.ownerKind(), request.ownerName(), context(request, kind), rows), mode);
        }
    }

    List<Map<String, Object>> search(SearchRequest request, AccessSubject subject) {
        return search(request, "logical", subject);
    }

    /**
     * Remember that the caller picked {@code id} in a reference field targeting
     * {@code targetKind}/{@code targetName}. Only a live record the caller can read is remembered;
     * anything else is silently ignored, so the endpoint answers the same either way and reveals
     * nothing about records outside the caller's scope.
     */
    @PostMapping("/recent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recent(@RequestBody RecentRequest request, AccessSubject subject) {
        if (recents == null || request.id() == null || !(subject instanceof AccessSubject.User user)) {
            return;
        }
        String kind = "document".equalsIgnoreCase(request.targetKind()) ? "document" : "catalog";
        String name;
        boolean visible;
        if ("document".equals(kind)) {
            DocumentDescriptor target = documents.require(request.targetName());
            access.requireRead(subject, target);
            name = target.logicalName();
            visible = !documents.rowsByIds(subject, target, List.of(request.id())).isEmpty();
        } else {
            CatalogDescriptor target = catalogs.require(request.targetName());
            access.requireRead(subject, target);
            name = target.logicalName();
            visible = !catalogs.rowsByIds(subject, target, List.of(request.id())).isEmpty();
        }
        if (visible) {
            recents.record(user.username(), kind, name, request.id());
        }
    }

    /**
     * How the picker dialog (#411) must present a list window's rows so it offers exactly what
     * the dropdown would. Every requested id is re-read through the same search as the dropdown
     * (the field's resolved {@code refFilter}, record scope, deletion mark) and then through the
     * field's {@link su.onno.picker.PickerOptions}. The answer lists the {@code offered} ids (request order)
     * and {@code decorations} for those that carry a badge, colour, reason or disabled flag. An id
     * missing from {@code offered} must not be shown; the answer never names it, so an
     * out-of-scope id is indistinguishable from an unknown one.
     */
    @PostMapping("/decorate")
    public Map<String, Object> decorate(@RequestBody DecorateRequest request, AccessSubject subject) {
        List<UUID> ids = request.ids() == null ? List.of()
                : request.ids().stream().filter(java.util.Objects::nonNull).distinct().limit(MAX_DECORATE_IDS).toList();
        if (ids.isEmpty()) {
            return Map.of("offered", List.of(), "decorations", Map.of());
        }
        String kind = "document".equalsIgnoreCase(request.targetKind()) ? "document" : "catalog";
        List<Map<String, Object>> rows;
        if ("document".equals(kind)) {
            DocumentDescriptor target = documents.require(request.targetName());
            access.requireRead(subject, target);
            rows = documents.search(subject, target, null, ids.size(), request.filter(),
                    Where.text(request.where()), ids);
        } else {
            CatalogDescriptor target = catalogs.require(request.targetName());
            access.requireRead(subject, target);
            rows = catalogs.search(subject, target, null, ids.size(), request.filter(),
                    Where.text(request.where()), ids);
        }
        Map<UUID, su.onno.picker.PickerOption> resolved = options.decorations(request.ownerKind(), request.ownerName(),
                new su.onno.picker.PickerContext(kind, request.targetName(), request.fieldPath(), request.formValues(),
                        request.section(), request.rowIndex(), request.rowValues(), request.documentId()),
                rows);
        Set<UUID> offered = new HashSet<>();
        for (Map<String, Object> row : rows) {
            UUID id = rowId(row);
            if (id != null) {
                offered.add(id);
            }
        }
        List<String> shown = new ArrayList<>();
        Map<String, Map<String, Object>> decorations = new java.util.LinkedHashMap<>();
        for (UUID id : ids) {
            su.onno.picker.PickerOption decoration = resolved.get(id);
            if (!offered.contains(id) || (decoration != null && decoration.hidden())) {
                continue;
            }
            shown.add(id.toString());
            if (decoration != null) {
                Map<String, Object> d = new java.util.LinkedHashMap<>();
                if (!decoration.badge().isBlank()) {
                    d.put("badge", decoration.badge());
                    d.put("tone", decoration.tone().name().toLowerCase());
                }
                if (!decoration.color().isBlank()) {
                    d.put("color", decoration.color());
                }
                if (decoration.disabled()) {
                    d.put("disabled", true);
                }
                if (!decoration.reason().isBlank()) {
                    d.put("reason", decoration.reason());
                }
                if (!d.isEmpty()) {
                    decorations.put(id.toString(), d);
                }
            }
        }
        return Map.of("offered", shown, "decorations", decorations);
    }

    /** Remembered picks worth re-reading for this request: only for a user's empty-query search. */
    private List<UUID> recentIds(SearchRequest request, AccessSubject subject, String kind, String targetName) {
        if (recents == null
                || Boolean.FALSE.equals(request.recent())
                || (request.query() != null && !request.query().isBlank())
                || !(subject instanceof AccessSubject.User user)) {
            return List.of();
        }
        return recents.recent(user.username(), kind, targetName, RefRecentStore.KEEP);
    }

    /**
     * The recent section (in pick order, at most {@link #RECENT_LIMIT}, each flagged
     * {@code _recent}) followed by the ordinary options minus those already shown, capped at
     * {@code cap} so the request's limit still bounds the response.
     */
    static List<Map<String, Object>> withRecent(List<UUID> recentIds, List<Map<String, Object>> recentRows,
                                                List<Map<String, Object>> rows, int cap) {
        Map<UUID, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> row : recentRows) {
            UUID id = rowId(row);
            if (id != null) {
                byId.put(id, row);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<UUID> shown = new HashSet<>();
        int recentCap = Math.min(RECENT_LIMIT, cap);
        for (UUID id : recentIds) {
            Map<String, Object> row = byId.get(id);
            if (row == null || result.size() >= recentCap) {
                continue;
            }
            row.put("_recent", true);
            result.add(row);
            shown.add(id);
        }
        for (Map<String, Object> row : rows) {
            if (result.size() >= cap) {
                break;
            }
            if (!shown.contains(rowId(row))) {
                result.add(row);
            }
        }
        return result;
    }

    private static UUID rowId(Map<String, Object> row) {
        Object id = row.get("_id");
        if (id instanceof UUID uuid) {
            return uuid;
        }
        try {
            return id == null ? null : UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static su.onno.picker.PickerContext context(SearchRequest request, String kind) {
        return new su.onno.picker.PickerContext(
                kind,
                request.targetName(),
                request.fieldPath(),
                request.formValues(),
                request.section(),
                request.rowIndex(),
                request.rowValues(),
                request.documentId());
    }

    /**
     * @param ownerKind  {@code catalog}/{@code document} whose form holds the reference field
     *                   (selects the field's {@link su.onno.picker.PickerOptions}; optional)
     * @param ownerName  that entity's logical name (optional)
     * @param recent whether an empty query may lead with the caller's recent picks; {@code null}
     *               (absent from the JSON body) means yes, {@code false} turns the section off
     */
    public record SearchRequest(
            String targetKind,
            String targetName,
            String ownerKind,
            String ownerName,
            String query,
            Integer limit,
            String filter,
            String fieldPath,
            Map<String, Object> formValues,
            String section,
            Integer rowIndex,
            Map<String, Object> rowValues,
            UUID documentId,
            Boolean recent,
            com.fasterxml.jackson.databind.JsonNode where
    ) {
        /** The pre-#409 shape: recent picks are offered (an absent {@code recent} means on). */
        public SearchRequest(String targetKind, String targetName, String ownerKind, String ownerName, String query,
                             Integer limit, String filter, String fieldPath, Map<String, Object> formValues,
                             String section, Integer rowIndex, Map<String, Object> rowValues,
                             UUID documentId) {
            this(targetKind, targetName, ownerKind, ownerName, query, limit, filter, fieldPath, formValues,
                    section, rowIndex, rowValues, documentId, null);
        }

        /** Without a structured {@code where} (only the string {@code filter}). */
        public SearchRequest(String targetKind, String targetName, String ownerKind, String ownerName, String query,
                             Integer limit, String filter, String fieldPath, Map<String, Object> formValues,
                             String section, Integer rowIndex, Map<String, Object> rowValues,
                             UUID documentId, Boolean recent) {
            this(targetKind, targetName, ownerKind, ownerName, query, limit, filter, fieldPath, formValues,
                    section, rowIndex, rowValues, documentId, recent, null);
        }
    }

    /**
     * A picker-dialog window to decorate: the field's target, resolved {@code refFilter} and live
     * form context (as in {@link SearchRequest}) plus the ids of the rows on screen.
     */
    public record DecorateRequest(
            String targetKind,
            String targetName,
            String ownerKind,
            String ownerName,
            String filter,
            String fieldPath,
            Map<String, Object> formValues,
            String section,
            Integer rowIndex,
            Map<String, Object> rowValues,
            UUID documentId,
            List<UUID> ids,
            com.fasterxml.jackson.databind.JsonNode where
    ) {
        /** Without a structured {@code where} (only the string {@code filter}). */
        public DecorateRequest(String targetKind, String targetName, String ownerKind, String ownerName, String filter,
                               String fieldPath, Map<String, Object> formValues, String section,
                               Integer rowIndex, Map<String, Object> rowValues, UUID documentId,
                               List<UUID> ids) {
            this(targetKind, targetName, ownerKind, ownerName, filter, fieldPath, formValues, section, rowIndex,
                    rowValues, documentId, ids, null);
        }
    }

    /** One pick to remember: the reference field's target and the chosen record. */
    public record RecentRequest(String targetKind, String targetName, UUID id) {
    }
}
