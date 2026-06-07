package com.onec.ui;

import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DocumentDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a per-entity {@link EntityView} (authored in code) over the
 * auto-generated metadata defaults into a renderer-agnostic {@link ResolvedListView}.
 * Entities without an EntityView fall back entirely to the defaults (system +
 * visible custom columns, in field-hint order), so adding a view is purely
 * additive. The DivKit emitter compiles the result; the model is renderer-neutral.
 */
public class UiViewResolver {

    private static final String DEFAULT = "";

    private final ResolvedMetadataService metadata;
    // entity -> (profile id | "" for default) -> view
    private final Map<Class<?>, Map<String, EntityView>> views = new LinkedHashMap<>();

    public UiViewResolver(ResolvedMetadataService metadata, List<EntityView> entityViews) {
        this.metadata = metadata;
        for (EntityView view : entityViews) {
            if (view.entity() == null) {
                continue;
            }
            String profile = view.profile() == null ? DEFAULT : view.profile();
            views.computeIfAbsent(view.entity(), k -> new LinkedHashMap<>()).put(profile, view);
        }
    }

    public ResolvedListView catalogList(CatalogDescriptor d, String profileId) {
        return resolveList(d.javaClass(), profileId, metadata.describeCatalog(d));
    }

    public ResolvedListView documentList(DocumentDescriptor d, String profileId) {
        return resolveList(d.javaClass(), profileId, metadata.describeDocument(d));
    }

    /**
     * Whether this entity is declared by a view (profile-specific or default) and
     * may therefore appear in the UI. The view layer is the allowlist: an entity
     * with no view is hidden from nav and its surfaces 404 — only what's authored
     * renders.
     */
    public boolean hasView(Class<?> entity, String profileId) {
        return entity != null && viewFor(entity, profileId) != null;
    }

    /** Profile-specific view wins, then the default view, then auto-generated columns. */
    private EntityView viewFor(Class<?> entity, String profileId) {
        Map<String, EntityView> byProfile = views.get(entity);
        if (byProfile == null) {
            return null;
        }
        EntityView specific = byProfile.get(profileId);
        return specific != null ? specific : byProfile.get(DEFAULT);
    }

    @SuppressWarnings("unchecked")
    private ResolvedListView resolveList(Class<?> entity, String profileId, Map<String, Object> meta) {
        ListSpec spec = new ListSpec();
        EntityView view = viewFor(entity, profileId);
        if (view != null) {
            view.list(spec);
        }

        // Available columns by field name: built-in system columns first, then custom.
        Map<String, ColumnMeta> available = new LinkedHashMap<>();
        for (Map<String, Object> sc : (List<Map<String, Object>>) meta.getOrDefault("systemColumns", List.of())) {
            available.put(str(sc.get("fieldName")), ColumnMeta.of(sc));
        }
        for (Map<String, Object> a : (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of())) {
            available.put(str(a.get("fieldName")), ColumnMeta.of(a));
        }

        List<ResolvedListView.Column> columns = new ArrayList<>();
        if (spec.explicit()) {
            // Author took explicit control: exactly these fields, in this order.
            for (String field : spec.include()) {
                ColumnMeta cm = available.get(field);
                if (cm == null) {
                    continue;
                }
                columns.add(new ResolvedListView.Column(
                        spec.labels().getOrDefault(field, cm.label()), cm.columnName(), cm.width(),
                        cm.widget(), cm.format()));
            }
        } else {
            // Default: visible columns in configured order, minus any hidden, with label overrides.
            available.entrySet().stream()
                    .filter(e -> e.getValue().visibleInList())
                    .filter(e -> !spec.hidden().contains(e.getKey()))
                    .sorted(Comparator.comparingInt(e -> e.getValue().order()))
                    .forEach(e -> columns.add(new ResolvedListView.Column(
                            spec.labels().getOrDefault(e.getKey(), e.getValue().label()),
                            e.getValue().columnName(), e.getValue().width(),
                            e.getValue().widget(), e.getValue().format())));
        }

        // An authored view title wins; otherwise the entity's display title (which itself
        // falls back to the logical name when no @…(title=…) is declared).
        String title = spec.title() != null ? spec.title() : str(meta.getOrDefault("title", meta.get("name")));
        // Resolve the authored sort field name to its data column (validated against available
        // columns); blank means the query layer's default (e.g. _code / _date).
        ColumnMeta sortMeta = spec.sortField() == null ? null : available.get(spec.sortField());
        String sortColumn = sortMeta != null ? sortMeta.columnName() : null;

        // Resolve each declared filter's field to its data column (validated against the available
        // columns); a filter on an unknown field is dropped rather than emitted with no column.
        List<ResolvedListView.Filter> filters = new ArrayList<>();
        for (ListSpec.Filter f : spec.filters()) {
            ColumnMeta cm = available.get(f.field());
            if (cm == null) {
                continue;
            }
            filters.add(new ResolvedListView.Filter(
                    f.field(), f.label(), cm.columnName(),
                    f.type() == ListSpec.FilterType.DATE_RANGE ? "dateRange" : "options",
                    f.options()));
        }
        return new ResolvedListView(title, columns, spec.searchable(), sortColumn,
                spec.sortDescending(), filters);
    }

    private record ColumnMeta(String label, String columnName, boolean visibleInList, int order,
                              String width, String widget, String format) {
        static ColumnMeta of(Map<String, Object> m) {
            Object order = m.get("order");
            return new ColumnMeta(
                    str(m.get("displayName")), str(m.get("columnName")),
                    Boolean.TRUE.equals(m.get("visibleInList")),
                    order == null ? 0 : ((Number) order).intValue(),
                    str(m.get("widthHint")), str(m.get("widget")), str(m.get("format")));
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
