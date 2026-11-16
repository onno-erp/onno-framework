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
                               int pageSize, Grouping grouping,
                               CustomView customView, boolean selectionCheckboxes, String selectionWidget,
                               CalendarView calendarView, TimelineView timelineView) {

    /** Backward-compatible constructor: no calendar or timeline view. */
    public ResolvedListView(String title, List<Column> columns, boolean searchable,
                            String sortColumn, boolean sortDescending, List<Filter> filters,
                            MapView mapView, int pageSize, Grouping grouping, CustomView customView,
                            boolean selectionCheckboxes, String selectionWidget) {
        this(title, columns, searchable, sortColumn, sortDescending, filters, mapView,
                pageSize, grouping, customView, selectionCheckboxes, selectionWidget, null, null);
    }

    public ResolvedListView(String title, List<Column> columns, boolean searchable,
                            String sortColumn, boolean sortDescending, List<Filter> filters,
                            MapView mapView, int pageSize, Grouping grouping, CustomView customView,
                            boolean selectionCheckboxes) {
        this(title, columns, searchable, sortColumn, sortDescending, filters, mapView,
                pageSize, grouping, customView, selectionCheckboxes, null);
    }

    /** Backward-compatible constructor: selection checkboxes are opt-in. */
    public ResolvedListView(String title, List<Column> columns, boolean searchable,
                            String sortColumn, boolean sortDescending, List<Filter> filters,
                            MapView mapView, int pageSize, Grouping grouping, CustomView customView) {
        this(title, columns, searchable, sortColumn, sortDescending, filters, mapView,
                pageSize, grouping, customView, false);
    }

    public ResolvedListView {
        filters = filters == null ? List.of() : List.copyOf(filters);
        pageSize = pageSize <= 0 ? 50 : pageSize;
        grouping = grouping == null ? Grouping.none() : grouping;
    }

    /**
     * The list's grouping capability: the columns a user may group by (the "Group by ▾" picker),
     * the per-group subtotals to show, the {@code defaultColumn} the list opens grouped by
     * (blank = opens flat), and whether those bands open {@code expanded} rather than collapsed.
     * Empty {@link #columns()} means the list can't be grouped.
     */
    public record Grouping(List<GroupColumn> columns, List<Aggregate> aggregates, String defaultColumn,
                           boolean expanded) {
        public Grouping {
            columns = columns == null ? List.of() : List.copyOf(columns);
            aggregates = aggregates == null ? List.of() : List.copyOf(aggregates);
            defaultColumn = defaultColumn == null ? "" : defaultColumn;
        }

        /** Backward-compatible constructor: group bands open collapsed. */
        public Grouping(List<GroupColumn> columns, List<Aggregate> aggregates, String defaultColumn) {
            this(columns, aggregates, defaultColumn, false);
        }

        public static Grouping none() {
            return new Grouping(List.of(), List.of(), "", false);
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
     * A resolved map view: the data {@code columnName}s the geometry reads — a marker point from a
     * {@code latField}/{@code lngField} pair and/or a {@code geoJsonField} (GeoJSON
     * points/paths/areas) — an optional
     * {@code labelField} for the popup, and whether the list opens on the map. Column names are
     * resolved + validated against the entity's real columns (an unresolved geo source drops the map
     * view). A blank string means "unset".
     */
    public record MapView(String latField, String lngField, String geoJsonField,
                          String labelField, boolean defaultView) {
        public MapView {
            latField = latField == null ? "" : latField;
            lngField = lngField == null ? "" : lngField;
            geoJsonField = geoJsonField == null ? "" : geoJsonField;
            labelField = labelField == null ? "" : labelField;
        }
    }

    /**
     * One field a calendar/timeline view reads: the logical row {@code key} (what the list feed's
     * rows and the update command call it) and the data {@code column} the feed's range filters
     * address. Both blank means "unset".
     */
    public record ViewField(String key, String column) {
        public static final ViewField NONE = new ViewField("", "");

        public ViewField {
            key = key == null ? "" : key;
            column = column == null ? "" : column;
        }

        public boolean isSet() {
            return !key.isEmpty();
        }
    }

    /**
     * A resolved calendar view (see {@code ListSpec.calendar}): the {@code start} field (always a
     * resolved date/time column — an unresolved one drops the view), the optional {@code end},
     * {@code title} and {@code color} fields, the grid it opens on ({@code "month"}, {@code "week"}
     * or {@code "day"}) and whether the list opens on the calendar.
     */
    public record CalendarView(ViewField start, ViewField end, ViewField title, ViewField color,
                               String mode, boolean defaultView) {}

    /**
     * A resolved timeline view (see {@code ListSpec.timeline}): the {@code start}/{@code end}
     * date-time fields (both resolved — otherwise the view is dropped), the optional {@code lane}
     * reference with the {@code laneKind} ({@code "catalogs"}/{@code "documents"}) and route
     * {@code laneTarget} its lanes are listed from, the optional {@code title}/{@code color}
     * fields, the slot step and the visible hours, and whether the list opens on the timeline.
     */
    public record TimelineView(ViewField start, ViewField end, ViewField lane, String laneKind,
                               String laneTarget, String laneLabel, ViewField title, ViewField color,
                               int slotMinutes, int startHour, int endHour, boolean defaultView) {
        public TimelineView {
            laneKind = laneKind == null ? "" : laneKind;
            laneTarget = laneTarget == null ? "" : laneTarget;
            laneLabel = laneLabel == null ? "" : laneLabel;
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

    }

    /**
     * A resolved column: the header label, the data column it reads, an optional width in whole
     * pixels (e.g. {@code "260"}: a {@code ListSpec.width(...)} override, else a pixel
     * {@code .field(...).width(...)} hint; blank = size to content — form-layout hints such as
     * {@code "half"} never appear here), and
     * the display hints carried over from the attribute — {@code widget} (so a list cell can
     * render an image thumbnail) and {@code format} (a date pattern or number spec applied to the
     * cell value). Both default to blank.
     */
    /** {@code cellMenu}: a row-action submenu label the cell opens directly on right-click
     *  ({@code ListSpec.cellMenu}); blank = the cell has no menu of its own. */
    public record Column(String label, String columnName, String fieldName, String width, String widget,
                         String format, String hint, String cellMenu) {
        /** Backward-compatible constructor for descriptors created before logical response keys. */
        public Column(String label, String columnName, String width, String widget, String format,
                      String hint, String cellMenu) {
            this(label, columnName, columnName, width, widget, format, hint, cellMenu);
        }
    }
}
