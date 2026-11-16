package su.onno.web;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an entity's default list surface from the model alone into a renderer-agnostic
 * {@link DefaultListView}: visible columns in model order, default filters for enumerations and the
 * document date, the global page size. Clients customize presentation on their side.
 */
public class DefaultListViews {

    private final ResolvedMetadataService metadata;
    // The keyset window size (onno.web.list.page-size).
    private final int defaultPageSize;

    /** Resolver with the built-in 50-row keyset window. */
    public DefaultListViews(ResolvedMetadataService metadata) {
        this(metadata, 50);
    }

    public DefaultListViews(ResolvedMetadataService metadata, int defaultPageSize) {
        this.metadata = metadata;
        this.defaultPageSize = defaultPageSize <= 0 ? 50 : defaultPageSize;
    }

    public DefaultListView catalogList(CatalogDescriptor d) {
        return resolveList(metadata.describeCatalog(d));
    }

    public DefaultListView documentList(DocumentDescriptor d) {
        return resolveList(metadata.describeDocument(d));
    }

    /**
     * Comments are on for every catalog/document when the comments module is enabled
     * ({@code onno.comments.enabled}); the client decides where to show the thread.
     */
    public boolean commentsEnabled(Class<?> entity) {
        return entity != null;
    }

    @SuppressWarnings("unchecked")
    private DefaultListView resolveList(Map<String, Object> meta) {
        // Available columns by field name: built-in system columns first, then custom.
        Map<String, ColumnMeta> available = new LinkedHashMap<>();
        for (Map<String, Object> sc : (List<Map<String, Object>>) meta.getOrDefault("systemColumns", List.of())) {
            available.put(str(sc.get("fieldName")), ColumnMeta.of(sc));
        }
        for (Map<String, Object> a : (List<Map<String, Object>>) meta.getOrDefault("attributes", List.of())) {
            available.put(str(a.get("fieldName")), ColumnMeta.of(a));
        }
        // Every column in model order (system columns first); the model carries no presentation
        // (width, widget, format, visibility), so the client picks and presents them.
        List<DefaultListView.Column> columns = new ArrayList<>();
        available.values().forEach(cm -> columns.add(
                new DefaultListView.Column(cm.label(), cm.columnName(), cm.fieldName(), "", "", "", "", cm.type())));
        // The entity's display title, which itself falls back to the logical name.
        String title = str(meta.getOrDefault("title", meta.get("name")));
        return new DefaultListView(title, columns, defaultFilters(available), defaultPageSize);
    }

    /**
     * Filters every list gets with no configuration: a multi-select for each enumeration field
     * (options from {@code @EnumLabel}, values the stored ids) and a date range on a document's date.
     */
    private static List<DefaultListView.Filter> defaultFilters(Map<String, ColumnMeta> available) {
        List<DefaultListView.Filter> out = new ArrayList<>();
        ColumnMeta date = available.get("date");
        if (date != null && "_date".equals(date.columnName())) {
            out.add(new DefaultListView.Filter("date", date.label(), date.columnName(), "dateRange", List.of()));
        }
        for (Map.Entry<String, ColumnMeta> e : available.entrySet()) {
            ColumnMeta cm = e.getValue();
            if (cm.enumValues().isEmpty()) {
                continue;
            }
            List<DefaultListView.Option> options = cm.enumValues().stream()
                    .map(v -> new DefaultListView.Option(str(v.get("id")), str(v.get("label")), str(v.get("color")), ""))
                    .toList();
            out.add(new DefaultListView.Filter(e.getKey(), cm.label(), cm.columnName(), "multiOptions", options));
        }
        return out;
    }

    /**
     * A list column's width in whole pixels ({@code "105"}), or blank to size automatically: the
     * attribute's width hint counts only when it is an explicit pixel value. Form-layout hints
     * ({@code "half"}, {@code "1/2"}, {@code "50%"}) never reach the list as a width.
     */
    static String listWidth(String widthHint) {
        String hint = widthHint == null ? "" : widthHint.trim();
        if (hint.regionMatches(true, Math.max(0, hint.length() - 2), "px", 0, 2)) {
            hint = hint.substring(0, hint.length() - 2).trim();
        }
        if (hint.isEmpty() || !hint.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return "";
        }
        try {
            int px = Integer.parseInt(hint);
            return px > 0 ? Integer.toString(px) : "";
        } catch (NumberFormatException overflow) {
            return "";
        }
    }

    /**
     * The field's value type as a list column reports it: {@code enum}, {@code ref},
     * {@code date}, {@code number}, {@code boolean} or {@code text}. System columns carry no
     * Java type; theirs follows from the column ({@code _date} a date, {@code _posted} a flag).
     */
    static String valueType(Map<String, Object> m) {
        if (Boolean.TRUE.equals(m.get("isEnum"))) return "enum";
        if (Boolean.TRUE.equals(m.get("isRef"))) return "ref";
        String column = str(m.get("columnName"));
        if ("_date".equals(column)) return "date";
        if ("_posted".equals(column)) return "boolean";
        return switch (str(m.get("javaType"))) {
            case "LocalDate", "LocalDateTime", "OffsetDateTime", "Instant", "ZonedDateTime" -> "date";
            case "BigDecimal", "Integer", "int", "Long", "long", "Double", "double", "Float", "float",
                 "Short", "short" -> "number";
            case "Boolean", "boolean" -> "boolean";
            default -> "text";
        };
    }

    private record ColumnMeta(String label, String columnName, String fieldName,
                              boolean visibleInList, int order,
                              String width, String widget, String format, String hint,
                              List<Map<String, Object>> enumValues, String type) {
        @SuppressWarnings("unchecked")
        static ColumnMeta of(Map<String, Object> m) {
            Object order = m.get("order");
            Object enumValues = m.get("enumValues");
            return new ColumnMeta(
                    str(m.get("displayName")), str(m.get("columnName")), str(m.get("fieldName")),
                    Boolean.TRUE.equals(m.get("visibleInList")),
                    order == null ? 0 : ((Number) order).intValue(),
                    str(m.get("widthHint")), str(m.get("widget")), str(m.get("format")),
                    str(m.get("hint")),
                    enumValues instanceof List<?> l ? (List<Map<String, Object>>) l : List.of(),
                    valueType(m));
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
