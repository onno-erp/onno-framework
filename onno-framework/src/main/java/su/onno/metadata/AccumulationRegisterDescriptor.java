package su.onno.metadata;

import su.onno.model.AccumulationType;
import su.onno.model.PostingOrder;

import java.util.List;

public record AccumulationRegisterDescriptor(
        String logicalName,
        String displayTitle,
        String tableName,
        String totalsTableName,
        Class<?> javaClass,
        AccumulationType accumulationType,
        boolean allowNegative,
        PostingOrder postingOrder,
        String context,
        List<String> readRoles,
        List<String> writeRoles,
        List<AttributeDescriptor> dimensions,
        List<AttributeDescriptor> resources
) {
}
