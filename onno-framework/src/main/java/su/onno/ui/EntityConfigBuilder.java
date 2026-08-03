package su.onno.ui;

import su.onno.fields.Field;
import su.onno.fields.Fields;
import su.onno.types.Ref;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-entity configuration scope passed to lambdas on
 * {@code SectionBuilder.catalog/document/register}.
 *
 * <p>Today this exposes field-level hints plus related lists and row/detail actions.
 * Future entity-level UI knobs (default sort, list columns subset, form
 * grouping order, etc.) belong here too.</p>
 */
public class EntityConfigBuilder<E> {

    private final Map<String, FieldHintBuilder<E, ?>> fields = new LinkedHashMap<>();
    private final Map<String, FormValidationBuilder<E>> validations = new LinkedHashMap<>();
    private final Map<String, String> actions = new LinkedHashMap<>();
    private final Map<String, RelatedListBuilder<E, ?>> relatedLists = new LinkedHashMap<>();
    private String icon = "";

    public FieldHintBuilder<E, Object> field(String name) {
        @SuppressWarnings("unchecked")
        FieldHintBuilder<E, Object> result = (FieldHintBuilder<E, Object>) fields.computeIfAbsent(
                name, n -> new FieldHintBuilder<>(this, n));
        return result;
    }

    /** Configure a field using a compiler-checked getter reference. */
    public <V> FieldHintBuilder<E, Object> field(Field<E, V> field) {
        return field(Fields.name(field));
    }

    /** Configure a Ref field while retaining its target type for target-field hints. */
    public <T> FieldHintBuilder<E, T> refField(Field<E, Ref<T>> field) {
        String name = Fields.name(field);
        @SuppressWarnings("unchecked")
        FieldHintBuilder<E, T> result = (FieldHintBuilder<E, T>) fields.computeIfAbsent(
                name, n -> new FieldHintBuilder<>(this, n));
        return result;
    }

    /** Configure a field inside a typed collection/tabular section. */
    public <R, V> FieldHintBuilder<E, Object> rowField(
            Field<E, ? extends Collection<R>> section,
            Field<R, V> rowField
    ) {
        return field(Fields.path(section, rowField));
    }

    /** Configure a Ref inside a typed collection/tabular section, retaining its target type. */
    public <R, T> FieldHintBuilder<E, T> rowRefField(
            Field<E, ? extends Collection<R>> section,
            Field<R, Ref<T>> rowField
    ) {
        String name = Fields.path(section, rowField);
        @SuppressWarnings("unchecked")
        FieldHintBuilder<E, T> result = (FieldHintBuilder<E, T>) fields.computeIfAbsent(
                name, n -> new FieldHintBuilder<>(this, n));
        return result;
    }

    /**
     * Add debounced, dependency-aware live feedback to the generated form. The validator class
     * must be a Spring bean; use {@link FormValidationBuilder#dependsOn} to avoid unrelated calls.
     */
    public FormValidationBuilder<E> validation(String key, Class<? extends FormValidator> validator) {
        return validations.computeIfAbsent(key, n -> new FormValidationBuilder<>(this, n, validator));
    }

    /**
     * Declare an inline related-list (child rows) panel for this catalog editor, backed by a
     * join catalog — the catalog-side analogue of a document's {@code @TabularSection}. Point it
     * at the join {@code @Catalog} class, then say which {@code Ref} scopes rows to this record
     * ({@link RelatedListBuilder#via via}) and which {@code Ref} to show/pick per row
     * ({@link RelatedListBuilder#display display}):
     *
     * <pre>
     * f.relatedList("doctors", ClinicDoctor.class).via("clinic").display("doctor");
     * </pre>
     *
     * <p>See {@link RelatedList}. Editor-only — no schema change; rows are read/written live
     * against the join catalog.</p>
     */
    @SuppressWarnings("unchecked")
    public <J> RelatedListBuilder<E, J> relatedList(String name, Class<J> junction) {
        return (RelatedListBuilder<E, J>) relatedLists.computeIfAbsent(
                name, n -> new RelatedListBuilder<>(this, n, junction));
    }

    /**
     * Configure where a detail-header action shows, keyed exactly as declared through
     * {@link EntityView#actions(ActionSpec)}. Built-ins include {@code post}, {@code unpost},
     * {@code edit}, and {@code delete}; custom detail actions use their authored key too.
     * By default Post is a primary button and the rest live in the overflow (⋯) menu; override with
     * {@code .primary()}, {@code .inMenu()} or {@code .hidden()}.
     */
    public ActionHintBuilder action(String name) {
        return new ActionHintBuilder(this, name);
    }

    void putAction(String name, String placement) {
        actions.put(name, placement);
    }

    /** Action placement overrides ({@code action key -> primary|menu|hidden}). */
    Map<String, String> buildActions() {
        return Map.copyOf(actions);
    }

    /**
     * The nav icon for this entity — any lucide icon name (e.g. {@code "key"},
     * {@code "calendar-check"}). Honored over the keyword heuristic, so an authored
     * icon always wins. Blank means "fall back to the heuristic".
     */
    public EntityConfigBuilder icon(String icon) {
        this.icon = icon;
        return this;
    }

    String buildIcon() {
        return icon;
    }

    public Map<String, FieldHint> buildFieldHints() {
        Map<String, FieldHint> result = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            result.put(entry.getKey(), entry.getValue().build());
        }
        return Map.copyOf(result);
    }

    public List<FormValidation> buildValidations() {
        return validations.values().stream().map(FormValidationBuilder::build).toList();
    }

    /** Related-list panels authored on this entity, in declaration order. */
    public List<RelatedList> buildRelatedLists() {
        List<RelatedList> result = new ArrayList<>();
        for (RelatedListBuilder<E, ?> b : relatedLists.values()) {
            result.add(b.build());
        }
        return List.copyOf(result);
    }
}
