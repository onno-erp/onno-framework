package su.onno.metadata;

import su.onno.model.Periodicity;

import java.util.List;

public record InformationRegisterDescriptor(
        String logicalName,
        String tableName,
        Class<?> javaClass,
        Periodicity periodicity,
        String context,
        List<String> readRoles,
        List<String> writeRoles,
        List<AttributeDescriptor> dimensions,
        List<AttributeDescriptor> resources,
        List<AttributeDescriptor> attributes
) {
}
