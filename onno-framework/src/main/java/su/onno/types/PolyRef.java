package su.onno.types;

import java.util.Objects;
import java.util.UUID;

/**
 * A reference whose target may be one of several declared catalog/document types.
 *
 * <p>The allowed target set belongs to field metadata through
 * {@link su.onno.annotations.RefTargets}; each value carries its concrete Java target and id.
 */
public record PolyRef(Class<?> type, UUID id) {

    private static final String SEPARATOR = "|";

    public PolyRef {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }

    public static PolyRef of(Class<?> type, UUID id) {
        return new PolyRef(type, id);
    }

    /**
     * Stable database/wire representation used by generated persistence and APIs.
     */
    public String externalForm() {
        return type.getName() + SEPARATOR + id;
    }

    public static PolyRef parse(String value) {
        if (value == null || value.isBlank()) return null;
        int separator = value.lastIndexOf(SEPARATOR);
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("Invalid polymorphic reference: " + value);
        }
        String typeName = value.substring(0, separator);
        String id = value.substring(separator + 1);
        try {
            return new PolyRef(Class.forName(typeName), UUID.fromString(id));
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Unknown polymorphic reference type: " + typeName, e);
        }
    }

    @Override
    public String toString() {
        return externalForm();
    }
}
