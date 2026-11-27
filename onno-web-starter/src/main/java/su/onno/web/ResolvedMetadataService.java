package su.onno.web;

import su.onno.validation.CheckRegistry;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.MetadataRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves descriptor metadata into the JSON-shaped entity description the default surfaces carry
 * (list columns, record form, register report): every field's name, type, reference target and
 * constraints, plus the entity's live checks (under {@code formValidations}: key, dependencies,
 * debounce). Data only — order, grouping, widths, widgets and visibility are the client's.
 */
public class ResolvedMetadataService {

    private final MetadataRegistry registry;
    private final CheckRegistry checks;

    public ResolvedMetadataService(MetadataRegistry registry, CheckRegistry checks) {
        this.registry = registry;
        this.checks = checks;
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
        map.put("attributes", describeAttributes(d.attributes()));
        map.put("tabularSections", describeSections(d.tabularSections()));
        map.put("systemColumns", List.of(
                systemColumn("code", "Code", "_code"),
                systemColumn("description", "Description", "_description")));
        map.put("formValidations", describeFormValidations(d.javaClass()));
        return map;
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
        // Whether this document type can be posted (implements Postable). The UI uses it to
        // decide whether to offer Post / Re-post / Unpost actions; a non-postable document
        // only ever gets a plain Save.
        map.put("postable", su.onno.lifecycle.Postable.class.isAssignableFrom(d.javaClass()));
        map.put("attributes", describeAttributes(d.attributes()));
        map.put("systemColumns", List.of(
                systemColumn("number", "Number", "_number"),
                systemColumn("date", "Date", "_date"),
                systemColumn("posted", "Status", "_posted")));
        map.put("tabularSections", describeSections(d.tabularSections()));
        map.put("formValidations", describeFormValidations(d.javaClass()));
        return map;
    }

    private List<Map<String, Object>> describeSections(
            List<su.onno.metadata.TabularSectionDescriptor> sections) {
        return sections.stream().map(ts -> {
            Map<String, Object> tsMap = new LinkedHashMap<>();
            tsMap.put("name", ts.name());
            tsMap.put("tableName", ts.tableName());
            tsMap.put("attributes", describeAttributes(ts.attributes()));
            return tsMap;
        }).toList();
    }

    private List<Map<String, Object>> describeFormValidations(Class<?> entity) {
        return checks.checksFor(entity).stream().map(validation -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", validation.key());
            map.put("dependencies", validation.dependencies());
            map.put("debounceMillis", validation.debounceMillis());
            return map;
        }).toList();
    }

    public Map<String, Object> describeRegister(AccumulationRegisterDescriptor d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", d.logicalName());
        map.put("title", d.displayTitle());
        map.put("tableName", d.tableName());
        map.put("type", d.accumulationType().name());
        map.put("allowNegative", d.allowNegative());
        map.put("postingOrder", d.postingOrder().name());
        map.put("context", d.context());
        map.put("dimensions", describeAttributes(d.dimensions()));
        map.put("resources", describeAttributes(d.resources()));
        return map;
    }

    /**
     * Describes a built-in system column (code/description/number/date/posted): its field and
     * column names and the English fallback label ("Code"/"Description"/"Number"/"Date"/"Status").
     */
    private Map<String, Object> systemColumn(String fieldName, String displayName, String columnName) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fieldName", fieldName);
        map.put("displayName", displayName);
        map.put("columnName", columnName);
        return map;
    }

    public List<Map<String, Object>> describeAttributes(List<AttributeDescriptor> attrs) {
        return attrs.stream().map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("fieldName", a.fieldName());
            map.put("displayName", a.displayName());
            map.put("columnName", a.columnName());
            map.put("javaType", a.javaType().getSimpleName());
            map.put("length", a.length());
            map.put("required", a.required());
            map.put("isRef", a.isRef());
            map.put("refTarget", a.refTarget());
            map.put("isPolymorphicRef", a.isPolymorphicRef());
            if (a.isPolymorphicRef()) {
                map.put("refTargets", a.refTargets().stream().map(target -> Map.of(
                        "kind", target.kind(),
                        "name", target.logicalName(),
                        "title", target.displayTitle(),
                        "javaType", target.javaTypeName()
                )).toList());
            }
            // Tell the UI whether a ref points at a catalog or a document so the picker
            // can hit the right endpoints. refTarget is the registered logical name, so a
            // matching document means it's a document ref; otherwise treat it as a catalog.
            if (a.isRef() && a.refTarget() != null) {
                boolean isDocument = registry.allDocuments().stream()
                        .anyMatch(d -> d.logicalName().equals(a.refTarget()));
                map.put("refKind", isDocument ? "document" : "catalog");
                // Picker buttons (#409): "open" is on; "clear" is on for optional refs and never
                // offered on a required one.
                map.put("refOpenButton", true);
                map.put("refClearButton", !a.required());
            }
            map.put("precision", a.precision());
            map.put("scale", a.scale());
            // Tell the UI to render a write-only password control and a "set / not set"
            // indicator instead of the value (which the read API never returns).
            map.put("secret", a.secret());
            // The declarative validation constraints the client mirrors for instant inline
            // errors. Bounds are emitted only when set.
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
}
