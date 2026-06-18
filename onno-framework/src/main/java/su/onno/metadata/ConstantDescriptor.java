package su.onno.metadata;

public record ConstantDescriptor(
        String logicalName,
        Class<?> javaClass,
        Class<?> valueType,
        String fieldName
) {
}
