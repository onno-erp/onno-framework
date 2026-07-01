package su.onno.schema;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.EnumerationValueDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.types.Ref;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders {@code CREATE TABLE IF NOT EXISTS} DDL for everything in the metadata registry.
 * Table layouts come from {@link SchemaModelBuilder}, the shared source of truth also used
 * by {@link SchemaDiffEngine}/{@link SchemaUpgrader} for evolving existing databases.
 *
 * <p>{@link #execute(Jdbi)} is the simple bootstrap path: create what is missing, then add
 * missing columns. For the full lifecycle (renames, type changes, history, destructive-change
 * gating) use {@link SchemaUpgrader}.
 */
public class SchemaGenerator {

    private static final Logger log = LoggerFactory.getLogger(SchemaGenerator.class);

    private final MetadataRegistry registry;

    public SchemaGenerator(MetadataRegistry registry) {
        this.registry = registry;
    }

    public static String generateSequenceTableDDL() {
        return DdlRenderer.createTable(SchemaModelBuilder.sequencesTable());
    }

    public static String generateOutboxTableDDL() {
        return DdlRenderer.createTable(SchemaModelBuilder.outboxTable());
    }

    public List<String> generateDDL() {
        return generateDDL(SqlDialect.H2);
    }

    public List<String> generateDDL(SqlDialect dialect) {
        SchemaModel model = new SchemaModelBuilder(registry).build();
        Map<String, EnumerationDescriptor> enumsByTable = new HashMap<>();
        for (EnumerationDescriptor enumeration : registry.allEnumerations()) {
            enumsByTable.put(enumeration.tableName(), enumeration);
        }
        List<String> statements = new ArrayList<>();
        for (TableModel table : model.tables()) {
            statements.add(DdlRenderer.createTable(table));
            EnumerationDescriptor enumeration = enumsByTable.get(table.name());
            if (enumeration != null) {
                statements.addAll(enumerationInserts(enumeration, dialect));
            }
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
        applySearchIndexes(jdbi, dialect);
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
        statements.add(index("onno_outbox", "_status"));
        for (CatalogDescriptor catalog : registry.allCatalogs()) {
            // Keyset pagination seeks by (sortKey, _id); the composite lets the database satisfy both
            // the default list order (_code) and the ref-picker order (_description) — plus their seek
            // comparison — as an index-only range scan in either direction.
            statements.add(index(catalog.tableName(), "_code", "_id"));
            statements.add(index(catalog.tableName(), "_description", "_id"));
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
            // (_date, _id) covers both plain date filtering (the _date prefix) and the newest-first
            // keyset seek used by the default document list order.
            statements.add(index(document.tableName(), "_date", "_id"));
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

    private static String index(String table, String... columns) {
        String cols = String.join(", ", columns);
        return "CREATE INDEX IF NOT EXISTS " + indexName(table, String.join("_", columns))
                + " ON " + table + " (" + cols + ")";
    }

    /**
     * PostgreSQL trigram (GIN) indexes that turn the list/search {@code LOWER(CAST(col AS VARCHAR))
     * LIKE '%term%'} scan into an index lookup — the single biggest win for substring search on a
     * large table. The indexed expression is byte-for-byte the one the query services emit, so the
     * planner actually uses it. Empty on any non-PostgreSQL dialect (H2 has no pg_trgm; it falls back
     * to the LIKE scan), and applied best-effort by {@link #applySearchIndexes} since {@code CREATE
     * EXTENSION} may require privileges the app role lacks.
     */
    public List<String> generateSearchIndexDDL(SqlDialect dialect) {
        if (dialect != SqlDialect.POSTGRESQL) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        statements.add("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        for (CatalogDescriptor catalog : registry.allCatalogs()) {
            for (String column : textSearchColumns(catalog.attributes(), "_code", "_description")) {
                statements.add(trigramIndex(catalog.tableName(), column));
            }
        }
        for (DocumentDescriptor document : registry.allDocuments()) {
            for (String column : textSearchColumns(document.attributes(), "_number")) {
                statements.add(trigramIndex(document.tableName(), column));
            }
        }
        return statements;
    }

    /**
     * Apply the trigram search indexes best-effort: a missing {@code pg_trgm} extension (or a role
     * without rights to create it) logs a warning and the app keeps running on LIKE scans rather than
     * failing to boot. A no-op on non-PostgreSQL dialects.
     */
    public void applySearchIndexes(Jdbi jdbi, SqlDialect dialect) {
        List<String> statements = generateSearchIndexDDL(dialect);
        if (statements.isEmpty()) {
            return;
        }
        try {
            jdbi.useHandle(handle -> {
                for (String statement : statements) {
                    handle.execute(statement);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Trigram search indexes unavailable — continuing with LIKE scans. Install the "
                    + "pg_trgm extension (or grant the app role rights to) for indexed substring "
                    + "search. Cause: {}", e.getMessage());
        }
    }

    /** The system text columns plus every non-secret String attribute — what list/ref search covers. */
    private static List<String> textSearchColumns(List<AttributeDescriptor> attributes, String... systemColumns) {
        List<String> columns = new ArrayList<>(List.of(systemColumns));
        attributes.stream()
                .filter(a -> a.javaType() == String.class && !a.secret())
                .forEach(a -> columns.add(a.columnName()));
        return columns;
    }

    private static String trigramIndex(String table, String column) {
        return "CREATE INDEX IF NOT EXISTS " + indexName("trgm_" + table, column)
                + " ON " + table + " USING gin (LOWER(CAST(" + column + " AS VARCHAR)) gin_trgm_ops)";
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

    static List<String> enumerationInserts(EnumerationDescriptor enumeration, SqlDialect dialect) {
        List<String> inserts = new ArrayList<>();
        List<String> columns = List.of("_id", "_name", "_label", "_order");
        List<String> keyColumns = List.of("_id");
        for (EnumerationValueDescriptor value : enumeration.values()) {
            List<String> values = List.of(
                    sqlString(value.id().toString()),
                    sqlString(value.name()),
                    sqlString(value.label()),
                    String.valueOf(value.order()));
            inserts.add(dialect.upsert(enumeration.tableName(), columns, keyColumns, values));
        }
        return inserts;
    }

    // Enum labels are author-supplied free text (e.g. "Won't fix") and may contain a single quote;
    // double it so the seed upsert stays valid SQL. Names/UUIDs can't contain one, but escaping
    // them too keeps the rendering uniform.
    private static String sqlString(String raw) {
        return "'" + raw.replace("'", "''") + "'";
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
