package com.onec.spring;

import com.onec.annotations.AccumulationRegister;
import com.onec.annotations.Catalog;
import com.onec.annotations.Document;
import com.onec.metadata.DefaultNamingStrategy;
import com.onec.model.AccumulationRecord;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;
import com.onec.model.TabularSectionRow;

import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

public class OnecNamingStrategy implements NamingStrategy {

    private final DefaultNamingStrategy delegate = new DefaultNamingStrategy();

    @Override
    public String getTableName(Class<?> type) {
        Catalog catalog = type.getAnnotation(Catalog.class);
        if (catalog != null) {
            return delegate.catalogTable(catalog.name());
        }
        Document document = type.getAnnotation(Document.class);
        if (document != null) {
            return delegate.documentTable(document.name());
        }
        AccumulationRegister register = type.getAnnotation(AccumulationRegister.class);
        if (register != null) {
            return delegate.registerTable(register.name());
        }
        // Tabular section rows — table name set via MappingContext
        return NamingStrategy.super.getTableName(type);
    }

    @Override
    public String getColumnName(RelationalPersistentProperty property) {
        String fieldName = property.getName();
        if ("folder".equals(fieldName)) {
            return "_is_folder";
        }

        // Base class fields get underscore prefix
        Class<?> owner = property.getOwner().getType();
        if (isBaseField(owner, fieldName)) {
            return "_" + delegate.column(fieldName);
        }

        return delegate.column(fieldName);
    }

    @Override
    public String getReverseColumnName(RelationalPersistentProperty property) {
        return "_parent_id";
    }

    @Override
    public String getKeyColumn(RelationalPersistentProperty property) {
        return "_line_number";
    }

    private boolean isBaseField(Class<?> owner, String fieldName) {
        boolean isCatalog = CatalogObject.class.isAssignableFrom(owner);
        boolean isDocument = DocumentObject.class.isAssignableFrom(owner);
        boolean isAccumulation = AccumulationRecord.class.isAssignableFrom(owner);
        boolean isTabular = TabularSectionRow.class.isAssignableFrom(owner);
        return switch (fieldName) {
            case "id" -> isCatalog || isDocument || isAccumulation || isTabular;
            case "code", "folder", "parent" -> isCatalog;
            case "description", "version", "deletionMark" -> isCatalog || isDocument;
            case "number", "date", "posted" -> isDocument;
            case "lineNumber" -> isTabular;
            case "period", "active", "documentRef", "movementType" -> isAccumulation;
            default -> false;
        };
    }
}
