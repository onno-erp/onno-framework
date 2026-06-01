package com.onec.spring;

import com.onec.annotations.Catalog;
import com.onec.annotations.Document;
import com.onec.annotations.TabularSection;

import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

public class OneCMappingContext extends JdbcMappingContext {

    public OneCMappingContext(NamingStrategy namingStrategy) {
        super(namingStrategy);
    }

    @Override
    protected boolean shouldCreatePersistentEntityFor(org.springframework.data.util.TypeInformation<?> type) {
        Class<?> rawType = type.getType();
        // Create persistent entities for our annotated classes and their nested types
        if (rawType.isAnnotationPresent(Catalog.class) || rawType.isAnnotationPresent(Document.class)) {
            return true;
        }
        return super.shouldCreatePersistentEntityFor(type);
    }
}
