package su.onno.ui;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.MetadataRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves descriptor metadata into a JSON-shaped attribute view, merging field
 * hints over descriptor defaults. Consumed by the DivKit emitters (list columns,
 * document detail, register reports) so every surface sees an identical,
 * field-hint-aware view of an entity's fields.
 */
public class ResolvedMetadataService {

    private static final Logger log = LoggerFactory.getLogger(ResolvedMetadataService.class);

    private final MetadataRegistry registry;
    private final FieldHintResolver fieldHints;

    public ResolvedMetadataService(MetadataRegistry registry, FieldHintResolver fieldHints) {
        this.registry = registry;
        this.fieldHints = fieldHints;
    }

    public Map<String, Object> describeCatalog(CatalogDescriptor d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", d.logicalName());
        map.put("title", d.displayTitle());
        map.put("tableName", d.tableName());
        map.put("codeLength", d.codeLength());
        map.put("hierarchical", d.hierarchical());
        map.put("autoNumber", d.autoNumber());
        map.put("codePrefix", d.codePrefix());
        map.put("context", d.context());
        map.put("readRoles", d.readRoles());
        map.put("writeRoles", d.writeRoles());
        Map<String, FieldHint> hints = fieldHints.forEntity(d.javaClass());
        map.put("attributes", describeAttributes(d.attributes(), hints));
        map.put("systemColumns", List.of(
                systemColumn("code", "Code", "_code", hints),
                systemColumn("description", "Description", "_description", hints)));
        map.put("relatedLists", describeRelatedLists(d.javaClass(), d.logicalName()));
        map.put("formValidations", describeFormValidations(d.javaClass()));
        return map;
    }

    /**
     * Resolves an entity's declared related-list panels (see {@link RelatedList}) into the JSON
     * both the form widget and the detail surface (read-only, when {@code showInDetail}) render.
     * Authored on catalogs <em>and</em> documents — 1C surfaces subordinate/related records on both
     * — so this works off the owning entity's java class, not a catalog-specific descriptor.
     *
     * <p>Each panel is resolved against its <em>junction</em>'s scanned metadata, where the
     * junction is a join catalog <em>or</em> an information register (see {@link Junctions}): the
     * {@code via} ref (back-reference that scopes rows to this record), the {@code display} ref
     * (the other side, also the add-row picker's target), and the columns to show. The
     * {@code display} ref is always included so the row's primary identity can never be dropped,
     * even when an explicit {@code columns()} list omits it; a named column that isn't a field on
     * the junction is dropped with a WARN. A panel pointing at an unregistered class, or naming a
     * {@code via}/{@code display} field that isn't a ref on the junction, is dropped with no panel
     * emitted — the editor degrades gracefully rather than breaking the whole form.</p>
     *
     * <p>Register-backed panels are flagged {@code readOnly} (and {@code sourceKind} {@code
     * "register"}): they render rows in both directions but offer no inline add/remove, since
     * information registers have no generic write REST surface yet.</p>
     */
    private List<Map<String, Object>> describeRelatedLists(Class<?> parentClass, String parentLogicalName) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (RelatedList rl : fieldHints.relatedListsFor(parentClass)) {
            Junctions.Junction junction = Junctions.resolve(registry, rl.joinCatalog());
            if (junction == null) {
                continue;
            }
            AttributeDescriptor via = Junctions.refField(junction, rl.via());
            AttributeDescriptor display = Junctions.refField(junction, rl.display());
            if (via == null || display == null) {
                continue;
            }
            // Resolve the author-declared columns against the junction's fields. An explicit list
            // adds attributes (role, sortOrder, …) on top of the name. A named column that matches
            // no field on the junction — a typo, or a field that lives elsewhere — is dropped, but
            // with a WARN: it's static config knowable up front, and silently dropping it renders
            // an incomplete (or empty) panel with no hint as to why (see #108).
            List<AttributeDescriptor> columns = new ArrayList<>();
            for (String field : rl.columns()) {
                AttributeDescriptor attr = Junctions.field(junction, field);
                if (attr == null) {
                    log.warn("Related-list panel '{}' on '{}' names column '{}', which is not a "
                            + "field on junction '{}' — dropping it. Check for a typo or a field "
                            + "that lives on a different entity.",
                            rl.name(), parentLogicalName, field, junction.logicalName());
                    continue;
                }
                columns.add(attr);
            }
            // The display ref is the row's primary identity; always render it, even when an
            // explicit columns() list omits it, so the name can never be accidentally dropped and
            // the panel can never collapse to blank rows. Prepend when absent (matching the
            // default, name-first layout); the builder already dedups, so this won't duplicate.
            if (columns.stream().noneMatch(a -> a.fieldName().equals(display.fieldName()))) {
                columns.add(0, display);
            }

            boolean targetIsDocument = registry.allDocuments().stream()
                    .anyMatch(doc -> doc.logicalName().equals(display.refTarget()));

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", rl.name());
            m.put("label", rl.label());
            // The junction the panel reads (and, for a catalog, writes) — its logical name. Kept
            // under "joinCatalog" for back-compat (catalog add/remove keys off it); "sourceKind"
            // tells the client whether it's a catalog or an information register.
            m.put("joinCatalog", junction.logicalName());
            m.put("sourceKind", junction.isRegister() ? "register" : "catalog");
            // Register-backed junctions are read-only (no generic info-register write yet); the
            // form widget hides add/remove for these and renders the rows only.
            m.put("readOnly", junction.isRegister());
            // Field the add-row create binds to the parent record, and the column the list query
            // filters on; both keyed by field name (the REST write contract takes field names).
            m.put("viaField", via.fieldName());
            // The other side: field picked per row, and the catalog/document it points at.
            m.put("displayField", display.fieldName());
            m.put("target", display.refTarget());
            m.put("targetKind", targetIsDocument ? "document" : "catalog");
            // Whether the panel also renders read-only in the detail/read view (default true); the
            // form widget renders every panel regardless, the detail surface honors this flag.
            m.put("showInDetail", !rl.hideInDetail());
            // Catalog junctions carry per-field UI hints from their own view; registers don't yet.
            Map<String, FieldHint> joinHints = junction.isRegister()
                    ? Map.of() : fieldHints.forEntity(junction.catalog().javaClass());
            m.put("columns", describeAttributes(columns, joinHints));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> describeDocument(DocumentDescriptor d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", d.logicalName());
        map.put("title", d.displayTitle());
        map.put("tableName", d.tableName());
        map.put("numberLength", d.numberLength());
        map.put("autoNumber", d.autoNumber());
        map.put("numberPrefix", d.numberPrefix());
        map.put("context", d.context());
        map.put("readRoles", d.readRoles());
        map.put("writeRoles", d.writeRoles());
        // Whether this document type can be posted (implements Postable). The UI uses it to
        // decide whether to offer Post / Re-post / Unpost actions; a non-postable document
        // only ever gets a plain Save.
        map.put("postable", su.onno.lifecycle.Postable.class.isAssignableFrom(d.javaClass()));
        // Detail-header action placement. Default mirrors 1C: Post is the primary
        // button, Unpost/Edit/Delete live in the overflow (⋯) menu. A view overrides
        // per action via f.action("...").primary()/.inMenu()/.hidden().
        Map<String, String> actionOverrides = fieldHints.actionsFor(d.javaClass());
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("post", actionOverrides.getOrDefault("post", "primary"));
        actions.put("unpost", actionOverrides.getOrDefault("unpost", "menu"));
        actions.put("edit", actionOverrides.getOrDefault("edit", "menu"));
        actions.put("delete", actionOverrides.getOrDefault("delete", "menu"));
        map.put("actions", actions);
        Map<String, FieldHint> hints = fieldHints.forEntity(d.javaClass());
        map.put("attributes", describeAttributes(d.attributes(), hints));
        map.put("systemColumns", List.of(
                systemColumn("number", "Number", "_number", hints),
                systemColumn("date", "Date", "_date", hints),
                systemColumn("posted", "Status", "_posted", hints)));
        map.put("tabularSections", d.tabularSections().stream().map(ts -> {
            Map<String, Object> tsMap = new LinkedHashMap<>();
            tsMap.put("name", ts.name());
            tsMap.put("tableName", ts.tableName());
            // Tabular-section column hints are addressed with a section-scoped key on the document's
            // own EntityView — e.g. f.field("items.unitPrice").format("currency:USD"). The prefix is
            // the section name; it keeps a line-field hint from colliding with a same-named top-level
            // field, and a document with no such keys behaves exactly as before (empty hint map).
            tsMap.put("attributes", describeAttributes(ts.attributes(), sectionHints(ts.name(), hints)));
            return tsMap;
        }).toList());
        // Documents surface related-list panels too (1C parity): a booking can show its guests —
        // the reverse side of a Booking↔Client junction — read-only on the detail and editable in
        // the form, exactly like a catalog. See #110.
        map.put("relatedLists", describeRelatedLists(d.javaClass(), d.logicalName()));
        map.put("formValidations", describeFormValidations(d.javaClass()));
        return map;
    }

    private List<Map<String, Object>> describeFormValidations(Class<?> entity) {
        return fieldHints.validationsFor(entity).stream().map(validation -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", validation.key());
            map.put("dependencies", validation.dependencies());
            map.put("debounceMillis", validation.debounceMillis());
            return map;
        }).toList();
    }

    /**
     * Raw per-action placement overrides authored on an entity's view via
     * {@code f.action(key).primary()/inMenu()/hidden()} — keyed by action key, valued
     * {@code primary|menu|hidden}. Unlike the {@code "actions"} map baked into
     * {@link #describeDocument} (which only carries the built-in post/unpost/edit/delete defaults),
     * this includes <em>custom</em> DETAIL action keys, so {@code DivKitController.detailActions}
     * can honor a placement override on a custom action too (issue #183).
     */
    public Map<String, String> actionOverrides(Class<?> entity) {
        return fieldHints.actionsFor(entity);
    }

    public Map<String, Object> describeRegister(AccumulationRegisterDescriptor d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", d.logicalName());
        map.put("title", d.displayTitle());
        map.put("tableName", d.tableName());
        map.put("type", d.accumulationType().name());
        map.put("context", d.context());
        map.put("readRoles", d.readRoles());
        map.put("writeRoles", d.writeRoles());
        Map<String, FieldHint> hints = fieldHints.forEntity(d.javaClass());
        map.put("dimensions", describeAttributes(d.dimensions(), hints));
        map.put("resources", describeAttributes(d.resources(), hints));
        // Display format for the movement timestamp column, authored on the register's view as
        // field("period").format(…) — the register analogue of a document's _date system column.
        FieldHint periodHint = hints.get("period");
        map.put("periodFormat", periodHint == null ? "" : pick(periodHint.format(), ""));
        return map;
    }

    /**
     * Describes a built-in system column (code/description/number/date/posted) so it
     * honors the same field-hint config as custom attributes — a developer can hide
     * or reorder it from the layout DSL (e.g. {@code .field("code").hideInList()})
     * without touching frontend code. Visibility defaults to shown when unset.
     *
     * <p>The hardcoded English {@code displayName} ("Code"/"Description"/"Number"/"Date"/"Status")
     * is a fallback: a {@code .field(fieldName).label("…")} hint overrides it, so system-column
     * labels localize from the layout DSL the same way custom attributes do (see #154).</p>
     */
    private Map<String, Object> systemColumn(String fieldName, String displayName, String columnName,
                                             Map<String, FieldHint> hints) {
        FieldHint hint = hints.get(fieldName);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fieldName", fieldName);
        map.put("displayName", pick(hint == null ? null : hint.label(), displayName));
        map.put("columnName", columnName);
        map.put("visibleInList", pick(hint == null ? null : hint.visibleInList(), Boolean.TRUE));
        map.put("visibleInDetail", pick(hint == null ? null : hint.visibleInDetail(), Boolean.TRUE));
        map.put("order", pick(hint == null ? null : hint.order(), Integer.MIN_VALUE));
        // A width hint on a system column (e.g. .field("description").width("320")) lets a
        // wide built-in column size itself like any custom attribute; blank = auto-fit.
        map.put("widthHint", hint == null ? "" : pick(hint.width(), ""));
        map.put("placeholder", hint == null ? "" : pick(hint.placeholder(), ""));
        // Display format (e.g. a date pattern on the _date column): list + detail rendering.
        map.put("format", hint == null ? "" : pick(hint.format(), ""));
        return map;
    }

    /**
     * The subset of a document's field hints that target a tabular section, re-keyed to the bare row
     * field name. A hint authored as {@code field("items.unitPrice")} on the document's view is keyed
     * {@code "items.unitPrice"}; for section {@code "items"} this returns it under {@code "unitPrice"}
     * so {@link #describeAttributes} matches it to the row attribute. Returns an empty map when no
     * hint targets the section, preserving the prior (hint-less) behaviour.
     */
    private static Map<String, FieldHint> sectionHints(String sectionName, Map<String, FieldHint> hints) {
        String prefix = sectionName + ".";
        Map<String, FieldHint> scoped = new LinkedHashMap<>();
        for (Map.Entry<String, FieldHint> e : hints.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                scoped.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return scoped;
    }

    public List<Map<String, Object>> describeAttributes(List<AttributeDescriptor> attrs,
                                                        Map<String, FieldHint> layoutHints) {
        return attrs.stream().map(a -> {
            FieldHint hint = layoutHints.get(a.fieldName());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("fieldName", a.fieldName());
            // A .field(name).label("…") hint overrides the attribute's @Attribute(displayName=…),
            // so a localized label can be authored from the layout DSL (#154); otherwise the
            // descriptor's display name stands.
            map.put("displayName", pick(hint == null ? null : hint.label(), a.displayName()));
            map.put("columnName", a.columnName());
            map.put("javaType", a.javaType().getSimpleName());
            map.put("length", a.length());
            map.put("required", a.required());
            map.put("isRef", a.isRef());
            map.put("refTarget", a.refTarget());
            // Tell the UI whether a ref points at a catalog or a document so the picker
            // can hit the right endpoints. refTarget is the registered logical name, so a
            // matching document means it's a document ref; otherwise treat it as a catalog.
            if (a.isRef() && a.refTarget() != null) {
                boolean isDocument = registry.allDocuments().stream()
                        .anyMatch(d -> d.logicalName().equals(a.refTarget()));
                map.put("refKind", isDocument ? "document" : "catalog");
                // Optional secondary attribute shown under the name in the ref picker (issue #184).
                // The hint names a field on the target; resolve it to that target's column so the
                // client reads the right key from the option payload (which carries every column).
                String secondary = hint == null ? null : hint.refSecondary();
                if (secondary != null && !secondary.isBlank()) {
                    map.put("refSecondary", refSecondaryColumn(a.refTarget(), secondary));
                }
                // Cascading picker predicate (f.field(...).refFilter("supplier = ${supplier}")).
                // Authored against the target's field names; rewrite each clause's left-hand side
                // to the target's column name so the client can send it straight to the typeahead's
                // ?filter= (which WidgetFilter validates against columns). ${...} placeholders are
                // the client's to substitute with current form values.
                String refFilter = hint == null ? null : hint.refFilter();
                if (refFilter != null && !refFilter.isBlank()) {
                    map.put("refFilter", refFilterColumns(a.refTarget(), refFilter));
                }
                String refOptionDecorator = hint == null ? null : hint.refOptionDecorator();
                if (refOptionDecorator != null && !refOptionDecorator.isBlank()) {
                    map.put("refOptionDecorator", refOptionDecorator);
                }
                if (Boolean.TRUE.equals(hint == null ? null : hint.uniqueWithinSection())) {
                    map.put("uniqueWithinSection", true);
                }
            }
            map.put("precision", a.precision());
            map.put("scale", a.scale());
            // Tell the UI to render a write-only password control and a "set / not set"
            // indicator instead of the value (which the read API never returns).
            map.put("secret", a.secret());
            // Layout hints win when set; otherwise fall back to scanner defaults.
            map.put("visibleInList", pick(hint == null ? null : hint.visibleInList(), a.visibleInList()));
            map.put("visibleInForm", pick(hint == null ? null : hint.visibleInForm(), a.visibleInForm()));
            map.put("visibleInDetail", pick(hint == null ? null : hint.visibleInDetail(), a.visibleInDetail()));
            map.put("order", pick(hint == null ? null : hint.order(), a.order()));
            map.put("group", pick(hint == null ? null : hint.group(), a.group()));
            map.put("widthHint", pick(hint == null ? null : hint.width(), a.widthHint()));
            map.put("widget", pick(hint == null ? null : hint.widget(), a.widget()));
            // Display format for list/detail cells (date pattern or number spec); DSL-only hint.
            map.put("format", pick(hint == null ? null : hint.format(), ""));
            // Optional help text, surfaced as a hoverable "?" icon next to the field's label; DSL-only.
            map.put("hint", pick(hint == null ? null : hint.hint(), ""));
            // Edit-form placeholder (UI hint only) + the declarative validation constraints the
            // client mirrors for instant inline errors. Bounds are emitted only when set.
            map.put("placeholder", pick(hint == null ? null : hint.placeholder(), ""));
            su.onno.metadata.AttributeDescriptor.Constraints c = a.constraints();
            if (c.hasMin()) {
                map.put("min", c.min());
            }
            if (c.hasMax()) {
                map.put("max", c.max());
            }
            if (c.minLength() > 0) {
                map.put("minLength", c.minLength());
            }
            if (!c.pattern().isBlank()) {
                map.put("pattern", c.pattern());
            }
            if (c.email()) {
                map.put("email", true);
            }
            boolean isEnum = a.javaType().isEnum();
            map.put("isEnum", isEnum);
            if (isEnum) {
                EnumerationDescriptor enumDesc = registry.allEnumerations().stream()
                        .filter(e -> e.javaClass().equals(a.javaType()))
                        .findFirst().orElse(null);
                if (enumDesc != null) {
                    map.put("enumName", enumDesc.logicalName());
                    map.put("enumTitle", enumDesc.displayTitle());
                    map.put("enumValues", enumDesc.values().stream().map(v -> {
                        Map<String, Object> vm = new LinkedHashMap<>();
                        vm.put("name", v.name());
                        vm.put("label", v.label());
                        // Badge colour for a status pill in the form dropdown / detail view; omitted
                        // when the value declares no @EnumLabel(color = …), so it reads as plain text.
                        if (!v.color().isEmpty()) {
                            vm.put("color", v.color());
                        }
                        vm.put("id", v.id().toString());
                        return vm;
                    }).toList());
                }
            }
            return map;
        }).toList();
    }

    private static <T> T pick(T fromHint, T fromDescriptor) {
        return fromHint != null ? fromHint : fromDescriptor;
    }

    /**
     * Resolve a ref picker's secondary field (named against the target entity) to the target's
     * actual column name, so the client reads the right key. Matches by field name or column name;
     * an unrecognized name falls through unchanged (best effort — a typo shows nothing rather than
     * breaking the picker).
     */
    /**
     * Rewrite a {@code refFilter} template's clause left-hand sides from the target entity's field
     * names to its column names (same best-effort mapping as {@link #refSecondaryColumn}), leaving
     * operators, values, and {@code ${...}} placeholders untouched — e.g.
     * {@code "assignedTo = ${manager} AND active = true"} → {@code "assigned_to = ${manager} AND active = true"}.
     */
    private String refFilterColumns(String refTarget, String template) {
        return java.util.regex.Pattern.compile("(?i)\\s+AND\\s+").splitAsStream(template.trim())
                .map(clause -> {
                    var m = java.util.regex.Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)(.*)$")
                            .matcher(clause);
                    return m.matches() ? refSecondaryColumn(refTarget, m.group(1)) + m.group(2) : clause;
                })
                .collect(java.util.stream.Collectors.joining(" AND "));
    }

    private String refSecondaryColumn(String refTarget, String fieldName) {
        return targetAttributes(refTarget).stream()
                .filter(a -> a.fieldName().equals(fieldName) || a.columnName().equals(fieldName))
                .map(AttributeDescriptor::columnName)
                .findFirst()
                .orElse(fieldName);
    }

    /** The attributes of a ref target named by its registered logical name (catalog or document). */
    private List<AttributeDescriptor> targetAttributes(String refTarget) {
        return registry.allCatalogs().stream()
                .filter(c -> c.logicalName().equals(refTarget))
                .map(CatalogDescriptor::attributes)
                .findFirst()
                .or(() -> registry.allDocuments().stream()
                        .filter(d -> d.logicalName().equals(refTarget))
                        .map(DocumentDescriptor::attributes)
                        .findFirst())
                .orElse(List.of());
    }
}
