package su.onno.metadata;

public class DefaultNamingStrategy {

    private static final String SQL_IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    public String catalogTable(String catalogName) {
        return requireSqlIdentifier("catalog_" + toSnake(catalogName));
    }

    public String documentTable(String documentName) {
        return requireSqlIdentifier("document_" + toSnake(documentName));
    }

    public String tabularSectionTable(String documentName, String sectionName) {
        return requireSqlIdentifier("document_" + toSnake(documentName) + "_" + toSnake(sectionName));
    }

    public String registerTable(String registerName) {
        return requireSqlIdentifier("register_" + toSnake(registerName));
    }

    public String registerTotalsTable(String registerName) {
        return requireSqlIdentifier("register_" + toSnake(registerName) + "_totals");
    }

    public String enumerationTable(String enumName) {
        return requireSqlIdentifier("enum_" + toSnake(enumName));
    }

    public String infoRegisterTable(String registerName) {
        return requireSqlIdentifier("inforeg_" + toSnake(registerName));
    }

    private static String toSnake(String name) {
        String normalized = name.replace(" ", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    public String column(String fieldName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return requireSqlIdentifier(sb.toString());
    }

    /**
     * Rejects identifiers that cannot be safely interpolated into framework-generated SQL.
     * Metadata names are code-owned structural input, but validating them at scan time keeps a
     * typo or punctuation character from reaching DDL or query rendering as an opaque SQL fragment.
     */
    static String requireSqlIdentifier(String identifier) {
        if (identifier == null || !identifier.matches(SQL_IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(
                    "Unsafe SQL identifier '" + identifier + "'. Expected " + SQL_IDENTIFIER_PATTERN);
        }
        return identifier;
    }
}
