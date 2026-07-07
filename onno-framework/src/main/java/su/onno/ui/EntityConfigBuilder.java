package su.onno.ui;

import java.util.ArrayList;
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
public class EntityConfigBuilder {

    private final Map<String, FieldHintBuilder> fields = new LinkedHashMap<>();
    private final Map<String, String> actions = new LinkedHashMap<>();
    private final Map<String, RelatedListBuilder> relatedLists = new LinkedHashMap<>();
    private String icon = "";

    public FieldHintBuilder field(String name) {
        return fields.computeIfAbsent(name, n -> new FieldHintBuilder(this, n));
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
    public RelatedListBuilder relatedList(String name, Class<?> joinCatalog) {
        return relatedLists.computeIfAbsent(name, n -> new RelatedListBuilder(this, n, joinCatalog));
    }

    /**
     * Configure where a detail-header action shows: {@code post}, {@code unpost},
     * {@code edit} or {@code delete}. By default Post is a primary button and the
     * rest live in the overflow (⋯) menu; override per action with
     * {@code .primary()}, {@code .inMenu()} or {@code .hidden()}.
     */
    public ActionHintBuilder action(String name) {
        return new ActionHintBuilder(this, name);
    }

    void putAction(String name, String placement) {
        actions.put(name, placement);
    }

    /** Action placement overrides ({@code action name -> primary|menu|hidden}). */
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

    /** Related-list panels authored on this entity, in declaration order. */
    public List<RelatedList> buildRelatedLists() {
        List<RelatedList> result = new ArrayList<>();
        for (RelatedListBuilder b : relatedLists.values()) {
            result.add(b.build());
        }
        return List.copyOf(result);
    }
}
