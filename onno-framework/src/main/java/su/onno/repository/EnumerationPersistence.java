package su.onno.repository;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps {@code @Enumeration} constants to/from their deterministic UUID ids.
 *
 * <p>Enumeration value rows are seeded by {@link su.onno.schema.SchemaGenerator}
 * during schema initialization, so this type only provides the stateless
 * id-resolution helpers used at query/persistence time, plus {@link #fromNameOrId} — the one rule
 * by which every public boundary (writes, filters, New-form prefill) reads a client's enum value.
 */
public final class EnumerationPersistence {

    private EnumerationPersistence() {
    }

    public static UUID resolveId(Class<? extends Enum<?>> enumClass, Enum<?> value) {
        return UUID.nameUUIDFromBytes(
                (enumClass.getName() + "." + value.name()).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <E extends Enum<E>> E resolveValue(Class<?> enumClass, UUID id) {
        Class<E> ec = (Class<E>) enumClass;
        for (E constant : ec.getEnumConstants()) {
            UUID candidateId = UUID.nameUUIDFromBytes(
                    (enumClass.getName() + "." + constant.name()).getBytes(StandardCharsets.UTF_8));
            if (candidateId.equals(id)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("No enum value found for UUID " + id + " in " + enumClass.getName());
    }

    /**
     * The constant of {@code enumClass} a client value names: the constant itself, its
     * {@link Enum#name() name} ({@code "NEW"}), or its stored id as a {@link UUID} or UUID string.
     * Surrounding blanks are ignored; the name is case-sensitive, and the {@code @EnumLabel} label is
     * not accepted because it is localized. A Java constant name can never parse as a UUID, so the
     * two spellings cannot be confused.
     *
     * @return the constant, or empty when {@code value} names none — {@code null}, an unknown or
     *         removed name, an id of no constant, or a constant of another enum
     */
    public static Optional<Enum<?>> fromNameOrId(Class<? extends Enum<?>> enumClass, Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Enum<?> constant) {
            return constant.getDeclaringClass() == enumClass ? Optional.of(constant) : Optional.empty();
        }
        UUID id = value instanceof UUID uuid ? uuid : null;
        String name = id == null ? value.toString().trim() : null;
        if (id == null) {
            try {
                id = UUID.fromString(name);
            } catch (IllegalArgumentException notAnId) {
                // a constant name
            }
        }
        for (Enum<?> constant : enumClass.getEnumConstants()) {
            if (id != null ? resolveId(enumClass, constant).equals(id) : constant.name().equals(name)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }
}
