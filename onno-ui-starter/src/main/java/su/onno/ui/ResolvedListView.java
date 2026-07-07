package su.onno.ui;

import java.util.List;

/**
 * Renderer-agnostic resolved list surface: a title and ordered columns. Produced
 * by {@link UiViewResolver} (merging an {@link EntityView} over the auto-generated
 * defaults) and consumed by the DivKit emitter — or any other renderer.
 */
public record ResolvedListView(String title, List<Column> columns,
                               boolean searchable, String sortColumn, boolean sortDescending,
                               List<Filter> filters, MapView mapView,
                               String feedMode, int pageSize, Grouping grouping,
                               CustomView customView) {

    public ResolvedListView {
        filters = filters == null ? List.of() : List.copyOf(filters);
        // The grid keys off these verbatim; normalize here so every constructor path (and every
        // renderer) sees a valid feed mode + a positive page size + a non-null grouping.
        feedMode = (feedMode == null || feedMode.isBlank()) ? "infinite" : feedMode;
        pageSize = pageSize <= 0 ? 50 : pageSize;
        grouping = grouping == null ? Grouping.none() : grouping;
    }

    /** Back-compat: a view with no custom body renderer. */
    public ResolvedListView(String title, List<Column> columns,
                            boolean searchable, String sortColumn, boolean sortDescending,
                            List<Filter> filters, MapView mapView,
                            String feedMode, int pageSize, Grouping grouping) {
        this(title, columns, searchable, sortColumn, sortDescending, filters, mapView,
                feedMode, pageSize, grouping, null);
    }

    /** Back-compat: a non-searchable, default-sorted view. */
    public ResolvedListView(String title, List<Column> columns) {
        this(title, columns, false, null, false, List.of(), null, "infinite", 50, Grouping.none());
    }

    /** Back-compat: a view with no declarative filters. */
    public ResolvedListView(String title, List<Column> columns,
                            boolean searchable, String sortColumn, boolean sortDescending) {
        this(title, columns, searchable, sortColumn, sortDescending, List.of(), null, "infinite", 50, Grouping.none());
    }

    /** Back-compat: a view with filters but no map view. */
    public ResolvedListView(String title, List<Column> columns,
                            boolean searchable, String sortColumn, boolean sortDescending,
                            List<Filter> filters) {
        this(title, columns, searchable, sortColumn, sortDescending, filters, null, "infinite", 50, Grouping.none());
    }

    /** Back-compat: a view with filters + map but default (infinite/50) feed + no grouping. */
    public ResolvedListView(String title, List<Column> columns,
                            boolean searchable, String sortColumn, boolean sortDescending,
                            List<Filter> filters, MapView mapView) {
        this(title, columns, searchable, sortColumn, sortDescending, filters, mapView, "infinite", 50, Grouping.none());
    }

    /** Back-compat: a full feed spec but no grouping. */
    public ResolvedListView(String title, List<Column> columns,
                            boolean searchable, String sortColumn, boolean sortDescending,
                            List<Filter> filters, MapView mapView, String feedMode, int pageSize) {
        this(title, columns, searchable, sortColumn, sortDescending, filters, mapView, feedMode, pageSize, Grouping.none());
    }

    /**
     * The list's grouping capability: the columns a user may group by (the "Group by ▾" picker) and
     * the per-group subtotals to show. Empty {@link #columns()} means the list can't be grouped.
     */
    public record Grouping(List<GroupColumn> columns, List<Aggregate> aggregates) {
        public Grouping {
            columns = columns == null ? List.of() : List.copyOf(columns);
            aggregates = aggregates == null ? List.of() : List.copyOf(aggregates);
        }

        public static Grouping none() {
            return new Grouping(List.of(), List.of());
        }

        public boolean isEmpty() {
            return columns.isEmpty();
        }
    }

    /**
     * One groupable column: the data {@code columnName} the {@code GROUP BY} runs on, its picker
     * {@code label}, and whether it is a date/time column ({@code date}) — a date column offers a
     * day/month/year granularity and buckets rows by period instead of exact value.
     */
    public record GroupColumn(String columnName, String label, boolean date) {}

    /**
     * One per-group subtotal: the numeric {@code columnName} to aggregate, the {@code fn}
     * ({@code sum}/{@code avg}/{@code min}/{@code max}), its header {@code label}, and the display
     * {@code format} carried from the column (so a money subtotal renders as money). {@code format}
     * may be blank.
     */
    public record Aggregate(String columnName, String fn, String label, String format) {}

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
     * A custom list-body renderer (see {@code ListSpec.custom}): the widget-registry {@code type}
     * the client resolves the component from, the toolbar-toggle {@code label} (blank = the UI's
     * {@code list.customView} message), and whether the list opens on the custom view. The type is
     * a registry key, not a column — resolution (and degradation to the default grid when nothing
     * is registered under it) happens client-side, where the registry lives.
     */
    public record CustomView(String type, String label, boolean defaultView) {
        public CustomView {
            label = label == null ? "" : label;
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
     * {@code color} (blank when none) carries an {@code @EnumLabel(color=…)} hex so the control can
     * tint the choice like the entity's status pills; {@code avatarUrl} lets reference choices carry
     * target profile photos.
     */
    public record Option(String value, String label, String color, String avatarUrl) {
        public Option {
            label = label == null ? value : label;
            color = color == null ? "" : color;
            avatarUrl = avatarUrl == null ? "" : avatarUrl;
        }

        public Option(String value, String label) {
            this(value, label, "", "");
        }

        public Option(String value, String label, String color) {
            this(value, label, color, "");
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
