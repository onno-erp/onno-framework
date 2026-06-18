package su.onno.repository;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Maps {@code @Enumeration} constants to/from their deterministic UUID ids.
 *
 * <p>Enumeration value rows are seeded by {@link su.onno.schema.SchemaGenerator}
 * during schema initialization, so this type only provides the stateless
 * id-resolution helpers used at query/persistence time.
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
}
