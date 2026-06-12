package com.onec.schema;

import com.onec.metadata.*;
import com.onec.model.AccumulationType;
import com.onec.model.Periodicity;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the desired {@link SchemaModel} from the metadata registry. This is the single
 * source of truth for table layouts: {@link SchemaGenerator} renders {@code CREATE TABLE}
 * statements from it and {@link SchemaDiffEngine} diffs it against the live database.
 */
public class SchemaModelBuilder {

    private final MetadataRegistry registry;
    private final DefaultNamingStrategy naming = new DefaultNamingStrategy();

    public SchemaModelBuilder(MetadataRegistry registry) {
        this.registry = registry;
    }

    public SchemaModel build() {
        List<TableModel> tables = new ArrayList<>();
        tables.add(sequencesTable());
        tables.add(outboxTable());
        for (CatalogDescriptor catalog : registry.allCatalogs()) {
            tables.add(catalogTable(catalog));
        }
        for (DocumentDescriptor document : registry.allDocuments()) {
            tables.add(documentTable(document));
            for (TabularSectionDescriptor section : document.tabularSections()) {
                tables.add(tabularSectionTable(document, section));
            }
        }
        for (AccumulationRegisterDescriptor register : registry.allRegisters()) {
            tables.add(registerTable(register));
            if (register.accumulationType() == AccumulationType.BALANCE) {
                tables.add(registerTotalsTable(register));
            }
        }
        for (EnumerationDescriptor enumeration : registry.allEnumerations()) {
            tables.add(enumerationTable(enumeration));
        }
        for (InformationRegisterDescriptor register : registry.allInformationRegisters()) {
            tables.add(infoRegisterTable(register));
        }
        if (!registry.allConstants().isEmpty()) {
            tables.add(constantsTable());
        }
        return new SchemaModel(List.copyOf(tables));
    }

    static TableModel sequencesTable() {
        return new TableModel("onec_sequences", List.of(
                ColumnModel.primaryKey("entity_name", "VARCHAR(255)"),
                ColumnModel.withDefault("last_value", "BIGINT", "0").asNotNull()
        ), List.of(), List.of());
    }

    static TableModel outboxTable() {
        return new TableModel("onec_outbox", List.of(
                ColumnModel.primaryKey("_id", "UUID"),
                ColumnModel.of("_aggregate_type", "VARCHAR(255)").asNotNull(),
                ColumnModel.of("_aggregate_id", "VARCHAR(255)"),
                ColumnModel.of("_event_type", "VARCHAR(255)").asNotNull(),
                ColumnModel.of("_payload", "TEXT").asNotNull(),
                ColumnModel.of("_created_at", "TIMESTAMP").asNotNull(),
                ColumnModel.of("_published_at", "TIMESTAMP"),
                ColumnModel.withDefault("_status", "VARCHAR(32)", "'NEW'").asNotNull()
        ), List.of(), List.of());
    }

    private TableModel catalogTable(CatalogDescriptor catalog) {
        List<ColumnModel> columns = new ArrayList<>();
        int codeLength = catalog.codeLength()
                + (catalog.codePrefix() == null ? 0 : catalog.codePrefix().length());
        columns.add(ColumnModel.primaryKey("_id", "UUID"));
        columns.add(ColumnModel.of("_code", "VARCHAR(" + codeLength + ")"));
        columns.add(ColumnModel.of("_description", "VARCHAR(255)"));
        columns.add(ColumnModel.withDefault("_deletion_mark", "BOOLEAN", "FALSE"));
        columns.add(ColumnModel.withDefault("_is_folder", "BOOLEAN", "FALSE"));
        columns.add(ColumnModel.of("_parent", "UUID"));
        columns.add(ColumnModel.withDefault("_version", "INTEGER", "0"));
        for (AttributeDescriptor attr : catalog.attributes()) {
            columns.add(attributeColumn(attr, attr.required()));
        }
        List<String> previousTables = new ArrayList<>();
        for (String previous : catalog.previousNames()) {
            previousTables.add(naming.catalogTable(previous));
            previousTables.add(previous);
        }
        return new TableModel(catalog.tableName(), columns, List.of(), previousTables);
    }

    private TableModel documentTable(DocumentDescriptor document) {
        List<ColumnModel> columns = new ArrayList<>();
        int numberLength = document.numberLength()
                + (document.numberPrefix() == null ? 0 : document.numberPrefix().length());
        columns.add(ColumnModel.primaryKey("_id", "UUID"));
        columns.add(ColumnModel.of("_number", "VARCHAR(" + numberLength + ")"));
        columns.add(ColumnModel.of("_date", "TIMESTAMP"));
        columns.add(ColumnModel.withDefault("_posted", "BOOLEAN", "FALSE"));
        columns.add(ColumnModel.withDefault("_deletion_mark", "BOOLEAN", "FALSE"));
        columns.add(ColumnModel.withDefault("_version", "INTEGER", "0"));
        for (AttributeDescriptor attr : document.attributes()) {
            columns.add(attributeColumn(attr, attr.required()));
        }
        List<String> previousTables = new ArrayList<>();
        for (String previous : document.previousNames()) {
            previousTables.add(naming.documentTable(previous));
            previousTables.add(previous);
        }
        return new TableModel(document.tableName(), columns, List.of(), previousTables);
    }

    private TableModel tabularSectionTable(DocumentDescriptor document, TabularSectionDescriptor section) {
        List<ColumnModel> columns = new ArrayList<>();
        columns.add(ColumnModel.primaryKey("_id", "UUID"));
        columns.add(new ColumnModel("_parent_id", "UUID", false, false, null,
                document.tableName() + "(_id)", List.of()));
        columns.add(ColumnModel.of("_line_number", "INTEGER"));
        for (AttributeDescriptor attr : section.attributes()) {
            columns.add(attributeColumn(attr, attr.required()));
        }
        // A renamed document drags its tabular-section tables along.
        List<String> previousTables = new ArrayList<>();
        for (String previous : document.previousNames()) {
            previousTables.add(naming.tabularSectionTable(previous, section.name()));
        }
        return new TableModel(section.tableName(), columns, List.of(), previousTables);
    }

    private TableModel registerTable(AccumulationRegisterDescriptor register) {
        List<ColumnModel> columns = new ArrayList<>();
        columns.add(ColumnModel.primaryKey("_id", "UUID"));
        columns.add(ColumnModel.of("_period", "TIMESTAMP"));
        columns.add(ColumnModel.withDefault("_active", "BOOLEAN", "TRUE"));
        columns.add(ColumnModel.of("_document_ref", "UUID"));
        columns.add(ColumnModel.of("_movement_type", "VARCHAR(10)"));
        for (AttributeDescriptor dim : register.dimensions()) {
            columns.add(attributeColumn(dim, false));
        }
        for (AttributeDescriptor res : register.resources()) {
            columns.add(attributeColumn(res, false));
        }
        return new TableModel(register.tableName(), columns, List.of(), List.of());
    }

    private TableModel registerTotalsTable(AccumulationRegisterDescriptor register) {
        List<ColumnModel> columns = new ArrayList<>();
        List<String> dimNames = new ArrayList<>();
        for (AttributeDescriptor dim : register.dimensions()) {
            columns.add(attributeColumn(dim, false));
            dimNames.add(dim.columnName());
        }
        for (AttributeDescriptor res : register.resources()) {
            columns.add(ColumnModel.withDefault(res.columnName(),
                    SchemaGenerator.columnType(res), "0"));
        }
        List<String> constraints = List.of("PRIMARY KEY (" + String.join(", ", dimNames) + ")");
        return new TableModel(register.totalsTableName(), columns, constraints, List.of());
    }

    private TableModel enumerationTable(EnumerationDescriptor enumeration) {
        return new TableModel(enumeration.tableName(), List.of(
                ColumnModel.primaryKey("_id", "UUID"),
                ColumnModel.of("_name", "VARCHAR(255)"),
                ColumnModel.of("_order", "INTEGER")
        ), List.of(), List.of());
    }

    private TableModel infoRegisterTable(InformationRegisterDescriptor register) {
        List<ColumnModel> columns = new ArrayList<>();
        columns.add(ColumnModel.primaryKey("_id", "UUID"));
        List<String> uniqueCols = new ArrayList<>();
        if (register.periodicity() != Periodicity.NONE) {
            columns.add(ColumnModel.of("_period", "TIMESTAMP"));
            uniqueCols.add("_period");
        }
        for (AttributeDescriptor dim : register.dimensions()) {
            columns.add(attributeColumn(dim, false));
            uniqueCols.add(dim.columnName());
        }
        for (AttributeDescriptor res : register.resources()) {
            columns.add(attributeColumn(res, false));
        }
        for (AttributeDescriptor attr : register.attributes()) {
            columns.add(attributeColumn(attr, false));
        }
        List<String> constraints = uniqueCols.isEmpty()
                ? List.of()
                : List.of("UNIQUE (" + String.join(", ", uniqueCols) + ")");
        return new TableModel(register.tableName(), columns, constraints, List.of());
    }

    private TableModel constantsTable() {
        return new TableModel("constants", List.of(
                ColumnModel.primaryKey("_name", "VARCHAR(255)"),
                ColumnModel.of("_value", "TEXT")
        ), List.of(), List.of());
    }

    private ColumnModel attributeColumn(AttributeDescriptor attr, boolean notNull) {
        List<String> previousColumns = new ArrayList<>();
        for (String previous : attr.previousNames()) {
            previousColumns.add(naming.column(previous));
        }
        return new ColumnModel(attr.columnName(), SchemaGenerator.columnType(attr),
                false, notNull, null, null, List.copyOf(previousColumns));
    }
}
