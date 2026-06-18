package su.onno.metadata;

import java.util.List;

public record EnumerationDescriptor(
        String logicalName,
        String tableName,
        Class<? extends Enum<?>> javaClass,
        List<EnumerationValueDescriptor> values
) {
}
