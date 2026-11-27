package su.onno.service;

import su.onno.repository.EnumerationPersistence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How the query and write services read a client's enum value. Which constant a value names is
 * {@link EnumerationPersistence#fromNameOrId}; this adds the filter binding and the error wording the
 * services share.
 */
final class EnumValues {

    private EnumValues() {
    }

    /** The constant names of {@code enumType}, comma-separated, for an error listing what is accepted. */
    static String names(Class<? extends Enum<?>> enumType) {
        List<String> names = new ArrayList<>();
        for (Enum<?> constant : enumType.getEnumConstants()) {
            names.add(constant.name());
        }
        return String.join(", ", names);
    }

    /**
     * The id a filter binds for {@code raw}: a UUID string as given — a filter on an id no row holds
     * simply matches nothing — or a constant name as its stored id. Anything else is a {@code 400}
     * prefixed with {@code subject} that lists the accepted names.
     */
    static UUID filterId(Class<? extends Enum<?>> enumType, String raw, String subject) {
        String text = raw == null ? "" : raw.trim();
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return EnumerationPersistence.fromNameOrId(enumType, text)
                    .map(constant -> EnumerationPersistence.resolveId(enumType, constant))
                    .orElseThrow(() -> new ServiceException(ServiceException.Status.BAD_REQUEST,
                            subject + ": unknown value '" + text + "', expected a UUID or one of "
                                    + names(enumType)));
        }
    }
}
