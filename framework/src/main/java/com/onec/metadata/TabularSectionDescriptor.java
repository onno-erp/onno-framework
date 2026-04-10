package com.onec.metadata;

import java.util.List;

public record TabularSectionDescriptor(
        String name,
        String fieldName,
        String tableName,
        Class<?> rowClass,
        List<AttributeDescriptor> attributes
) {
}
