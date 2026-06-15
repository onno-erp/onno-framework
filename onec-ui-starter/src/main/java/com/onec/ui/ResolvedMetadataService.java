package com.onec.ui;

import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.AttributeDescriptor;
import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.EnumerationDescriptor;
import com.onec.metadata.MetadataRegistry;

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
        map.put("postable", com.onec.lifecycle.Postable.class.isAssignableFrom(d.javaClass()));
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
            // Tabular section field hints are not yet configurable via the layout
            // DSL; they continue to come from @UiHint on the row class for now.
            tsMap.put("attributes", describeAttributes(ts.attributes(), Map.of()));
            return tsMap;
        }).toList());
        // Documents surface related-list panels too (1C parity): a booking can show its guests —
        // the reverse side of a Booking↔Client junction — read-only on the detail and editable in
        // the form, exactly like a catalog. See #110.
        map.put("relatedLists", describeRelatedLists(d.javaClass(), d.logicalName()));
        return map;
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
        return map;
    }

    /**
     * Describes a built-in system column (code/description/number/date/posted) so it
     * honors the same field-hint config as custom attributes — a developer can hide
     * or reorder it from the layout DSL (e.g. {@code .field("code").hideInList()})
     * without touching frontend code. Visibility defaults to shown when unset.
     */
    private Map<String, Object> systemColumn(String fieldName, String displayName, String columnName,
                                             Map<String, FieldHint> hints) {
        FieldHint hint = hints.get(fieldName);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fieldName", fieldName);
        map.put("displayName", displayName);
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

    public List<Map<String, Object>> describeAttributes(List<AttributeDescriptor> attrs,
                                                        Map<String, FieldHint> layoutHints) {
        return attrs.stream().map(a -> {
            FieldHint hint = layoutHints.get(a.fieldName());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("fieldName", a.fieldName());
            map.put("displayName", a.displayName());
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
            }
            map.put("precision", a.precision());
            map.put("scale", a.scale());
            // Tell the UI to render a write-only password control and a "set / not set"
            // indicator instead of the value (which the read API never returns).
            map.put("secret", a.secret());
            // Layout hints win when set; otherwise fall back to descriptor (which
            // reflects @UiHint on the field, or scanner default if absent).
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
            com.onec.metadata.AttributeDescriptor.Constraints c = a.constraints();
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
                    map.put("enumValues", enumDesc.values().stream().map(v -> {
                        Map<String, Object> vm = new LinkedHashMap<>();
                        vm.put("name", v.name());
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
}
