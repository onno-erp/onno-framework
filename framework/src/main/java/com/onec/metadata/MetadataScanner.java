package com.onec.metadata;

import com.onec.annotations.*;
import com.onec.model.AccumulationRecord;
import com.onec.model.AccumulationType;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;
import com.onec.model.TabularSectionRow;
import com.onec.types.Ref;

import java.math.BigDecimal;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class MetadataScanner {

    private final DefaultNamingStrategy naming;

    public MetadataScanner(DefaultNamingStrategy naming) {
        this.naming = naming;
    }

    public CatalogDescriptor scan(Class<?> clazz) {
        Catalog catalog = clazz.getAnnotation(Catalog.class);
        if (catalog == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @Catalog");
        }
        if (!CatalogObject.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(clazz.getName() + " must extend CatalogObject");
        }

        String logicalName = catalog.name();
        String tableName = naming.catalogTable(logicalName);
        int codeLength = catalog.codeLength();

        List<AttributeDescriptor> attributes = scanAttributes(clazz, CatalogObject.class);

        return new CatalogDescriptor(logicalName, tableName, clazz, codeLength, attributes);
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
        String tableName = naming.documentTable(logicalName);
        int numberLength = document.numberLength();

        List<AttributeDescriptor> attributes = scanAttributes(clazz, DocumentObject.class);
        List<TabularSectionDescriptor> tabularSections = scanTabularSections(clazz, logicalName);

        return new DocumentDescriptor(logicalName, tableName, clazz, numberLength, attributes, tabularSections);
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
        String tableName = naming.registerTable(logicalName);
        String totalsTableName = naming.registerTotalsTable(logicalName);
        AccumulationType type = reg.type();

        List<AttributeDescriptor> dimensions = scanDimensions(clazz);
        List<AttributeDescriptor> resources = scanResources(clazz);

        return new AccumulationRegisterDescriptor(
                logicalName, tableName, totalsTableName, clazz, type, dimensions, resources);
    }

    private List<AttributeDescriptor> scanDimensions(Class<?> clazz) {
        List<AttributeDescriptor> result = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != AccumulationRecord.class && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                Dimension dim = field.getAnnotation(Dimension.class);
                if (dim == null) continue;

                String fieldName = field.getName();
                String columnName = naming.column(dim.name().isEmpty() ? fieldName : dim.name());
                Class<?> javaType = field.getType();
                boolean isRef = Ref.class.isAssignableFrom(javaType);

                result.add(new AttributeDescriptor(
                        fieldName, columnName, javaType, 255, false, isRef, 0, 0));
            }
            current = current.getSuperclass();
        }

        return result;
    }

    private List<AttributeDescriptor> scanResources(Class<?> clazz) {
        List<AttributeDescriptor> result = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != AccumulationRecord.class && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                Resource res = field.getAnnotation(Resource.class);
                if (res == null) continue;

                String fieldName = field.getName();
                String columnName = naming.column(res.name().isEmpty() ? fieldName : res.name());

                result.add(new AttributeDescriptor(
                        fieldName, columnName, BigDecimal.class, 0, false, false,
                        res.precision(), res.scale()));
            }
            current = current.getSuperclass();
        }

        return result;
    }

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
                String columnName = naming.column(
                        attr.name().isEmpty() ? fieldName : attr.name()
                );
                Class<?> javaType = field.getType();
                boolean isRef = Ref.class.isAssignableFrom(javaType);

                result.add(new AttributeDescriptor(
                        fieldName,
                        columnName,
                        javaType,
                        attr.length(),
                        attr.required(),
                        isRef,
                        attr.precision(),
                        attr.scale()
                ));
            }
            current = current.getSuperclass();
        }

        return result;
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
