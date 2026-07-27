package su.onno.metadata;

/**
 * One catalog/document type allowed by a polymorphic reference field.
 */
public record ReferenceTargetDescriptor(
        String kind,
        String logicalName,
        String displayTitle,
        String javaTypeName
) {
}
