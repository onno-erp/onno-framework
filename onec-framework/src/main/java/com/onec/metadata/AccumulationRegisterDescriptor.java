package com.onec.metadata;

import com.onec.model.AccumulationType;

import java.util.List;

public record AccumulationRegisterDescriptor(
        String logicalName,
        String displayTitle,
        String tableName,
        String totalsTableName,
        Class<?> javaClass,
        AccumulationType accumulationType,
        String context,
        List<String> readRoles,
        List<String> writeRoles,
        List<AttributeDescriptor> dimensions,
        List<AttributeDescriptor> resources
) {
}
