package com.onec.metadata;

import com.onec.annotations.*;
import com.onec.model.AccumulationRecord;
import com.onec.model.AccumulationType;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;
import com.onec.model.InformationRecord;
import com.onec.model.Periodicity;
import com.onec.model.TabularSectionRow;
import com.onec.types.Ref;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MetadataScanner {

    private final DefaultNamingStrategy naming;

    public MetadataScanner(DefaultNamingStrategy naming) {
        this.naming = naming;
    }

    static String humanize(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) return camelCase;
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(camelCase.charAt(0)));
        for (int i = 1; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    @SuppressWarnings("removal")
    public List<DashboardWidgetDescriptor> scanDashboardWidgets(Class<?> clazz) {
        DashboardWidget[] widgets = clazz.getAnnotationsByType(DashboardWidget.class);
        if (widgets.length == 0) return List.of();

        String entityType;
        String entityName;
        if (clazz.isAnnotationPresent(Document.class)) {
            entityType = "document";
            entityName = clazz.getAnnotation(Document.class).name();
        } else if (clazz.isAnnotationPresent(Catalog.class)) {
            entityType = "catalog";
            entityName = clazz.getAnnotation(Catalog.class).name();
        } else if (clazz.isAnnotationPresent(AccumulationRegister.class)) {
            entityType = "register";
            entityName = clazz.getAnnotation(AccumulationRegister.class).name();
        } else {
            return List.of();
        }

        List<DashboardWidgetDescriptor> result = new ArrayList<>();
        for (DashboardWidget w : widgets) {
            java.util.Map<String, String> extra = new java.util.LinkedHashMap<>();
            for (String kv : w.extraConfig()) {
                int eq = kv.indexOf('=');
                if (eq > 0) extra.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
            result.add(new DashboardWidgetDescriptor(
                    w.title(), w.type(), w.order(), w.width(),
                    entityType, entityName, w.maxItems(),
                    w.dateField(), w.titleField(), extra, w.hint()));
        }
        return result;
    }

    public CatalogDescriptor scan(Class<?> clazz) {
        Catalog catalog = clazz.getAnnotation(Catalog.class);
        if (catalog == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @Catalog");
        }
        if (!CatalogObject.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(clazz.getName() + " must extend CatalogObject");
        }

        rejectTabularSections(clazz, CatalogObject.class, "@Catalog");

        String logicalName = catalog.name();
        String displayTitle = catalog.title().isEmpty() ? logicalName : catalog.title();
        String storageKey = catalog.tableName().isEmpty() ? logicalName : catalog.tableName();
        String tableName = naming.catalogTable(storageKey);
        int codeLength = catalog.codeLength();

        List<AttributeDescriptor> attributes = scanAttributes(clazz, CatalogObject.class);

        return new CatalogDescriptor(logicalName, displayTitle, tableName, clazz, codeLength,
                catalog.hierarchical(), catalog.autoNumber(), catalog.codePrefix(),
                catalog.context(), readRoles(clazz), writeRoles(clazz), attributes,
                List.of(catalog.previousNames()));
    }

    public DocumentDescriptor scanDocument(Class<?> clazz) {
        Document document = clazz.getAnnotation(Document.class);
        if (document == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @Document");
        }
        if (!DocumentObject.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(clazz.getName() + " must extend DocumentObject");
        }

        String logicalName = document.name();
        String displayTitle = document.title().isEmpty() ? logicalName : document.title();
        String storageKey = document.tableName().isEmpty() ? logicalName : document.tableName();
        String tableName = naming.documentTable(storageKey);
        int numberLength = document.numberLength();

        List<AttributeDescriptor> attributes = scanAttributes(clazz, DocumentObject.class);
        List<TabularSectionDescriptor> tabularSections = scanTabularSections(clazz, storageKey);

        return new DocumentDescriptor(logicalName, displayTitle, tableName, clazz, numberLength,
                document.autoNumber(), document.numberPrefix(), document.context(),
                readRoles(clazz), writeRoles(clazz), attributes, tabularSections,
                List.of(document.previousNames()));
    }

    public AccumulationRegisterDescriptor scanRegister(Class<?> clazz) {
        AccumulationRegister reg = clazz.getAnnotation(AccumulationRegister.class);
        if (reg == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @AccumulationRegister");
        }
        if (!AccumulationRecord.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(clazz.getName() + " must extend AccumulationRecord");
        }

        String logicalName = reg.name();
        String displayTitle = reg.title().isEmpty() ? logicalName : reg.title();
        String storageKey = reg.tableName().isEmpty() ? logicalName : reg.tableName();
        String tableName = naming.registerTable(storageKey);
        String totalsTableName = naming.registerTotalsTable(storageKey);
        AccumulationType type = reg.type();

        List<AttributeDescriptor> dimensions = scanDimensions(clazz, AccumulationRecord.class);
        List<AttributeDescriptor> resources = scanResources(clazz, AccumulationRecord.class);

        return new AccumulationRegisterDescriptor(
                logicalName, displayTitle, tableName, totalsTableName, clazz, type, reg.context(),
                readRoles(clazz), writeRoles(clazz), dimensions, resources);
    }

    @SuppressWarnings("unchecked")
    public EnumerationDescriptor scanEnumeration(Class<?> clazz) {
        Enumeration enumAnnotation = clazz.getAnnotation(Enumeration.class);
        if (enumAnnotation == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @Enumeration");
        }
        if (!clazz.isEnum()) {
            throw new IllegalArgumentException(clazz.getName() + " must be a Java enum");
        }

        String logicalName = enumAnnotation.name();
        String storageKey = enumAnnotation.tableName().isEmpty() ? logicalName : enumAnnotation.tableName();
        String tableName = naming.enumerationTable(storageKey);

        Object[] constants = clazz.getEnumConstants();
        List<EnumerationValueDescriptor> values = new ArrayList<>();
        for (int i = 0; i < constants.length; i++) {
            Enum<?> enumValue = (Enum<?>) constants[i];
            UUID id = UUID.nameUUIDFromBytes(
                    (clazz.getName() + "." + enumValue.name()).getBytes(StandardCharsets.UTF_8));
            values.add(new EnumerationValueDescriptor(enumValue.name(), id, i));
        }

        return new EnumerationDescriptor(logicalName, tableName, (Class<? extends Enum<?>>) clazz, values);
    }

    public InformationRegisterDescriptor scanInformationRegister(Class<?> clazz) {
        InformationRegister reg = clazz.getAnnotation(InformationRegister.class);
        if (reg == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @InformationRegister");
        }
        if (!InformationRecord.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(clazz.getName() + " must extend InformationRecord");
        }

        String logicalName = reg.name();
        String storageKey = reg.tableName().isEmpty() ? logicalName : reg.tableName();
        String tableName = naming.infoRegisterTable(storageKey);
        Periodicity periodicity = reg.periodicity();

        List<AttributeDescriptor> dimensions = scanDimensions(clazz, InformationRecord.class);
        List<AttributeDescriptor> resources = scanResources(clazz, InformationRecord.class);
        List<AttributeDescriptor> attributes = scanAttributes(clazz, InformationRecord.class);

        return new InformationRegisterDescriptor(
                logicalName, tableName, clazz, periodicity, reg.context(),
                readRoles(clazz), writeRoles(clazz), dimensions, resources, attributes);
    }

    private List<String> readRoles(Class<?> clazz) {
        AccessControl access = clazz.getAnnotation(AccessControl.class);
        return access == null ? List.of() : List.of(access.readRoles());
    }

    private List<String> writeRoles(Class<?> clazz) {
        AccessControl access = clazz.getAnnotation(AccessControl.class);
        return access == null ? List.of() : List.of(access.writeRoles());
    }

    public ConstantDescriptor scanConstant(Class<?> clazz) {
        Constant constant = clazz.getAnnotation(Constant.class);
        if (constant == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @Constant");
        }

        String logicalName = constant.name();

        Field[] fields = clazz.getDeclaredFields();
        if (fields.length != 1) {
            throw new IllegalArgumentException(
                    clazz.getName() + " must have exactly one field, found " + fields.length);
        }

        Field field = fields[0];
        return new ConstantDescriptor(logicalName, clazz, field.getType(), field.getName());
    }

    @SuppressWarnings("removal")
    private List<AttributeDescriptor> scanDimensions(Class<?> clazz, Class<?> stopClass) {
        List<AttributeDescriptor> result = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != stopClass && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                Dimension dim = field.getAnnotation(Dimension.class);
                if (dim == null) continue;

                String fieldName = field.getName();
                String displayName = dim.displayName().isEmpty()
                        ? humanize(fieldName) : dim.displayName();
                String columnName = naming.column(dim.name().isEmpty() ? fieldName : dim.name());
                Class<?> javaType = field.getType();
                boolean isRef = Ref.class.isAssignableFrom(javaType);

                UiHint hint = field.getAnnotation(UiHint.class);
                result.add(new AttributeDescriptor(
                        fieldName, displayName, columnName, javaType, 255, false, isRef,
                        isRef ? extractRefTargetName(field) : null, 0, 0,
                        hint == null || hint.visibleInList(),
                        hint == null || hint.visibleInForm(),
                        hint == null || hint.visibleInDetail(),
                        hint == null ? 0 : hint.order(),
                        hint == null ? "" : hint.group(),
                        hint == null ? "" : hint.width(),
                        hint == null ? "" : hint.widget(),
                        AttributeDescriptor.Constraints.NONE,
                        false));
            }
            current = current.getSuperclass();
        }

        return result;
    }

    @SuppressWarnings("removal")
    private List<AttributeDescriptor> scanResources(Class<?> clazz, Class<?> stopClass) {
        List<AttributeDescriptor> result = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != stopClass && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                Resource res = field.getAnnotation(Resource.class);
                if (res == null) continue;

                String fieldName = field.getName();
                String displayName = res.displayName().isEmpty()
                        ? humanize(fieldName) : res.displayName();
                String columnName = naming.column(res.name().isEmpty() ? fieldName : res.name());

                UiHint hint = field.getAnnotation(UiHint.class);
                result.add(new AttributeDescriptor(
                        fieldName, displayName, columnName, BigDecimal.class, 0, false, false,
                        null, res.precision(), res.scale(),
                        hint == null || hint.visibleInList(),
                        hint == null || hint.visibleInForm(),
                        hint == null || hint.visibleInDetail(),
                        hint == null ? 0 : hint.order(),
                        hint == null ? "" : hint.group(),
                        hint == null ? "" : hint.width(),
                        hint == null ? "" : hint.widget(),
                        AttributeDescriptor.Constraints.NONE,
                        false));
            }
            current = current.getSuperclass();
        }

        return result;
    }

    @SuppressWarnings("removal")
    private List<AttributeDescriptor> scanAttributes(Class<?> clazz, Class<?> stopClass) {
        List<AttributeDescriptor> result = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != stopClass && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                Attribute attr = field.getAnnotation(Attribute.class);
                if (attr == null) {
                    continue;
                }

                String fieldName = field.getName();
                String displayName = attr.displayName().isEmpty()
                        ? humanize(fieldName) : attr.displayName();
                String columnName = naming.column(
                        attr.name().isEmpty() ? fieldName : attr.name()
                );
                Class<?> javaType = field.getType();
                boolean isRef = Ref.class.isAssignableFrom(javaType);

                UiHint hint = field.getAnnotation(UiHint.class);
                result.add(new AttributeDescriptor(
                        fieldName,
                        displayName,
                        columnName,
                        javaType,
                        attr.length(),
                        attr.required(),
                        isRef,
                        isRef ? extractRefTargetName(field) : null,
                        attr.precision(),
                        attr.scale(),
                        hint == null || hint.visibleInList(),
                        hint == null || hint.visibleInForm(),
                        hint == null || hint.visibleInDetail(),
                        hint == null ? 0 : hint.order(),
                        hint == null ? "" : hint.group(),
                        hint == null ? "" : hint.width(),
                        hint == null ? "" : hint.widget(),
                        new AttributeDescriptor.Constraints(
                                attr.min(), attr.max(), attr.minLength(), attr.pattern(), attr.email()),
                        attr.secret(),
                        List.of(attr.previousNames())
                ));
            }
            current = current.getSuperclass();
        }

        return result;
    }

    /**
     * Rejects {@code @TabularSection} fields on a kind of entity that has no tabular-section storage.
     * Only documents generate child tables and round-trip their line items; a tabular section on a
     * catalog (or any other entity) is scanned but never persisted, so the first write would fail at
     * runtime with a bad-SQL-grammar insert against a table that was never created (issue #27). Fail
     * fast at scan time with an actionable message instead.
     */
    private void rejectTabularSections(Class<?> clazz, Class<?> stopClass, String entityKind) {
        Class<?> current = clazz;
        while (current != null && current != stopClass && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(TabularSection.class)) {
                    throw new IllegalArgumentException(
                            "@TabularSection is only supported on @Document, but field '"
                                    + field.getName() + "' on " + entityKind + " " + clazz.getName()
                                    + " declares one. Move the line items to a @Document, or model them"
                                    + " as a separate @Catalog referenced by Ref<>.");
                }
            }
            current = current.getSuperclass();
        }
    }

    private List<TabularSectionDescriptor> scanTabularSections(Class<?> clazz, String documentName) {
        List<TabularSectionDescriptor> result = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != DocumentObject.class && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                TabularSection ts = field.getAnnotation(TabularSection.class);
                if (ts == null) {
                    continue;
                }

                if (!List.class.isAssignableFrom(field.getType())) {
                    throw new IllegalArgumentException(
                            "@TabularSection field " + field.getName() + " must be of type List");
                }

                Class<?> rowClass = extractRowClass(field);
                if (!TabularSectionRow.class.isAssignableFrom(rowClass)) {
                    throw new IllegalArgumentException(
                            "Row class " + rowClass.getName() + " must extend TabularSectionRow");
                }

                String sectionName = ts.name().isEmpty() ? field.getName() : ts.name();
                String tableName = naming.tabularSectionTable(documentName, sectionName);
                List<AttributeDescriptor> rowAttributes = scanAttributes(rowClass, TabularSectionRow.class);

                result.add(new TabularSectionDescriptor(
                        sectionName,
                        field.getName(),
                        tableName,
                        rowClass,
                        rowAttributes
                ));
            }
            current = current.getSuperclass();
        }

        return result;
    }

    private String extractRefTargetName(Field field) {
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType paramType)) return null;
        Type[] typeArgs = paramType.getActualTypeArguments();
        if (typeArgs.length != 1 || !(typeArgs[0] instanceof Class<?> targetClass)) return null;
        // Resolve to the target's registered logical name so the UI can look it up
        // (catalog or document). A Ref<SomeDocument> must carry the @Document name —
        // not the Java simple name — or the document ref picker/display can't find it.
        Catalog catalog = targetClass.getAnnotation(Catalog.class);
        if (catalog != null) return catalog.name();
        Document document = targetClass.getAnnotation(Document.class);
        if (document != null) return document.name();
        return targetClass.getSimpleName();
    }

    private Class<?> extractRowClass(Field field) {
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType paramType)) {
            throw new IllegalArgumentException(
                    "@TabularSection field " + field.getName() + " must have a generic type parameter (e.g. List<InvoiceLine>)");
        }
        Type[] typeArgs = paramType.getActualTypeArguments();
        if (typeArgs.length != 1 || !(typeArgs[0] instanceof Class<?>)) {
            throw new IllegalArgumentException(
                    "@TabularSection field " + field.getName() + " must have a concrete type parameter");
        }
        return (Class<?>) typeArgs[0];
    }
}
