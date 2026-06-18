package su.onno.metadata;

import su.onno.model.AccumulationType;

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
