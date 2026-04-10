package com.onec.spring;

import com.onec.annotations.AccumulationRegister;
import com.onec.annotations.Catalog;
import com.onec.annotations.Document;
import com.onec.metadata.DefaultNamingStrategy;

import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

public class OneCNamingStrategy implements NamingStrategy {

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
        return switch (fieldName) {
            case "id", "code", "description", "deletionMark" -> true;
            case "number", "date", "posted" -> true;
            case "lineNumber" -> true;
            case "period", "active", "documentRef", "movementType" -> true;
            default -> false;
        };
    }
}
