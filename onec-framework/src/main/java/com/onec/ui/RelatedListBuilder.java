package com.onec.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds one {@link RelatedList} panel for a catalog editor. Obtained from
 * {@link EntityConfigBuilder#relatedList(String, Class)} inside an {@link EntityView#fields}
 * lambda.
 *
 * <p>Chain {@link #via(String)} / {@link #display(String)} / {@link #columns(String...)};
 * call {@link #relatedList(String, Class)} to add another panel or {@link #field(String)} to
 * switch back to per-field hints on the same entity.</p>
 */
public class RelatedListBuilder {

    private final EntityConfigBuilder parent;
    private final String name;
    private final Class<?> joinCatalog;

    private String via;
    private String display;
    private final List<String> columns = new ArrayList<>();
    private String label = "";
    private boolean hideInDetail = false;

    RelatedListBuilder(EntityConfigBuilder parent, String name, Class<?> joinCatalog) {
        this.parent = parent;
        this.name = name;
        this.joinCatalog = joinCatalog;
    }

    /**
     * The {@code Ref} field on the join catalog that scopes rows to the record being edited —
     * the back-reference to this catalog. Required.
     */
    public RelatedListBuilder via(String field) {
        this.via = field;
        return this;
    }

    /**
     * The {@code Ref} field on the join catalog shown (and picked) per row — the "other side" of
     * the relationship. Resolved to its description for display and used as the add-row picker's
     * target catalog. Required.
     */
    public RelatedListBuilder display(String field) {
        this.display = field;
        return this;
    }

    /**
     * Extra join-row fields to render as columns (e.g. a {@code role} or {@code sortOrder}
     * attribute on the join catalog). When unset, the panel shows just the {@link #display} ref.
     * The {@link #display} ref is always rendered as the row's primary (name) column whether or
     * not it appears here, so an explicit list adds columns on top of the name rather than
     * replacing it; listing the display field is fine and is not duplicated. A field name that
     * matches no attribute on the join catalog (a typo, or a field on a different catalog) is
     * dropped with a {@code WARN} at metadata resolution.
     */
    public RelatedListBuilder columns(String... fields) {
        for (String f : fields) {
            if (!columns.contains(f)) {
                columns.add(f);
            }
        }
        return this;
    }

    /** Heading for the panel; blank derives one from the panel name. */
    public RelatedListBuilder label(String label) {
        this.label = label;
        return this;
    }

    /**
     * Hide this panel in the read/detail view, keeping it only in the edit form. By default a
     * related list renders read-only in the detail view (so the roster is visible without entering
     * edit mode) <em>and</em> editable in the form; call this to opt a panel out of the detail render.
     */
    public RelatedListBuilder hideInDetail() {
        this.hideInDetail = true;
        return this;
    }

    /** Add another related-list panel on the same entity. */
    public RelatedListBuilder relatedList(String name, Class<?> joinCatalog) {
        return parent.relatedList(name, joinCatalog);
    }

    /** Switch back to configuring a plain field on the same entity. */
    public FieldHintBuilder field(String name) {
        return parent.field(name);
    }

    RelatedList build() {
        return new RelatedList(name, joinCatalog, via, display, List.copyOf(columns), label, hideInDetail);
    }
}
