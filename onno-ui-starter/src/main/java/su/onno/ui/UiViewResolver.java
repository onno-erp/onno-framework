package su.onno.ui;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;

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
    // Global fallbacks for lists that don't author their own feed mode / page size.
    private final ListSpec.FeedMode defaultFeed;
    private final int defaultPageSize;
    // entity -> (profile id | "" for default) -> view
    private final Map<Class<?>, Map<String, EntityView>> views = new LinkedHashMap<>();

    /** Back-compat: resolver with the built-in defaults (infinite feed, 50 rows). */
    public UiViewResolver(ResolvedMetadataService metadata, List<EntityView> entityViews) {
        this(metadata, entityViews, ListSpec.FeedMode.INFINITE, 50);
    }

    public UiViewResolver(ResolvedMetadataService metadata, List<EntityView> entityViews,
                          ListSpec.FeedMode defaultFeed, int defaultPageSize) {
        this.metadata = metadata;
        this.defaultFeed = defaultFeed == null ? ListSpec.FeedMode.INFINITE : defaultFeed;
        this.defaultPageSize = defaultPageSize <= 0 ? 50 : defaultPageSize;
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

    /**
     * Whether comments are enabled for this entity — true if any of its views (default or
     * profile-specific) opts in via {@link EntityView#comments()}. The comment panel and the
     * {@code /api/comments} endpoint are both gated on this, so comments are an opt-in, per-entity
     * capability (on top of the global {@code onno.comments.enabled} switch). Resolved at the entity
     * level, not per profile.
     */
    public boolean commentsEnabled(Class<?> entity) {
        Map<String, EntityView> byProfile = entity == null ? null : views.get(entity);
        return byProfile != null && byProfile.values().stream().anyMatch(EntityView::comments);
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
                        cm.widget(), cm.format(), cm.hint()));
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
                            e.getValue().widget(), e.getValue().format(), e.getValue().hint())));
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
                    f.field(), f.label(), cm.columnName(), filterType(f.type()),
                    filterOptions(f, cm)));
        }

        ResolvedListView.MapView mapView = resolveMap(spec.mapSpec(), available);

        // Custom body renderer: pass the registry type through as authored (a blank type is
        // dropped). Unlike the map's geo fields, there is nothing to validate server-side — the
        // widget registry lives on the client, which degrades an unregistered type to the grid.
        ListSpec.CustomSpec customSpec = spec.customSpec();
        ResolvedListView.CustomView customView =
                customSpec == null || customSpec.type() == null || customSpec.type().isBlank()
                        ? null
                        : new ResolvedListView.CustomView(
                                customSpec.type(), customSpec.label(), customSpec.isDefaultView());

        // Feed mode + page size: the authored value wins, else the global default. Carried to the
        // renderer as the lowercase token the grid keys off ("infinite" / "paged").
        ListSpec.FeedMode feed = spec.feedMode() != null ? spec.feedMode() : defaultFeed;
        int pageSize = spec.pageSize() > 0 ? spec.pageSize() : defaultPageSize;

        // Grouping: resolve each groupable field + each subtotal's field to its data column
        // (validated against the available columns); an unknown field is dropped rather than emitted.
        List<ResolvedListView.GroupColumn> groupCols = new ArrayList<>();
        for (String field : spec.groupable()) {
            ColumnMeta cm = available.get(field);
            if (cm == null) {
                continue;
            }
            groupCols.add(new ResolvedListView.GroupColumn(cm.columnName(), cm.label(), cm.isTemporal()));
        }
        List<ResolvedListView.Aggregate> aggregates = new ArrayList<>();
        for (ListSpec.Aggregate a : spec.aggregates()) {
            ColumnMeta cm = available.get(a.field());
            if (cm == null) {
                continue;
            }
            aggregates.add(new ResolvedListView.Aggregate(cm.columnName(),
                    a.fn().name().toLowerCase(java.util.Locale.ROOT),
                    a.label() != null ? a.label() : cm.label(), cm.format()));
        }
        ResolvedListView.Grouping grouping = new ResolvedListView.Grouping(groupCols, aggregates);

        return new ResolvedListView(title, columns, spec.searchable(), sortColumn,
                spec.sortDescending(), filters, mapView,
                feed.name().toLowerCase(java.util.Locale.ROOT), pageSize, grouping, customView);
    }

    /**
     * Resolve a declared map spec's field names to data columns (validated against the available
     * columns), or null when there's no usable geo source — a combined field that doesn't resolve,
     * or a lat/lng pair where either side is missing/unknown — so a misconfigured map degrades to
     * "no map view" rather than a broken toggle.
     */
    private static ResolvedListView.MapView resolveMap(ListSpec.MapSpec spec,
                                                       Map<String, ColumnMeta> available) {
        if (spec == null) {
            return null;
        }
        String geo = columnOf(spec.field(), available);
        String lat = columnOf(spec.latField(), available);
        String lng = columnOf(spec.lngField(), available);
        String geoJson = columnOf(spec.geoJsonField(), available);
        String label = columnOf(spec.labelField(), available);
        boolean hasGeo = !geo.isEmpty() || (!lat.isEmpty() && !lng.isEmpty()) || !geoJson.isEmpty();
        if (!hasGeo) {
            return null;
        }
        return new ResolvedListView.MapView(geo, lat, lng, geoJson, label, spec.isDefaultView());
    }

    /** Resolve a field name to its data column, or "" when the name is blank/unknown. */
    private static String columnOf(String field, Map<String, ColumnMeta> available) {
        if (field == null || field.isBlank()) {
            return "";
        }
        ColumnMeta cm = available.get(field);
        return cm == null ? "" : cm.columnName();
    }

    /** The renderer-neutral control name the island keys off (see {@link ResolvedListView.Filter}). */
    private static String filterType(ListSpec.FilterType type) {
        return switch (type) {
            case MULTI_OPTIONS -> "multiOptions";
            case CONTAINS -> "contains";
            case STARTS_WITH -> "startsWith";
            case DATE_RANGE -> "dateRange";
            case OPTIONS -> "options";
        };
    }

    /**
     * The option list a select/multi-select filter offers. For a plain column the authored options
     * pass through as-is. An {@code @Enumeration}-typed field needs translation: its constants
     * persist as deterministic UUIDs, so an option whose value is the constant name (or its
     * {@code @EnumLabel} text) would match nothing — each is mapped to the stored id, keeping the
     * authored label. Authoring <em>no</em> options on an enum field is the shorthand for "offer
     * every declared value", labelled like the entity's status pills.
     */
    private static List<ResolvedListView.Option> filterOptions(ListSpec.Filter f, ColumnMeta cm) {
        boolean selectControl = f.type() == ListSpec.FilterType.OPTIONS
                || f.type() == ListSpec.FilterType.MULTI_OPTIONS;
        List<Map<String, Object>> enumValues = cm.enumValues();
        if (!selectControl || enumValues.isEmpty()) {
            return f.options().stream()
                    .map(o -> new ResolvedListView.Option(o.value(), o.label(), "", o.avatarUrl()))
                    .toList();
        }
        if (f.options().isEmpty()) {
            return enumValues.stream()
                    .map(v -> new ResolvedListView.Option(
                            str(v.get("id")), str(v.get("label")), str(v.get("color"))))
                    .toList();
        }
        return f.options().stream().map(o -> {
            Map<String, Object> match = enumValues.stream()
                    .filter(v -> o.value().equals(str(v.get("name")))
                            || o.value().equalsIgnoreCase(str(v.get("label"))))
                    .findFirst().orElse(null);
            // An option that isn't a known constant passes through untranslated (e.g. an id).
            return match == null
                    ? new ResolvedListView.Option(o.value(), o.label(), "", o.avatarUrl())
                    : new ResolvedListView.Option(str(match.get("id")), o.label(), str(match.get("color")));
        }).toList();
    }

    private record ColumnMeta(String label, String columnName, boolean visibleInList, int order,
                              String width, String widget, String format, String hint, String javaType,
                              List<Map<String, Object>> enumValues) {
        @SuppressWarnings("unchecked")
        static ColumnMeta of(Map<String, Object> m) {
            Object order = m.get("order");
            Object enumValues = m.get("enumValues");
            return new ColumnMeta(
                    str(m.get("displayName")), str(m.get("columnName")),
                    Boolean.TRUE.equals(m.get("visibleInList")),
                    order == null ? 0 : ((Number) order).intValue(),
                    str(m.get("widthHint")), str(m.get("widget")), str(m.get("format")),
                    str(m.get("hint")), str(m.get("javaType")),
                    enumValues instanceof List<?> l ? (List<Map<String, Object>>) l : List.of());
        }

        /**
         * Whether this column is a date/time — so grouping buckets it by period (day/month/year)
         * rather than exact value. Driven by the attribute's Java type; the built-in {@code _date} /
         * {@code _period} system columns carry no javaType, so they're recognized by name.
         */
        boolean isTemporal() {
            return switch (javaType) {
                case "LocalDate", "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime",
                     "Date", "Timestamp" -> true;
                default -> columnName.equals("_date") || columnName.equals("_period");
            };
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
