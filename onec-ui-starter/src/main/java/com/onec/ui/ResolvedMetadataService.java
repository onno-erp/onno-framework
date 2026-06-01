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
 * Resolves descriptor metadata into the JSON-shaped attribute view served by the
 * metadata API, merging layout field hints over descriptor defaults. Shared by
 * {@link MetadataApiController} and the DivKit emitters so every renderer sees an
 * identical, layout-aware view of fields.
 */
public class ResolvedMetadataService {

    private final MetadataRegistry registry;
    private final UiLayout uiLayout;
    private final UiLayoutResolver layoutResolver;

    public ResolvedMetadataService(MetadataRegistry registry,
                                   UiLayout uiLayout,
                                   UiLayoutResolver layoutResolver) {
        this.registry = registry;
        this.uiLayout = uiLayout;
        this.layoutResolver = layoutResolver;
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
        Map<String, FieldHint> hints = layoutResolver.resolveFieldHints(
                uiLayout, "catalog", d.logicalName());
        map.put("attributes", describeAttributes(d.attributes(), hints));
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
        Map<String, FieldHint> hints = layoutResolver.resolveFieldHints(
                uiLayout, "document", d.logicalName());
        map.put("attributes", describeAttributes(d.attributes(), hints));
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
        Map<String, FieldHint> hints = layoutResolver.resolveFieldHints(
                uiLayout, "register", d.logicalName());
        map.put("dimensions", describeAttributes(d.dimensions(), hints));
        map.put("resources", describeAttributes(d.resources(), hints));
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
