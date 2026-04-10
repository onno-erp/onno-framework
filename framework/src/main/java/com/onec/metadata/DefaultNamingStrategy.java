package com.onec.metadata;

public class DefaultNamingStrategy {

    public String catalogTable(String catalogName) {
        return "_catalog_" + catalogName;
    }

    public String documentTable(String documentName) {
        return "_document_" + documentName;
    }

    public String tabularSectionTable(String documentName, String sectionName) {
        return "_document_" + documentName + "_" + sectionName;
    }

    public String registerTable(String registerName) {
        return "_register_" + registerName;
    }

    public String registerTotalsTable(String registerName) {
        return "_register_" + registerName + "_totals";
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
