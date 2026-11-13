package su.onno.metadata;

import su.onno.annotations.AccessControl;

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
        List<String> readRoles,
        List<String> writeRoles,
        List<AttributeDescriptor> attributes,
        List<TabularSectionDescriptor> tabularSections,
        List<String> previousNames
) {

    /** Backward-compatible constructor for callers predating {@code previousNames}. */
    public DocumentDescriptor(
            String logicalName, String displayTitle, String tableName, Class<?> javaClass,
            int numberLength, boolean autoNumber, String numberPrefix, String context,
            List<String> readRoles, List<String> writeRoles,
            List<AttributeDescriptor> attributes, List<TabularSectionDescriptor> tabularSections) {
        this(logicalName, displayTitle, tableName, javaClass, numberLength, autoNumber,
                numberPrefix, context, readRoles, writeRoles, attributes, tabularSections, List.of());
    }

    /**
     * Roles that may post the document on top of write access ({@code @AccessControl.postRoles});
     * empty means every writer may post.
     */
    public List<String> postRoles() {
        AccessControl access = accessControl();
        return access == null ? List.of() : List.of(access.postRoles());
    }

    /**
     * Roles that may unpost the document on top of write access
     * ({@code @AccessControl.unpostRoles}); empty means every writer may unpost.
     */
    public List<String> unpostRoles() {
        AccessControl access = accessControl();
        return access == null ? List.of() : List.of(access.unpostRoles());
    }

    private AccessControl accessControl() {
        return javaClass == null ? null : javaClass.getAnnotation(AccessControl.class);
    }
}
