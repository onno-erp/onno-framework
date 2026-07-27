package su.onno.ui;

import su.onno.fields.Field;
import su.onno.fields.Fields;
import su.onno.types.Ref;

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
public class RelatedListBuilder<E, J> {

    private final EntityConfigBuilder<E> parent;
    private final String name;
    private final Class<?> junction;

    private String via;
    private String display;
    private final List<String> columns = new ArrayList<>();
    private String label = "";
    private boolean hideInDetail = false;

    RelatedListBuilder(EntityConfigBuilder<E> parent, String name, Class<J> junction) {
        this.parent = parent;
        this.name = name;
        this.junction = junction;
    }

    /**
     * The {@code Ref} field on the junction that scopes rows to the record being edited —
     * the back-reference to this catalog. Required.
     */
    public RelatedListBuilder<E, J> via(String field) {
        this.via = field;
        return this;
    }

    /** Compiler-checked back-reference to the entity owning this related list. */
    public RelatedListBuilder<E, J> via(Field<J, Ref<E>> field) {
        return via(Fields.name(field));
    }

    /**
     * The {@code Ref} field on the junction shown (and picked) per row — the "other side" of
     * the relationship. Resolved to its description for display and used as the add-row picker's
     * target catalog. Required.
     */
    public RelatedListBuilder<E, J> display(String field) {
        this.display = field;
        return this;
    }

    /** Compiler-checked reference shown as the related row's primary value. */
    public <T> RelatedListBuilder<E, J> display(Field<J, Ref<T>> field) {
        return display(Fields.name(field));
    }

    /**
     * Extra join-row fields to render as columns (e.g. a {@code role} or {@code sortOrder}
     * attribute on the junction). When unset, the panel shows just the {@link #display} ref.
     * The {@link #display} ref is always rendered as the row's primary (name) column whether or
     * not it appears here, so an explicit list adds columns on top of the name rather than
     * replacing it; listing the display field is fine and is not duplicated. A field name that
     * matches no attribute on the junction (a typo, or a field on a different entity) is
     * dropped with a {@code WARN} at metadata resolution.
     */
    public RelatedListBuilder<E, J> columns(String... fields) {
        for (String f : fields) {
            if (!columns.contains(f)) {
                columns.add(f);
            }
        }
        return this;
    }

    /** Compiler-checked join-row columns. */
    @SafeVarargs
    public final RelatedListBuilder<E, J> columns(Field<J, ?>... fields) {
        for (Field<J, ?> field : fields) {
            String name = Fields.name(field);
            if (!columns.contains(name)) {
                columns.add(name);
            }
        }
        return this;
    }

    /** Heading for the panel; blank derives one from the panel name. */
    public RelatedListBuilder<E, J> label(String label) {
        this.label = label;
        return this;
    }

    /**
     * Hide this panel in the read/detail view, keeping it only in the edit form. By default a
     * related list renders read-only in the detail view (so the roster is visible without entering
     * edit mode) <em>and</em> editable in the form; call this to opt a panel out of the detail render.
     */
    public RelatedListBuilder<E, J> hideInDetail() {
        this.hideInDetail = true;
        return this;
    }

    /** Add another related-list panel on the same entity. */
    public <K> RelatedListBuilder<E, K> relatedList(String name, Class<K> junction) {
        return parent.relatedList(name, junction);
    }

    /** Switch back to configuring a plain field on the same entity. */
    public FieldHintBuilder<E, Object> field(String name) {
        return parent.field(name);
    }

    /** Switch back to a compiler-checked field on the owning entity. */
    public <V> FieldHintBuilder<E, Object> field(Field<E, V> field) {
        return parent.field(field);
    }

    RelatedList build() {
        return new RelatedList(name, junction, via, display, List.copyOf(columns), label, hideInDetail);
    }
}
