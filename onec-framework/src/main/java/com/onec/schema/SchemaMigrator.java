package com.onec.schema;

import com.onec.metadata.*;
import com.onec.model.AccumulationType;
import com.onec.model.Periodicity;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

public class SchemaMigrator {

    private final MetadataRegistry registry;

    public SchemaMigrator(MetadataRegistry registry) {
        this.registry = registry;
    }

    public List<String> generateAdditiveDDL(Jdbi jdbi) {
        return jdbi.withHandle(handle -> {
            List<String> ddl = new ArrayList<>();
            addOutboxColumns(handle, ddl);
            for (CatalogDescriptor catalog : registry.allCatalogs()) {
                addCatalogColumns(handle, ddl, catalog);
            }
            for (DocumentDescriptor document : registry.allDocuments()) {
                addDocumentColumns(handle, ddl, document);
                for (TabularSectionDescriptor section : document.tabularSections()) {
                    addTabularSectionColumns(handle, ddl, section);
                }
            }
            for (AccumulationRegisterDescriptor register : registry.allRegisters()) {
                addRegisterColumns(handle, ddl, register);
                if (register.accumulationType() == AccumulationType.BALANCE) {
                    addRegisterTotalsColumns(handle, ddl, register);
                }
            }
            for (InformationRegisterDescriptor register : registry.allInformationRegisters()) {
                addInfoRegisterColumns(handle, ddl, register);
            }
            return ddl;
        });
    }

    public void executeAdditive(Jdbi jdbi) {
        List<String> ddl = generateAdditiveDDL(jdbi);
        jdbi.useHandle(handle -> {
            for (String statement : ddl) {
                handle.execute(statement);
            }
        });
    }

    private void addCatalogColumns(Handle handle, List<String> ddl, CatalogDescriptor catalog) {
        addColumn(handle, ddl, catalog.tableName(), "_is_folder", "BOOLEAN DEFAULT FALSE");
        addColumn(handle, ddl, catalog.tableName(), "_parent", "UUID");
        addColumn(handle, ddl, catalog.tableName(), "_version", "INTEGER DEFAULT 0");
        for (AttributeDescriptor attr : catalog.attributes()) {
            addColumn(handle, ddl, catalog.tableName(), attr.columnName(), columnType(attr));
        }
    }

    private void addDocumentColumns(Handle handle, List<String> ddl, DocumentDescriptor document) {
        addColumn(handle, ddl, document.tableName(), "_version", "INTEGER DEFAULT 0");
        for (AttributeDescriptor attr : document.attributes()) {
            addColumn(handle, ddl, document.tableName(), attr.columnName(), columnType(attr));
        }
    }

    private void addTabularSectionColumns(Handle handle, List<String> ddl, TabularSectionDescriptor section) {
        for (AttributeDescriptor attr : section.attributes()) {
            addColumn(handle, ddl, section.tableName(), attr.columnName(), columnType(attr));
        }
    }

    private void addRegisterColumns(Handle handle, List<String> ddl, AccumulationRegisterDescriptor register) {
        for (AttributeDescriptor dim : register.dimensions()) {
            addColumn(handle, ddl, register.tableName(), dim.columnName(), columnType(dim));
        }
        for (AttributeDescriptor res : register.resources()) {
            addColumn(handle, ddl, register.tableName(), res.columnName(), columnType(res));
        }
    }

    private void addRegisterTotalsColumns(Handle handle, List<String> ddl, AccumulationRegisterDescriptor register) {
        for (AttributeDescriptor res : register.resources()) {
            addColumn(handle, ddl, register.totalsTableName(), res.columnName(), columnType(res) + " DEFAULT 0");
        }
    }

    private void addInfoRegisterColumns(Handle handle, List<String> ddl, InformationRegisterDescriptor register) {
        if (register.periodicity() != Periodicity.NONE) {
            addColumn(handle, ddl, register.tableName(), "_period", "TIMESTAMP");
        }
        for (AttributeDescriptor dim : register.dimensions()) {
            addColumn(handle, ddl, register.tableName(), dim.columnName(), columnType(dim));
        }
        for (AttributeDescriptor res : register.resources()) {
            addColumn(handle, ddl, register.tableName(), res.columnName(), columnType(res));
        }
        for (AttributeDescriptor attr : register.attributes()) {
            addColumn(handle, ddl, register.tableName(), attr.columnName(), columnType(attr));
        }
    }

    private void addOutboxColumns(Handle handle, List<String> ddl) {
        addColumn(handle, ddl, "onec_outbox", "_status", "VARCHAR(32) DEFAULT 'NEW'");
        addColumn(handle, ddl, "onec_outbox", "_published_at", "TIMESTAMP");
    }

    private void addColumn(Handle handle, List<String> ddl, String tableName, String columnName, String type) {
        if (tableExists(handle, tableName) && !columnExists(handle, tableName, columnName)) {
            ddl.add("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + type);
        }
    }

    private boolean tableExists(Handle handle, String tableName) {
        return handle.createQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = UPPER(:table)")
                .bind("table", tableName)
                .mapTo(Integer.class)
                .one() > 0;
    }

    private boolean columnExists(Handle handle, String tableName, String columnName) {
        return handle.createQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                                "WHERE UPPER(TABLE_NAME) = UPPER(:table) AND UPPER(COLUMN_NAME) = UPPER(:column)")
                .bind("table", tableName)
                .bind("column", columnName)
                .mapTo(Integer.class)
                .one() > 0;
    }

    private String columnType(AttributeDescriptor attr) {
        return SchemaGenerator.sqlType(attr.javaType(), attr.length(), attr.precision(), attr.scale(), attr.columnName())
                + (attr.required() ? " NOT NULL" : "");
    }
}
