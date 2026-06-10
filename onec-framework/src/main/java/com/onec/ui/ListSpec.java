package com.onec.ui;

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
     * </pre>
     */
    public FilterBuilder filter(String field) {
        FilterBuilder b = new FilterBuilder(field);
        filters.add(b);
        return b;
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
     * A resolved list filter: the bound field, its label, the control type and — for the
     * {@link FilterType#OPTIONS}/{@link FilterType#MULTI_OPTIONS} controls — its choices.
     */
    public record Filter(String field, String label, FilterType type, List<String> options) {
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
        private List<String> options = List.of();

        FilterBuilder(String field) {
            this.field = field;
        }

        /** Override the control's label (defaults to the field name). */
        public FilterBuilder label(String label) {
            this.label = label;
            return this;
        }

        /** A SELECT filter: pick one of {@code options}, matched for equality on the field. */
        public FilterBuilder options(String... options) {
            this.type = FilterType.OPTIONS;
            this.options = List.of(options);
            return this;
        }

        /**
         * A multi-select filter: pick any number of {@code options}, matched as {@code field IN (…)}
         * over the chosen values (an empty selection adds no constraint).
         */
        public FilterBuilder multiOptions(String... options) {
            this.type = FilterType.MULTI_OPTIONS;
            this.options = List.of(options);
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
    }
}
