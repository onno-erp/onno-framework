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
        return generateDDL(SqlDialect.H2);
    }

    public List<String> generateDDL(SqlDialect dialect) {
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
            statements.addAll(generateEnumerationInserts(e, dialect));
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
        SqlDialect dialect = jdbi.withHandle(handle -> SqlDialect.detect(handle.getConnection()));
        List<String> ddl = generateDDL(dialect);
        jdbi.useHandle(handle -> {
            for (String statement : ddl) {
                handle.execute(statement);
            }
        });
        new SchemaMigrator(registry).executeAdditive(jdbi);
        // Indexes go last: a ref column added by the additive migration above must exist
        // before its index is created.
        jdbi.useHandle(handle -> {
            for (String statement : generateIndexDDL()) {
                handle.execute(statement);
            }
        });
    }

    /**
     * Index statements for every generated table, covering the columns the framework
     * itself queries by: ref columns (auto-joins and reference expansion), catalog
     * hierarchy parents, document dates, tabular-section owners, register periods,
     * document refs, and dimensions. Statements use {@code IF NOT EXISTS} and run after
     * the additive column migration, so existing databases pick them up on next boot.
     */
    public List<String> generateIndexDDL() {
        List<String> statements = new ArrayList<>();
        statements.add(index("onec_outbox", "_status"));
        for (CatalogDescriptor catalog : registry.allCatalogs()) {
            if (catalog.hierarchical()) {
                statements.add(index(catalog.tableName(), "_parent"));
            }
            for (AttributeDescriptor attr : catalog.attributes()) {
                if (attr.isRef()) {
                    statements.add(index(catalog.tableName(), attr.columnName()));
                }
            }
        }
        for (DocumentDescriptor document : registry.allDocuments()) {
            statements.add(index(document.tableName(), "_date"));
            for (AttributeDescriptor attr : document.attributes()) {
                if (attr.isRef()) {
                    statements.add(index(document.tableName(), attr.columnName()));
                }
            }
            for (TabularSectionDescriptor section : document.tabularSections()) {
                statements.add(index(section.tableName(), "_parent_id"));
                for (AttributeDescriptor attr : section.attributes()) {
                    if (attr.isRef()) {
                        statements.add(index(section.tableName(), attr.columnName()));
                    }
                }
            }
        }
        for (AccumulationRegisterDescriptor reg : registry.allRegisters()) {
            statements.add(index(reg.tableName(), "_period"));
            statements.add(index(reg.tableName(), "_document_ref"));
            for (AttributeDescriptor dim : reg.dimensions()) {
                statements.add(index(reg.tableName(), dim.columnName()));
            }
        }
        for (InformationRegisterDescriptor reg : registry.allInformationRegisters()) {
            for (AttributeDescriptor dim : reg.dimensions()) {
                statements.add(index(reg.tableName(), dim.columnName()));
            }
        }
        return statements;
    }

    private static String index(String table, String column) {
        return "CREATE INDEX IF NOT EXISTS " + indexName(table, column)
                + " ON " + table + " (" + column + ")";
    }

    // PostgreSQL truncates identifiers beyond 63 characters, which would make two long
    // names collide and IF NOT EXISTS silently skip the second index; cap with a hash.
    static String indexName(String table, String column) {
        String name = "idx_" + table + "_" + column;
        if (name.length() <= 63) {
            return name;
        }
        return name.substring(0, 54) + "_" + Integer.toHexString(name.hashCode());
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

    private List<String> generateEnumerationInserts(EnumerationDescriptor e, SqlDialect dialect) {
        List<String> inserts = new ArrayList<>();
        List<String> columns = List.of("_id", "_name", "_order");
        List<String> keyColumns = List.of("_id");
        for (EnumerationValueDescriptor v : e.values()) {
            List<String> values = List.of(
                    "'" + v.id() + "'",
                    "'" + v.name() + "'",
                    String.valueOf(v.order()));
            inserts.add(dialect.upsert(e.tableName(), columns, keyColumns, values));
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

    // Above this declared length a String column is stored as TEXT rather than VARCHAR(n): the
    // author is asking for unbounded text (e.g. a base64 image behind .widget("image")), which a
    // capped VARCHAR would reject on insert.
    private static final int MAX_VARCHAR = 65_535;

    /** SQL type for a metadata attribute, naming the column in any error it raises. */
    static String columnType(AttributeDescriptor attr) {
        if (attr.javaType() == String.class) {
            // Secret String values are stored encrypted; the base64 ciphertext is larger than the
            // declared plaintext length. A very large declared length is likewise a request for
            // unbounded text. Both widen to TEXT rather than the attribute's VARCHAR(length).
            if (attr.secret() || attr.length() > MAX_VARCHAR) {
                return "TEXT";
            }
        }
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
