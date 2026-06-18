package su.onno.spring;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.model.AccumulationRecord;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;

import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

import java.util.Map;

public class OnnoNamingStrategy implements NamingStrategy {

    private final DefaultNamingStrategy delegate = new DefaultNamingStrategy();

    /**
     * Tabular-section row class &rarr; child table name (e.g. {@code Guest -> document_bookings_guests}),
     * built from the document metadata so Spring Data JDBC maps each {@link TabularSectionRow} to the
     * exact table the {@link su.onno.schema.SchemaGenerator} created. A row class participates in a
     * single tabular section, so the class alone identifies its table.
     */
    private final Map<Class<?>, String> tabularSectionTables;

    public OnnoNamingStrategy() {
        this(Map.of());
    }

    public OnnoNamingStrategy(Map<Class<?>, String> tabularSectionTables) {
        this.tabularSectionTables = tabularSectionTables;
    }

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
        // Tabular section rows are mapped to the schema generator's child table
        // (document_<doc>_<section>) via the metadata-derived registry.
        String tabularTable = tabularSectionTables.get(type);
        if (tabularTable != null) {
            return tabularTable;
        }
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

    /**
     * The back-reference column on a tabular-section child table. Spring Data JDBC resolves the
     * reverse column through this {@link RelationalPersistentEntity} overload (see
     * {@code BasicRelationalPersistentProperty#getReverseColumnName(RelationalPersistentEntity)}),
     * not the property overload above — so this one must also return {@code _parent_id} for the
     * mapping to match the generated schema. The only owned collections in this framework are
     * tabular sections, so an unconditional {@code _parent_id} is correct.
     */
    @Override
    public String getReverseColumnName(RelationalPersistentEntity<?> owner) {
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
