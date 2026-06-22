package su.onno.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builder for an entity's list/table surface, used inside {@link EntityView#list}.
 *
 * <p>With no calls the list shows the auto-generated columns (built-in system
 * columns + visible custom fields, in their configured order). Call
 * {@link #columns} to take explicit control of which columns appear and in what
 * order, or {@link #hide}/{@link #label} to tweak the defaults. Field names are
 * the entity's Java field names (e.g. {@code "displayName"}); {@code "code"},
 * {@code "description"} (catalogs) and {@code "number"}, {@code "date"},
 * {@code "posted"} (documents) address the built-in system columns.</p>
 */
public final class ListSpec {

    private String title;
    private final List<String> include = new ArrayList<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private boolean searchable = true;
    private String sortField;
    private boolean sortDescending = false;
    private final List<FilterBuilder> filters = new ArrayList<>();
    private MapSpec map;

    public ListSpec title(String title) {
        this.title = title;
        return this;
    }

    /** Whether the list shows a search bar (server-side filter across text columns). Default on. */
    public ListSpec searchable(boolean searchable) {
        this.searchable = searchable;
        return this;
    }

    /** Turn the search bar off for this list. */
    public ListSpec noSearch() {
        return searchable(false);
    }

    /** The column the list is sorted by initially (a field name); ascending. */
    public ListSpec sortBy(String field) {
        return sortBy(field, false);
    }

    /** The initial sort column + direction. */
    public ListSpec sortBy(String field, boolean descending) {
        this.sortField = field;
        this.sortDescending = descending;
        return this;
    }

    /** Take explicit control: only these fields, in this order. */
    public ListSpec columns(String... fields) {
        include.addAll(List.of(fields));
        return this;
    }

    /** Add an explicit column with a custom header label. */
    public ListSpec column(String field, String label) {
        include.add(field);
        labels.put(field, label);
        return this;
    }

    /** Override a column's header label. */
    public ListSpec label(String field, String label) {
        labels.put(field, label);
        return this;
    }

    /** Hide fields from the default column set (ignored when {@link #columns} is used). */
    public ListSpec hide(String... fields) {
        hidden.addAll(List.of(fields));
        return this;
    }

    /**
     * Declare a user-facing filter control bound to {@code field} (an entity field name, like the
     * column/sort field names). Unlike a toolbar {@link InputSpec input} — which feeds action
     * handlers — a filter drives the list query itself: its value narrows the rows the grid shows.
     * Returns a {@link FilterBuilder}; pick the control with {@link FilterBuilder#options} (a SELECT
     * matched for equality), {@link FilterBuilder#multiOptions} (a multi-select matched as
     * {@code field IN (…)}), {@link FilterBuilder#contains}/{@link FilterBuilder#startsWith} (a
     * field-scoped typeahead for high-cardinality fields, matched case-insensitively as
     * {@code LIKE}), or {@link FilterBuilder#dateRange} (from/to pickers, a {@code field >= from AND
     * field <= to} range).
     *
     * <p>When several filters are declared they combine with {@code AND}: each contributes its own
     * {@code WHERE} fragment and the row must satisfy all of them. A {@code multiOptions} filter is
     * internally an {@code OR}/{@code IN} over its picked values, but across different filters the
     * combination is always {@code AND}. A filter whose control is left empty (no selection, blank
     * text) contributes no constraint, and a filter on a field the entity no longer has degrades to
     * "no constraint" rather than failing the list.</p>
     *
     * <pre>
     * list.filter("season").options("2024", "2025", "2026");        // SELECT -> season = value
     * list.filter("doctorName").label("Doctor").contains();         // typeahead -> doctor_name ILIKE %v%
     * list.filter("role").multiOptions("Хирург", "Терапевт");        // multi-select -> role IN (…)
     * list.filter("checkIn").dateRange();                           // from/to pickers -> checkIn range
     * // value→label split: the query matches the stored value, the dropdown shows the label
     * var statuses = new LinkedHashMap&lt;String, String&gt;();           // ordered: dropdown follows it
     * statuses.put("NEW", "Новый"); statuses.put("FILES_RECEIVED", "Файлы получены");
     * list.filter("statusName").label("Статус").multiOptions(statuses);
     * </pre>
     */
    public FilterBuilder filter(String field) {
        FilterBuilder b = new FilterBuilder(field);
        filters.add(b);
        return b;
    }

    /**
     * Enable a map view for this list: a Table ⇄ Map toggle in the toolbar that plots the records as
     * markers over OpenStreetMap tiles. Returns a {@link MapSpec}; tell it where each record's point
     * comes from — a single {@code "lat,lng"} string field via {@link MapSpec#field}, or a numeric
     * latitude/longitude pair via {@link MapSpec#lat}/{@link MapSpec#lng} — and optionally a
     * {@link MapSpec#label} field for the marker popup and {@link MapSpec#defaultView} to open on the
     * map. Field names are entity field names, like the column/sort/filter ones.
     *
     * <p>Calling {@code map()} more than once returns the same spec (so chained calls accumulate).
     * A map whose geo field(s) don't resolve to real columns degrades to "no map view" rather than
     * failing the list.</p>
     *
     * <pre>
     * list.map().field("location").label("name");          // "lat,lng" string field
     * list.map().lat("latitude").lng("longitude");          // split numeric fields
     * list.map().field("location").defaultView();           // open on the map, not the table
     * </pre>
     */
    public MapSpec map() {
        if (map == null) {
            map = new MapSpec();
        }
        return map;
    }

    public String title() { return title; }

    public List<String> include() { return List.copyOf(include); }

    public Set<String> hidden() { return Set.copyOf(hidden); }

    public Map<String, String> labels() { return Map.copyOf(labels); }

    public boolean explicit() { return !include.isEmpty(); }

    public boolean searchable() { return searchable; }

    public String sortField() { return sortField; }

    public boolean sortDescending() { return sortDescending; }

    /** The declared list filters, in declaration order. */
    public List<Filter> filters() {
        return filters.stream().map(FilterBuilder::build).toList();
    }

    /** The map view spec, or null when {@link #map()} was never called (no map view). */
    public MapSpec mapSpec() {
        return map;
    }

    /**
     * Where a list's map geometry comes from, and how it reads. A marker point comes from either a
     * single combined {@link #field} ({@code "lat,lng"} string) or a {@link #lat}/{@link #lng} pair;
     * arbitrary shapes (points/paths/areas) come from a {@link #geoJson} field (a GeoJSON string). A
     * record may use any combination. {@link #label} names the field shown in a feature's popup;
     * {@link #defaultView} opens the list on the map rather than the table.
     */
    public static final class MapSpec {
        private String field;
        private String latField;
        private String lngField;
        private String geoJsonField;
        private String labelField;
        private boolean defaultView = false;

        MapSpec() {}

        /** A single {@code "lat,lng"} string field for a marker point (what {@code .widget("map")} writes). */
        public MapSpec field(String field) {
            this.field = field;
            return this;
        }

        /** The latitude field, when the point is stored as a numeric lat/lng pair. */
        public MapSpec lat(String latField) {
            this.latField = latField;
            return this;
        }

        /** The longitude field, when the point is stored as a numeric lat/lng pair. */
        public MapSpec lng(String lngField) {
            this.lngField = lngField;
            return this;
        }

        /** A GeoJSON field for arbitrary geometry — points, paths, and areas (what {@code .widget("geojson")} writes). */
        public MapSpec geoJson(String geoJsonField) {
            this.geoJsonField = geoJsonField;
            return this;
        }

        /** The field shown in a feature's popup (defaults to a system identifier when unset). */
        public MapSpec label(String labelField) {
            this.labelField = labelField;
            return this;
        }

        /** Open the list on the map view rather than the table. */
        public MapSpec defaultView() {
            this.defaultView = true;
            return this;
        }

        public String field() { return field; }

        public String latField() { return latField; }

        public String lngField() { return lngField; }

        public String geoJsonField() { return geoJsonField; }

        public String labelField() { return labelField; }

        public boolean isDefaultView() { return defaultView; }
    }

    /** How a filter narrows the list query (and which control the grid renders). */
    public enum FilterType {
        /** A SELECT of {@link Filter#options}; the chosen value is matched for equality on the field. */
        OPTIONS,
        /** A multi-select of {@link Filter#options}; the picked values match as {@code field IN (…)}. */
        MULTI_OPTIONS,
        /** A debounced text typeahead; the text matches case-insensitively as {@code field LIKE %v%}. */
        CONTAINS,
        /** A debounced text typeahead; the text matches case-insensitively as {@code field LIKE v%}. */
        STARTS_WITH,
        /** A pair of from/to date pickers driving a {@code field >= from AND field <= to} range. */
        DATE_RANGE
    }

    /**
     * One choice of a {@link FilterType#OPTIONS}/{@link FilterType#MULTI_OPTIONS} filter: the
     * {@code value} matched against the field by the query, and the {@code label} the UI renders for
     * it. When a filter is declared with the plain {@code String...} overloads the two are identical
     * (the value is shown verbatim); the {@code Map<String,String>} overloads carry a value→label
     * split so a filter over a code/English/enum-mirror column can show a localized choice.
     */
    public record Option(String value, String label) {
        public Option {
            label = label == null ? value : label;
        }
    }

    /**
     * A resolved list filter: the bound field, its label, the control type and — for the
     * {@link FilterType#OPTIONS}/{@link FilterType#MULTI_OPTIONS} controls — its
     * {@link Option choices} (each a value→label pair).
     */
    public record Filter(String field, String label, FilterType type, List<Option> options) {
        public Filter {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * Fluent builder for one filter; {@link #options}/{@link #multiOptions}/{@link #contains}/
     * {@link #startsWith}/{@link #dateRange} pick the control type.
     */
    public static final class FilterBuilder {
        private final String field;
        private String label;
        private FilterType type = FilterType.OPTIONS;
        private List<Option> options = List.of();

        FilterBuilder(String field) {
            this.field = field;
        }

        /** Override the control's label (defaults to the field name). */
        public FilterBuilder label(String label) {
            this.label = label;
            return this;
        }

        /**
         * A SELECT filter: pick one of {@code options}, matched for equality on the field. Each
         * option is shown verbatim (its value is also its label); use {@link #options(Map)} when the
         * displayed choice should differ from the matched value.
         */
        public FilterBuilder options(String... options) {
            this.type = FilterType.OPTIONS;
            this.options = identity(options);
            return this;
        }

        /**
         * A SELECT filter with a value→label split: the query matches each map <em>key</em> on the
         * field while the dropdown renders the map <em>value</em>. The choices appear in the map's
         * iteration order, so pass an ordered map (e.g. {@link java.util.LinkedHashMap}) to control
         * the dropdown order — {@code Map.of(...)} is unordered. The answer for a filter over a
         * column whose stored values are codes/English (or an enum text-mirror) in a localized UI.
         */
        public FilterBuilder options(Map<String, String> valueToLabel) {
            this.type = FilterType.OPTIONS;
            this.options = pairs(valueToLabel);
            return this;
        }

        /**
         * A multi-select filter: pick any number of {@code options}, matched as {@code field IN (…)}
         * over the chosen values (an empty selection adds no constraint). Each option is shown
         * verbatim; use {@link #multiOptions(Map)} for a value→label split.
         */
        public FilterBuilder multiOptions(String... options) {
            this.type = FilterType.MULTI_OPTIONS;
            this.options = identity(options);
            return this;
        }

        /**
         * A multi-select filter with a value→label split: the query matches the map <em>keys</em> as
         * {@code field IN (…)} while the dropdown renders the map <em>values</em>. The choices appear
         * in the map's iteration order, so pass an ordered map (e.g.
         * {@link java.util.LinkedHashMap}) to control the dropdown order — {@code Map.of(...)} is
         * unordered.
         */
        public FilterBuilder multiOptions(Map<String, String> valueToLabel) {
            this.type = FilterType.MULTI_OPTIONS;
            this.options = pairs(valueToLabel);
            return this;
        }

        /**
         * A field-scoped typeahead: a debounced text input matched case-insensitively as
         * {@code field LIKE %text%}. The high-cardinality answer where a SELECT of every value would
         * be unusable (e.g. filter a roster by a ~1.4k-name doctor column).
         */
        public FilterBuilder contains() {
            this.type = FilterType.CONTAINS;
            return this;
        }

        /** Like {@link #contains} but anchored at the start: matched as {@code field LIKE text%}. */
        public FilterBuilder startsWith() {
            this.type = FilterType.STARTS_WITH;
            return this;
        }

        /** A from/to date-range filter (two date pickers) over the field. */
        public FilterBuilder dateRange() {
            this.type = FilterType.DATE_RANGE;
            return this;
        }

        Filter build() {
            return new Filter(field, label != null ? label : field, type, options);
        }

        /** Plain values shown verbatim (value == label). */
        private static List<Option> identity(String... values) {
            List<Option> opts = new ArrayList<>(values.length);
            for (String v : values) {
                opts.add(new Option(v, v));
            }
            return opts;
        }

        /** Value→label pairs in the map's iteration order. */
        private static List<Option> pairs(Map<String, String> valueToLabel) {
            List<Option> opts = new ArrayList<>(valueToLabel.size());
            valueToLabel.forEach((value, label) -> opts.add(new Option(value, label)));
            return opts;
        }
    }
}
