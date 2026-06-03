package com.onec.ui;

import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.AttributeDescriptor;
import com.onec.metadata.CatalogDescriptor;
import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.EnumerationDescriptor;
import com.onec.metadata.MetadataRegistry;

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

    private final MetadataRegistry registry;
    private final FieldHintResolver fieldHints;

    public ResolvedMetadataService(MetadataRegistry registry, FieldHintResolver fieldHints) {
        this.registry = registry;
        this.fieldHints = fieldHints;
    }

    public Map<String, Object> describeCatalog(CatalogDescriptor d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", d.logicalName());
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
        return map;
    }

    public Map<String, Object> describeDocument(DocumentDescriptor d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", d.logicalName());
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
        return map;
    }

    public Map<String, Object> describeRegister(AccumulationRegisterDescriptor d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", d.logicalName());
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
            // Layout hints win when set; otherwise fall back to descriptor (which
            // reflects @UiHint on the field, or scanner default if absent).
            map.put("visibleInList", pick(hint == null ? null : hint.visibleInList(), a.visibleInList()));
            map.put("visibleInForm", pick(hint == null ? null : hint.visibleInForm(), a.visibleInForm()));
            map.put("visibleInDetail", pick(hint == null ? null : hint.visibleInDetail(), a.visibleInDetail()));
            map.put("order", pick(hint == null ? null : hint.order(), a.order()));
            map.put("group", pick(hint == null ? null : hint.group(), a.group()));
            map.put("widthHint", pick(hint == null ? null : hint.width(), a.widthHint()));
            map.put("widget", pick(hint == null ? null : hint.widget(), a.widget()));
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
