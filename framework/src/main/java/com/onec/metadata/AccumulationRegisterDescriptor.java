package com.onec.metadata;

import com.onec.model.AccumulationType;

import java.util.List;

public record AccumulationRegisterDescriptor(
        String logicalName,
        String tableName,
        String totalsTableName,
        Class<?> javaClass,
        AccumulationType accumulationType,
        List<AttributeDescriptor> dimensions,
        List<AttributeDescriptor> resources
) {
}
