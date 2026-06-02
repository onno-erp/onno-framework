package com.onec.schema;

import com.onec.metadata.*;
import com.onec.model.AccumulationType;
import com.onec.model.Periodicity;
import com.onec.types.Ref;

import org.jdbi.v3.core.Jdbi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class SchemaGenerator {

    private final MetadataRegistry registry;

    public SchemaGenerator(MetadataRegistry registry) {
        this.registry = registry;
    }

    public static String generateSequenceTableDDL() {
        return "CREATE TABLE IF NOT EXISTS onec_sequences (\n" +
                "    entity_name VARCHAR(255) PRIMARY KEY,\n" +
                "    last_value BIGINT NOT NULL DEFAULT 0\n" +
                ")";
    }

    public static String generateOutboxTableDDL() {
        return "CREATE TABLE IF NOT EXISTS onec_outbox (\n" +
                "    _id UUID PRIMARY KEY,\n" +
                "    _aggregate_type VARCHAR(255) NOT NULL,\n" +
                "    _aggregate_id VARCHAR(255),\n" +
                "    _event_type VARCHAR(255) NOT NULL,\n" +
                "    _payload TEXT NOT NULL,\n" +
                "    _created_at TIMESTAMP NOT NULL,\n" +
                "    _published_at TIMESTAMP,\n" +
                "    _status VARCHAR(32) NOT NULL\n" +
                ")";
    }

    public List<String> generateDDL() {
        List<String> statements = new ArrayList<>();
        statements.add(generateSequenceTableDDL());
        statements.add(generateOutboxTableDDL());
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
        for (EnumerationDescriptor e : registry.allEnumerations()) {
            statements.add(generateEnumerationDDL(e));
            statements.addAll(generateEnumerationInserts(e));
        }
        for (InformationRegisterDescriptor reg : registry.allInformationRegisters()) {
            statements.add(generateInfoRegisterDDL(reg));
        }
        if (!registry.allConstants().isEmpty()) {
            statements.add(generateConstantsTableDDL());
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
        new SchemaMigrator(registry).executeAdditive(jdbi);
    }

    private String generateCatalogDDL(CatalogDescriptor catalog) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(catalog.tableName()).append(" (\n");
        sb.append("    _id UUID PRIMARY KEY,\n");
        sb.append("    _code VARCHAR(")
                .append(catalog.codeLength() + (catalog.codePrefix() == null ? 0 : catalog.codePrefix().length()))
                .append("),\n");
        sb.append("    _description VARCHAR(255),\n");
        sb.append("    _deletion_mark BOOLEAN DEFAULT FALSE,\n");
        sb.append("    _is_folder BOOLEAN DEFAULT FALSE,\n");
        sb.append("    _parent UUID,\n");
        sb.append("    _version INTEGER DEFAULT 0");

        for (AttributeDescriptor attr : catalog.attributes()) {
            sb.append(",\n    ").append(attr.columnName()).append(" ");
            sb.append(columnType(attr));
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
        sb.append("    _number VARCHAR(")
                .append(document.numberLength() + (document.numberPrefix() == null ? 0 : document.numberPrefix().length()))
                .append("),\n");
        sb.append("    _date TIMESTAMP,\n");
        sb.append("    _posted BOOLEAN DEFAULT FALSE,\n");
        sb.append("    _deletion_mark BOOLEAN DEFAULT FALSE,\n");
        sb.append("    _version INTEGER DEFAULT 0");

        for (AttributeDescriptor attr : document.attributes()) {
            sb.append(",\n    ").append(attr.columnName()).append(" ");
            sb.append(columnType(attr));
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
            sb.append(columnType(attr));
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
            sb.append(columnType(dim));
        }

        for (AttributeDescriptor res : reg.resources()) {
            sb.append(",\n    ").append(res.columnName()).append(" ");
            sb.append(columnType(res));
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
            sb.append(columnType(dim));
            first = false;
        }

        for (AttributeDescriptor res : reg.resources()) {
            sb.append(",\n    ").append(res.columnName()).append(" ");
            sb.append(columnType(res));
            sb.append(" DEFAULT 0");
        }

        String dimCols = reg.dimensions().stream()
                .map(AttributeDescriptor::columnName)
                .collect(Collectors.joining(", "));
        sb.append(",\n    PRIMARY KEY (").append(dimCols).append(")");

        sb.append("\n)");
        return sb.toString();
    }

    private String generateEnumerationDDL(EnumerationDescriptor e) {
        return "CREATE TABLE IF NOT EXISTS " + e.tableName() + " (\n" +
                "    _id UUID PRIMARY KEY,\n" +
                "    _name VARCHAR(255),\n" +
                "    _order INTEGER\n" +
                ")";
    }

    private List<String> generateEnumerationInserts(EnumerationDescriptor e) {
        List<String> inserts = new ArrayList<>();
        for (EnumerationValueDescriptor v : e.values()) {
            inserts.add("MERGE INTO " + e.tableName() +
                    " (_id, _name, _order) KEY(_id) VALUES ('" +
                    v.id() + "', '" + v.name() + "', " + v.order() + ")");
        }
        return inserts;
    }

    private String generateInfoRegisterDDL(InformationRegisterDescriptor reg) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(reg.tableName()).append(" (\n");
        sb.append("    _id UUID PRIMARY KEY");

        if (reg.periodicity() != Periodicity.NONE) {
            sb.append(",\n    _period TIMESTAMP");
        }

        for (AttributeDescriptor dim : reg.dimensions()) {
            sb.append(",\n    ").append(dim.columnName()).append(" ");
            sb.append(columnType(dim));
        }
        for (AttributeDescriptor res : reg.resources()) {
            sb.append(",\n    ").append(res.columnName()).append(" ");
            sb.append(columnType(res));
        }
        for (AttributeDescriptor attr : reg.attributes()) {
            sb.append(",\n    ").append(attr.columnName()).append(" ");
            sb.append(columnType(attr));
        }

        // UNIQUE constraint on period + dimensions for upsert semantics
        List<String> uniqueCols = new ArrayList<>();
        if (reg.periodicity() != Periodicity.NONE) {
            uniqueCols.add("_period");
        }
        for (AttributeDescriptor dim : reg.dimensions()) {
            uniqueCols.add(dim.columnName());
        }
        if (!uniqueCols.isEmpty()) {
            sb.append(",\n    UNIQUE (").append(String.join(", ", uniqueCols)).append(")");
        }

        sb.append("\n)");
        return sb.toString();
    }

    private String generateConstantsTableDDL() {
        return "CREATE TABLE IF NOT EXISTS constants (\n" +
                "    _name VARCHAR(255) PRIMARY KEY,\n" +
                "    _value TEXT\n" +
                ")";
    }

    /** SQL type for a metadata attribute, naming the column in any error it raises. */
    static String columnType(AttributeDescriptor attr) {
        return sqlType(attr.javaType(), attr.length(), attr.precision(), attr.scale(), attr.columnName());
    }

    public static String sqlType(Class<?> javaType, int length, int precision, int scale) {
        return sqlType(javaType, length, precision, scale, null);
    }

    public static String sqlType(Class<?> javaType, int length, int precision, int scale, String fieldContext) {
        if (javaType == String.class) {
            return "VARCHAR(" + length + ")";
        } else if (javaType == int.class || javaType == Integer.class) {
            return "INTEGER";
        } else if (javaType == long.class || javaType == Long.class) {
            return "BIGINT";
        } else if (javaType == boolean.class || javaType == Boolean.class) {
            return "BOOLEAN";
        } else if (javaType == double.class || javaType == Double.class
                || javaType == float.class || javaType == Float.class) {
            return "DOUBLE PRECISION";
        } else if (javaType == BigDecimal.class) {
            return "DECIMAL(" + precision + "," + scale + ")";
        } else if (javaType == UUID.class) {
            return "UUID";
        } else if (javaType == LocalDate.class) {
            return "DATE";
        } else if (javaType == LocalDateTime.class) {
            return "TIMESTAMP";
        } else if (Ref.class.isAssignableFrom(javaType)) {
            return "UUID";
        } else if (javaType.isEnum()) {
            return "UUID";
        }
        throw new IllegalArgumentException(
                "Unsupported attribute type " + javaType.getName()
                        + (fieldContext == null ? "" : " for column '" + fieldContext + "'")
                        + ". Supported types: String, int/Integer, long/Long, boolean/Boolean, "
                        + "double/Double, float/Float, BigDecimal, UUID, LocalDate, LocalDateTime, "
                        + "enums, and Ref subtypes.");
    }
}
