package com.onec.metadata;

import java.util.List;

public record CatalogDescriptor(
        String logicalName,
        String tableName,
        Class<?> javaClass,
        int codeLength,
        List<AttributeDescriptor> attributes
) {
}
