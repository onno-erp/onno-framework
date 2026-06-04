package com.onec.metadata;

import java.util.List;

public record DocumentDescriptor(
        String logicalName,
        String displayTitle,
        String tableName,
        Class<?> javaClass,
        int numberLength,
        boolean autoNumber,
        String numberPrefix,
        String context,
        List<String> readRoles,
        List<String> writeRoles,
        List<AttributeDescriptor> attributes,
        List<TabularSectionDescriptor> tabularSections
) {
}
