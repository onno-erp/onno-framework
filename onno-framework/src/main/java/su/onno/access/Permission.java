package su.onno.access;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A capability that is not about one entity: editing settings, managing user accounts, closing
 * periods, moderating comments. The module that enforces a capability declares it as a constant
 * and checks it; an application grants it to roles in {@link RoleGrants}:
 *
 * <pre>{@code
 * public final class CorePermissions {
 *     public static final Permission EDIT_SETTINGS =
 *             Permission.of("settings.edit", "Change @Constant settings");
 * }
 * }</pre>
 *
 * <p>Two permissions are equal when their keys are; the key is the stable identifier the UI model
 * and the generated TypeScript types carry.
 *
 * @param key         stable dotted identifier, e.g. {@code "settings.edit"}
 * @param description one line for documentation and the generated types
 */
public record Permission(String key, String description) {

    private static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+");

    public Permission {
        Objects.requireNonNull(key, "key");
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Permission key must be dotted lower camel case like \"settings.edit\": " + key);
        }
        description = description == null ? "" : description;
    }

    public static Permission of(String key, String description) {
        return new Permission(key, description);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Permission p && p.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
