package su.onno.metadata;

import java.util.UUID;

/**
 * A single value of an {@code @Enumeration}.
 *
 * @param name  the Java constant name — the stable contract key the {@link #id()} is derived from.
 * @param label the human-facing display label (from {@code @EnumLabel}); equals {@code name} when
 *              the constant is unlabelled.
 * @param id    the deterministic UUID this value is stored as.
 * @param order the declaration order (ordinal).
 */
public record EnumerationValueDescriptor(
        String name,
        String label,
        UUID id,
        int order
) {
}
