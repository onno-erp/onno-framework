package com.onec.spring;

import com.onec.annotations.Catalog;
import com.onec.annotations.Document;
import com.onec.model.TabularSectionRow;

import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.relational.core.mapping.NamingStrategy;

public class OnecMappingContext extends JdbcMappingContext {

    public OnecMappingContext(NamingStrategy namingStrategy) {
        super(namingStrategy);
    }

    @Override
    protected boolean shouldCreatePersistentEntityFor(org.springframework.data.util.TypeInformation<?> type) {
        Class<?> rawType = type.getType();
        // Create persistent entities for our annotated aggregate roots and for tabular-section
        // rows, so a document's @TabularSection List<Row> round-trips as a Spring Data JDBC
        // one-to-many against the schema generator's document_<doc>_<section> child table.
        if (rawType.isAnnotationPresent(Catalog.class)
                || rawType.isAnnotationPresent(Document.class)
                || TabularSectionRow.class.isAssignableFrom(rawType)) {
            return true;
        }
        return super.shouldCreatePersistentEntityFor(type);
    }
}
