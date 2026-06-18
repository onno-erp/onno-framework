package su.onno.security;

import su.onno.metadata.AttributeDescriptor;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strips {@code @Attribute(secret = true)} values out of read responses so the generic API is
 * write-only for secrets. Each secret column is replaced in place with a sentinel — {@link #SET}
 * when a value is stored, {@code null} when not — so the UI can render "set vs not set" without
 * ever seeing the (encrypted) value.
 */
public final class SecretRedactor {

    /** Sentinel returned in place of a stored secret value. */
    public static final String SET = "__SECRET_SET__";

    private SecretRedactor() {}

    public static void redact(List<Map<String, Object>> rows, List<AttributeDescriptor> attributes) {
        for (AttributeDescriptor attr : attributes) {
            if (!attr.secret()) continue;
            String col = attr.columnName();
            for (Map<String, Object> row : rows) {
                Object value = row.containsKey(col) ? row.get(col)
                        : row.get(col.toUpperCase(Locale.ROOT));
                boolean present = value != null && !value.toString().isEmpty();
                // Write under whatever casing the driver returned the column as, so the
                // redacted value is the one callers actually read.
                if (row.containsKey(col)) {
                    row.put(col, present ? SET : null);
                } else if (row.containsKey(col.toUpperCase(Locale.ROOT))) {
                    row.put(col.toUpperCase(Locale.ROOT), present ? SET : null);
                } else {
                    row.put(col, present ? SET : null);
                }
            }
        }
    }
}
