package su.onno.metadata;

import java.util.List;

public record EnumerationDescriptor(
        String logicalName,
        String displayTitle,
        String tableName,
        Class<? extends Enum<?>> javaClass,
        List<EnumerationValueDescriptor> values
) {
}
