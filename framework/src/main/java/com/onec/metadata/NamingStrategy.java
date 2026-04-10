package com.onec.metadata;

public interface NamingStrategy {

    String catalogTable(String catalogName);

    String column(String fieldName);

    default String documentTable(String documentName) {
        return "_document_" + documentName;
    }

    default String tabularSectionTable(String documentName, String sectionName) {
        return "_document_" + documentName + "_" + sectionName;
    }

    default String registerTable(String registerName) {
        return "_register_" + registerName;
    }

    default String registerTotalsTable(String registerName) {
        return "_register_" + registerName + "_totals";
    }
}
