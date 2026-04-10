package com.onec.schema;

import com.onec.metadata.*;
import com.onec.model.AccumulationType;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SchemaGenerator {

    private final MetadataRegistry registry;
    private final TypeMapping typeMapping;

    public SchemaGenerator(MetadataRegistry registry, TypeMapping typeMapping) {
        this.registry = registry;
        this.typeMapping = typeMapping;
    }

    public List<String> generateDDL() {
        List<String> statements = new ArrayList<>();
        for (CatalogDescriptor catalog : registry.allCatalogs()) {
            statements.add(generateCatalogDDL(catalog));
        }
        for (DocumentDescriptor document : registry.allDocuments()) {
            statements.add(generateDocumentDDL(document));
            for (TabularSectionDescriptor section : document.tabularSections()) {
                statements.add(generateTabularSectionDDL(document, section));
            }
        }
        for (AccumulationRegisterDescriptor reg : registry.allRegisters()) {
            statements.add(generateRegisterDDL(reg));
            if (reg.accumulationType() == AccumulationType.BALANCE) {
                statements.add(generateRegisterTotalsDDL(reg));
            }
        }
        return statements;
    }

    public void execute(Jdbi jdbi) {
        List<String> ddl = generateDDL();
        jdbi.useHandle(handle -> {
            for (String statement : ddl) {
                handle.execute(statement);
            }
        });
    }

    private String generateCatalogDDL(CatalogDescriptor catalog) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(catalog.tableName()).append(" (\n");
        sb.append("    _id UUID PRIMARY KEY,\n");
        sb.append("    _code VARCHAR(").append(catalog.codeLength()).append("),\n");
        sb.append("    _description VARCHAR(255),\n");
        sb.append("    _deletion_mark BOOLEAN DEFAULT FALSE");

        for (AttributeDescriptor attr : catalog.attributes()) {
            sb.append(",\n    ").append(attr.columnName()).append(" ");
            sb.append(typeMapping.sqlType(attr.javaType(), attr.length(), attr.precision(), attr.scale()));
            if (attr.required()) {
                sb.append(" NOT NULL");
            }
        }

        sb.append("\n)");
        return sb.toString();
    }

    private String generateDocumentDDL(DocumentDescriptor document) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(document.tableName()).append(" (\n");
        sb.append("    _id UUID PRIMARY KEY,\n");
        sb.append("    _number VARCHAR(").append(document.numberLength()).append("),\n");
        sb.append("    _date TIMESTAMP,\n");
        sb.append("    _posted BOOLEAN DEFAULT FALSE,\n");
        sb.append("    _deletion_mark BOOLEAN DEFAULT FALSE");

        for (AttributeDescriptor attr : document.attributes()) {
            sb.append(",\n    ").append(attr.columnName()).append(" ");
            sb.append(typeMapping.sqlType(attr.javaType(), attr.length(), attr.precision(), attr.scale()));
            if (attr.required()) {
                sb.append(" NOT NULL");
            }
        }

        sb.append("\n)");
        return sb.toString();
    }

    private String generateTabularSectionDDL(DocumentDescriptor document, TabularSectionDescriptor section) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(section.tableName()).append(" (\n");
        sb.append("    _id UUID PRIMARY KEY,\n");
        sb.append("    _parent_id UUID REFERENCES ").append(document.tableName()).append("(_id),\n");
        sb.append("    _line_number INTEGER");

        for (AttributeDescriptor attr : section.attributes()) {
            sb.append(",\n    ").append(attr.columnName()).append(" ");
            sb.append(typeMapping.sqlType(attr.javaType(), attr.length(), attr.precision(), attr.scale()));
            if (attr.required()) {
                sb.append(" NOT NULL");
            }
        }

        sb.append("\n)");
        return sb.toString();
    }

    private String generateRegisterDDL(AccumulationRegisterDescriptor reg) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(reg.tableName()).append(" (\n");
        sb.append("    _id UUID PRIMARY KEY,\n");
        sb.append("    _period TIMESTAMP,\n");
        sb.append("    _active BOOLEAN DEFAULT TRUE,\n");
        sb.append("    _document_ref UUID,\n");
        sb.append("    _movement_type VARCHAR(10)");

        for (AttributeDescriptor dim : reg.dimensions()) {
            sb.append(",\n    ").append(dim.columnName()).append(" ");
            sb.append(typeMapping.sqlType(dim.javaType(), dim.length(), dim.precision(), dim.scale()));
        }

        for (AttributeDescriptor res : reg.resources()) {
            sb.append(",\n    ").append(res.columnName()).append(" ");
            sb.append(typeMapping.sqlType(res.javaType(), res.length(), res.precision(), res.scale()));
        }

        sb.append("\n)");
        return sb.toString();
    }

    private String generateRegisterTotalsDDL(AccumulationRegisterDescriptor reg) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(reg.totalsTableName()).append(" (\n");

        boolean first = true;
        for (AttributeDescriptor dim : reg.dimensions()) {
            if (!first) sb.append(",\n");
            sb.append("    ").append(dim.columnName()).append(" ");
            sb.append(typeMapping.sqlType(dim.javaType(), dim.length(), dim.precision(), dim.scale()));
            first = false;
        }

        for (AttributeDescriptor res : reg.resources()) {
            sb.append(",\n    ").append(res.columnName()).append(" ");
            sb.append(typeMapping.sqlType(res.javaType(), res.length(), res.precision(), res.scale()));
            sb.append(" DEFAULT 0");
        }

        String dimCols = reg.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(Collectors.joining(", "));
        sb.append(",\n    PRIMARY KEY (").append(dimCols).append(")");

        sb.append("\n)");
        return sb.toString();
    }
}
