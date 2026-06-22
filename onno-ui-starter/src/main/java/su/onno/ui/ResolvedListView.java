package su.onno.ui;

import java.util.List;

/**
 * Renderer-agnostic resolved list surface: a title and ordered columns. Produced
 * by {@link UiViewResolver} (merging an {@link EntityView} over the auto-generated
 * defaults) and consumed by the DivKit emitter — or any other renderer.
 */
public record ResolvedListView(String title, List<Column> columns,
                               boolean searchable, String sortColumn, boolean sortDescending,
                               List<Filter> filters, MapView mapView) {

    public ResolvedListView {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    /** Back-compat: a non-searchable, default-sorted view. */
    public ResolvedListView(String title, List<Column> columns) {
        this(title, columns, false, null, false, List.of(), null);
    }

    /** Back-compat: a view with no declarative filters. */
    public ResolvedListView(String title, List<Column> columns,
                            boolean searchable, String sortColumn, boolean sortDescending) {
        this(title, columns, searchable, sortColumn, sortDescending, List.of(), null);
    }

    /** Back-compat: a view with filters but no map view. */
    public ResolvedListView(String title, List<Column> columns,
                            boolean searchable, String sortColumn, boolean sortDescending,
                            List<Filter> filters) {
        this(title, columns, searchable, sortColumn, sortDescending, filters, null);
    }

    /**
     * A resolved map view: the data {@code columnName}s the geometry reads — a marker point (a
     * combined {@code geoField} {@code "lat,lng"} string, or a {@code latField}/{@code lngField}
     * pair) and/or a {@code geoJsonField} (GeoJSON points/paths/areas) — an optional
     * {@code labelField} for the popup, and whether the list opens on the map. Column names are
     * resolved + validated against the entity's real columns (an unresolved geo source drops the map
     * view). A blank string means "unset".
     */
    public record MapView(String geoField, String latField, String lngField, String geoJsonField,
                          String labelField, boolean defaultView) {
        public MapView {
            geoField = geoField == null ? "" : geoField;
            latField = latField == null ? "" : latField;
            lngField = lngField == null ? "" : lngField;
            geoJsonField = geoJsonField == null ? "" : geoJsonField;
            labelField = labelField == null ? "" : labelField;
        }
    }

    /**
     * A resolved list filter: a stable {@code key} (the field name, the client's state key), the
     * {@code label}, the data {@code columnName} the query filters on (resolved + validated against
     * the entity's columns), the control {@code type} ({@code "options"}, {@code "multiOptions"},
     * {@code "contains"}, {@code "startsWith"} or {@code "dateRange"}) and, for the (multi-)options
     * controls, its {@code options} (each a value→label pair).
     */
    public record Filter(String key, String label, String columnName, String type, List<Option> options) {
        public Filter {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * One choice of a (multi-)options filter: the {@code value} the query matches against the column,
     * and the {@code label} the control renders. The two are identical for a plain-string filter; a
     * value→label split lets a filter over a code/English/enum-mirror column show a localized choice.
     */
    public record Option(String value, String label) {
        public Option {
            label = label == null ? value : label;
        }
    }

    /**
     * A resolved column: the header label, the data column it reads, an optional width hint
     * (e.g. {@code "260"} px, from {@code .field(...).width(...)}; blank = size to content), and
     * the display hints carried over from the attribute — {@code widget} (so a list cell can
     * render an image thumbnail) and {@code format} (a date pattern or number spec applied to the
     * cell value). Both default to blank.
     */
    public record Column(String label, String columnName, String width, String widget, String format,
                         String hint) {
        /** Back-compat: a column with no display widget/format hints. */
        public Column(String label, String columnName, String width) {
            this(label, columnName, width, "", "", "");
        }

        /** Back-compat: a column with no help hint. */
        public Column(String label, String columnName, String width, String widget, String format) {
            this(label, columnName, width, widget, format, "");
        }
    }
}
