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
        List<AttributeDescriptor> attributes,
        List<String> previousNames,
        List<TabularSectionDescriptor> tabularSections
) {

    /** Backward-compatible constructor for callers predating catalog tabular sections. */
    public CatalogDescriptor(
            String logicalName, String displayTitle, String tableName, Class<?> javaClass,
            int codeLength, boolean hierarchical, boolean autoNumber, String codePrefix,
            String context, List<AttributeDescriptor> attributes, List<String> previousNames) {
        this(logicalName, displayTitle, tableName, javaClass, codeLength, hierarchical,
                autoNumber, codePrefix, context, attributes, previousNames, List.of());
    }

    /** Backward-compatible constructor for callers predating {@code previousNames}. */
    public CatalogDescriptor(
            String logicalName, String displayTitle, String tableName, Class<?> javaClass,
            int codeLength, boolean hierarchical, boolean autoNumber, String codePrefix,
            String context, List<AttributeDescriptor> attributes) {
        this(logicalName, displayTitle, tableName, javaClass, codeLength, hierarchical,
                autoNumber, codePrefix, context, attributes, List.of());
    }
}
