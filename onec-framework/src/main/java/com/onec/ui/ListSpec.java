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

    public String title() { return title; }

    public List<String> include() { return List.copyOf(include); }

    public Set<String> hidden() { return Set.copyOf(hidden); }

    public Map<String, String> labels() { return Map.copyOf(labels); }

    public boolean explicit() { return !include.isEmpty(); }

    public boolean searchable() { return searchable; }

    public String sortField() { return sortField; }

    public boolean sortDescending() { return sortDescending; }
}
