package com.onec.metadata;

import java.util.List;

public record DocumentDescriptor(
        String logicalName,
        String tableName,
        Class<?> javaClass,
        int numberLength,
        List<AttributeDescriptor> attributes,
        List<TabularSectionDescriptor> tabularSections
) {
}
