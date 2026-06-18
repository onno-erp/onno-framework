package su.onno.metadata;

public class DefaultNamingStrategy {

    public String catalogTable(String catalogName) {
        return "catalog_" + toSnake(catalogName);
    }

    public String documentTable(String documentName) {
        return "document_" + toSnake(documentName);
    }

    public String tabularSectionTable(String documentName, String sectionName) {
        return "document_" + toSnake(documentName) + "_" + toSnake(sectionName);
    }

    public String registerTable(String registerName) {
        return "register_" + toSnake(registerName);
    }

    public String registerTotalsTable(String registerName) {
        return "register_" + toSnake(registerName) + "_totals";
    }

    public String enumerationTable(String enumName) {
        return "enum_" + toSnake(enumName);
    }

    public String infoRegisterTable(String registerName) {
        return "inforeg_" + toSnake(registerName);
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
        return sb.toString();
    }
}
