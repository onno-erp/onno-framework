package su.onno.metadata;

import java.util.List;

public record DocumentDescriptor(
        String logicalName,
        String displayTitle,
        String tableName,
        Class<?> javaClass,
        int numberLength,
        boolean autoNumber,
        String numberPrefix,
        String context,
        List<AttributeDescriptor> attributes,
        List<TabularSectionDescriptor> tabularSections,
        List<String> previousNames
) {

    /** Backward-compatible constructor for callers predating {@code previousNames}. */
    public DocumentDescriptor(
            String logicalName, String displayTitle, String tableName, Class<?> javaClass,
            int numberLength, boolean autoNumber, String numberPrefix, String context,
            List<AttributeDescriptor> attributes, List<TabularSectionDescriptor> tabularSections) {
        this(logicalName, displayTitle, tableName, javaClass, numberLength, autoNumber,
                numberPrefix, context, attributes, tabularSections, List.of());
    }
}
