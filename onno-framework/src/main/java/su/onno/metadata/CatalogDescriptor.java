package su.onno.metadata;

import java.util.List;

public record CatalogDescriptor(
        String logicalName,
        String displayTitle,
        String tableName,
        Class<?> javaClass,
        int codeLength,
        boolean hierarchical,
        boolean autoNumber,
        String codePrefix,
        String context,
        List<String> readRoles,
        List<String> writeRoles,
        List<AttributeDescriptor> attributes,
        List<String> previousNames
) {

    /** Backward-compatible constructor for callers predating {@code previousNames}. */
    public CatalogDescriptor(
            String logicalName, String displayTitle, String tableName, Class<?> javaClass,
            int codeLength, boolean hierarchical, boolean autoNumber, String codePrefix,
            String context, List<String> readRoles, List<String> writeRoles,
            List<AttributeDescriptor> attributes) {
        this(logicalName, displayTitle, tableName, javaClass, codeLength, hierarchical,
                autoNumber, codePrefix, context, readRoles, writeRoles, attributes, List.of());
    }
}
